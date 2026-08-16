# ACode 阶段六：工具结果渲染（Claude Code 式命令输出展示）— 验收清单

> 最后更新：2026-08-16
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 默认值说明：运行行格式「● <工具名>(<参数摘要>)」（工具名青色）；输出块首行「  ⎿  <内容行>」（成功绿/失败红）、后续行「     <内容行>」（原色）；脚注「  ⎿  (<耗时>)」（灰色）；耗时格式 <1s→`823ms`、≥1s→`2.3s`；单条上限 300 行，超出补「…（输出过长，已截断）」；空内容出「（无返回结果）」。

## T1 ToolResultEvent.elapsedMs

- [ ] `mvn compile` 通过，`com.acode.agent` 包编译无警告
- [ ] `toolResultEventRecordCarriesOutputAndErrorFlag` 断言 `elapsedMs()` 取回正确
- [ ] AgentEventTest 三处构造（L43/L47/L90）均补 `elapsedMs` 参数

## T2 StreamingToolExecutor 计时

- [ ] 正常路径 ToolResultEvent 的 `elapsedMs` ≥ 0（对真实执行的桩工具可观察到 >0）
- [ ] 确认拒绝路径（gate 返回 false）ToolResultEvent `elapsedMs == 0`
- [ ] 取消路径（cancelled 置位）不产生新的 ToolResultEvent（维持现状）

## T3 ToolCallDisplay 渲染

- [ ] 运行行含「●」与工具名，不再含「⏳」「调用工具」字样
- [ ] 成功终态：首行含 `  ⎿  ` 与 `STYLE_OK`，不含「成功」字
- [ ] 失败终态：首行含 `  ⎿  ` 与 `STYLE_ERR`，不含「失败」字
- [ ] 多行结果逐行渲染，后续行前缀「     」对齐缩进、不带成败色
- [ ] >300 行输出被截断，末尾含「…（输出过长，已截断）」
- [ ] 空/无结果出占位行「（无返回结果）」
- [ ] 脚注行含 `  ⎿  ` 与 `(<耗时>)`；`formatDuration`：`823→"823ms"`、`2300→"2.3s"`、`0→"0ms"`、`5000→"5.0s"`（含 `Locale.ROOT` 不产生逗号小数）
- [ ] `collapse()` 与 `MAX_RESULT_LENGTH` 已删除，无残留引用

## T4 主流程接线

- [ ] `updateToolCalls(List<ToolResult>, List<Long>)` 新签名编译通过，两处生产调用（LoopComplete/TurnComplete）与一处测试调用均传平行耗时列表
- [ ] ConversationController 事件循环对 ToolResultEvent 同时记 `turnResults` 与 `elapsedList`（声明序对齐）
- [ ] TurnComplete 后 `elapsedList` 与 `turnResults` 同处重置
- [ ] `StreamPrinterTest.updateToolCallsCommitsDoneCardsToModel` 断言模型包含输出块行与耗时脚注行

## T5 单测收口

- [ ] ToolCallDisplayTest 重写后全绿（● 行 / 首行着色 / 多行缩进 / 截断 / 空占位 / 脚注 / formatDuration）
- [ ] StreamingToolExecutorTest `toolResultEventEmittedPerCompletedCall` 断言 `elapsedMs ≥ 0`
- [ ] 全程 `JAVA_HOME="D:\java\jdk21" mvn test` 全绿（新增用例无网络依赖）

## T6 端到端验收 ⚑

- [ ] 真实 API：让模型执行 `Bash` 命令（如 `echo hello`）→ 屏上出现「● Bash(command="...")」→ 执行完出现缩进输出块（`  ⎿  hello`）→ 末尾「⎿ (XXms)」脚注
- [ ] 真实 API：让模型执行失败命令（如 `ls /nonexistent`）→ 输出块首行呈红色 `⎿`、整体可读
- [ ] 真实 API：让模型 `ReadFile` 一个多行文件 → 内容原样缩进展示（每行对齐、不折叠成一行）
- [ ] 真实 API：让模型跑长输出命令（如 `mvn -q dependency:tree` 或遍历大目录）→ 出现「…（输出过长，已截断）」而非刷屏
- [ ] 真实 API：多工具一轮（先 ReadFile 再 EditFile 或 Bash）→ 每个工具独立 ● 块 + 各自耗时脚注，前后可分辨
- [ ] 拒绝/批准轮次后 `--resume` 恢复会话 → 历史里工具卡片为输出块形态，可继续对话
- [ ] 全程 `mvn test` 全绿
