# 共享运行时不能依据宿主当前调用量裁剪动态业务的导出符号。
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-repackageclasses app.luoxianlv.hot.runtimeinternal
-keepattributes SourceFile,LineNumberTable
