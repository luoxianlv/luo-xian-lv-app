plugins {
    // 根项目只声明插件版本，不应用；实际构建模块见 :app
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
