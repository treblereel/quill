rootProject.name = "quill-tests-gradle-multimodule"
include("common", "service", "custom")
project(":custom").projectDir = file("modules/custom")
