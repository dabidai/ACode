# ACode 阶段七：选择交互（↑↓ 菜单替换 y/n + AI 选择工具）— 验收清单

> 最后更新：2026-08-16
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 默认值说明：选中行「`> ` + 整行反显 `\033[7m`」、未选中行「两空格 + 文本」、菜单首行 header 原色；确认菜单选项固定「是/否」默认选「是」；Esc/Ctrl+C/EOF 等价取消；AskUser 为单选菜单、options 为空回传失败「选项不能为空」、取消回传「用户取消选择」。

## T1 按键源抽象

- [ ] `MenuKeySource` 为函数接口，含 KEY_NONE/UP/DOWN/ENTER/CANCEL 常量与 `default void drainPendingInput() {}`
- [ ] `TerminalMenuKeySource.readKey` 解析：`\r`/`\n`→ENTER、0x03→CANCEL、`\033[A`/`\033OA`→UP、`\033[B`/`\033OB`→DOWN、裸 Esc→CANCEL、**EOF(-1)→CANCEL**
- [ ] `drainPendingInput` 50ms 窗口排空残留字节（EOF 结束）
- [ ] `TerminalMenuKeySourceTest` 全绿（假 NonBlockingReader 脚本化字节，无真实终端）

## T2 SelectionMenu

- [ ] 渲染：首行 header 原色；选中行含 `\033[7m> X\033[0m`；未选中行 `"  Y"`（两空格前缀）
- [ ] ↑/↓ 环形移动：首项↑→末项、末项↓→首项
- [ ] Enter 返回选中 index；Esc 返回 -1；NONE 忽略不重绘错误
- [ ] 所有退出路径（Enter/Esc）后 `rowsWritten()==0`（clear 收敛在 select 内）
- [ ] `SelectionMenuTest` 全绿（脚本化键队列）

## T3 /resume 重构

- [ ] `selectSession` 改用 SelectionMenu 后编译通过，`readMenuKey`/`drainPendingInput`/`KEY_*`/`menuLines`/`menuLine` 已删除无残留
- [ ] /resume 条目列表最后一个会话为初始选中（反显）
- [ ] header 文案仍为「（↑/↓ 选择会话，回车加载，Esc 取消）」
- [ ] `preview` 缩略预览逻辑保留

## T4 确认菜单

- [ ] ConfirmationPrompt 构造签名改为 `(MenuKeySource, LiveRegionRenderer, Writer)`，`isYes`/`isNo`/`readAnswer`/`PROMPT` 已删
- [ ] 提示行文案「要执行「X（args）」？」不再含 `[y/n]` 后缀
- [ ] 键序列注入：默认选「是」→ 返回 true + 写「（已批准执行「X」）」；选「否」→ false + 「（已拒绝执行「X」）」；Esc → false + 「（已取消）」
- [ ] ConfirmationPromptTest 重写后全绿；`answerConfirmationPrompt` 在 `tui==null` 时返回 false（测试环境兜底）

## T5 Choice + ChoiceRequestEvent

- [ ] `Choice.await` 50ms 轮询；cancel 置位→null；中断恢复中断位→null；null 语义=取消
- [ ] `answer` 幂等（重复应答不阻塞/不报错；null→Optional.empty）
- [ ] AgentEvent 新增 `ChoiceRequestEvent(toolId, toolName, question, options, response)`；AgentEventTest 构造冒烟通过

## T6 InteractiveTool 分派

- [ ] `StreamingToolExecutor.runCall` 对 InteractiveTool 走 `executeInteractive`，不走 `execute`
- [ ] 交互路径 ToolResultEvent `elapsedMs == 0`（不含用户思考时间）
- [ ] cancelled 置位时交互路径不产生新 ToolResultEvent（fillCancelled 兜底）
- [ ] AskUser 为 READ 权限，不触确认门；StreamingToolExecutorTest 新增用例全绿

## T7 AskUserTool

- [ ] `inputSchema` 为手写 string 数组 schema：`options` 为 `{"type":"array","items":{"type":"string"}}`，required 含 question 与 options
- [ ] options 为空/非法 → 直接 failure「选项不能为空」，不弹事件不弹菜单
- [ ] 用户选中「X」→ 工具结果 `success("X")`；Esc/Ctrl+C/EOF 取消 → failure「用户取消选择」
- [ ] ConversationController 构造器注册 AskUserTool 后，首轮请求 `tools()` 含 AskUser（AskUserToolTest 断言）
- [ ] `execute()` 防御路径返回 failure（生产只走 executeInteractive）

## T8 接入主流程

- [ ] `setChoiceAnswerer` 可注入；`answerChoicePrompt` 在 `tui==null` 时返回 null（取消兜底）
- [ ] 事件循环 ChoiceRequestEvent 分支调用 `choice.response().answer(...)`
- [ ] 端到端：FakeProvider 脚本模型调 AskUser → 桩选「B」→ 第二轮请求 `tool_result.content()=="B"` 且 `isError=false`、`toolUseId` 关联原 id
- [ ] 端到端：桩返 null → `tool_result.isError=true` 且内容含「取消」
- [ ] 选中后界面写「（已选择「B」）」、取消写「（已取消）」（真实 answerChoicePrompt 路径；setChoiceAnswerer 桩跳过 UI 写入，状态行由 T9 真终端手测覆盖）
- [ ] 全程 `JAVA_HOME="D:\java\jdk21" mvn test` 全绿（新增用例无网络依赖）

## T9 端到端验收 ⚑

- [ ] 真实 API：让模型执行 WriteFile/Bash → 弹出 ↑↓ 菜单（`> 是` 反显默认选中）而非 `[y/n]` 行输入
- [ ] 真实 API：菜单中 ↑↓ 移动高亮、Enter 批准 → 工具执行 + 「（已批准执行「X」）」
- [ ] 真实 API：菜单中 Esc → 显示「（已取消）」、工具不执行、模型收到拒绝结果并调整
- [ ] 真实 API：让模型调 AskUser（如「你想先做 A 还是 B？」多选项）→ 出多选项菜单、选中后模型收到所选文本并继续
- [ ] 真实 API：AskUser 菜单中 Esc → 模型收到失败结果（含「取消」）并调整
- [ ] 真实 API：plan 模式下 AskUser 可用（READ 权限自动进工具表）
- [ ] 真实 API：/resume 菜单行为不变（`>` 箭头 + 反显、↑↓/回车/Esc 各试一遍）
- [ ] 真终端确认/选择菜单进出后回滚不污染（滚动回看搜不到菜单行），退出后终端状态正常
