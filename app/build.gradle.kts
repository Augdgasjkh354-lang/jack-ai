import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.jack.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jack.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        // 版本名：v1.0.N 用到第 99 次打包；从第 100 次起是 v1.1.0、v1.1.1……（versionCode 仍是打包次数，自动更新靠它）
        versionName = if (buildNumber >= 100) "1.1.${buildNumber - 100}" else "1.0.$buildNumber"
        // Python 只打包 64 位 ARM，现在的安卓手机基本都是
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("boolean", "STORE", "false")
    }

    // 两个版本，同一份代码（见 Edition）：
    // - dev 开发版：现在的全部功能，包名不变（Jack 手机上装的就是它，数据都在这个包名下）；
    // - store 正式版：上应用商店用，去掉浏览器、操作其他 App、后台手机助手、代理（VPN）、Linux 沙箱、读通知、使用时长、
    //   App 内自更新这些审核卡得严的东西（清单里也删掉，见 src/store/AndroidManifest.xml）。
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
        }
        create("store") {
            dimension = "channel"
            // 上架前可以改；一旦在商店发布就不能再改
            applicationId = "com.kavi.ai"
            buildConfigField("boolean", "STORE", "true")
        }
    }

    // 签名密钥不在仓库里：打包时由 GitHub Actions 从 Secrets 还原成文件，路径和密码经环境变量传进来。
    // 本地没有这几个变量时 release 不签名（本地只编译、跑测试，用不到）
    val keystore = System.getenv("KAVI_KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null) create("release") {
            // 固定签名，保证每次新版本都能直接覆盖安装，聊天记录不丢
            storeFile = file(keystore)
            storePassword = System.getenv("KAVI_KEYSTORE_PASSWORD")
            keyAlias = "kavi"
            keyPassword = System.getenv("KAVI_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // 界面测试（Robolectric）要用到 Android 资源
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 运行时要下载安卓系统包；设了 ROBOLECTRIC_REPO 就从这个镜像下（本机 Maven Central 被限流时用）
        unitTests.all { t ->
            System.getenv("ROBOLECTRIC_REPO")?.let { t.systemProperty("robolectric.dependency.repo.url", it) }
            // 测试里有中文文件名：系统语言不是 UTF-8 的机器上（比如云端容器）会被写成「????」，固定成 UTF-8
            t.environment("LC_ALL", "C.UTF-8")
            // 测试分两个进程同时跑（Robolectric 每个进程要 1～2G 内存，CI 机器 4 核 16G，两个正好）
            t.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
        }
    }
    packaging {
        // 代理内核是个可执行程序，伪装成 .so 放进 jniLibs；要解压到安装目录才能运行
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += listOf("/META-INF/LICENSE.md", "/META-INF/NOTICE.md", "/META-INF/LICENSE.txt", "/META-INF/NOTICE.txt")
    }
}

// 正式版不带代理内核、转发组件和 Linux 沙箱的可执行文件
androidComponents {
    onVariants(selector().withFlavor("channel" to "store")) { v ->
        v.packaging.jniLibs.excludes.addAll(listOf(
            "**/libsingbox.so", "**/libhev-socks5-tunnel.so", "**/libproot.so", "**/libproot_loader.so", "**/libmikarun.so",
        ))
    }
}


chaquopy {
    defaultConfig {
        version = "3.10"
        pip {
            install("numpy")
            install("pandas")
            install("matplotlib")
            install("openpyxl")
            // 读 PDF 文字（纯 Python，手机上能装）
            install("pypdf")
        }
    }
}

dependencies {
    // Mika 的源码单独一个模块（见 mika/build.gradle.kts）
    implementation(project(":mika"))
    // Office、设计、音视频的生成引擎（见 studio/build.gradle.kts）
    implementation(project(":studio"))
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // 在电脑上模拟手势的界面测试（只在 debug 下跑，见 src/testDebug）
    testImplementation(composeBom)
    debugImplementation(composeBom)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // 毛玻璃：顶栏、输入框、圆按钮后面的消息真的被模糊（Android 12 起；更老的系统退成半透明）
    implementation("dev.chrisbanes.haze:haze:1.2.2")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // Jack 浏览器单独设代理（ProxyController）
    // 网页后台播放通知和耳机控制，固定版本
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    // Jack 浏览器下拉刷新
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 自带的 fragment 1.2.5 太旧：不支持新式 ActivityResult，拍照/选图/选文件会报 requestCode 错
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.mozilla:rhino:1.7.15")
    // 剪视频、压缩视频（手机自带的编解码器，硬件加速）
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // 读写用户授权的手机文件夹（系统的文件选择器给的树形地址）
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Kavi 直连时用加密 DNS（DoH），公共 Wi-Fi、运营商看不到访问了哪些网站
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // 邮箱：IMAP 收信、SMTP 发信
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    // Jack 专用代理：读 Clash 订阅（YAML）、扫二维码（不依赖谷歌服务）
    implementation("org.yaml:snakeyaml:2.3")
    // Linux 沙箱：解压下载来的根文件系统（tar.xz）
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // 照片里的表格转 Excel：离线中文文字识别，模型打包在 App 里，不需要谷歌服务
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Shizuku：装了 Shizuku 的手机用它以 shell 身份起后台手机助手，不用无线调试配对
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 本地向量模型（设置 → 本地模型）：ONNX Runtime 在手机上跑，模型文件另外下载，不打进安装包
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
}

// ---------- 文档查看器的前端库 ----------
// Word、PPT 在 App 里按原样排版显示，用的是 docx-preview（Apache-2.0）、jszip（MIT）、pptx-preview（ISC，作者没公开源码、不许把它的代码发到网上）。
// 所以不放进仓库：打包时从 npm 下载，核对文件指纹后放进 assets/docview/。下不来就打包失败，不会悄悄换成别的版本。
val docViewLibs = listOf(
    Triple("https://registry.npmjs.org/docx-preview/-/docx-preview-0.4.1.tgz", "package/dist/docx-preview.min.js",
        "c4a133c65a112799e35b143c572dfff9e923a3bf39b5fd3b07a06aa0f8f530c2"),
    Triple("https://registry.npmjs.org/jszip/-/jszip-3.10.1.tgz", "package/dist/jszip.min.js",
        "acc7e41455a80765b5fd9c7ee1b8078a6d160bbbca455aeae854de65c947d59e"),
    Triple("https://registry.npmjs.org/pptx-preview/-/pptx-preview-1.0.7.tgz", "package/dist/pptx-preview.umd.js",
        "98bcf8fbb5efb42dff406eeea7e7b9d7497a388955582e5b1815489c63c75fe2"),
)
val docViewDir = layout.buildDirectory.dir("generated/docview")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

val fetchDocViewLibs by tasks.registering {
    outputs.dir(docViewDir)
    inputs.property("libs", docViewLibs.toString())
    doLast {
        val out = docViewDir.get().dir("docview").asFile.apply { mkdirs() }
        for ((url, entry, sha) in docViewLibs) {
            val target = File(out, entry.substringAfterLast('/'))
            if (target.exists() && sha256(target) == sha) continue
            val tgz = File(temporaryDir, url.substringAfterLast('/'))
            URI(url).toURL().openStream().use { input -> tgz.outputStream().use { input.copyTo(it) } }
            val file = tarTree(resources.gzip(tgz)).matching { include(entry) }.singleFile
            check(sha256(file) == sha) { "$entry 的指纹对不上，不打包（下到的文件被改过或版本变了）" }
            file.copyTo(target, overwrite = true)
        }
    }
}
android.sourceSets["main"].assets.srcDir(docViewDir)
tasks.named("preBuild") { dependsOn(fetchDocViewLibs) }
