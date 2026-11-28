# 发版时用 R8 删掉用不到的代码和资源，但不改类名、方法名（见 app/build.gradle.kts 的 release）：
# - 手机上排好的后台任务按 Worker 的类全名存着，改名就找不到了；
# - Mika 的助手进程靠类全名用 app_process 启动（HelperHandoff.HELPER_CLASS）；
# - 按 App 分流的转发组件按「com/jack/ai/vpn/TProxyService」注册方法；
# - 闪退报告（自动发到 Issues）里的类名、方法名照样看得懂。
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Mika 助手进程的入口：系统 app_process 按名字找 main
-keep class com.wangzan101.mika.shell.HelperMain { public static void main(java.lang.String[]); }

# 转发组件（libhev-socks5-tunnel.so）在 JNI_OnLoad 里按名字给这几个方法挂实现
-keep class com.jack.ai.vpn.TProxyService { *; }

# 网页里调 Kotlin 的接口：网页只认名字
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# QuickJS：底层按名字回调 Kotlin 这一侧
-keep class com.dokar.quickjs.** { *; }

# Rhino（run_code）：解释器内部大量按名字反射自己的类
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# 邮件：javamail 按 META-INF 里的配置、按名字加载协议实现和数据处理器
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.**
-dontwarn java.beans.**

# PDF：按名字读自带的字体、编码表这些资源
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**

# YAML：构造对象时走反射
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# 压缩包：可选的压缩算法没带（zstd、brotli 这些），用不到
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.tukaani.xz.**

# OkHttp 可选的 TLS 实现
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Chaquopy：Python 一侧按名字访问 Java
-keep class com.chaquo.python.** { *; }

# ONNX Runtime（本地向量、语音转文字、抠图、本地小模型）：底层 C++ 用 JNI 按名字建 Java 对象（TensorInfo、OnnxTensor、OnnxMap……）、
# 调构造函数和字段，Java 这边没人直接调用的会被 R8 删掉，一推理就 JniAbort 闪退。它的 AAR 只保留了遥测那几个类
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
