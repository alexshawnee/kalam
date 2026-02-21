# Kalam

A `protoc` plugin that generates idiomatic RPC client and server code from `.proto` files with pluggable transports.

## How It Looks

### Client

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

### Server

```dart
class MyUserService extends UserServiceHandler {
  @override
  Future<GetUserResponse> getUser(GetUserRequest request) async {
    return GetUserResponse(name: 'User ${request.id}');
  }
}
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
  protoc/                           # Go protoc plugin (codegen)
    main.go
    templates/
      dart.tmpl
      kotlin.tmpl
      swift.tmpl
    runtime/
      kalam.dart                    # Dart UDS transport
      kalam.swift                   # Swift UDS transport
    build.gradle.kts

  runtime/                          # Kotlin Multiplatform UDS transport
    src/
      commonMain/kotlin/com/kalam/  # Frame, Kalam, KalamServer
      jvmMain/kotlin/com/kalam/    # java.nio UDS
      appleMain/kotlin/com/kalam/  # POSIX UDS via cinterop
      mingwMain/kotlin/com/kalam/  # Winsock UDS (TODO)

  testdata/
    user.proto
    dart/                           # Dart integration test
    kotlin/                         # Kotlin integration test
    swift/                          # Swift integration test
```

## Usage

```bash
# Build the protoc plugin
./gradlew :protoc:goBuild

# Generate + run tests
./gradlew :protoc:generate          # Dart
./gradlew :protoc:generateKotlin    # Kotlin
./gradlew :protoc:generateSwift     # Swift

./gradlew :testdata:dart:run        # Dart integration test
./gradlew :testdata:kotlin:run      # Kotlin integration test
./gradlew :testdata:swift:run       # Swift integration test
```

## Wire Protocol

```
[1 byte version][4 bytes request_id][1 byte frame_type][4 bytes method_len][method][4 bytes payload_len][payload]
```

- `frame_type 0` — unary request/response
- `frame_type 1` — stream chunk
- `frame_type 2` — stream end
- `frame_type 3` — error

All integers are big-endian. Payload is protobuf-encoded.
