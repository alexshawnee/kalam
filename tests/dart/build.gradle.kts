plugins {
    base
    id("com.kalam")
}

kalam {
    proto.from(rootProject.file("tests/user.proto"))
    dart {
        outputDir = "lib/generated"
    }
}

tasks.register<Exec>("pubGet") {
    description = "Fetch Dart dependencies"
    workingDir = projectDir
    commandLine("dart", "pub", "get")
}

tasks.register<Exec>("run") {
    description = "Run Dart integration test"
    dependsOn("pubGet", "generateKalamDart")
    workingDir = projectDir
    commandLine("dart", "run")
}
