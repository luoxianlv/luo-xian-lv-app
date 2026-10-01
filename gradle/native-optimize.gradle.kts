import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.kotlin.dsl.configure

buildscript {
    dependencies {
        classpath(files(rootProject.buildscript.configurations.getByName("classpath")))
    }
}

// 从根项目 apply，必须早于三个应用模块的 variant 创建。
check(project == rootProject) { "native-optimize.gradle.kts must be applied by the root project" }
val optimizeValue = providers.gradleProperty("nativeOptimize").orNull ?: "false"
require(optimizeValue == "true" || optimizeValue == "false") { "nativeOptimize must be true or false" }
val optimizeNative = optimizeValue == "true"

listOf(":app-host", ":app-runtime", ":app-business").forEach { modulePath ->
    project(modulePath).plugins.withId("com.android.application") {
        project(modulePath).extensions.configure<ApplicationAndroidComponentsExtension> {
            finalizeDsl { android ->
                android.buildTypes.getByName("release").apply {
                    isMinifyEnabled = optimizeNative && modulePath != ":app-business"
                    // 分层 APK 的外部资源引用不能由单 APK 的 shrinker 判定不可达。
                    isShrinkResources = false
                    if (isMinifyEnabled) {
                        proguardFiles(
                            android.getDefaultProguardFile("proguard-android-optimize.txt"),
                            rootProject.file(
                                if (modulePath == ":app-host") "gradle/native-host-r8.pro"
                                else "gradle/native-runtime-r8.pro"
                            ),
                        )
                    }
                }
            }
        }
    }
}
