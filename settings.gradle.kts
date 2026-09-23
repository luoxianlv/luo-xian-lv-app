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
rootProject.name = "AutoPlayMusic"
include(":app")
