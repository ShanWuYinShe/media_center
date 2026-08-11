# media_app_legacy ProGuard / R8 规则
# 基于 proguard-android-optimize.txt(default),只补充项目特定 keep。
# legacy 依赖冻结在支持 API 19 的老版本(ExoPlayer 2.x / OkHttp 3.12),
# 这些库的 consumer rules 可能不全,这里显式保留关键反射点。

# --- ExoPlayer 2.x ---
# 2.x 大量用反射加载 Decoder/Extractor/Adapter(按全限定类名 newInstance),
# consumer rules 在 2.18.7 可能不全,整包保留最稳妥。
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# --- kotlinx-serialization ---
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.mediacenter.**$$serializer { *; }
-keepclassmembers class com.example.mediacenter.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.mediacenter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.example.mediacenter.** {
    <fields>;
}

# --- Conscrypt ---
# JNI 原生层通过类名反射加载,整包保留。
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# --- OkHttp 3.12 ---
# 3.12 自带 consumer rules,但内部用反射读平台属性,补充 Platform 相关保留。
-dontwarn okhttp3.internal.platform.**

# --- kotlinx-coroutines ---
# 协程内部用反射,keep 状态机相关类。
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
