# Jack AI

一个调用 DeepSeek API 的安卓聊天 App。

## 功能
- 流式对话（一个字一个字地出来），随时停止
- 聊天记录保存在手机本地，多个对话、重命名、删除
- 普通对话 / 深度思考 一键切换，深度思考会显示思考过程
- Markdown、代码、表格、数学公式渲染
- 复制、重新回答、删除消息
- 人设（系统提示词）预设，可自己添加
- 查询账户余额
- 浅色 / 深色 / 跟随系统

## 安装
1. 打开本仓库的 [Releases](../../releases/latest) 页面，下载最新的 `JackAI-*.apk`
2. 在手机上点开安装（第一次需要允许"安装未知来源应用"）
3. 打开 App，在设置里填入 DeepSeek API Key（在 https://platform.deepseek.com/api_keys 创建）

每次往 `main` 分支推代码，GitHub Actions 都会自动打包一个新版本。
