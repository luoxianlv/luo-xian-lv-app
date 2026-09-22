# 友盟统计 SDK（U-App）混淆保留规则，见官方集成文档：
# https://developer.umeng.com/docs/119267/detail/118584
-keep class com.umeng.** {*;}
-keep class org.repackage.** {*;}
-keep class com.uyumao.** { *; }
# U-APM 性能监控
-keep class com.uc.** { *; }
-keep class com.efs.** { *; }
-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# U-APM 网络监控引用 okhttp3，但 SDK 不强制打包（无 okhttp 时该模块不工作），R8 报缺失类
-dontwarn okhttp3.**
-dontwarn okio.**
