# 代码编辑器：CodeMirror 6

App 里的代码编辑器（`ui/CodeEditorScreen.kt`）用 [CodeMirror 6](https://codemirror.net/)（MIT），在 WebView 里打开 `app/src/main/assets/editor/index.html`。

- `editor.js`：Kavi 自己写的入口（语言、主题、和 App 之间的桥、和旧版本对比）。
- `package.json`、`package-lock.json`：用到的库和版本。
- 打包：在这个目录里 `npm ci && npx esbuild editor.js --bundle --minify --format=iife --legal-comments=eof --outfile=../../app/src/main/assets/editor/editor.js`。
  打出来的文件提交在仓库里，App 打包时不跑 npm。
- 用到的每个库的名字、版本和许可证全文在 `app/src/main/assets/editor/LICENSES.txt`（都是 MIT，库原样未改）。
