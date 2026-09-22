# 友盟统计 SDK（U-App）混淆保留规则，见官方集成文档：
# https://developer.umeng.com/docs/119267/detail/118584
-keep class com.umeng.** {*;}
-keep class org.repackage.** {*;}
-keep class com.uyumao.** { *; }
-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
