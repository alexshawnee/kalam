package com.kalam.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val protocGenKalamBinary: RegularFileProperty

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

        // Language-specific protobuf message plugins
        when (lang) {
            "swift" -> args.add("--swift_out=${outDir}")
            "dart" -> args.add("--dart_out=${outDir}")
        }

        args.add("--kalam_out=${outDir}")
        if (lang != "dart") {
            args.add("--kalam_opt=lang=${lang}")
        }

        for (dir in protoPathDirs) {
            args.add("--proto_path=${dir.absolutePath}")
        }

        for (proto in resolvedProtos) {
            args.add(proto.absolutePath)
        }

        // If explicit binary path provided, prepend its directory to PATH
        // Otherwise protoc-gen-kalam must be on PATH (e.g. via go install)
        val extraPaths = mutableListOf<String>()
        if (protocGenKalamBinary.isPresent) {
            extraPaths.add(protocGenKalamBinary.get().asFile.parentFile.absolutePath)
        }
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
