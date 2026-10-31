# 图表扩容：原生图表从 5 种到 9 种

## 结论

- 原有原生图表：`PptChart.TYPES` 里 5 种（column、bar、line、pie、doughnut），数据内嵌 Excel、双击可改数、颜色跟主题走。
- 新增原生图表：4 种，**总数从 5 种到 9 种**：
  - scatter（散点图）：x、y 两个数值轴，连线 + 圆点；
  - radar（雷达图）：分类轴 + 数值轴，连线 + 圆点；
  - area（面积图）：分类轴 + 数值轴，填充面积，系列写法复用柱状那套；
  - bubble（气泡图）：x、y 两个数值轴 + 气泡大小第三维。
- 改动只在 `app/src/main/java/com/jack/ai/data/PptChart.kt` 一个文件，原有 5 种的生成逻辑一行没动。

## 模型写法（给模型 / 调用方）

- type 写 scatter、radar、area、bubble 即可进新分支；不在 TYPES 里的 type 仍回落到 column，行为不变。
- scatter：categories 写 x 坐标（数字；写文字或空着就按序号 1、2、3… 当 x），series.values 是 y。分类空着也行，clean 会按最长一组自动补序号。
- radar、area：写法和折线、柱状完全一样（categories + series.values）。
- bubble：series 里多给一个 sizes 数组（每点气泡大小），和 values 一一对应；不给 sizes 时气泡大小跟 y 走；sizes 长度对不上分类时按 y 补齐并给模型报一条提示。
- 上限和原来一致：分类最多 24 个、系列最多 8 组。

## 内嵌表与 Excel 直连的对照

- 内嵌模式（PPT、Word 用）：workbook 布局跟着变——散点是 A 列 x、后面每组一列 y；气泡是 A 列 x、后面每组两列（y、大小），组名在 y 列上（B、D、F…）。双击图表改数、图跟着变，照样成立。
- Excel 直连模式（refs）：散点的 x 指着分类列会有问题（那列是文字），所以 x 改用字面值（numLit，不指格子）；气泡没有单独的大小列，大小跟 y 走。这两点是故意的取舍，不是 bug。

## 刻意没做的事

- 散点、气泡、雷达默认不贴数值标签：散点贴标签意义不大，雷达轴线密集时标签易重叠；图例区分系列。面积图复用柱状的标签写法（outEnd + 组数 ≤3 且分类 ≤12 时显示数值）。
- 漏斗、甘特、金字塔、矩阵、循环、SWOT、鱼骨、组织架构这类规范里没有的图，不属于原生图表，下一批走形状拼（仓库已有 DiagramWriter 做思维导图、流程图，可往上加）。

## 待验证（合分支时请 Claude 做）

- 本分支只做了静态检查：新增 XML 片段逐段拼合后都是合法 XML，元素顺序按 DrawingML chart 的 CT_ScatterChart / CT_RadarChart / CT_AreaChart / CT_BubbleChart / CT_ScatterSer / CT_BubbleSer / CT_RadarSer 对过。
- 没法在这里编译 Android 工程、也没法在 PowerPoint 里打开验证：合分支后请在 PowerPoint / WPS 里各生成一张散点、雷达、面积、气泡确认能打开、不报文件损坏，再合 main。
