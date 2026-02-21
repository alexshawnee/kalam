plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "com.kalam"
version = "0.1.0"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    listOf(
        macosArm64(),
        macosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { target ->
        target.compilations.getByName("main") {
            cinterops { create("uds") { defFile("src/nativeInterop/cinterop/uds.def") } }
        }
    }

    mingwX64()

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        groupId = "com.kalam"
    }
}
