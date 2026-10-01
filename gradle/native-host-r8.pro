# 热更业务按原始名称链接宿主 SDK；名称、层级、成员声明及描述符类型严格保留。
# 动态业务不在宿主 R8 闭世界分析中，契约本身不开放 allowoptimization。
-keep,includedescriptorclasses class app.luoxianlv.hot.contract.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters,*Annotation*,AnnotationDefault,SourceFile,LineNumberTable

# 只重命名宿主内部实现，不与 runtime 的保留 SDK 或业务命名空间混用。
-repackageclasses 'app.luoxianlv.host.r8'
