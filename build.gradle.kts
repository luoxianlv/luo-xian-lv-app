plugins {
    // 根项目只声明插件版本，不应用；实际构建模块见 :app
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // 只把 U-APM 插桩插件（EfsFactory）放进 classpath，不应用：其自带的 EfsNewPlugin 无法在 Kotlin DSL 下配置，
    // 实际注册在 app/build.gradle.kts 里通过 AGP 的 Instrumentation API 完成。
    alias(libs.plugins.umeng.apm.plugin) apply false
}
