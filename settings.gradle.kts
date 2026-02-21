pluginManagement {
    includeBuild("gradle-plugin")
}

rootProject.name = "kalam"

include("runtime-kotlin")
include("tests:dart")
include("tests:kotlin")
include("tests:swift")
