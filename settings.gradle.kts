pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // 友盟 apm-plugin 没有发布 Gradle Plugin Marker，只能把插件 id 映射到 Maven 模块。
            if (requested.id.id == "com.efs.sdk.plugin") {
                useModule("com.umeng.umsdk:apm-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LuoXianLv"
include(":app")
// 项目 ID 保持稳定，物理目录按正式模块与测试应用归组。
listOf("app-host", "app-runtime", "app-business", "hot-core", "hot-contract", "business-ui", "update-core")
    .forEach { name ->
        include(":$name")
        project(":$name").projectDir = file("modules/$name")
    }
listOf("hot-runtime", "hot-business").forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("samples/$name")
}
