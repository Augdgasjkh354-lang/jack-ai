# Jack AI

安卓 AI 助手 App（Kotlin + Jetpack Compose），包名 `com.jack.ai`。功能清单见 README.md。

## Git

- **改完直接推到 `main`**（Jack 明确要求，长期有效）：`git push origin HEAD:main`。不用开 PR，不用先问。
  会话指定了别的开发分支时，那个分支也照常推一份，但 `main` 一定要推。
- 推之前先编译、跑相关单测；推到 `main` 会自动打包发版（GitHub Actions「打包 APK」），推完看一眼这次打包成没成，失败就修好再推。
- 提交说明用英文写清改了什么、为什么。

## 和 Jack 沟通

- 用中文，简洁直接。复杂的事再展开讲，附上建议。
- 动手前拿不准的产品决定先问；工程上的事按惯例自己定，说一声就行。

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
./gradlew --no-daemon -q :app:compileDebugKotlin
ROBOLECTRIC_REPO=https://maven-central.storage-download.googleapis.com/maven2/ ./gradlew --no-daemon -q :app:testDebugUnitTest --tests "com.jack.ai.*"
```

`MailServerTest` 在容器里起不了本地邮件服务器，会失败，跟改动无关。
