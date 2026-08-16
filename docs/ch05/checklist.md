# ACode 阶段五：工具权限确认（破坏性工具征求同意）— 验收清单

> 最后更新：2026-08-16
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 默认值说明：拒绝文案「用户拒绝执行「<工具名>」」、参数预览截断 160 字符、确认提示格式「要执行 <工具> <参数预览> ？[y/n] 」、批准状态行「（已批准执行 <工具>）」、拒绝状态行「（已拒绝执行 <工具>）」、取消/EOF 等价拒绝、非 y/n 输入重问。

## T1 Confirmation + ConfirmationGate

- [ ] `mvn compile` 通过，`com.acode.agent` 包编译无警告
- [ ] `Confirmation.await` 在有答复时返回该答复（true/false 各验证一次）
- [ ] `Confirmation.await` 在 `cancelled` 已置位时立即返回 false（不阻塞）
- [ ] `Confirmation.answer` 幂等：连续两次 answer 只取第一次
- [ ] `ConfirmationGate.ALWAYS_ALLOW` 恒返回 true

## T2 ConfirmationRequestEvent

- [ ] record 五个访问器齐全：toolId / toolName / argsSummary / response（Confirmation 类型）
- [ ] AgentEventTest 冒烟通过

## T3 StreamingToolExecutor 门槛

- [ ] 旧构造 `StreamingToolExecutor(registry, context)` 仍可用（存量测试零改动全绿）
- [ ] WRITE 工具 + gate 批准 → 工具正常执行、结果成功
- [ ] WRITE 工具 + gate 拒绝 → 结果 failure、content 含「用户拒绝执行」、工具未执行、ToolResultEvent isError=true
- [ ] READ 工具 → gate 未被调用（调用计数 = 0）、正常执行
- [ ] cancelled=true 且 gate 拒绝 → 返回失败结果，不挂死
- [ ] 未注册工具 → 走原「未注册」失败路径（不被门槛干扰）

## T4 Agent 传递 gate

- [ ] Agent 默认 gate 为 ALWAYS_ALLOW（未 set 时 WriteFile 照常执行，存量 AgentTest/AgentIntegrationTest 全绿）
- [ ] `setConfirmationGate(null)` 不覆盖为 null（保持默认）

## T5 EventConfirmationGate

- [ ] 假队列 + 应答线程：事件入队字段正确（toolName=调用名、argsSummary=截断后预览）
- [ ] 应答 true → confirm 返回 true；应答 false → 返回 false
- [ ] cancelled 置位 → confirm 返回 false（不发答案也不挂死）
- [ ] `summarize`：>160 字符截断补「…」；空/null 返回空串

## T6 UI 确认提示

- [ ] `InputPane.readLine(prompt)` 存在且 `readLine()` 行为不变
- [ ] ConfirmationPrompt：输入 `y` → 返回 true、状态行「（已批准执行 …）」
- [ ] 输入 `n` → 返回 false、状态行「（已拒绝执行 …）」
- [ ] 输入空白/非 y/n → 重问（连续输入两次非 y/n 后给 y → 返回 true）
- [ ] 读行抛 UserInterruptException（模拟 Ctrl+C）→ 返回 false 且写拒绝状态行
- [ ] 读行返回 null（EOF）→ 返回 false

## T7 接入主流程

- [ ] `handleExchange` 事件循环能消费 ConfirmationRequestEvent：注入 prompt 桩后，确认被应答、不挂死
- [ ] 注入拒绝桩 → 模型收到含「用户拒绝执行」的失败 tool_result、历史无悬空 tool_use（每个 tool_use 都有对应 tool_result）
- [ ] 注入批准桩 → 工具真实执行、结果入历史
- [ ] 存量 ConversationControllerTest 全绿（默认放行不破坏既有用例）

## T8 单测补强

- [ ] `writeToolRunsWhenGateApproves` 全绿
- [ ] `writeToolNotExecutedAndFailureWhenGateDenies` 全绿（含内容断言「拒绝」、isError）
- [ ] `readToolSkipsConfirmationGate` 全绿（gate 调用 0 次）
- [ ] `gateDeniedUnderCancellationReturnsFailureNotHang` 全绿
- [ ] 全程 `mvn test` 全绿（新增用例无网络依赖）

## T9 端到端验收 ⚑

- [ ] 真实 API：让模型「向 hello.cpp 写入九九乘法表」→ 屏幕出现「要执行 WriteFile … ？[y/n] 」→ 输入 `n` → 模型收到「用户拒绝执行」失败结果 → 模型改变策略（如改为用 EditFile 或先询问）继续对话
- [ ] 真实 API：同样提示输入 `y` → 文件真实写入、工具卡片转为成功终态
- [ ] 真实 API：让模型先读文件再改文件 → ReadFile/Glob/Grep 全程不弹确认，仅 WriteFile/EditFile/Bash 弹
- [ ] 真实 API：让模型执行 `Bash` 命令（如 `mvn -q compile`）→ 弹确认；`y` 放行执行、`n` 拒绝返回失败
- [ ] 真实 API：`/plan` 规划模式下 → 模型只读探索不弹确认（无 WRITE/EXEC 工具）
- [ ] 真实 API：确认提示期间按 Ctrl+C → 等价拒绝、界面无残影、可继续输入新问题
- [ ] 确认后拒绝/批准的轮次 `--resume` 恢复会话继续对话正常（无悬空工具调用）
- [ ] 全程 `mvn test` 全绿
