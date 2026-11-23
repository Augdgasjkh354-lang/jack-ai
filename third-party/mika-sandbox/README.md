# Linux 沙箱：PRoot、启动器与系统包

Kavi 的 Linux 沙箱（设置 → Linux 沙箱）参考 Mika（https://github.com/wangzan101/Mika ，Apache-2.0）的每对话沙箱，
固定来源提交 `65c65b0d3c205e77c2ffa9e084f40c6bb2a7316a`。Kotlin 部分是 Kavi 自己的实现（`app/src/main/java/com/jack/ai/data/LinuxSandbox.kt`），
启动参数、/dev 只绑的设备、/proc 替身文件、时区写法照 Mika 的 `ProcRunner`、`ProotDevices`、`RootfsInstaller`。

## 随 APK 带的三个程序

都在 `app/src/main/jniLibs/arm64-v8a/`（提交在仓库里；同目录的代理内核是打包时编的，不进仓库），随 APK 装到 `nativeLibraryDir`。
Kavi 只以子进程方式运行它们，不链接它们。文件与 Mika 同一提交里的产物逐字节相同，打包前按 `binaries.sha256` 核对：

| 文件 | 是什么 | 许可证 | SHA-256 |
|---|---|---|---|
| `libproot.so` | PRoot 5.4.0（打了 `patches/` 里的四个补丁），静态链接 talloc 2.4.3 | GPL-2.0-or-later（PRoot）、LGPL-3.0-or-later（talloc） | `3184f54e363b560f128ce888c69d434fa6bfe80293c9fff47108456e79e48fe2` |
| `libproot_loader.so` | PRoot 的载入器 | GPL-2.0-or-later | `a73384d2f78b535936401ddee33909ae91966812e56437a16486934e0faa588c` |
| `libmikarun.so` | Mika 的沙箱启动器（超时、断网、清场），源码 `mikarun/mikarun.c` | Apache-2.0，Copyright (c) 2026 Mika authors | `3daa38a1543aed7c93e605f6df585c3e2d169d56f00c556d8726a93eadab477a` |

- PRoot：Copyright (C) 2015 STMicroelectronics，作者名单见源码包内 `AUTHORS`。全文 `GPL-2.0.txt`。
- talloc：Copyright (C) Andrew Tridgell 2004；Copyright (C) Stefan Metzmacher 2006。全文 `LGPL-3.0.txt`（附加在 `GPL-3.0.txt` 之上）。
- 两个源码包就放在 `sources/` 里（打包时不联网下载，只按 `sources.lock` 核对 SHA-256），原地址也记在 `sources.lock`；补丁在 `patches/`（跟随 PRoot 的 GPL-2.0-or-later）；编译脚本 `build-native.sh` 原样取自 Mika
  （脚本里的路径按 Mika 仓库的布局：`tools/sandbox/` 对应这里）。
- 每次发版，打包流程把这两个源码包、补丁、编译脚本和许可证打成 `Kavi-<版本>-gpl-sources.tar.gz`，和 APK 放在同一个发布页
  （见 `.github/workflows/build.yml`）。

## 系统包（不随 APK 发布）

第一次装沙箱时下载 Mika 同一提交里的 `app/src/main/assets/runtime/rootfs.tar.xz`（Alpine Linux 3.22.7 aarch64 最小根文件系统，
加 Python 3.12、Node 22、git、curl、Pillow、pypdf 等，约 58 MB），下载后核对 SHA-256
`250e9fbe32c8cfafb5aaf9864fe04fa3e5b2092e6524b2c611fb585c0521ba19`，不对就删掉。
包里每个软件包有各自的许可证；它由 Mika 从 Alpine 官方源取得、核对签名后生成，GPL/LGPL/MPL 软件包的对应源码由 Mika 随它的每个版本发布。

## 其他

- `app/src/main/mika/com/wangzan101/mika/core/cap/sandbox/RootfsExtractor.kt`：原样移植自同一提交（Apache-2.0），保留上游命名空间。
- 解压用的库：XZ for Java 1.10（0BSD）、Apache Commons Compress 1.28.0（Apache-2.0）。
- Jack AI modification：不移植 Mika 的沙箱桥、技能同步、终端和分享导出；系统包从随 APK 自带改成首次使用时下载；
  沙箱只在用户装好后给模型一个 `run_shell` 工具，读到可疑注入内容后照 `run_python` 一样先问用户。
