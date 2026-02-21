pluginManagement {
    includeBuild("gradle-plugin")
}

rootProject.name = "kalam"

include("protoc-gen-kalam")
include("runtime")
include("testdata:dart")
include("testdata:kotlin")
include("testdata:swift")
