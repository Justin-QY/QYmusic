# QY MUSIC 混淆规则（简单加固，主要防止直接反编译复用接口与密钥）

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# JNI：签名方法在 native 层按类名查找，不能混淆
-keep class com.qymusic.app.QYMusicApplication { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 反射：MusicModel 通过字段名在 Bundle 与 MediaMetadata 之间拷贝
-keep class com.qymusic.app.model.** { *; }

# LitePal 通过反射读写实体
-keep class * extends org.litepal.crud.LitePalSupport { *; }
-keep class org.litepal.** { *; }
-dontwarn org.litepal.**

# kotlinx.serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# 网络转换器依赖泛型类型
-keep class com.qymusic.app.utils.converter.** { *; }
-keep class com.drake.** { *; }
-dontwarn com.drake.**
-dontwarn okhttp3.**
-dontwarn okio.**

# 保留基础信息，便于崩溃定位
-renamesourcefileattribute SourceFile

# Shizuku / hidden API
-keep class rikka.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn org.lsposed.hiddenapibypass.**
