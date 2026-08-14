# ACode 阶段四：终端渲染改造（主屏可复制）— 验收清单

> 最后更新：2026-08-14
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 关键值说明：活跃区重绘写序列 = `\033[{up}A`（上移，up=min(已写行数, 屏高-1)，up>0 时写）→ `\033[J`（清到屏尾）→ 每段 `段内容\r\n`；所有输出以换行收尾、光标停行首，禁止绝对定位转义（`\033[r;cH`）；输入提交启用擦除行选项、应用统一追加「● 输入」；折行按显示宽度（宽字符占 2 列）、折点不切断宽字符与 ANSI 序列；终端宽高变化后活跃区已写行数先归零再重绘；`appendCommitted` 写 `行\r\n`、进入回滚、不计已写行数。

## T1 活跃区渲染器 LiveRegionRenderer

- [ ] `mvn compile` 通过，`com.acode.ui.LiveRegionRenderer` 无编译警告
- [ ] wrap 迁移后原 AcodeTerminalTest 的 7 个 wrap 用例（含 `displayWidth` 辅助方法）迁入 LiveRegionRendererTest 且全绿
- [ ] `upRows(已写行数, 高度)` = min(已写行数, 高度-1)；高度 ≤1 时返回 0
- [ ] `visibleSegs` 只返回末尾可见段，段数 ≤ 高度-1
- [ ] `redraw` 写出的转义序列（StringWriter 捕获）含 `\033[NA`（上移）、`\033[J`（清到屏尾）、每段行尾 `\r\n`；已写行数 0 时不含上移序列
- [ ] 内容宽度恰好等于终端宽度时，redraw 不产生幻影空行（R4）
- [ ] 超过一屏的活跃区重绘只含可见后缀段（R3）
- [ ] `clear()` 写出清空序列且已写行数归零
- [ ] 终端宽高变化后 redraw 先把已写行数归零再重绘（reflow 后旧行数失效）
- [ ] `appendCommitted(Writer, 行)` 写出 `行\r\n`、进入回滚、不改变已写行数

## T2 瘦身终端壳 AcodeTerminal

- [ ] `grep -rnF '\033[?1049' src` 无结果（备用屏幕进出已删）
- [ ] `grep -rnE 'shadow|invalidateShadow|repaintOutputArea' src/main/java/com/acode/ui/AcodeTerminal.java` 无结果
- [ ] AcodeTerminal 公开方法只剩 open/close/height/width/write/flush/terminal()
- [ ] `open()` 检查 height>0 且具备光标上移/清屏能力（cursor_up/clr_eos）；无可用终端时仍抛错退出
- [ ] `close()` 直接关闭终端，无退出备用屏幕转义
- [ ] AcodeTerminalTest 文件整体删除（wrap 用例已迁 LiveRegionRendererTest、滚动条/视口用例已删）

## T3 OutputPane 删视口逻辑

- [ ] `grep -rnE 'scrollOffset|visibleLines|scrollUp|scrollDown|scrollBy|resetScroll|setScrollOffset' src/main/java/com/acode/ui/OutputPane.java` 无结果
- [ ] OutputPane 保留 append/appendLine/clear/removeLast/lineCount/lines，行为不变
- [ ] OutputPaneTest 视口/滚动用例删除 12 个（visibleLines 4 个 :78-105、scroll 8 个 :142-230），保留用例在 T10 全量测试全绿

## T4 InputPane 精简

- [ ] `grep -rnE 'trackMouse|MouseTracking|Option.MOUSE|MouseEvent|bindMouse|runScroll|ScrollHandler' src/main/java/com/acode/ui/InputPane.java` 无结果
- [ ] InputPane 构造不再需要 ScrollHandler 参数
- [ ] 提交输入后输入行被擦除、屏幕无输入原文残留（配合 T9 双写检查）
- [ ] 方向键输入历史、Shift+Enter 多行、Enter 提交、粘贴行为与改造前一致

## T5/T6 StreamPrinter 双写 + ToolCallDisplay 拆渲染

- [ ] StreamPrinter 的全部渲染经 LiveRegionRenderer，不再直接写终端
- [ ] onDelta 每帧触发一次活跃区重绘（注入假 live 断言调用次数 ≥1）
- [ ] 工具卡片：onToolUse 后活跃区出现「进行中」卡片且不写入内容模型；updateToolCalls 后终态卡片写入内容模型并在活跃区更新为「完成/失败」终态
- [ ] 中断/报错后内容模型无「运行中」卡片行（已滚入回滚的运行中文本属接受的边界残留）
- [ ] onError 后半截回复被清除、错误行显示在活跃区
- [ ] ToolCallDisplay.appendRunning 结果仅供活跃区、appendDone 结果由 StreamPrinter 提交内容模型，二者均不再直接写 OutputPane
- [ ] StreamPrinterTest / ToolCallDisplayTest 改造后全绿

## T7 handleExchange 接入活跃区

- [ ] `grep -rnE '\.repaint\(|resetScroll' src/main/java/com/acode/ConversationController.java` 无结果
- [ ] handleExchange 全程输出经活跃区，无全屏重绘调用
- [ ] ConversationControllerTest 存量用例平移后全绿
- [ ] 新增用例：流式期间每次增量触发活跃区重绘（假 live 断言），通过

## T8 会话选择菜单与恢复渲染

- [ ] `/resume` 菜单按键后：菜单在活跃区重绘、不进回滚（回滚内搜不到菜单行文本）
- [ ] 选定会话后菜单清除、历史经 appendCommitted 以追加方式渲染显示
- [ ] 加载另一会话时旧会话文本保留在回滚、新会话历史追加其后（不清空回滚、不重复打印 banner）
- [ ] Esc 取消菜单恢复原状；↑/↓/回车/Esc 行为与改造前一致（drainPendingInput/readMenuKey 保留）
- [ ] `--resume` 恢复会话历史以追加方式显示，工具块仍为单行摘要，文本可选中

## T9 接入主流程

- [ ] `grep -rnE 'invalidateShadow|repaintOutputArea|\.repaint\(' src/main/java/com/acode` 无结果（全屏重绘彻底移除）
- [ ] InputPane 以去掉 ScrollHandler 参数的构造装配（仍传 Terminal+prompt），编译通过
- [ ] banner 与「输入 /help 查看命令」提示为回滚中可复制的文本
- [ ] `/clear` 后回滚保留旧内容、只追加一行清空标记
- [ ] 每次 exchange 收尾光标停于内容下方行首；下一行输入提示位置正确、无错位
- [ ] 正常退出后终端回到 shell 正常状态，无残留转义/raw 状态

## T10 端到端验收 ⚑

- [ ] Windows Terminal 中鼠标直接划选任意历史输出并复制成功，复制内容与屏幕一致（含代码块与普通文本）
- [ ] 滚轮向上滚动可回看本会话全部历史（原生 scrollback），滚回底部后输入正常
- [ ] 输入一个需要长回答的问题，流式输出超过一屏继续正常渲染，无残留错行、无幻影空行
- [ ] 流式输出中途用鼠标划选一段文本：选择保持、不被重绘打断
- [ ] 拖动窗口改变尺寸：内容原生回流、无清屏、不错位、输入继续可用（含流式输出中途拖拽，下一帧重绘不错位）
- [ ] `/resume` 菜单 ↑/↓ 选择、回车加载、Esc 取消均正常，选定后菜单消失
- [ ] 流式输出中 Ctrl+C → 输出「已中断」、可继续输入新问题
- [ ] 每次提交后输入行无原文残留（无「输入行 + ● 输入」双行）
- [ ] Shift+Enter 多行输入、粘贴 20 行代码与改造前一致
- [ ] 全程 `mvn test` 全绿（`JAVA_HOME=D:\java\jdk21`）
