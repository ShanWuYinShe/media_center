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
        minSdk = 21
        targetSdk = 37
        // 版本号由 git tag 驱动:versionName 从最近的 v* tag 读取(如 v1.1.0 → 1.1.0),
        // tag 之后若有新 commit 则带 -dev.N 后缀(如 1.1.0-dev.3)标识未发布。
        // versionCode 从 SemVer 部分派生(major*100000+minor*1000+patch),整数比较可靠。
        // 发版只需:git tag v1.x.y && git push --tags,无需手改 build.gradle。
        val resolvedVersion = gitVersionName()
        versionName = resolvedVersion
        versionCode = semverToCode(resolvedVersion)
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
        buildConfigField("String", "APP_VARIANT", "\"normal\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
}

/**
 * 从 git 读取版本号。优先用最近的 v* tag(如 v1.1.0);tag 之后有新 commit 则带
 * -dev.N 后缀(如 1.1.0-dev.3)标识未发布构建。无 tag 或不在 git 仓库时返回
 * 安全默认值 0.1.0-dev(不阻断构建,便于克隆后直接跑)。
 *
 * 发版流程:git tag v1.x.y && git push --tags,然后构建即得到干净的 versionName。
 *
 * 用 ValueSource 执行 git(而非直接 ProcessBuilder),兼容 Gradle 配置缓存
 * (配置阶段不允许启动外部进程,ValueSource 是官方支持的例外)。
 */
abstract class GitDescribeValueSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = try {
        val proc = ProcessBuilder(
            "git", "describe", "--tags", "--match", "v[0-9]*", "--long", "--always",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        proc.inputStream.bufferedReader().readText().trim().ifEmpty { "0.1.0-dev-0" }
    } catch (_: Exception) {
        "0.1.0-dev-0"
    }
}

fun gitVersionName(): String {
    val described = providers.of(GitDescribeValueSource::class.java) {}.get()
    // describe 产出:v1.1.0-0-gabc(正好在 tag,count=0) 或 v1.1.0-3-gabc(tag 后 3 个 commit)
    val match = Regex("""^v(\d+\.\d+\.\d+)-(\d+)-g[0-9a-f]+$""").find(described) ?: return "0.1.0-dev"
    val semver = match.groupValues[1]
    val commitCount = match.groupValues[2].toIntOrNull() ?: 0
    return if (commitCount == 0) semver else "$semver-dev.$commitCount"
}

/**
 * 把语义化版本号换算成单调递增的 versionCode 整数。
 *
 * 容量规则:patch 段 0..999(1000 个值),minor 段 0..99(100 个值)。
 * 派生公式: major*100000 + minor*1000 + patch。
 *   - patch 满 1000 进位到 minor,minor 满 100 进位到 major,保证单调递增;
 *   - 整数比较永远可靠,无字符串 "1.10.0" < "1.9.0" 的字典序陷阱。
 * 只取 SemVer 部分(忽略 -dev.N 后缀),保证同一 tag 的开发构建和正式发布 versionCode 一致。
 */
fun semverToCode(versionName: String): Int {
    val semver = versionName.substringBefore("-") // 剥离 -dev.N 后缀
    val parts = semver.split(".")
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
    val media3Version = "1.10.1"

    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.multidex:multidex:2.0.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
