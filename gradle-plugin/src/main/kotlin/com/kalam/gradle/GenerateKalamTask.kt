package com.kalam.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject

@CacheableTask
abstract class GenerateKalamTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protoFiles: ConfigurableFileCollection

    @get:Input
    abstract val language: Property<String>

    @get:Input
    abstract val protocPath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDirectory.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val lang = language.get()
        val protoc = protocPath.get()

        // Resolve proto files: if directories, find .proto within; otherwise use files directly
        val protoDirs = protoFiles.files.filter { it.isDirectory }.toSet()
        val resolvedProtos = if (protoDirs.isNotEmpty()) {
            protoDirs.flatMap { dir ->
                dir.walkTopDown().filter { it.extension == "proto" }.toList()
            }
        } else {
            protoFiles.files.filter { it.name.endsWith(".proto") }.toList()
        }

        if (resolvedProtos.isEmpty()) {
            logger.warn("No .proto files found for kalam generation")
            return
        }

        val protoPathDirs = if (protoDirs.isNotEmpty()) {
            protoDirs
        } else {
            resolvedProtos.map { it.parentFile }.toSet()
        }

        val args = mutableListOf(protoc)

        // Each language has its own protoc plugin(s)
        when (lang) {
            "kotlin" -> {
                args.add("--kotlinx_out=${outDir}")
                args.add("--klm-kotlin_out=${outDir}")
            }
            "swift" -> {
                args.add("--swift_out=${outDir}")
                args.add("--klm-swift_out=${outDir}")
            }
            "dart" -> {
                args.add("--dart_out=${outDir}")
                args.add("--klm-dart_out=${outDir}")
            }
        }

        for (dir in protoPathDirs) {
            args.add("--proto_path=${dir.absolutePath}")
        }

        for (proto in resolvedProtos) {
            args.add(proto.absolutePath)
        }

        // Add pub-cache to PATH for Dart protoc plugin
        val extraPaths = mutableListOf<String>()
        if (lang == "dart") {
            extraPaths.add("${System.getProperty("user.home")}/.pub-cache/bin")
        }
        val currentPath = System.getenv("PATH") ?: ""

        execOperations.exec { spec ->
            spec.commandLine(args)
            if (extraPaths.isNotEmpty()) {
                spec.environment("PATH", (extraPaths + currentPath).joinToString(":"))
            }
        }
    }
}
