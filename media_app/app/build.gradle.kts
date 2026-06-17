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
        versionCode = 4
        versionName = "1.4"
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
