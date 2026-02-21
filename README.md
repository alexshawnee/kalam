# Kalam

Transport-agnostic RPC codegen from `.proto` files.

## How It Looks

```dart
// Dart
final user = await UserService.getUser(GetUserRequest(id: 42));
```

```swift
// Swift
let user = try await UserService.getUser(req)
```

```kotlin
// Kotlin
val user = UserService.getUser(request)
```

## Supported Languages

| Platform   | Unary              | Streaming                | Status |
|------------|--------------------|--------------------------|--------|
| Dart       | `Future<T>`        | `Stream<T>`              | Done   |
| Kotlin     | `suspend fun`      | `Flow<T>`                | Done   |
| Swift      | `async` / `await`  | `AsyncThrowingStream<T>` | Done   |
| TypeScript | `Promise<T>`       | `AsyncGenerator<T>`      | —      |
| C#         | `Task<T>`          | `IAsyncEnumerable<T>`    | —      |
| C++        | `co_await` / TBD   | TBD                      | —      |

## Project Structure

```
kalam/
  protoc-gen-klm/                     # Go: RPC stubs codegen (all languages)
    gen/                              # Shared types, helpers, Run()
      gen.go
      templates/{kotlin,swift,dart}.tmpl
    cmd/
      protoc-gen-klm-kotlin/main.go   # One binary per language
      protoc-gen-klm-swift/main.go
      protoc-gen-klm-dart/main.go

  protoc-gen-kotlinx/                 # Go: KMP-compatible protobuf DTOs
    main.go
    templates/kotlinx.tmpl

  runtime-kotlin/                     # KMP UDS transport (Maven)
    src/
      commonMain/kotlin/com/kalam/   # Frame, Kalam, KalamServer
      jvmMain/                        # java.nio UDS
      appleMain/                      # POSIX UDS via cinterop
      mingwMain/                      # Winsock UDS

  runtime-swift/                      # Swift UDS transport (CocoaPods/SPM)
  runtime-dart/                       # Dart UDS transport (pub.dev)

  gradle-plugin/                      # Gradle plugin for KMP projects
  tests/                              # Integration tests
    user.proto
    kotlin/                           # Kotlin: unary + streaming + mux
    swift/
    dart/
```

## Usage

### Install protoc plugins

```bash
cd protoc-gen-klm && go install ./cmd/...
cd protoc-gen-kotlinx && go install .
```

### Generate code directly

```bash
protoc --kotlinx_out=. --klm-kotlin_out=. --proto_path=. service.proto
protoc --swift_out=. --klm-swift_out=. --proto_path=. service.proto
protoc --dart_out=. --klm-dart_out=. --proto_path=. service.proto
```

### Gradle plugin (KMP projects)

```kotlin
kalam {
    proto.from("proto")
    kotlin()   // → protoc-gen-kotlinx + protoc-gen-klm-kotlin
    swift()    // → protoc --swift_out + protoc-gen-klm-swift
    dart()     // → protoc --dart_out + protoc-gen-klm-dart
}
```

### Run tests

```bash
./gradlew :tests:kotlin:run
./gradlew :tests:swift:run
./gradlew :tests:dart:run
```

## Wire Protocol

```
[1B version][4B request_id][1B frame_type][4B method_len][method][4B payload_len][payload]
```

- `0` — unary request/response
- `1` — stream chunk
- `2` — stream end
- `3` — error

All integers are big-endian. Payload is protobuf-encoded.
