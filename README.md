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
- `frame_type 3` — error

All multi-byte integers are big-endian. Payload is protobuf-encoded.

## Generated Code

### Client (example: Dart)

Names match the `.proto` definition exactly — no suffixes, no wrappers:

```dart
final response = await UserService.getUser(GetUserRequest(id: 42));
```

### Server (example: Dart)

Implement a typed handler interface, routing is generated:

```dart
class MyUserService extends UserServiceHandler {
  @override
  Future<GetUserResponse> getUser(GetUserRequest request) async {
    return GetUserResponse(name: 'User ${request.id}');
  }
}
```

## Async Primitives by Platform

| Platform   | Unary              | Streaming                |
|------------|--------------------|--------------------------|
| Kotlin     | `suspend fun`      | `Flow<T>`                |
| Dart       | `Future<T>`        | `Stream<T>`              |
| Swift      | `async` / `await`  | `AsyncSequence`          |
| TypeScript | `Promise<T>`       | `AsyncGenerator<T>`      |
| C#         | `Task<T>`          | `IAsyncEnumerable<T>`    |
| C++        | `co_await` / TBD   | TBD                      |

## Project Structure

```
kalam/
  settings.gradle.kts              # root build
  build.gradle.kts

  protoc/                           # Go protoc plugin
    main.go
    go.mod / go.sum
    templates/
      dart.tmpl
    runtime/
      kalam.dart
    build.gradle.kts                # goBuild, generate tasks

  runtime/                          # KMP library (Kotlin client/server runtime)
    build.gradle.kts                # kotlin-multiplatform + maven-publish
    src/
      commonMain/kotlin/com/kalam/  # Frame, FrameReader, Kalam, KalamServer
      jvmMain/kotlin/com/kalam/    # java.nio UDS
      appleMain/kotlin/com/kalam/  # POSIX UDS via cinterop
      mingwMain/kotlin/com/kalam/  # Winsock UDS (TODO)

  testdata/
    user.proto
    dart/                           # Dart integration test
    kotlin/                         # Kotlin integration test
```

## Usage

```bash
# Build the protoc plugin
./gradlew :protoc:goBuild

# Generate code for Dart integration tests
./gradlew :protoc:generate

# Publish KMP runtime to local Maven
./gradlew :runtime:publishToMavenLocal

# Run Dart integration test
cd testdata/dart && dart run integration_test.dart
```

## Roadmap

- [x] Wire protocol (framing, multiplexing, streaming, errors)
- [x] Dart client + server runtime
- [x] Dart codegen template
- [x] Kotlin Multiplatform runtime library
- [ ] Kotlin codegen template
- [ ] Bidirectional streaming
- [ ] Remaining client languages (Swift, TypeScript, C#)
- [ ] C++ runtime — decide on minimum standard (C++17 vs C++20 coroutines) and async I/O library (standalone Asio vs libuv); `std::future` is too limited (no chaining, blocks on `.get()`), so the runtime needs either Asio coroutines or libuv event loop under the hood
- [ ] Gradle plugin for codegen
- [ ] 💭 ZeroMQ as optional transport — PUB/SUB for fan-out scenarios (one stream, many subscribers across platforms); current UDS protocol is point-to-point only
