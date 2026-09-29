# ACode 终端缩放故障交接（2026-09-25）

## 当前状态

用户在真实 Windows CMD 中验证了第四版：拖动窗口放大、缩小后，模式行和长分隔线会重复堆叠，甚至覆盖启动 banner。用户输入 `abc`、缩放、再输入 `def`，最终完整提交为 `abcdef`。因此**输入内容未丢，但画面仍损坏；resize 故障没有修复**。

用户要求暂停当前对话，另开对话继续解决。此文档写完后不要在本对话继续实现。当前工作区有未提交改动，**第五版单行提示符改造只完成了一部分，未运行测试、未打包、未真机验证**。`target/acode.jar` 仍是第四版，不能当作第五版结果。

## 复现材料

- 第四版真机输出：`C:\Users\liuch\.codex\attachments\6199e7d6-49df-46a3-9575-4c62fda1b4f9\已粘贴的文本.txt`。每次宽度变化都留下类似 `────[default] · /permission <模式>` 的一行；最后可见 `● abcdef`。
- 第三版真机输出：`C:\Users\liuch\.codex\attachments\d70c75d1-4abd-46d1-96e1-94c85a981fbe\已粘贴的文本.txt`。出现阶梯状重复模式行。
- 最早含 CPR 响应污染的截图：`C:\Users\liuch\AppData\Local\Temp\codex-clipboard-63cd9480-55fc-417a-a582-d61435d6c0f0.png`。
- 历史诊断：本目录 `findings.md`、`progress.md`；项目根目录 `findings.md`、`progress.md`、`task_plan.md`、`spec.md`、`tasks.md`、`checklist.md`。这些文档包含已被真机否定的旧方案，应以本交接的现状为准。

## 已试过的路径与证据

1. `ResizeProbe fix` 用 ANSI CPR 查询实际光标：活动 `readLine` 与 CPR 抢同一输入流，响应变成用户输入，屏幕出现 `;;3R` / `R[...]`。此模式已在 `probe/ResizeProbe.java` 中标记废弃，不要再运行。
2. 120ms watcher + JNA 原生光标查询：避免了 CPR 污染，但 watcher 与 JLine WINCH 各自重绘，放大使输入框异常变高，缩小留下多个页眉。
3. 统一到 `LineReaderImpl.handleSignal(WINCH)`，但在 `super.handleSignal` 前手动移动真实光标/清屏：JLine 内部 `Display.cursorPos` 没同步，真机产生阶梯状重复页眉。
4. 第四版删除活动读取期间的应用层光标查询、移动和清屏；仅更新 prompt，再由 JLine 默认 WINCH 重绘。全量测试通过（1123 tests，0 failures，0 errors，1 skipped），但真机依然每次 WINCH 留下模式行和全宽分隔线。`abcdef` 正确提交。这表明虚拟终端测试不能代表 Windows 真实回流；“多行 prompt + Status”是下一步应重点隔离的组合，尚不能断言其为唯一根因。

## 中断时的代码状态（重要）

已经写入但尚未验证的第五版草稿：

- `src/main/java/com/acode/CommandProcessor.java`：`InputFrame.promptHeader()` 已改为 `inputPrompt()`，`prompt()` 改为直接返回单行内容。
- `src/main/java/com/acode/ConversationController.java`：输入帧改调用 `StatusBar.inputPrompt(...)`，去掉顶部全宽分隔线。
- `src/main/java/com/acode/ui/StatusBar.java`：新增 `inputPrompt(mode, width)`，把模式信息和 `> ` 放在一行。
- `src/test/java/com/acode/CommandProcessorTest.java`：部分断言已改为单行提示符。

以下相关测试**仍是旧版**，说明上一个补丁在中途被打断：

- `src/test/java/com/acode/ConversationControllerTest.java` 的 `promptHeaderPrefixesDefaultPromptWithModeLineAndDivider` 仍断言三行和顶部全宽分隔线。
- `src/test/java/com/acode/ui/ResizeAwareLineReaderTest.java` 仍用 `"width=...\n> "` 多行 prompt 做事件风暴测试。
- `src/test/java/com/acode/ui/StatusBarTest.java` 尚无 `inputPrompt` 的宽度/窄窗口断言。

预计当前源码与测试不一致。**不要把上次 1123 测试全绿的记录当作当前工作区的测试结果。** `git diff --check` 在交接前未发现空白错误，但它不是编译或功能验证。

其他已有实现：`ResizeAwareLineReader.java` 在 WINCH 中调用 `setPrompt(...)`、`super.handleSignal(...)`、页脚回调；`InputPane.java` 暴露本轮是否发生 resize；`CommandProcessor` 在 resize 后丢弃旧 `BottomAnchor.unpin` 距离；`BottomAnchor` 在进入读输入前优先用 Windows 原生光标位置，不可用时才用限时 CPR。上述是第四版代码，不能保证视觉正确。

## 新对话建议的起点

1. 先读本文件与上述三处第五版草稿，检查 `git status --short`；保留工作区其他未跟踪文件和既有用户改动，不执行重置/清理。
2. 明确验证目标：启动布局正常；连续缩窄和放大时只有一份活动提示符、页脚；`abc` + resize + `def` 只提交一次 `abcdef`；提交、下一轮、Ctrl+C 后无残留。
3. 完成或修正当前单行提示符草稿及对应测试；注意单行提示符接近终端宽度时，输入字符可能立刻折行，建议预留编辑空间，不能仅保证提示符自身宽度不超界。
4. 先用隔离实验判断重复来自 JLine prompt、JLine Status、`BottomAnchor`，还是三者的交互。单行方案只是待验证假设，不能在虚拟终端通过后宣称修好。
5. 运行定向测试、全量测试和打包，再在真实 Windows 终端拖动验证。真实终端验收前保持任务未完成。

项目使用 Java 21，JDK 路径 `D:\java\jdk21`，Maven。真实终端运行时需要清除从 Codex 继承的 `TERM=dumb`，例如在 CMD 中执行 `set TERM=` 后再运行 `D:\java\jdk21\bin\java.exe -jar target\acode.jar`。Codex 内置命令 PTY 不具备此 TUI 所需能力，不能替代真实终端验收。

数据库限制：未经允许不得修改数据库；若要修改或删除，先备份。此次工作没有修改数据库。不要提交或推送，除非用户另行要求。
