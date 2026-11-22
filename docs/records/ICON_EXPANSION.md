# 图标扩容：Tabler Icons（MIT）

## 结论

- 现有图标：`app/src/main/resources/ppt/icons.json`，206 个，来自 Lucide 1.65.0（ISC，见同目录 `LICENSE-lucide.txt`）。
- 新增图标：`app/src/main/resources/ppt/tabler-icons.json`，**5203 个**，来自 Tabler Icons 描边版（outline，MIT，见同目录 `LICENSE-tabler.txt`）。
- 两份文件格式完全一致：`{图标名: [路径字符串]}`，路径只含 M/L/C/Z，坐标已乘 100 到 2400×2400 网格，可直接复用现有图标绘制代码。

## 怎么做出来的

Tabler outline 版和 Lucide 一样是 24×24 描边风格，所以直接复用 `tools/lucide2json.py`，不用改脚本：

```
python3 tools/lucide2json.py <tabler-icons/icons/outline> <名字列表> app/src/main/resources/ppt/tabler-icons.json
```

名字列表 = outline 目录下全部 5203 个文件名（去 `.svg` 后缀）。转换结果 5203/5203 成功，0 缺失、0 空图标；另有 6 条路径字符串因圆弧转贝塞尔后坐标轻微超出 24 格（最大 1.5%），已钳制回 0–2400，复查 0 越界。

## 接入时注意（给改 App 代码的人）

- **两个文件分开存放，不要合并成一个 JSON**：Tabler 与 Lucide 有 **139 个重名**（如 target、calendar、heart）。合并会互相覆盖。建议 App 启动时两份都加载，重名以 Lucide 为准（保持现有 PPT 效果不变），Tabler 只补新增的名字。
- **MIT 致谢**：Tabler 是 MIT 协议，致谢集中放在 App「关于」页即可（与 Lucide 的 ISC 致谢放一起），用户生成的 PPT 成品不需要署名。
- **不要用 filled 版**：Tabler 还有 1054 个 filled（实心填充）版，与描边风格不统一，本次只取了 outline 版。
- 图标名是 Tabler 原名（kebab-case，如 `chart-bar`、`arrow-up-right`），与现有 Lucide 名是同一命名风格。
- 关键词联想（`iconName()` 那套映射）如需覆盖新图标，从 Tabler 名字里挑，不用改转换管子。

## 来源

- Tabler Icons：https://github.com/tabler/tabler-icons ，MIT License，Copyright (c) 2020–2026 Paweł Kuna。
- 转换脚本：`tools/lucide2json.py`（原用于 Lucide，本次零改动复用）。
