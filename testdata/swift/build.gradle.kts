plugins {
    base
}

tasks.register<Exec>("resolve") {
    description = "Resolve Swift package dependencies"
    workingDir = projectDir
    commandLine("swift", "package", "resolve")
}

tasks.register<Exec>("run") {
    description = "Run Swift integration test"
    dependsOn("resolve", ":protoc-gen-kalam:generateSwift")
    workingDir = projectDir
    commandLine("swift", "run")
}
