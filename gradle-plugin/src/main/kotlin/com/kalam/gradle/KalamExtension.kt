package com.kalam.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class KalamExtension @Inject constructor(objects: ObjectFactory) {

    /** Proto source files/directories. Usage: proto.from("src/main/proto") */
    val proto: ConfigurableFileCollection = objects.fileCollection()

    /** Path to protoc-gen-kalam binary. Required. */
    abstract val protocGenKalamPath: Property<String>

    /** Path to protoc binary. Defaults to "protoc" (found on PATH). */
    abstract val protocPath: Property<String>

    internal val languages = mutableListOf<LanguageConfig>()

    fun kotlin(configure: LanguageConfig.() -> Unit = {}) {
        languages.add(LanguageConfig("kotlin").apply(configure))
    }

    fun swift(configure: LanguageConfig.() -> Unit = {}) {
        languages.add(LanguageConfig("swift").apply(configure))
    }

    fun dart(configure: LanguageConfig.() -> Unit = {}) {
        languages.add(LanguageConfig("dart").apply(configure))
    }

    init {
        protocPath.convention("protoc")
    }
}

class LanguageConfig(val name: String) {
    /** Override output directory. Defaults to build/generated/kalam/{name}/ */
    var outputDir: String? = null
}
