# media_app ProGuard / R8 规则
# 基于 proguard-android-optimize.txt(default),只补充项目特定 keep。

# --- kotlinx-serialization ---
# 插件生成的 $$serializer 内部类及其无参构造:$Companion.serializer() 反射调用。
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 所有 @Serializable 标注的类与其生成的 serializer:R8 full mode 需显式保留,
# 否则 JSON 编解码会因缺失 serializer 元数据而失败。
-keep,includedescriptorclasses class com.example.mediacenter.**$$serializer { *; }
-keepclassmembers class com.example.mediacenter.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.mediacenter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 带 @SerialName 的字段常量不可被内联/重命名(影响 JSON 字段名)。
-keepclassmembers @kotlinx.serialization.Serializable class com.example.mediacenter.** {
    <fields>;
}

# --- Conscrypt ---
# JNI 原生层通过类名反射加载,整包保留。
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
