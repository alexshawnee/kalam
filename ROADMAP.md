# Kalam — Roadmap

## Core Idea

Transport-agnostic RPC codegen. gRPC is tied to HTTP/2. Kalam separates codegen from transport.

## Architecture

Every client on every language needs exactly two runtime dependencies:

1. **Protobuf** — serialization (`toBytes` / `fromBytes`)
2. **Kalam Runtime** — transport (`send` / `receive`)

Generated stubs glue them together:

```
stub.check(request)
  → request.toBytes()        // protobuf
  → transport.call(bytes)    // kalam runtime
  → Response.fromBytes(data) // protobuf
```

### Three Layers

```
Codegen     — protoc plugins that emit stubs per language (build-time only)
Wire        — framing protocol: encode/decode, mux, streaming (no I/O)
Transport   — UDS for local IPC, HTTP for curl/Postman
```

### Codegen: protoc Plugins

| Plugin | What it generates |
|--------|-------------------|
| `protoc-gen-kotlinx` | `@Serializable` data classes for KMP (fills the gap `protoc --kotlin_out` leaves) |
| `protoc-gen-klm-kotlin` | Kotlin RPC stubs + handlers |
| `protoc-gen-klm-swift` | Swift RPC stubs + handlers |
| `protoc-gen-klm-dart` | Dart RPC stubs + handlers |
| `protoc-gen-klm-cpp` | C++ RPC stubs + handlers |
| `protoc-gen-klm-sharp` | C# RPC stubs + handlers |
| `protoc-gen-klm-ts` | TypeScript RPC stubs + handlers |

For Dart and Swift, standard protobuf plugins (`--dart_out`, `--swift_out`) handle DTO generation.
For Kotlin, `protoc-gen-kotlinx` generates DTOs because `protoc --kotlin_out` produces JVM-only code.

### Runtime Packages

Each language gets a published package with the transport implementation:

| Language | Package | Registry |
|----------|---------|----------|
| Kotlin (KMP) | `com.kalam:runtime` | Maven (required — multiplatform expect/actual) |
| Swift | `KalamRuntime` | CocoaPods / SPM |
| Dart | `kalam_runtime` | pub.dev |
| C++ | `kalam-runtime` | vcpkg / Conan / header-only |
| C# | `Kalam.Runtime` | NuGet |
| TypeScript | `@kalam/runtime` | npm |

Standard delivery is always a package — versioned, updatable, reproducible on CI.

### Special Runtime Packages

Some platforms can't access UDS from their standard runtime:

| Package | Why |
|---------|-----|
| `react-native-klm-runtime` | JSC/Hermes have no socket API — native module bridges UDS |
| `electron-klm-runtime` | Browser context has no sockets — node addon bridges UDS |

Dart, C#, C++, Swift, Kotlin — all have native socket access, no bridge needed.

## Adding a New Language

Formula is always the same:

1. Write `protoc-gen-klm-{lang}` — one Go template (~100 lines)
2. Publish `kalam-{lang}-runtime` — UDS client (~200 lines) as a package
3. In consumer project: run codegen, add two dependencies (protobuf + kalam runtime)

Example — Unity:
```bash
protoc --klm-sharp_out=./Assets/Generated  *.proto
```
```xml
<PackageReference Include="Google.Protobuf" />
<PackageReference Include="Kalam.Runtime" />
```

## Transport Matrix

| Consumer      | Runtime          | Transport | Module     |
|---------------|------------------|-----------|------------|
| Native Swift  | Same binary      | UDS       | kalam-ipc  |
| Native C++    | Same binary      | UDS       | kalam-ipc  |
| Flutter       | Dart VM          | UDS       | kalam-ipc  |
| React Native  | JSC / Hermes     | UDS       | kalam-ipc  |
| Electron      | V8               | UDS       | kalam-ipc  |
| Unity         | Mono / IL2CPP    | UDS       | kalam-ipc  |
| Postman / curl| —                | HTTP      | kalam-rpc  |

## XPoint SDK Integration (KMP)

For our KMP project specifically:

| Platform | Codegen | Runtime | Notes |
|----------|---------|---------|-------|
| Android | Kotlin stubs | Maven `com.kalam:runtime` | UDS relay to native server |
| iOS | Swift stubs in podspec | CocoaPods `KalamRuntime` | UDS to sdk.framework |
| macOS/Windows | C++ stubs | header alongside .dll/.dylib | UDS to native binary |
| Flutter | Dart stubs | pub `kalam_runtime` | dart:io sockets directly |
| React Native | TS stubs | npm `@kalam/runtime` + `react-native-klm-runtime` | native bridge for sockets |

### Gradle Plugin

The kalam gradle plugin orchestrates codegen for KMP projects:

```kotlin
kalam {
    proto.from("proto")
    kotlin()    // → protoc-gen-kotlinx + protoc-gen-klm-kotlin
    swift()     // → protoc --swift_out + protoc-gen-klm-swift
    dart()      // → protoc --dart_out + protoc-gen-klm-dart
}
```

## Why Not gRPC?

gRPC bakes HTTP/2 into the generated stubs (Channel, Metadata, StatusCode). Can't swap transport without losing half the API. Designed for microservices over network, not for local IPC between runtimes in the same app.

## Open Questions

### Swift: iOS 13 Compatibility
- Current Swift template and runtime use `async/await`, `AsyncThrowingStream`, `Task`, `CheckedContinuation` — all require iOS 15+
- Need to support iOS 13+
- Options:
  - **Callbacks**: `completion: @escaping (Result<T, Error>) -> Void` — works on iOS 13+
  - **Combine**: `Future<T, Error>` / `AnyPublisher<T, Error>` — works on iOS 13+
  - **Both**: callback API as default, async/await wrappers under `@available(iOS 15, *)`

### C++ Runtime
- C++17 (callbacks) vs C++20 (coroutines)?
- Standalone Asio vs libuv?
- `std::future` is too limited (no chaining, blocks on `.get()`)
- Asio: header-only option exists but full Boost pulls megabytes
- libuv: ~600KB, C API, proven (Node.js), but callback-oriented

### ZeroMQ as Optional Transport
- PUB/SUB pattern for fan-out (one stream, many subscribers across platforms)
- Current wire protocol is point-to-point only
- ZeroMQ `inproc://` = shared memory between threads, `ipc://` = UDS under the hood
- Would enable `SharedFlow`-like semantics across language boundaries
- Trade-off: native dependency per platform vs implementing fan-out ourselves

### Rust Core for kalam-wire
- Currently wire protocol (Frame, FrameReader, encode/decode) is reimplemented per language (~120 lines each)
- With 6+ languages this becomes a maintenance burden
- Rust core would compile to `libkalam_wire.a` / `.dylib` with C API, zero runtime overhead
- Each language gets a thin wrapper (~30-40 lines) that maps C callbacks → native async primitives (Future, Flow, AsyncStream, etc.)
- Binding approach per language: Swift (direct C import), Kotlin/Native (cinterop), C++ (direct `#include`), C# (P/Invoke), Dart (dart:ffi)
- Caveat: Node.js N-API bindings are painful; Dart goes from single-file runtime to a library with native dependency
- Trade-off: single source of truth vs added build complexity (cross-compile Rust for each target)
- Makes sense when language count justifies it; premature with 3 languages

### Shared Memory Transport (kalam-transport-mem)
- Replace UDS with shared memory (ring buffer + semaphores) for Flutter, React Native, Electron, Unity
- Eliminates socket file on filesystem — cleaner, no cleanup needed
- Lower latency than UDS (no kernel round-trip for each message)
- Approach: thin C library (Boost.Interprocess-style, but C not C++) with FFI bindings per language
- KMP side (server): needs C interop layer since KMP can't link C++ directly
- Client side: Flutter/RN plugins wrap the same C library via dart:ffi / JSI
- Drop-in replacement: swap `UdsTransport` → `MemTransport`, same `KalamTransport` interface
- Only worth doing when UDS latency becomes a bottleneck or socket file management is painful

### Bidirectional Streaming
- Current protocol supports server streaming only
- Client streaming and bidi streaming need flow control
- Wire protocol change needed (new frame types? backpressure?)

## Checklist

- [ ] Extract `protoc-gen-kotlinx` from kalam (standalone KMP protobuf codegen)
- [ ] Rename codegen plugins to `protoc-gen-klm-{lang}` scheme
- [ ] Publish Kotlin runtime to Maven (`com.kalam:runtime`)
- [ ] Publish Swift runtime to CocoaPods/SPM (`KalamRuntime`)
- [ ] Publish Dart runtime to pub.dev (`kalam_runtime`)
- [ ] Extract abstract `Transport` interface from current code
- [ ] Split into kalam / kalam-wire / kalam-ipc modules
- [ ] iOS 13 compatibility for Swift template and runtime
- [ ] HTTP transport (kalam-rpc)
- [ ] C++ codegen + runtime
- [ ] C# codegen + runtime (NuGet)
- [ ] TypeScript codegen + runtime (npm)
- [ ] `react-native-klm-runtime` native module
- [ ] Bidirectional streaming
- [ ] Shared memory transport (eliminate socket files, lower latency)
- [ ] ZeroMQ as optional transport (PUB/SUB fan-out)
