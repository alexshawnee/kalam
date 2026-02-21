plugins {
    kotlin("jvm")
    id("com.google.protobuf") version "0.9.4"
    id("com.kalam")
    application
}

dependencies {
    implementation(project(":runtime"))
    implementation("com.google.protobuf:protobuf-java:4.29.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
}

kalam {
    proto.from(rootProject.file("testdata/user.proto"))
    kotlin()
}

application {
    mainClass.set("testdata.IntegrationTestKt")
}
