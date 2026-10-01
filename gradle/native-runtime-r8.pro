# 当前 runtime-sdk.jar 导出全部编译类型与依赖；没有可安全裁剪/改名的子集。
# allowoptimization 只开放实现优化；不开放 allowshrinking/allowobfuscation。
-keep,allowoptimization,includedescriptorclasses class ** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters,*Annotation*,AnnotationDefault,SourceFile,LineNumberTable
-repackageclasses 'app.luoxianlv.runtime.r8'

# 与现有 :app 相同的友盟可选网络监控依赖；不吞掉其它缺失类型诊断。
-dontwarn okhttp3.**
-dontwarn okio.**

# error_prone_annotations 2.15.0：两个 CLASS/ANNOTATION_TYPE 元注解的 deprecated
# value() 仅引用 JDK java.compiler 枚举；APP/冻结依赖无其它运行调用，不补入 JDK 类。
-dontwarn javax.lang.model.element.Modifier
