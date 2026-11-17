# 当前 runtime-sdk.jar 导出全部编译类型与依赖；没有可安全裁剪/改名的子集。
# 实际 R8 9.1.31 的 allowoptimization 已删改直接接口/成员声明并新增 final；
# 后续热更业务不在 R8 的闭世界分析中，整个 SDK 必须 strict keep。
-keep,includedescriptorclasses class ** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters,*Annotation*,AnnotationDefault,SourceFile,LineNumberTable
-repackageclasses 'app.luoxianlv.runtime.r8'

# 与现有 :app 相同的友盟可选网络监控依赖；不吞掉其它缺失类型诊断。
-dontwarn okhttp3.**
-dontwarn okio.**

# error_prone_annotations 2.15.0：两个 CLASS/ANNOTATION_TYPE 元注解的 deprecated
# value() 仅引用 JDK java.compiler 枚举；APP/冻结依赖无其它运行调用，不补入 JDK 类。
-dontwarn javax.lang.model.element.Modifier

# Ktor 3.6 的 IDEA 调试器探测以 catch(Throwable) 在 Android 返回 false。
# 已核对冻结 JAR 字节码；仅忽略这两个可选 JDK 管理接口，保留全部 SDK 成员。
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
