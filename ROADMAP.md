# Kalam — Roadmap

## Core Idea

Transport-agnostic RPC codegen. gRPC is tied to HTTP/2. Kalam separates codegen from transport.

## Module Architecture

```
kalam              — codegen (stubs + handlers from .proto, zero transport dependency)
kalam-wire         — framing protocol (encode/decode, mux, streaming — no I/O)
kalam-ipc          — UDS transport (all platforms, all runtimes)
kalam-rpc          — HTTP transport (testable with Postman/curl)
```

### Dependencies

```
kalam-ipc  → kalam-wire → kalam
kalam-rpc  → kalam
```

### What each module does

**kalam** — pure codegen. Takes `.proto`, emits stubs that call an abstract `Transport`:

```swift
protocol KalamTransport {
    func call(_ method: String, _ payload: Data) async throws -> Data
    func stream(_ method: String, _ payload: Data) throws -> AsyncThrowingStream<Data, Error>
}
```

**kalam-wire** — the binary framing protocol. Encode/decode frames, request multiplexing, stream lifecycle. No sockets, no I/O — just bytes in, frames out. Testable in isolation, fuzzable.

**kalam-ipc** — UDS transport. Single transport for all platforms:

```swift
Kalam.transport = UdsTransport(path: "/tmp/app.sock")
```

**kalam-rpc** — HTTP transport. No wire protocol needed, HTTP handles framing:

```swift
Kalam.transport = HttpTransport(url: "http://localhost:8080")
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

## Why Not gRPC?

gRPC bakes HTTP/2 into the generated stubs (Channel, Metadata, StatusCode). Can't swap transport without losing half the API. Designed for microservices over network, not for local IPC between runtimes in the same app.

## Codegen Architecture

### Current State

protoc-gen-kalam generates both DTOs and RPC stubs for each language:

| Language | DTO generation | RPC stubs | Serialization runtime |
|----------|---------------|-----------|----------------------|
| Dart     | `protoc --dart_out` (standard) | `protoc --kalam_out` | `protobuf` package |
| Swift    | `protoc --swift_out` (standard) | `protoc --kalam_out` | `SwiftProtobuf` |
| Kotlin   | `protoc --kalam_out` (built-in) | `protoc --kalam_out` | `kotlinx.serialization.protobuf` |

For Dart and Swift, standard protobuf plugins handle DTO generation. For Kotlin, kalam generates DTOs itself because `protoc --kotlin_out` produces JVM-only code (`protobuf-java` dependency) which doesn't work for Kotlin Multiplatform.

### Target State: Extract protoc-gen-kotlinx

Split Kotlin DTO generation into a standalone protoc plugin:

```
protoc-gen-kotlinx   — generates @Serializable data classes from .proto (KMP-compatible)
protoc-gen-kalam     — generates RPC stubs only (all languages)
```

`protoc-gen-kotlinx` would:
- Generate `@Serializable` data classes with `@ProtoNumber` annotations
- Generate enum classes
- Provide `toByteArray()` / `parseFrom()` via `kotlinx.serialization.protobuf`
- Be usable independently of kalam (any project needing KMP protobuf)
- Fill the gap that Google's `protoc --kotlin_out` leaves for multiplatform

The kalam gradle plugin would then invoke it the same way it does for Dart/Swift:
```kotlin
"kotlin" -> args.add("--kotlinx_out=${outDir}")
"swift"  -> args.add("--swift_out=${outDir}")
"dart"   -> args.add("--dart_out=${outDir}")
```

This makes each piece independently useful and testable.

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

- [ ] Extract abstract `Transport` interface from current code
- [ ] Split into kalam / kalam-wire / kalam-ipc modules
- [ ] Extract `protoc-gen-kotlinx` from kalam (standalone KMP protobuf codegen)
- [ ] iOS 13 compatibility for Swift template and runtime
- [ ] HTTP transport (kalam-rpc)
- [ ] Bidirectional streaming
- [ ] TypeScript codegen + runtime
- [ ] C# codegen + runtime
- [ ] C++ runtime
- [ ] Shared memory transport (eliminate socket files, lower latency)
- [ ] ZeroMQ as optional transport (PUB/SUB fan-out)
