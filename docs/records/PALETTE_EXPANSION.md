# 配色扩容：微软官方模板 10 套进内置

## 结论

- 原有内置配色：`PptDeck.THEMES` 里 9 套（blue、dark、green、ink、orange、purple、red、teal、warm）。
- 新增内置配色：10 套，key 分别是 shapes、impact、pacific、droplet、macchiato、headlines、scientific、city、ms11-blue、music，**内置总数从 9 套到 19 套**。
- 来源：微软 Create 官方模板页公开的 .pptx，经 `tools/ppt_theme_extract.py` 抽出的主题（见 `tools/ms-templates.json`，kavi/template-ingest 分支），颜色与原模板一致。

## 改动（本分支）

- `app/src/main/java/com/jack/ai/data/PptDeck.kt`：只在 `THEMES` 里追加 10 行 + 1 行注释，纯新增，没有改动原有 9 套和任何其他代码。
- 新 key 与原有 9 个均不重名；`theme(name)` 找不到名字时仍回落到 blue，行为不变。

## 取舍说明

- **字体用了默认**：原模板的西文字体（如 Agency FB、Kristen ITC、Niagara Solid、Posterama）不一定在用户手机上有，内置主题统一用默认微软雅黑，只取颜色。原字体仍保留在 `tools/ms-templates.json` 里，走「我的模板」路线时可用。
- **10 套全是浅色**：抽出的微软模板 bg 都是 FFFFFF、dark 都是 false；深色内置目前仍只有 dark 一套，深色扩容是下一批的事。
- **模型要用上新名字，还差一步**：`THEMES` 加了 key 只是代码侧就绪，生成 PPT 时模型可选的主题名清单（工具描述那侧）也需要同步加上这 10 个名字，否则模型不知道有新配色可选。这一步不在本分支里，合分支时请 Claude 一并改。

## 下一批（未做）

- Coolors、Adobe Color、ColorHunt 上的公开色板和中国传统色：颜色本身不受版权保护，可以直接做成内置配色；这批需要人工挑色板、起名字，不像微软这批有现成抽取管子，留待下一轮。
