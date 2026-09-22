import java.net.URI
import java.net.URISyntaxException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.mediacenter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.mediacenter"
        minSdk = 23
        targetSdk = 37
        // 版本号由 git tag 驱动:versionName 从最近的 v* tag 读取(如 v1.1.0 → 1.1.0),
        // tag 之后若有新 commit 则带 -dev.N 后缀(如 1.1.0-dev.3)标识未发布。
        // versionCode 从 SemVer 部分派生(major*100000+minor*1000+patch),整数比较可靠。
        // 发版只需:git tag v1.x.y && git push --tags,无需手改 build.gradle。
        val resolvedVersion = gitVersionName()
        versionName = resolvedVersion
        versionCode = semverToCode(resolvedVersion)

        val serverBaseUrl = providers.gradleProperty("MEDIA_SERVER_URL")
            .orElse(providers.environmentVariable("MEDIA_SERVER_URL"))
            // 独立站规范前缀 /api/v1;旧默认(workwork 聚合栈)经其过渡反代仍可用
            .orElse("https://media.chaoset.com/api/v1")
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
                "MEDIA_SERVER_URL must be an absolute HTTPS URL, e.g. https://media.chaoset.com/api/v1",
            )
        }
        val escapedServerBaseUrl = serverBaseUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "MEDIA_SERVER_URL", "\"$escapedServerBaseUrl\"")
        buildConfigField("String", "APP_VARIANT", "\"normal\"")
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
        compose = true
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

    buildTypes {
        // release 开启 R8 minify + 资源压缩,减小发布包体积(debug 29M → release ~11M)。
        // 自用包用 debug keystore 签名(signingConfig = debug),无需维护正式 keystore,
        // 同时保证 release APK 可直接安装(unsigned APK 无法安装)。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
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
    // HLS 模块:YouTube 高清档位(>720p)只提供 m3u8 播放列表,progressive mp4 播不到。
    // DefaultMediaSourceFactory 在 classpath 上发现该模块后自动支持 application/x-mpegurl。
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Conscrypt:作为 JCE Provider 在进程级注入,让 OkHttp / HttpsURLConnection /
    // Media3 DefaultHttpDataSource 统一走 BoringSSL,解决老系统自带 SSL 不支持目标
    // 站点 TLS 握手的问题(替代此前自研的 OCSP soft-fail TrustManager)。
    // 2.6.1:含 #1268(2.5.3 的 JNI 崩溃)修复 + 16KB 页对齐;2.5.3 有崩溃不可用。
    // 注意:2.6.x 放弃 API 19/20 支持,故 legacy 变体(minSdk 19)仍锁 2.5.2。
    implementation("org.conscrypt:conscrypt-android:2.6.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Compose BOM + TV Material
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // material3 1.4.0 起不再传递引入 material-icons-core,需显式声明
    // (项目用 Icons.Filled.Search/Settings、Icons.AutoMirrored.Filled.ArrowBack)。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Coil
    implementation("io.coil-kt:coil-compose:2.7.0")

    // lifecycle-runtime-compose (用于 collectAsStateWithLifecycle)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM 单测(JUnit4):覆盖无 Android 依赖的纯 Kotlin 逻辑(时长格式化、WBI 签名、id 解析)。
    testImplementation("junit:junit:4.13.2")
}
