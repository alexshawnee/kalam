plugins {
    base
}

val goBinary = layout.buildDirectory.file("protoc-gen-kalam")

tasks.register<Exec>("goBuild") {
    description = "Build the protoc-gen-kalam Go binary"
    workingDir = projectDir
    commandLine("go", "build", "-o", goBinary.get().asFile.absolutePath, ".")
    inputs.files("main.go", "go.mod", "go.sum")
    inputs.dir("templates")
    inputs.dir("runtime")
    outputs.file(goBinary)
}

tasks.register<Exec>("generate") {
    description = "Run protoc with kalam + dart plugins"
    dependsOn("goBuild")

    val protoDir = rootProject.file("testdata")
    val dartOut = rootProject.file("testdata/dart/generated")

    inputs.file(protoDir.resolve("user.proto"))
    inputs.file(goBinary)
    outputs.dir(dartOut)

    doFirst {
        dartOut.deleteRecursively()
        dartOut.mkdirs()
    }

    val pubCache = System.getProperty("user.home") + "/.pub-cache/bin"
    environment("PATH", "${goBinary.get().asFile.parentFile.absolutePath}:$pubCache:${System.getenv("PATH")}")
    commandLine(
        "protoc",
        "--dart_out=${dartOut}",
        "--kalam_out=${dartOut}",
        "--proto_path=${protoDir}",
        "${protoDir}/user.proto"
    )
}

tasks.register<Exec>("generateKotlin") {
    description = "Run protoc with kalam plugin for Kotlin"
    dependsOn("goBuild")

    val protoDir = rootProject.file("testdata")
    val kotlinOut = rootProject.file("testdata/kotlin/generated")

    inputs.file(protoDir.resolve("user.proto"))
    inputs.file(goBinary)
    outputs.dir(kotlinOut)

    doFirst {
        kotlinOut.deleteRecursively()
        kotlinOut.mkdirs()
    }

    environment("PATH", "${goBinary.get().asFile.parentFile.absolutePath}:${System.getenv("PATH")}")
    commandLine(
        "protoc",
        "--kalam_out=${kotlinOut}",
        "--kalam_opt=lang=kotlin",
        "--proto_path=${protoDir}",
        "${protoDir}/user.proto"
    )
}

tasks.register<Exec>("generateSwift") {
    description = "Run protoc with kalam + swift plugins"
    dependsOn("goBuild")

    val protoDir = rootProject.file("testdata")
    val swiftOut = rootProject.file("testdata/swift/Sources/Generated")

    inputs.file(protoDir.resolve("user.proto"))
    inputs.file(goBinary)
    outputs.dir(swiftOut)

    doFirst {
        swiftOut.deleteRecursively()
        swiftOut.mkdirs()
    }

    environment("PATH", "${goBinary.get().asFile.parentFile.absolutePath}:${System.getenv("PATH")}")
    commandLine(
        "protoc",
        "--swift_out=${swiftOut}",
        "--kalam_out=${swiftOut}",
        "--kalam_opt=lang=swift",
        "--proto_path=${protoDir}",
        "${protoDir}/user.proto"
    )
}

tasks.register<Delete>("goClean") {
    description = "Remove the built Go binary"
    delete(goBinary)
}

tasks.named("clean") {
    dependsOn("goClean")
}
