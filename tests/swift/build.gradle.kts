plugins {
    base
    id("com.kalam")
}

kalam {
    proto.from(rootProject.file("tests/user.proto"))
    swift()
}

tasks.register<Exec>("resolve") {
    description = "Resolve Swift package dependencies"
    workingDir = projectDir
    commandLine("swift", "package", "resolve")
}

tasks.register<Exec>("run") {
    description = "Run Swift integration test"
    dependsOn("resolve", "generateKalamSwift")
    workingDir = projectDir
    commandLine("swift", "run")
}
