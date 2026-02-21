// swift-tools-version: 5.7
import PackageDescription

let package = Package(
    name: "KalamRuntime",
    platforms: [.iOS(.v13), .macOS(.v10_15)],
    products: [
        .library(name: "KalamRuntime", targets: ["KalamRuntime"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.25.0"),
    ],
    targets: [
        .target(
            name: "KalamRuntime",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
            ],
            path: "Sources"
        ),
    ]
)
