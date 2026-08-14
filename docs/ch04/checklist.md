# ACode 阶段三：Agent Loop — 验收清单

> 最后更新：2026-08-14
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 默认值说明：max_iterations 默认 20（非正报错）、截断恢复上限 3 次、可重试错误重试上限 2 次（退避复用 RetryPolicy）、事件队列容量 64、工具结果进入历史前截断 2000 字符、计划落盘 `.acode/plans/plan-<slug>.md`、取消补位文案「已取消」、截断继续提示文案「输出被截断，请从断点继续，不要重复已输出内容」、触顶提示「达到最大轮数」、plan 交付提示「输入 /do 退出 plan 模式开始执行」、ExitPlanMode 非 plan 模式错误文案「只能在 plan 模式下调用」。

## T1 AgentEvent 事件模型

- [ ] `mvn compile` 通过，`com.acode.agent` 包编译无警告
- [ ] 7 种事件类型齐全：StreamText / ToolUseEvent / ToolResultEvent / TurnComplete / LoopComplete / ErrorEvent / RetryEvent
- [ ] 队列容量常量值为 64，Agent 与测试共用同一常量
- [ ] 各 record 构造/访问器冒烟测试通过

## T2 Provider 层 stop_reason 透传

- [ ] `ChatListener.onComplete()` 改 default 委托后，存量实现（StreamPrinter / FakeProvider / 各处匿名类）零改动且测试全绿
- [ ] Anthropic 录制片段：message_delta 带 `stop_reason:"end_turn"` → listener 收到 stopReason="end_turn"
- [ ] Anthropic 录制片段：message_delta 带 `stop_reason:"max_tokens"` → listener 收到 stopReason="max_tokens"
- [ ] OpenAI 录制片段：finish_reason="stop" → listener 收到 stopReason="stop"
- [ ] OpenAI 录制片段：finish_reason="length" → listener 收到 stopReason="length"
- [ ] 无 stop_reason 的流（如 FakeProvider 无参 complete）→ 收到 null，行为与改前一致

## T3 TurnCollector 流式收集器

- [ ] 单轮脚本（delta + tool_use + complete("end_turn")）→ text() 拼接完整、toolUses() 含全部调用、stopReason()="end_turn"
- [ ] 事件入队顺序：StreamText 先于 ToolUseEvent，且与脚本顺序一致
- [ ] cancelled 置位后，后续 onDelta/onToolUse/onComplete 回调被忽略（不累积、不发事件）
- [ ] onError 被记录且可被上层读取

## T4 StreamingToolExecutor 工具分区执行器

- [ ] 混合批次（ReadFile + WriteFile + ReadFile）→ 执行时序：两个读先完成、写在读之后开始（用记录时序的桩工具断言）
- [ ] 两个读类调用真实并发执行（两桩同时运行，总耗时约等于单个耗时而非两倍）
- [ ] 结果 List 顺序与输入声明顺序一致（回传顺序 = 声明顺序，与执行顺序无关）
- [ ] 每完成一个调用 → 事件队列收到一条 ToolResultEvent（含 toolId/toolName/output/isError）
- [ ] cancelled 置位时未执行/未完成的调用补 `ToolResult.failure("已取消")`，结果 List 长度与输入一致
- [ ] 未注册/已禁用的工具 → 返回失败结果，不抛异常
- [ ] 空批次 → 返回空 List，不产生事件

## T5 Agent 循环本体

- [ ] 单轮无工具脚本 → 1 次请求、LoopComplete(1)、termination=NORMAL、assistant 文本入历史
- [ ] 两轮脚本（tool_use → 最终文本）→ 2 次请求、第 2 轮历史含第 1 轮 tool_result、LoopComplete(2)
- [ ] 三轮链（tool_use → tool_use → 文本）→ 3 次请求、每轮历史逐轮累积、LoopComplete(3)
- [ ] maxIterations=2 且脚本持续返回 tool_use → 第 2 轮触顶不执行工具、LoopComplete、termination=MAX_ITERATIONS、已完成的工具结果保留在历史
- [ ] 流式中 cancel() → 循环结束、termination=CANCELED、不发 LoopComplete
- [ ] 工具执行中 cancel() → 未执行工具补「已取消」结果入历史、无悬空 tool_use（R5）
- [ ] `complete("max_tokens")` 脚本 → 文本入历史 + 注入继续提示 → 下一轮请求含该提示；连续 4 次截断 → 第 4 次按正常终止（恢复上限 3 次）
- [ ] 截断轮文本与工具调用都为空 → 跳过 assistant 消息、只注入继续提示（R8）
- [ ] 流错误（不可重试）→ ErrorEvent + LoopComplete、termination=ERROR
- [ ] 流错误（可重试）→ RetryEvent(reason, waitMs) 后成功续跑；连续 3 次仍错 → ErrorEvent（重试上限 2 次）
- [ ] 工具结果超过 2000 字符 → 进入历史前截断并附提示

## T6 max_iterations 配置

- [ ] 不写配置时默认值为 20
- [ ] `max_iterations: 5` → 加载后生效为 5
- [ ] `max_iterations: 0` 或负数 → 配置校验报错，错误文本包含字段名
- [ ] examples 两份配置含 max_iterations 注释示例
- [ ] ConfigLoaderTest / ConfigValidatorTest 新增用例全绿

## T7 ExitPlanModeTool + ToolContext + PlanWriter

- [ ] planMode=true 的上下文下调用 ExitPlanMode → 返回成功结果（非 is_error）
- [ ] planMode=false（默认构造）下调用 → 返回 is_error，错误文本含「只能在 plan 模式下调用」
- [ ] ExitPlanMode 权限元信息为 READ
- [ ] PlanWriter 在 @TempDir 下：自动创建 `.acode/plans/` 目录、文件内容与入参一致、返回的路径指向已存在文件
- [ ] slug 清洗：含中文/特殊字符的文本生成的 slug 只含字母数字与连字符，非空且有兜底
- [ ] 旧 `ToolContext(Path)` 构造仍可用，现有工具测试全绿

## T8 Plan Mode 编排

- [ ] setPlanMode(true) 后请求的 tools 只含 READ 权限工具 + ExitPlanMode（`receivedRequests().get(i).tools()` 名称集合断言）
- [ ] setPlanMode(false) 后请求 tools 恢复为全部可用工具且不含 ExitPlanMode
- [ ] plan 模式第 1 轮请求首条 SYSTEM 消息为完整提醒（含「只读」「交付计划工具」「计划落盘路径」要点）
- [ ] plan 模式第 2 轮起 SYSTEM 消息为稀疏提醒（单行）
- [ ] 脚本让模型调用 ExitPlanMode → 本轮累积文本写入 `.acode/plans/plan-<slug>.md`、tool_result 入历史、LoopComplete、termination=PLAN_DELIVERED、planPath() 非空
- [ ] plan 模式轮数触顶 → 终止原因正确、不写计划文件

## T9 CommandRouter /plan /do

- [ ] 输入 `/plan` → route 返回 PLAN；`/do` → DO；其他输入行为不变
- [ ] HELP_TEXT 包含 /plan 与 /do 两行说明
- [ ] CommandRouterTest 新增用例全绿

## T10 Agent 综合测试

- [ ] 3 轮工具链：事件序列符合 StreamText → ToolUseEvent → ToolResultEvent → TurnComplete → LoopComplete 的相对顺序（每个事件至少出现一次且顺序不颠倒）
- [ ] receivedRequests 逐轮断言：第 i+1 轮请求含第 i 轮的 tool_result 与 assistant 文本
- [ ] 历史消息结构：assistant 消息含 text 块 + tool_use 块、tool_result 的 tool_use_id 对齐、超长结果被截断
- [ ] 取消后历史无悬空 tool_use（每个 tool_use 都有对应 tool_result）
- [ ] plan 全流程后 setPlanMode(false) → 工具列表恢复

## T11 接入主流程

- [ ] 存量 `singleStepToolLoopExecutesToolAndReturnsFinalText` / `failedToolResultPassedBackWithErrorFlag` / `hugeToolResultIsTruncatedBeforeEnteringHistory` / `plainQuestionUsesSingleRoundWithoutTools` 平移后全绿
- [ ] 原「第二轮 tool_use 只显示文本」用例重写为真实执行：3 轮脚本断言工具执行、历史完整、无「连环工具调用暂不支持」字样
- [ ] 新增取消用例：工具执行时注入 ctrlC=true → 输出含「已中断」、可继续下一次 exchange
- [ ] 新增触顶用例：maxIterations 小值 → 输出含「达到最大轮数」
- [ ] 循环期间屏幕出现：流式文本、工具卡片（进行中 → 完成/失败）、重试状态行（若有）、最终回复
- [ ] 普通单轮对话（无工具）行为与阶段二一致（单次请求、直接展示文本）

## T12 端到端验收 ⚑

- [ ] 真实 API（anthropic 与 openai 各一遍）：问「读 `pom.xml` 总结依赖，然后跑 `mvn -q compile` 并把失败的测试修好」类多步任务 → 屏幕出现 ≥2 轮工具卡片、模型自主连续执行到自然收尾、最终回复引用真实执行结果
- [ ] 真实 API：流式输出中按 Ctrl+C → 循环结束、无残影、可继续输入新问题
- [ ] 真实 API：工具执行中按 Ctrl+C → 输出「已中断」、历史无悬空工具调用（退出后 resume 该会话继续对话不报错）
- [ ] 真实 API：`/plan` 后提一个多步需求 → 模型只用读工具探索、调用 ExitPlanMode 交付 → 计划文件出现在 `.acode/plans/` 且内容为完整计划 → 界面提示「输入 /do 退出 plan 模式开始执行」→ `/do` 后可正常写文件
- [ ] 真实 API：`max_iterations: 2` 下提多步任务 → 触顶提示「达到最大轮数」、已完成步骤结果保留
- [ ] 退出后 `--resume` 恢复含多轮工具调用的会话 → 工具块显示摘要、继续对话正常（模型能引用恢复前的工具结果）
- [ ] `/help` 输出含 /plan 与 /do 说明
- [ ] 全程 `mvn test` 全绿（新增 agent 包测试均无网络依赖）
