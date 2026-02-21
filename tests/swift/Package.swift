// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "KalamIntegrationTest",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(path: "../../runtime-swift"),
    ],
    targets: [
        .executableTarget(
            name: "IntegrationTest",
            dependencies: [
                .product(name: "KalamRuntime", package: "runtime-swift"),
            ],
            path: "Sources"
        ),
    ]
)
