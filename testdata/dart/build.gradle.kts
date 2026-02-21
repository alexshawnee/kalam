plugins {
    base
}

tasks.register<Exec>("pubGet") {
    description = "Fetch Dart dependencies"
    workingDir = projectDir
    commandLine("dart", "pub", "get")
}

tasks.register<Exec>("run") {
    description = "Run Dart integration test"
    dependsOn("pubGet", ":protoc:generate")
    workingDir = projectDir
    commandLine("dart", "run", "integration_test.dart")
}
