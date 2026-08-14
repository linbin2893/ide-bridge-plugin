plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "ide-bridge"

// 只有 IDEA 端是 Gradle 工程；dsh-plugin 是纯 ESM，不参与构建
include("idea-plugin")
