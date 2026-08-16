# ACode 阶段五：工具权限确认（破坏性工具征求同意）— 任务清单

> 最后更新：2026-08-16
> 依赖关系：`T1→T2→T3→T4`；`T2→T6`；`T1,T2→T5`；`T4,T5,T6→T7→T8→T9`。
> 参考实现：MewCode Java `src/main/java/com/mewcode/permission/`（PermissionMode.decide → ALLOW/DENY/ASK；PermissionResponse）与 `src/main/java/com/mewcode/tui/dialog/PlanApprovalDialog.java`（弹窗思路，本项目走行输入）。

## 约定

- 包根 `com.acode`；握手/事件类型放 `src/main/java/com/acode/agent/`，提示渲染放 `src/main/java/com/acode/ui/`，测试 `src/test/java/com/acode/`
- 现有文件行号以 2026-08-16 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；测试方法名用英文驼峰
- 需要复用的现有设施：`AgentEvent.putSafe`（阻塞入队）、`ToolResult.failure`、`LiveRegionRenderer`/`appendCommitted`（UI 渲染）、`InputPane`（JLine 读行）、`FakeProvider`（测试桩）、`RecordingTool`（执行时序桩，StreamingToolExecutorTest 内）
- 本轮不改 `Permission` 枚举、不改工具实现、不引入配置

### 全局集成风险（各任务参考资料会引用，编号 R1~R4）

- **R1** 握手死锁：agent 线程在 `await` 阻塞、在 `putSafe` 发事件时阻塞；主线程在事件循环里持续 `poll` 消费并应答，二者不同步则握手挂死。关键约束：`Confirmation.await` 必须轮询检查 `cancelled`（不能裸 `take()` 永不醒），`EventConfirmationGate.confirm` 发事件必须先于 `await`。
- **R2** JLine 线程安全：LineReader 只能在主线程用。agent 线程（虚拟线程）绝不碰终端；确认读行只发生在 `handleExchange` 事件循环内（此时 `mainLoop` 的 `readLine` 已返回，主线程空闲）。Ctrl+C 在 readLine 内抛 `UserInterruptException`，按拒绝处理并继续循环（与 mainLoop 的退出语义不同：mainLoop 是退出，这里只拒绝）。
- **R3** 渲染交错：确认提示与流式渲染共存。当前轮文本已在 `onToolUse` 定稿（`StreamPrinter.onToolUse` → `textFinalized=true`），确认事件到达时无未完成尾行；应答结果按现有状态行模式 `output.appendLine` + `live.appendCommitted` 双写，不复用 `StreamPrinter`。
- **R4** 存量测试破坏：`StreamingToolExecutor` 构造签名变化 → 用「旧签名重载委托默认放行」保持零破坏；`Agent` 默认 `ALWAYS_ALLOW`；`ConversationController` 的确认提示处理器默认走真实终端、测试注入桩。

---

### T1 Confirmation 握手原语 + ConfirmationGate 接口

**目标**：确认的答复通道与门槛契约，以及「默认放行」实现。

**影响文件（新建）**
- `src/main/java/com/acode/agent/Confirmation.java` — 包装 `BlockingQueue<Boolean>`（容量 1）：
  - `void answer(boolean approved)` — `offer` 发布答复（第二次忽略，幂等）
  - `boolean await(AtomicBoolean cancelled)` — 50ms 轮询取答复；`cancelled` 置位立即返回 false；`InterruptedException` 恢复中断位并返回 false
- `src/main/java/com/acode/agent/ConfirmationGate.java` — `@FunctionalInterface`，方法 `boolean confirm(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled)`；常量 `ALWAYS_ALLOW = (c, e, cc) -> true`
- `src/test/java/com/acode/agent/ConfirmationTest.java` — 三用例（见下）

**依赖**：无

**参考资料**
- MewCode `PermissionResponse`（ALLOW/DENY 二元答复；本项目无需 ALLOW_ALWAYS）

---

### T2 ConfirmationRequestEvent 事件类型

**目标**：确认请求作为事件进队列，答复通道随事件传给 UI。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/agent/AgentEvent.java` — 嵌套 record：`record ConfirmationRequestEvent(String toolId, String toolName, String argsSummary, Confirmation response) implements AgentEvent`
- `src/test/java/com/acode/agent/AgentEventTest.java` — 构造/访问器冒烟（toolName、argsSummary、response 非空、确认接口类型）

**依赖**：T1

**参考资料**
- AgentEvent.java:29-47（现有 record 风格；`putSafe` 已在 20-26 行）

---

### T3 StreamingToolExecutor 门槛

**目标**：非 READ 工具执行前过确认门槛；拒绝 → 失败结果照常落位与发事件；默认放行保持存量零破坏。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/agent/StreamingToolExecutor.java`：
  - 加字段 `private final ConfirmationGate confirmationGate;`
  - 旧构造 `StreamingToolExecutor(ToolRegistry, ToolContext)` 委托 `this(registry, context, ConfirmationGate.ALWAYS_ALLOW)`；新增 `StreamingToolExecutor(ToolRegistry, ToolContext, ConfirmationGate)`
  - `runCall`（101-113 行）：`registry.available(call.name())` 非空且 `permission() != READ` 且 `!confirmationGate.confirm(call, events, cancelled)` → `results[index] = ToolResult.failure("用户拒绝执行「" + call.name() + "」")` + `AgentEvent.putSafe(events, new ToolResultEvent(call.id(), call.name(), content, true))` + return；未注册工具（tool==null）走原失败路径
- `src/test/java/com/acode/agent/StreamingToolExecutorTest.java` — 既有用例构造改用默认/显式 gate；新增（见 T8 汇总，T3 先落基础两例：批准执行、拒绝返回失败且工具未进入）

**依赖**：T1、T2

**参考资料**
- StreamingToolExecutor.java:31-35（构造）、101-113（runCall）、51-60（分区：READ 并行、非 READ 串行）
- ToolExecutor.java:20-28（tool==null 时返回「未注册」失败，门槛需放行让原逻辑处理）

---

### T4 Agent 传递 gate

**目标**：Agent 持有确认门槛并传给执行器；默认放行。

**影响文件（修改）**
- `src/main/java/com/acode/agent/Agent.java` — 字段 `private ConfirmationGate confirmationGate = ConfirmationGate.ALWAYS_ALLOW;`；`public void setConfirmationGate(ConfirmationGate gate)`（null 忽略）；`executeTools`（338-351 行）构造 executor 改 `new StreamingToolExecutor(registry, planMode ? planContext : context, confirmationGate)`

**依赖**：T3

**参考资料**
- Agent.java:55-76（字段区）、338-351（executeTools）

---

### T5 EventConfirmationGate 生产实现

**目标**：事件握手的具体实现：构造 Confirmation → 发事件 → 阻塞等待；参数预览截断。

**影响文件（新建 + 测试）**
- `src/main/java/com/acode/agent/EventConfirmationGate.java` — `implements ConfirmationGate`：
  - `confirm(...)`：`Confirmation conf = new Confirmation();` → `AgentEvent.putSafe(events, new ConfirmationRequestEvent(call.id(), call.name(), summarize(call.input()), conf))` → `return conf.await(cancelled);`
  - `static String summarize(JsonNode input)` — `input.toString()` 超 160 字符截断补「…」（空/null → 空串）
- `src/test/java/com/acode/agent/EventConfirmationGateTest.java` — 假 `BlockingQueue<AgentEvent>` + 应答线程：事件入队字段正确（toolName/argsSummary）、批准返回 true、拒绝返回 false、cancelled 返回 false；summarize 截断行为

**依赖**：T1、T2

**参考资料**
- AgentEvent.putSafe（20-26 行）；MewCode 弹窗应答的阻塞等待思路

---

### T6 UI 确认提示

**目标**：真实终端弹 y/n 提示：渲染、读行、重问、中断/EOF 拒绝、结果双写。

**影响文件（修改 + 新建）**
- `src/main/java/com/acode/ui/InputPane.java` — 新增 `public String readLine(String prompt)`（委托 `reader.readLine(prompt)`）；`readLine()` 改为调用它
- `src/main/java/com/acode/ui/ConfirmationPrompt.java`（新）— 构造 `(OutputPane output, LiveRegionRenderer live, Writer writer, InputPane input)`：
  - `boolean ask(ConfirmationRequestEvent event)` — `while(true)` 内 `input.readLine("要执行 " + toolName + " " + argsSummary + " ？[y/n] ")`；`y` → `status("（已批准执行 " + toolName + "）")` return true；`n` → `status("（已拒绝执行 " + toolName + "）")` return false；`null`/`UserInterruptException`/`EndOfFileException` → 拒绝；其他输入继续循环
  - `status(line)` — `output.appendLine(line)` + `live.appendCommitted(writer, line)`
- `src/test/java/com/acode/ui/ConfirmationPromptTest.java` — 假 OutputPane/LiveRegionRenderer/Writer + 注入的「读行来源」桩（用函数接口而非真实 InputPane）：y→true、n→false、空串重问（连续两次输入）、中断→false

**依赖**：T2

**参考资料**
- InputPane.java:49-51（现 readLine）；ConversationController 状态行模式（「（已中断）」535-536 行、「（重试中…）」566-567 行）
- 注意 R3：不触碰 StreamPrinter

---

### T7 接入主流程 ConversationController

**目标**：handleExchange 装配生产 gate 与事件分支；存量测试全绿。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`：
  - 字段：`private InputPane inputPane;`（mainLoop 创建后赋值，168-170 行附近）；`private Function<ConfirmationRequestEvent, Boolean> confirmationPrompt;`（测试注入）
  - `mainLoop`：`InputPane input = new InputPane(...)` 后加 `this.inputPane = input;`
  - `handleExchange`：创建 Agent 后（524-526 行）`agent.setConfirmationGate(new EventConfirmationGate());`
  - 事件循环（531 行 while 内）加分支 `else if (event instanceof ConfirmationRequestEvent confirm)` → `boolean approved = confirmationPrompt != null ? confirmationPrompt.apply(confirm) : new ConfirmationPrompt(output, live, writer, inputPane).ask(confirm); confirm.response().answer(approved);`
  - `inputPane == null` 兜底：ConfirmationPrompt.ask 内判空返回 false（防御，真实流程 mainLoop 必先建）
- `src/test/java/com/acode/ConversationControllerTest.java` — 注入 `setConfirmationPrompt` 桩：拒绝 → 模型收到含「拒绝」的失败结果、历史无悬空 tool_use；批准 → 工具真实执行

**依赖**：T4、T5、T6

**参考资料**
- ConversationController.java:168-170（mainLoop InputPane 创建）、516-572（handleExchange 事件循环）、524-526（Agent 创建）
- 复用 `EventConfirmationGate`（T5）；提示复用 `ConfirmationPrompt`（T6）

---

### T8 单测补强

**目标**：四路径收口：批准 / 拒绝 / READ 不触发 / 取消等价拒绝。

**影响文件（修改 + 新建）**
- `src/test/java/com/acode/agent/StreamingToolExecutorTest.java` — 新增：
  - `writeToolRunsWhenGateApproves` — gate 返回 true，WRITE 桩工具 entered 计数 =1，结果成功
  - `writeToolNotExecutedAndFailureWhenGateDenies` — gate 返回 false，结果 failure 且 content 含「拒绝」、工具未进入、ToolResultEvent isError=true
  - `readToolSkipsConfirmationGate` — gate 记录调用次数，READ 桩工具 → gate 调用 0 次、正常执行
  - `gateDeniedUnderCancellationReturnsFailureNotHang` — cancelled=true 且 gate 返回 false → 返回失败结果而非挂死
- `src/test/java/com/acode/ConversationControllerTest.java` — 拒绝/批准各一条端到端编排（FakeProvider 脚本：tool_use → 文本；注入 prompt 桩）

**依赖**：T3、T7

**参考资料**
- StreamingToolExecutorTest 既有 `RecordingTool`/`call(...)`/`queue()` 桩（34-37 行）；FakeProvider 脚本编排（T8 参考 ch04 ConversationControllerTest 范式）

---

### T9 端到端验证

**目标**：全量回归 + 真实终端手动验收。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md`（若不存在则创建）追加阶段五小节 — 手测步骤：真实 provider 下让模型写文件 → 弹 y/n；输入 n → 模型收到「用户拒绝执行」并调整方案重试；输入 y → 文件真实写入；ReadFile/Glob/Grep 不弹确认；规划模式（/plan）不弹确认；确认期间 Ctrl+C → 等价拒绝、界面无残影、可继续对话
- 修 bug 产生的影响文件视情况

**依赖**：T7

**参考资料**
- 手测按 checklist.md 逐项打勾；联网问题用临时错误 base_url 模拟（沿用 ch02 做法）
