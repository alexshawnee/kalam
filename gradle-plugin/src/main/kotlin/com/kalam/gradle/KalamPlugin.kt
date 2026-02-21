package com.kalam.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider

class KalamPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("kalam", KalamExtension::class.java)

        project.afterEvaluate {
            val genTasks = mutableListOf<TaskProvider<GenerateKalamTask>>()

            for (langConfig in extension.languages) {
                val lang = langConfig.name
                val taskName = "generateKalam${lang.replaceFirstChar { it.uppercase() }}"

                val outputDir = if (langConfig.outputDir != null) {
                    project.layout.projectDirectory.dir(langConfig.outputDir!!)
                } else {
                    project.layout.buildDirectory.dir("generated/kalam/${lang}").get()
                }

                val genTask = project.tasks.register(taskName, GenerateKalamTask::class.java) { task ->
                    task.description = "Generate kalam ${lang} stubs from proto files"
                    task.group = "kalam"

                    task.protoFiles.from(extension.proto)
                    task.language.set(lang)
                    task.protocPath.set(extension.protocPath)
                    task.outputDirectory.set(outputDir)

                }

                genTasks.add(genTask)

                if (lang == "kotlin") {
                    wireKotlinSources(project, genTask, outputDir)
                }
            }

            // Umbrella task
            if (genTasks.isNotEmpty()) {
                project.tasks.register("generateKalam") { task ->
                    task.description = "Generate kalam stubs for all configured languages"
                    task.group = "kalam"
                    task.dependsOn(genTasks)
                }
            }
        }
    }

    private fun wireKotlinSources(
        project: Project,
        genTask: TaskProvider<GenerateKalamTask>,
        outputDir: org.gradle.api.file.Directory
    ) {
        // kotlin("jvm") plugin
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType(SourceSetContainer::class.java)
                .named("main") { sourceSet ->
                    sourceSet.java.srcDir(outputDir)
                }
            project.tasks.named("compileKotlin") {
                it.dependsOn(genTask)
            }
        }

        // kotlin("multiplatform") plugin
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.tasks.matching {
                it.name.contains("compileKotlin", ignoreCase = true)
            }.configureEach {
                it.dependsOn(genTask)
            }
        }
    }
}
