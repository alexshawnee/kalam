# Kalam

A `protoc` plugin that generates native IPC client and server code from `.proto` files. Replaces FFI bindings with clean, idiomatic, transport-agnostic inter-process communication over Unix Domain Sockets.

## Why

Cross-platform FFI bindings are painful: manual bridging code, no idiomatic async support, leaky abstractions. Kalam replaces FFI with IPC — define your API once in a `.proto` file and get clean, native code for every platform.

- **Native client code** with idiomatic async primitives — looks like a direct API call, no "Client" suffixes, no RPC boilerplate
- **Server handler interface + router** — implement a typed interface, routing is generated
- **Transport runtime** for each language — a singleton managing the UDS connection, framing, and dispatch

## Architecture

```
.proto file
    |
    v
protoc + kalam plugin
    |
    +---> client stubs (Dart, Kotlin, Swift, TypeScript, C#)
    +---> server handler interface + router (any language)
    +---> transport runtime (per language)
```

### Typical deployment

```
core (server)  <--UDS-->  android-app (Kotlin, client)
core (server)  <--UDS-->  ios-app (Swift, client)
core (server)  <--UDS-->  flutter-app (Dart, client)
core (server)  <--UDS-->  unity-app (C#, client)
```

## Wire Protocol

```
[1 byte version][4 bytes request_id][1 byte frame_type][4 bytes method_name_length][method_name][4 bytes payload_length][payload]
```

- `frame_type 0` — unary request/response
- `frame_type 1` — stream chunk
- `frame_type 2` — stream end
- `request_id = 0` for blocking calls, `> 0` for future duplex support

All multi-byte integers are big-endian. Payload is protobuf-encoded.

## Generated Code

### Client (example: Dart)

Names match the `.proto` definition exactly — no suffixes, no wrappers:

```dart
// generated: user_service.dart
import 'transport.dart';
import 'user.pb.dart';

class UserService {
  static Future<GetUserResponse> getUser(GetUserRequest request) async {
    final bytes = await Transport.instance.call(
      "UserService/GetUser",
      request.writeToBuffer(),
    );
    return GetUserResponse.fromBuffer(bytes);
  }
}
```

Usage:
```dart
final response = await UserService.getUser(request);
```

### Server (example: Kotlin)

Handler interface — you implement this:

```kotlin
interface UserServiceHandler {
    suspend fun getUser(request: GetUserRequest): GetUserResponse
}
```

Router — generated, wires method names to your handler:

```kotlin
class UserServiceRouter(private val handler: UserServiceHandler) : ServiceRouter {
    override suspend fun route(method: String, payload: ByteArray): ByteArray {
        return when (method) {
            "GetUser" -> {
                val request = GetUserRequest.parseFrom(payload)
                handler.getUser(request).toByteArray()
            }
            else -> throw UnknownMethodException(method)
        }
    }
}
```

### Transport (per language)

A singleton providing both client and server capabilities:

```dart
// Client side
Transport.instance.useSockets("/tmp/myapp.sock");

// Server side (same singleton, different role)
Transport.instance.serve("/tmp/myapp.sock", (methodName, payload) => ...);
```

The socket path can be passed as a `protoc` option (hardcoded at generation time) or configured at runtime.

## Async Primitives by Platform

| Platform   | Unary              | Streaming                |
|------------|--------------------|--------------------------|
| Kotlin     | `suspend fun`      | `Flow<T>`                |
| Dart       | `Future<T>`        | `Stream<T>`              |
| Swift      | `async` / `await`  | `AsyncSequence`          |
| TypeScript | `Promise<T>`       | `AsyncGenerator<T>`      |
| C#         | `Task<T>`          | `IAsyncEnumerable<T>`    |

## Project Structure

```
/plugin
  main.go              <- protoc plugin: protogen + template execution

/templates
  dart.tmpl
  kotlin.tmpl
  swift.tmpl
  typescript.tmpl
  csharp.tmpl

/runtime
  transport.dart
  transport.kt
  transport.swift
  transport.ts
  transport.cs
```

One template per language generates both client stubs and server handler/router. The `side` parameter controls which part is emitted. Runtime files are copied alongside generated code.

## Usage

```bash
protoc --kalam_out=. \
       --kalam_opt=socket=/tmp/myapp.sock,side=client,lang=dart \
       user.proto
```

## Modes

- **production** — IPC only, `Transport.init()` called automatically on import
- **debug** — IPC + exposes a debug RPC server for E2E testing (same binary as production)

## Roadmap

- [ ] Dart client + Kotlin server PoC
- [ ] Remaining client languages (Swift, TypeScript, C#, Kotlin)
- [ ] Server support for all languages
- [ ] Stream support (frame_type 1, 2)
- [ ] React Native transport plugin (TCP fallback)
