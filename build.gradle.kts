plugins {
    // 根项目只声明插件版本，不应用；实际构建模块见 :app
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
