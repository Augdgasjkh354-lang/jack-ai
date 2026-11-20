# Jack AI

安卓 AI 助手 App（Kotlin + Jetpack Compose），包名 `com.jack.ai`。功能清单见 README.md。

## Git

- **改完直接推到 `main`**（Jack 明确要求，长期有效）：`git push origin HEAD:main`。不用开 PR，不用先问。
  这条只给 Claude：其他厂商的模型（Codex、GPT 等）一律不许推 `main`，见 `AGENTS.md`。它们推的分支由 Claude 检查后再合进 `main`。
  会话指定了别的开发分支时，那个分支也照常推一份，但 `main` 一定要推。
- 推之前先编译、跑相关单测；推到 `main` 会自动打包发版（GitHub Actions「打包 APK」）。推完直接收工，不用等打包结果（Jack 明确要求）；只告诉他「已推送，打包在跑」，打包失败他会回来说。
- 提交说明用英文写清改了什么、为什么。
- 其他模型推的分支会自动跑「检查分支」（`.github/workflows/check.yml`：编译 + 单测 + Office 文件校验，不发版）。合之前先看它是不是绿的，红的别合。
- 签名密钥不进仓库（仓库是公开的）：打包时从 GitHub Secrets 的 `KAVI_KEYSTORE_BASE64`、`KAVI_KEYSTORE_PASSWORD` 还原，别名 `kavi`。别把 keystore、密码写进代码或提交。

## 和 Jack 沟通

- 用中文，简洁直接。复杂的事再展开讲，附上建议。
- 动手前拿不准的产品决定先问；工程上的事按惯例自己定，说一声就行。

## 闪退

- App 闪退后下次打开会自动发到本仓库 Issues（标签 `crash`，标题末尾 `[签名]` 去重），只有异常类型和代码位置，不含报错文字。修 bug 前先去看有没有新的；修好了关掉对应 issue。
- Jack 手机上的 GitHub 令牌要有 Issues 写权限才发得出去。

## 两个版本

- `dev` 开发版：全部功能，包名 `com.jack.ai`（Jack 用的，发 Release、自动更新）。
- `store` 正式版：上应用商店，包名 `com.kavi.ai`；没有浏览器、操作其他 App、后台手机助手、专用代理（VPN）、Linux 沙箱、读通知和微信、使用时长、App 内自更新。
- 正式版第一次打开先弹《用户协议》《隐私政策》（`ui/Legal.kt`，正文在 `app/src/main/assets/legal/`），同意前不建主界面、不联网、不申请权限。改了功能涉及数据怎么用，要同步改这两份；有重大修改把 `Legal.VERSION` 加一。
- 同一份代码，用 `Edition`（`com.jack.ai.Edition`）判断；正式版的清单在 `app/src/store/AndroidManifest.xml` 里删组件和权限。新加这类功能时两处都要顾到。

## 代码

- 包结构：`api/`（模型和网络客户端）、`chat/`（Agent、提示词）、`tools/`（工具执行）、`data/`（存储、记忆、各种服务）、`features/`、`ui/`。
- 注释、界面文字都是中文，跟周围代码的风格保持一致。
- 单测在 `app/src/test`（纯逻辑和 Robolectric），界面测试在 `app/src/testDebug`。

## 本地编译（云端容器）

容器里默认没有 Android SDK，需要时：

```bash
mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp \
  && curl -sSL -o cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
  && unzip -q -o cmd.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > local.properties
```

Maven Central 常被限流（429），用 Google 的镜像：在 `~/.gradle/init.d/mirror.gradle` 写

```groovy
def mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
settingsEvaluated { s ->
  [s.pluginManagement.repositories, s.dependencyResolutionManagement.repositories].each { r ->
    r.removeIf { it.name == "MavenRepo" }
    def m = r.maven { url mirror; name "CentralMirror" }
    r.remove(m); r.add(0, m)
  }
}
```

然后：

```bash
./gradlew --no-daemon -q :app:compileDevDebugKotlin :app:compileStoreDebugKotlin
ROBOLECTRIC_REPO=https://maven-central.storage-download.googleapis.com/maven2/ ./gradlew --no-daemon -q :app:testDevDebugUnitTest --tests "com.jack.ai.*"
```

