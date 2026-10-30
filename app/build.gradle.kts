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
    }

    signingConfigs {
        create("release") {
            // 固定签名，保证每次新版本都能直接覆盖安装，聊天记录不丢
            storeFile = file("jack-ai.keystore")
            storePassword = "jackai2026"
            keyAlias = "jackai"
            keyPassword = "jackai2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    }
    // 界面测试（Robolectric）要用到 Android 资源
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 运行时要下载安卓系统包；设了 ROBOLECTRIC_REPO 就从这个镜像下（本机 Maven Central 被限流时用）
        unitTests.all { t -> System.getenv("ROBOLECTRIC_REPO")?.let { t.systemProperty("robolectric.dependency.repo.url", it) } }
    }
    packaging {
        // 代理内核是个可执行程序，伪装成 .so 放进 jniLibs；要解压到安装目录才能运行
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += listOf("/META-INF/LICENSE.md", "/META-INF/NOTICE.md", "/META-INF/LICENSE.txt", "/META-INF/NOTICE.txt")
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
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // Jack 浏览器单独设代理（ProxyController）
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
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
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
