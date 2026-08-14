# ACode 阶段四：终端渲染改造（主屏可复制）— 任务清单

> 最后更新：2026-08-14
> 依赖关系：`T1→T2`；`T3` 无代码依赖、可并行（但删除的方法会让 ConversationController 编译中断，见约定）；`T2+T3→T4`；`T4→T5→T6→T7`；`T7→T8`；`T4,T7,T8→T9`（接入主流程）→`T10`（端到端验证）。

## 约定

- 改造范围：渲染层（`com.acode.ui`）与主流程（`ConversationController`），不触碰 Provider/Agent/工具/会话持久化
- 现有文件行号以 2026-08-14 的 HEAD 为准，改动时先 Read 确认
- 全章任务实现完后统一跑 `mvn test`（构建环境：`JAVA_HOME=D:\java\jdk21`）；T2/T3 会临时删除 ConversationController 仍在引用的方法（repaint 系列 / resetScroll），到 T7/T8/T9 才清理完毕，中间任务编译暂不通过属预期；测试方法名用英文驼峰
- 核心不变量：**输出一律以换行收尾、光标停在行首**；活跃区重绘只用相对移动与清屏序列，绝不用绝对定位（`\033[r;cH` 类转义）

### 全局集成风险（各任务参考资料会引用，编号 R1~R6）

- **R1 光标锚点契约**：JLine 3.27.1 的 `org.jline.utils.Display` 全部用相对移动、不解析写出的转义序列（javap 核实 jar 内无输出解析类），输入组件模型锚点=接受输入后空缓冲偏移 0。因此活跃区每次重绘必须回到「内容下方行首」——任何一次重绘若把光标停在行中或依赖绝对定位，下一次输入提示行就会错位。
- **R2 输入回显双写**：不加擦除行选项时，主屏下接受输入会留下输入原文，应用再追加「● 输入」造成双行。必须在输入组件启用擦除行选项（接受时擦除输入行、不写换行），由应用统一追加。
- **R3 超屏活跃区**：流式回复超过一屏后，顶部行被终端滚进回滚、不可再改。活跃区只重绘可见后缀：上移行数钳制为 `min(已写行数, 屏高-1)`，新内容也只取末尾可见段。底部锚定使窗口缩放回流大体安全，但尺寸变化后旧已写行数失效，重绘检测到宽高变化时应先归零重锚定（见 T1）。
- **R4 整宽行幻影空行**：内容宽度恰好等于终端宽度时，打印后终端进入 pending-wrap 状态，直接续写会多出幻影空行。每段内容行尾补 `\r` 化解（`内容\r\n`）。
- **R5 菜单 overlay**：会话选择菜单若写进已提交内容则无法从回滚删除。菜单必须作为活跃区覆盖渲染：每次按键清除旧菜单重画，选定/取消后整个菜单清除、历史再追加。
- **R6 wrap 迁移**：折行逻辑（AcodeTerminal.java:365-414）是活跃区行数计算的基础，先迁到活跃区渲染器再删旧实现。折行按显示宽度（宽字符占 2 列）、折点不切断宽字符与 ANSI 序列、SGR 颜色状态跨段延续。

---

### T1 新建活跃区渲染器 LiveRegionRenderer

**目标**：活跃区重绘的核心：折行、上移行数、可见后缀、写序列，全部为可单测的纯函数；实例持有已写行数。

**影响文件（新建）**
- `src/main/java/com/acode/ui/LiveRegionRenderer.java`：
  - 静态纯函数：
    - `wrap(String line, int width)` — 从 AcodeTerminal.java:365-414 迁入（含 `isAnsiFinalByte`，:416-418）
    - `upRows(int rowsWritten, int height)` — `min(rowsWritten, height-1)`，高度 ≤1 时为 0（R3）
    - `visibleSegs(List<String> segs, int height)` — 末尾 `min(segs.size(), height-1)` 段（R3）
  - 实例：持有 终端高度/宽度 + `rowsWritten` 已写行数；方法 `redraw(Writer, List<String> 渲染行)` 与 `clear(Writer)`
  - 写序列：`\033[{up}A`（up>0 时）→ `\033[J`（清到屏尾，旧活跃区可能比新高）→ 每段 `段内容\r\n`（R4）；`clear()` = `\033[{up}A`（up>0 时）→ `\033[J` → `rowsWritten=0`
  - 追加已提交：`appendCommitted(Writer, String 行)` 写 `行\r\n`，原生折行、进入回滚、不计 rowsWritten（T8/T9 的 banner/输入/历史渲染用）
  - 尺寸感知：redraw 检测到终端宽高与上次不同时，先 `rowsWritten=0` 再重绘（reflow 后旧行数失效，R3）
- `src/test/java/com/acode/ui/LiveRegionRendererTest.java`（新）：
  - 迁移 AcodeTerminalTest.java:40-90 的 wrap 用例（7 个：中文/ANSI 跨段/宽字符/超宽行等），测试内 `displayWidth` 辅助方法一并迁入（AcodeTerminalTest 将整体删除，见 T2）
  - 新增：`upRows` 钳制（含高度 1）、`visibleSegs` 可见后缀截取、`redraw` 写出的转义序列断言（StringWriter 捕获 `\033[NA` `\033[J` 与行尾 `\r\n`）、整宽行无幻影空行（R4）、超屏只重绘可见后缀（R3）、`clear()` 清空序列与行数归零、终端宽高变化后已写行数归零重锚定、`appendCommitted` 写出 `行\r\n` 且不改变已写行数

**依赖**：无

**参考资料**
- AcodeTerminal.java:365-418（wrap/isAnsiFinalByte，原样迁出）；AcodeTerminalTest.java:40-90（wrap 用例迁移，含 `displayWidth` 辅助方法）
- R3、R4、R6

---

### T2 瘦身终端壳 AcodeTerminal

**目标**：把 AcodeTerminal 从「全屏重绘器」改为「终端生命周期壳」，删除备用屏幕、全屏重绘、滚动条、视口数学全部代码。

**影响文件（修改 + 删除）**
- `src/main/java/com/acode/ui/AcodeTerminal.java`：
  - 删除备用屏幕：`open()` 里 `\033[?1049h`（:46-48）、`close()` 里 `\033[?1049l`（:420-430 改为直接关终端）
  - 删除 `clearScreen`（:82-84）、`moveTo`（:87-89）
  - 删除 shadow 字段与 `repaint`/`repaintOutputArea`/`checkResize`/`invalidateShadow`（:91-137）
  - 删除 `drawOutputArea`（:139-188）、`outputArea`（:191-194）、`drawScrollbar`（:200-210）、`scrollbarCell`（:216-230）、`scrollToMouseY`（:236-254）、`computeWrapCounts`（:257-264）、`prefixSums`（:266-272）、`thumbHeight`（:275-277）、`displayFrom`（:284-290）、`displayRows`（:296-303）、`thumbTop`（:306-311）、`targetScrollOffset`（:318-344）、`separatorLine`（:347-349）、`SCROLLBAR` 常量（:352-358）、`wrap`/`isAnsiFinalByte`（:365-418，T1 已迁走）
  - `open()` 的 cursor_address 检查（:40-44）改为「`height() > 0` 且具备光标上移/清屏能力（InfoCmp 的 cursor_up / clr_eos）」——主屏模式不用绝对定位，但活跃区重绘仍依赖光标移动序列，不能只查高度
  - 保留：`open`/`close`/`height`/`width`/`write`/`flush`/`terminal()`
- `src/test/java/com/acode/ui/AcodeTerminalTest.java`：整体删除（wrap 用例已随 T1 迁走、滚动条/视口用例删除，测试类无剩余 @Test）

**依赖**：T1

**参考资料**
- AcodeTerminal.java 全文；AcodeTerminalTest.java:91-206
- 注意：删除 repaint/repaintOutputArea/invalidateShadow/scrollToMouseY 后，ConversationController.java:159/164/167/176/231/401 的引用暂时编译失败，T7/T8/T9 一并清理（见约定）

---

### T3 OutputPane 删视口逻辑

**目标**：内容模型移除滚动偏移与视口取窗，只保留逐行追加/清除/移除/计数/快照。

**影响文件（修改）**
- `src/main/java/com/acode/ui/OutputPane.java`：删除 `scrollOffset` 字段（:16-17）、`visibleLines`（:79-88）、`scrollUp`/`scrollDown`/`scrollBy`（:90-111）、`resetScroll`（:113-116）、`setScrollOffset`/`scrollOffset`（:118-126）；保留 `append`/`appendLine`/`clear`/`removeLast`/`lineCount`/`lines`
- `src/test/java/com/acode/ui/OutputPaneTest.java`：删除视口/滚动用例 12 个（visibleLines 4 个 :78-105、scroll 8 个 :142-230），保留追加/清除/快照用例

**依赖**：无（可与 T1/T2 并行）

**参考资料**
- OutputPane.java 全文；OutputPaneTest.java 全文
- 注意：resetScroll 仍被 ConversationController.java:184/336/420 引用，T7/T8/T9 清理（见约定）

---

### T4 InputPane 精简与输入回显处理

**目标**：去掉鼠标捕获与滚动 widget，启用擦除行选项（R2），输入层只保留键盘交互。

**影响文件（修改）**
- `src/main/java/com/acode/ui/InputPane.java`：
  - 删除 `ScrollHandler` 接口（:25-30）与构造参数（:36-38）
  - 删除 `Option.MOUSE`（:42）、`trackMouse`（:47）、`bindMouse`/`onScrollbarPress`（:76-121）、`WHEEL_STEP`（:124）、`pageLines`（:127-129）、`outputAreaLines`（:132-135）、`runScroll`（:141-146）
  - 删除 SCROLL_UP/DOWN widget 与 PageUp/PageDown 绑定（:55-73 中相关段）
  - 构造启用擦除行选项（R2），使接受输入时擦除输入行、不写换行
  - 保留：NEWLINE_WIDGET 与 Shift+Enter/Ctrl+Enter 绑定（:20、:51-54、:68）、Enter 提交绑定（:66）、`readLine`

**依赖**：T2、T3

**参考资料**
- InputPane.java 全文；R2（擦除行选项在构造 `LineReaderBuilder` 处，:39-43）

---

### T5 StreamPrinter 双写改造

**目标**：流式渲染全部经活跃区渲染器：增量重绘、卡片渲染、错误收尾都同步活跃区。

**影响文件（修改）**
- `src/main/java/com/acode/ui/StreamPrinter.java`：构造注入 `LiveRegionRenderer`（替代 `redraw` 回调）；`replaceTail`（:92-103）改为「内容模型尾部替换 + live.redraw」；`onToolUse`（:44-56）提交文本后把运行中卡片渲染进活跃区（**不写入内容模型**）；`updateToolCalls`（:59-72）改为「终态卡片写入内容模型 + live.redraw」，删除旧的 `output.removeLast(cardLines)`（:64）——运行中卡片从未提交、无可移除；运行中卡片已滚入回滚的边界残留接受（R3）；`onError`（:81-90）清除半截回复并显示错误行；`onComplete`（:75-78）保持重置
- `src/test/java/com/acode/ui/StreamPrinterTest.java`：约 5 个用例改为断言活跃区收到的渲染行 / 写序列（注入假 LiveRegionRenderer 或 StringWriter 捕获）；卡片用例断言运行中卡片不进内容模型、终态卡片写入内容模型

**依赖**：T4

**参考资料**
- StreamPrinter.java 全文；T6 的 ToolCallDisplay 改造与本任务耦合，建议同会话完成

---

### T6 ToolCallDisplay 拆渲染与写入

**目标**：工具卡片渲染与写入解耦：卡片只产生渲染行，写入统一由 StreamPrinter 经活跃区完成。

**影响文件（修改）**
- `src/main/java/com/acode/ui/ToolCallDisplay.java`：`appendRunning`/`appendDone` 改为返回渲染行字符串（List<String>），不再写 OutputPane；`appendRunning` 的结果仅供活跃区渲染（不提交），`appendDone` 的结果由 StreamPrinter 写入内容模型并进入回滚；`lineCount` 保持
- `src/test/java/com/acode/ui/ToolCallDisplayTest.java`：改调用点（渲染行断言替代写入断言）

**依赖**：T5

**参考资料**
- ToolCallDisplay.java 全文；StreamPrinter.java:49-72（当前调用点）

---

### T7 handleExchange 接入活跃区

**目标**：主流程的交换循环把 repaint 语义切换为活跃区重绘，删除残余视口调用。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`：
  - `handleExchange`（:419-472）里 repaint 回调改为活跃区重绘；StreamPrinter 构造注入 LiveRegionRenderer；删除 `output.resetScroll()`（:420）
  - 测试用 `setOutput`/`conversation()` 保留
- `src/test/java/com/acode/ConversationControllerTest.java`：存量用例基本不动（repaint 仍传 no-op）；新增 1 个用例：流式期间每次增量触发活跃区重绘（注入假 live 断言调用次数）

**依赖**：T5、T6

**参考资料**
- ConversationController.java:419-472（handleExchange 事件循环）；StreamPrinter.java:29-32（构造）

---

### T8 会话选择菜单与恢复渲染

**目标**：`/resume` 会话选择菜单改为活跃区 overlay（R5），会话历史恢复改为追加渲染。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`：
  - `selectSession`（:216-250）：菜单行作为活跃区 overlay 列表（不进 OutputPane 已提交内容、不进回滚）；按键走同一上移/清屏/重绘序列；选定/取消后清除菜单、再追加会话历史
  - `loadSession`（:330-344）与 `restoreIfResume`（:135-150）：历史消息经 `appendCommitted` 追加式渲染进回滚；加载另一会话不再清空回滚（append-only 下旧文本不可擦除，新会话历史追加其后、不重复打印 banner），删除 `output.clear()` 与 `output.resetScroll()`（:336）
  - `drainPendingInput`（:309-327）与 `readMenuKey`（:272-303）逻辑保留
- 测试：`renderHistoryMessage`（:360-389）单测保留（渲染文本不变）

**依赖**：T7

**参考资料**
- ConversationController.java:135-150、:216-250、:272-327、:330-344；R5

---

### T9 接入主流程

**目标**：mainLoop 与启动流程切到活跃区渲染，全屏重绘彻底移除，确认光标契约与输入衔接。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`：
  - mainLoop（:152-204）：删除 `tui.repaint(output)`（:167）、`tui.invalidateShadow()`（:176）与 ScrollHandler 匿名类（:153-165，连带 `repaintOutputArea`/`scrollToMouseY` 引用）；InputPane 改「去掉 ScrollHandler 参数」的构造装配
  - `start`（:122-133）：banner 与「输入 /help 查看命令」提示经 `appendCommitted` 追加式渲染进回滚
  - `/clear`（:182-186）：只追加一行清空标记，不触碰回滚中的历史；删除 `output.resetScroll()`（:184）
  - 每次 exchange 收尾确认光标停于内容下方行首（R1），与下一次 readLine 衔接
- 依赖 T2 删掉的 `repaint`/`repaintOutputArea` 引用随之清除（`grep -rnE '\.repaint\(|invalidateShadow|repaintOutputArea' src/main/java` 应无结果）

**依赖**：T4、T7、T8

**参考资料**
- ConversationController.java:122-204（start/mainLoop）；InputPane.java 全文（去掉 ScrollHandler 参数的构造）

---

### T10 端到端验证

**目标**：全量回归 + 真实终端手动验收，确认主屏可复制架构端到端成立。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md` 追加阶段四小节 — 手测步骤：启动后在 Windows Terminal 中直接划选历史输出复制；滚轮滚动回看；输入长问题看超屏流式渲染；流式中途划选不被重绘打断；拖动窗口 resize；`/resume` 菜单选择；Ctrl+C 中断；Shift+Enter 多行与粘贴；确认输入无回显双写、退出后终端状态正常
- 修 bug 产生的影响文件视情况

**依赖**：T9

**参考资料**
- 手测按 checklist.md 逐项打勾；全量 `mvn test`（`JAVA_HOME=D:\java\jdk21`）
