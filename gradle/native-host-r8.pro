# 热更业务按原始名称链接宿主 SDK；所有契约成员及描述符类型都必须保留。
-keep,allowoptimization,includedescriptorclasses class app.luoxianlv.hot.contract.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters,*Annotation*,AnnotationDefault,SourceFile,LineNumberTable

# 只重命名宿主内部实现，不与 runtime 的保留 SDK 或业务命名空间混用。
-repackageclasses 'app.luoxianlv.host.r8'
