# 当前 runtime-sdk.jar 导出全部编译类型与依赖；没有可安全裁剪/改名的子集。
# allowoptimization 只开放实现优化；不开放 allowshrinking/allowobfuscation。
-keep,allowoptimization,includedescriptorclasses class ** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters,*Annotation*,AnnotationDefault,SourceFile,LineNumberTable
-repackageclasses 'app.luoxianlv.runtime.r8'

# 与现有 :app 相同的友盟可选网络监控依赖；不吞掉其它缺失类型诊断。
-dontwarn okhttp3.**
-dontwarn okio.**
