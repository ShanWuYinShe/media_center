import java.net.URI
import java.net.URISyntaxException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.mediacenter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.mediacenter"
        minSdk = 19
        targetSdk = 34
        // 版本规范:只维护 versionName(语义化版本 major.minor.patch),versionCode 由
        // 它自动派生(major*10000 + minor*100 + patch + 基数)。这样:
        //  - 比较仍用 versionCode 整数(永远可靠,无字符串比较的 1.10<1.9 陷阱);
        //  - versionCode 与 SemVer 一一对应、单调递增;
        //  - 基数 20000000 保证 > 历史日期值(20260704),兼容已部署旧版设备收得到更新。
        // 发版时只需改 versionName 一个值。
        versionName = "1.1.0"
        versionCode = semverToCode(versionName!!)
        multiDexEnabled = true

        val serverBaseUrl = providers.gradleProperty("MEDIA_SERVER_URL")
            .orElse(providers.environmentVariable("MEDIA_SERVER_URL"))
            .orElse("https://yinghuoren.com/api/media")
            .map { it.trim().trimEnd('/') }
            .orNull
            ?: error("MEDIA_SERVER_URL default is missing")
        val serverUri = try {
            URI(serverBaseUrl)
        } catch (error: URISyntaxException) {
            throw GradleException("MEDIA_SERVER_URL must be a valid URL", error)
        }
        if (serverUri.scheme?.lowercase() != "https" || serverUri.host.isNullOrBlank()) {
            throw GradleException(
                "MEDIA_SERVER_URL must be an absolute HTTPS URL, e.g. https://yinghuoren.com/api/media",
            )
        }
        val escapedServerBaseUrl = serverBaseUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "MEDIA_SERVER_URL", "\"$escapedServerBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // androidx.graphics:graphics-path 自带的预编译 libandroidx.graphics.path.so
    // 无法被 NDK strip(已处理过),构建时会刷出 "Unable to strip ... packaging
    // them as they are" 警告。显式保留其符号、跳过 strip,消除该无害警告。
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }

    buildToolsVersion = "37.0.0"
    compileSdkMinor = 0
}

/**
 * 把语义化版本号(major.minor.patch)换算成单调递增的 versionCode 整数。
 *
 * 容量规则:patch 段 0..999(1000 个值),minor 段 0..99(100 个值)。
 * 派生公式: major*100000 + minor*1000 + patch。
 *   - patch 满 1000 进位到 minor,minor 满 100 进位到 major,保证单调递增;
 *   - 整数比较永远可靠,无字符串 "1.10.0" < "1.9.0" 的字典序陷阱。
 * 只需维护 versionName,versionCode 自动正确递增。
 */
fun semverToCode(versionName: String): Int {
    val parts = versionName.split(".")
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "versionName 必须是 major.minor.patch 格式,当前: $versionName"
    }
    val (major, minor, patch) = parts.map { it.toInt() }
    require(major in 0..99 && minor in 0..99 && patch in 0..999) {
        "版本段超限: major 0..99, minor 0..99, patch 0..999,当前: $versionName"
    }
    return major * 100000 + minor * 1000 + patch
}

// AGP 9.0+ 内置 Kotlin 后,android.kotlinOptions{} 已废弃,
// 改用顶层 kotlin { compilerOptions { ... } } DSL。
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val media3Version = "1.4.1"

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.multidex:multidex:2.0.1")
}
