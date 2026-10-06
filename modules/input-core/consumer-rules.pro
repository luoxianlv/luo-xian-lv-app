# JNI 入口与工作线程回调保持名称；其余类仍交给 R8 处理。
-keep class app.luoxianlv.input.TouchEngine { *; }
-keep interface app.luoxianlv.input.TouchEngine$Listener { *; }
# app_process 由固定类名启动；只保留入口与平台 TLS 导出反射要求的类型。
-keep class app.luoxianlv.input.InputHelperMain { public static void main(java.lang.String[]); }
# Shizuku 反射调用带宿主上下文的构造器。
-keep class app.luoxianlv.input.InputUserService { public <init>(android.content.Context); }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class io.github.muntashirakon.crypto.spake2.Spake2Context { *; }
