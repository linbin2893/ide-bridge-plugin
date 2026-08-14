plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    // 新一代插件（2.x），支持 Gradle 8.5+ / 9.x
    id("org.jetbrains.intellij.platform") version "2.4.0"
}

group = "com.dsh"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 以 IntelliJ IDEA Community 2023.3 为最低目标平台
        intellijIdeaCommunity("2023.3")
        // PsiClass / PsiTreeUtil 取 Java 类信息，需要 Java 插件（基础平台不含）
        bundledPlugin("com.intellij.java")
    }
    // JSON 用平台自带的 Gson（lib/util-8.jar），不额外捆绑序列化库：
    // 平台同样自带 kotlinx-serialization，插件再捆一份会因 parent-first 被遮蔽，
    // 编译期版本与运行期版本对不上时是运行期 NoSuchMethodError
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            // 不设上限：写死的 untilBuild 会让插件在未来版本被直接禁用
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(17)
}
