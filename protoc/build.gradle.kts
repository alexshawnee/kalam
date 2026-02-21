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

tasks.register<Delete>("goClean") {
    description = "Remove the built Go binary"
    delete(goBinary)
}

tasks.named("clean") {
    dependsOn("goClean")
}
