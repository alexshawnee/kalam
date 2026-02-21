plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.1.10"
}

group = "com.kalam"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

gradlePlugin {
    plugins {
        create("kalam") {
            id = "com.kalam"
            implementationClass = "com.kalam.gradle.KalamPlugin"
        }
    }
}
