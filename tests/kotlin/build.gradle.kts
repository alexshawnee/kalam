plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.kalam")
    application
}

dependencies {
    implementation(project(":runtime-kotlin"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

kalam {
    proto.from(rootProject.file("tests/user.proto"))
    kotlin()
}

application {
    mainClass.set("testdata.IntegrationTestKt")
}
