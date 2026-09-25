# 终端缩放与固定输入框任务

## T1 复现真实终端故障

- 影响文件：`docs/ui-resize-diagnosis/HANDOFF.md`、`findings.md`
- 依赖任务：无
- 参考资料：交接文档中的 CMD 复现步骤与用户提供的窗口内容
- 工作：记录连续缩放留下模式行、横线和横向错位，但 `abcdef` 仍只提交一次。

## T2 固化界面验收范围

- 影响文件：`spec.md`、`checklist.md`、`docs/manual-test.md`
- 依赖任务：T1
- 参考资料：用户确认的五层输入框、长输入向上增高、CMD/PowerShell/Windows Terminal 三种环境
- 工作：写明输入框贴底、滚轮回看、鼠标复制和真实终端验收标准。

## T3 修复 Windows 终端初始化

- 影响文件：`pom.xml`、`src/main/java/com/acode/ui/AcodeTerminal.java`、`src/main/java/com/acode/ui/TerminalCaps.java`
- 依赖任务：T1
- 参考资料：`AcodeTerminal.statusBarSafeType`、`TerminalCaps.shouldUseWindowsType`、`infocmp` 启动堆栈
- 工作：升级 JLine；空白 TERM 使用内置 Windows 能力表，启动时不调用缺失的 `infocmp`。

## T4 统一底部输入框绘制

- 影响文件：`src/main/java/com/acode/ui/ResizeAwareLineReader.java`、`src/main/java/com/acode/ui/InputPane.java`、`src/main/java/com/acode/ui/StatusBar.java`
- 依赖任务：T2、T3
- 参考资料：`ResizeAwareLineReader.redisplay`、`StatusBar.frameModeLine`、JLine `Status.update`
- 工作：让模式行、两条边线、编辑内容和模型页脚共用一个绘制区域。

## T5 缩放后重建对话视图

- 影响文件：`src/main/java/com/acode/ui/ResizeAwareLineReader.java`、`src/main/java/com/acode/ui/OutputPane.java`
- 依赖任务：T4
- 参考资料：`ResizeAwareLineReader.handleSignal`、`OutputPane.lines`、Windows VT 清屏行为
- 工作：清除终端回流留下的旧帧，重放 ACode 已提交内容，再绘制单份当前输入框。

## T6 支持多行向上增高

- 影响文件：`src/main/java/com/acode/ui/ResizeAwareLineReader.java`、`src/test/java/com/acode/ui/ResizeAwareLineReaderTest.java`
- 依赖任务：T4
- 参考资料：`ResizeAwareLineReader.inputRows`、`ResizeAwareLineReader.redisplay`、JLine `Status.update`
- 工作：预留稳定高度，长行和 Shift+Enter 消耗上方空白行，不反复改变终端滚动区域。

## T7 确定滚轮和复制交互

- 影响文件：`src/main/java/com/acode/ui/ResizeAwareLineReader.java`、`spec.md`、`checklist.md`
- 依赖任务：T4、T5
- 参考资料：`ResizeAwareLineReader.mouse`、`paintHistory`、Windows CMD QuickEdit 行为
- 工作：按用户选择明确原生 CMD 鼠标复制与固定滚轮回看的优先顺序，并在三种终端保持一致的可理解行为。

## T8 完成自动化与说明

- 影响文件：`src/test/java/com/acode/ui/ResizeAwareLineReaderTest.java`、`README.md`、`docs/manual-test.md`、`checklist.md`
- 依赖任务：T3 至 T7
- 参考资料：现有输入缓冲、事件风暴、状态区及主循环测试
- 工作：覆盖输入完整性、固定高度、滚轮路径和缩放重建；记录自动化无法模拟 Windows 真实回流。

## T9 接入主流程

- 影响文件：`src/main/java/com/acode/CommandProcessor.java`、`src/main/java/com/acode/ConversationController.java`、`src/main/java/com/acode/ui/InputPane.java`
- 依赖任务：T3 至 T8
- 参考资料：`CommandProcessor.mainLoop`、`ConversationController.commandProcessor`、`RenderContext.closeStatus`
- 工作：把统一绘制、缩放重建、滚轮行为及退出清理连接到实际对话流程。

## T10 端到端验证

- 影响文件：`target/acode.jar`、`checklist.md`、`docs/ui-resize-diagnosis/progress.md`
- 依赖任务：T9
- 参考资料：`docs/manual-test.md` 的启动、缩放、多行、滚轮、提交和退出场景
- 工作：全量测试和打包，在 CMD、PowerShell、Windows Terminal 分别验收无残影、输入框贴底、鼠标复制、长输入、多行、单次提交及退出后历史。
