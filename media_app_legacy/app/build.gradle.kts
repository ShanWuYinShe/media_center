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
        buildConfigField("String", "APP_VARIANT", "\"legacy\"")
        // 凭证接口令牌:与部署端 MEDIA_SERVER_APP_API_TOKEN 一致。留空则 App 走
        // 无凭证模式(免费清晰度),凭证接口请求不带 token,服务端 401。
        val appApiToken = providers.gradleProperty("APP_API_TOKEN")
            .orElse(providers.environmentVariable("APP_API_TOKEN"))
            .orElse("")
            .get()
        val escapedAppApiToken = appApiToken
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "APP_API_TOKEN", "\"$escapedAppApiToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // 启用核心库脱糖:API 19 缺 java.time / 部分 Stream / Optional 等 Java 8+ API,
        // compileSdk=37 下编译期不报错,但运行期 NoClassDefFoundError 只在真机暴露。
        // 启用后由 desugar_jdk_libs 在低 API 上提供这些 API 的实现,作为纵深防御
        // (当前代码刻意用 TimeUnit 等避开了 java.time,但防止后续误用导致静默崩溃)。
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
    compileSdkMinor = 0
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
    // ExoPlayer 锁定 2.18.7(2.x 中功能完整且 deprecation 警告最少的稳定版):
    // 2.19.x 是迁移 Media3 的过渡版,把 Player/PlaybackException/PlayerView 等
    // 核心 API 全部标记 @Deprecated,产生 95+ 条编译警告;而 Media3 要求 minSdk 21,
    // 与项目"必须支持 Android 4.4.2"的目标冲突,不能升级解决。
    // 2.16.1 虽无 PlayerView @Deprecated 标记,但缺 AUDIO_CONTENT_TYPE_MOVIE 等
    // 播放功能所需常量,故取 2.18.7:仅剩 PlayerView 类级标记(无替代 API,
    // 由项目内 PlayerViewWrapper 收敛处理)。
    val exoVersion = "2.18.7"

    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("com.google.android.exoplayer:exoplayer-core:$exoVersion")
    implementation("com.google.android.exoplayer:exoplayer-dash:$exoVersion")
    implementation("com.google.android.exoplayer:exoplayer-hls:$exoVersion")
    implementation("com.google.android.exoplayer:exoplayer-ui:$exoVersion")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    // Conscrypt:Google 官方 BoringSSL JSSE 实现,官方支持 Android KitKat (API 19)+。
    // 在 4.4.2 上提供 TLS 1.2/1.3 + 全套现代密码套件(GCM/CHACHA20/ECDSA),
    // 根治平台 TLS 栈(OpenSSL 1.0.1)与现代服务器(Cloudflare 等)握手失败的问题。
    // 锁定 2.5.2:2.5.3 有 JNI 崩溃报告(google/conscrypt#1268)。
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.multidex:multidex:2.0.1")
    // Java 8+ 核心 API 脱糖:为 API 19 提供 java.time 等缺失 API 的实现。
    // 2.1.5 支持 minSdk 19(media_app 同版本)。
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
