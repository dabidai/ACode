# ACode 阶段三：Agent Loop — 任务清单

> 最后更新：2026-08-14
> 依赖关系：`(T1,T2)→T3→T5`；`T1→T4→T5`；`T6`、`T7`、`T9` 相互独立可并行；`T5+T7→T8`；`T5,T8,T9,T10→T11→T12`。
> 参考实现：MewCode Java（`F:/code/agent-doc-tech/agent-doc-tech/downloads/source/2_mewcode-java.zip` 内 `src/main/java/com/mewcode/agent/`），任务中标注的参考文件用 `unzip -p` 直接读取，不必解压。

## 约定

- 包根 `com.acode`，新增 Agent 层 `src/main/java/com/acode/agent/`（事件模型、收集器、执行器、循环本体、plan 配套），测试 `src/test/java/com/acode/agent/`
- 现有文件行号以 2026-08-14 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；测试方法名用英文驼峰
- 需要复用的现有设施：`ToolExecutor`（单工具执行语义）、`ToolCallDisplay`/`StreamPrinter`（UI 渲染）、`FakeProvider`（测试桩）、`RetryPolicy`（退避重试）、`Conversation.estimateTokens` 与 `trim`（上下文控制）

### 全局集成风险（各任务参考资料会引用，编号 R1~R8）

- **R1** `ChatListener::onComplete` 被 `FakeProvider.complete()` 以方法引用形式依赖（FakeProvider.java:29-31），还有 StreamPrinter（StreamPrinter.java:75）与多处匿名实现。扩展签名必须用「反向委托」：无参 `onComplete()` 变 default 并委托给新的带参版本。若反过来（带参委托无参），只覆写带参版的收集器将收不到完成信号，Agent 循环挂死。
- **R2** `FakeProvider.scripted()` 脚本耗尽后静默返回、不回调（FakeProvider.java:71-73）。N 轮循环测试的脚本份数必须精确匹配请求次数；Agent 层重试会额外消耗一份脚本，编排时计入。
- **R3** `StreamPrinter.onComplete()` 只重置 renderer 与 responseLines，不重置 toolCalls 与 textFinalized（StreamPrinter.java:74-78），不可跨轮复用。接入事件流后保持「每轮新建实例」，`TurnComplete` 时对旧实例调 `updateToolCalls` 收尾。
- **R4** Ctrl+C 检测从「主线程 20ms 轮询」变为「跨线程信号」：取消用 AtomicBoolean + interrupt，Agent 在 delta 回调、轮边界、每个工具执行前检查。socket 读阻塞对 interrupt 不敏感（ch03 同样存在），provider 子线程保持 daemon，靠取消标志保证状态不乱。
- **R5** 取消时历史一致性：未执行的工具调用必须补 `ToolResult.failure("已取消")` 一并入历史，否则会话恢复或继续对话时出现悬空 tool_use，Anthropic 会报 400。
- **R6** 工具列表从会话级静态（`Conversation.setTools`，Conversation.java:46-49）变为每请求动态（plan 模式过滤）。最小改动：加 `buildRequest(List<Tool>, ChatMessage systemReminder)` 重载，旧 `buildRequest()` 委托保留，存量测试零改动。
- **R7** plan 提醒以 SYSTEM role 注入：Anthropic 端聚合进 system 字段（AnthropicProvider.java:74-95）、OpenAI 端输出 role:"system" 消息（OpenAiProvider.java:101-104），两端已原生支持、无需改 provider。提醒必须「只进请求、不进历史」，且在上下文截断之前独立插入——若并入历史参与丢弃，超限时会作为最旧消息被扔掉。
- **R8** max_tokens 截断轮可能文本与工具调用都为空（纯 tool_use 场景被截断）：此时跳过 assistant 消息、只注入继续提示（空 assistant 消息对 Anthropic 非法）。

---

### T1 AgentEvent 事件模型

**目标**：sealed interface + 7 个 record，Agent 与 UI 之间的唯一契约；一次性定义队列容量常量。

**影响文件（新建）**
- `src/main/java/com/acode/agent/AgentEvent.java` — `sealed interface AgentEvent`，records：
  - `StreamText(String text)` — 模型文本增量
  - `ToolUseEvent(String toolId, String toolName, JsonNode args)` — 模型发起工具调用
  - `ToolResultEvent(String toolId, String toolName, String output, boolean isError)` — 单个工具执行完成
  - `TurnComplete(int turn)` — 一轮结束（工具结果已回填，可开始下一轮）
  - `LoopComplete(int totalTurns)` — 循环结束（正常/触顶/计划交付/错误统一以该事件收尾，具体原因经 Agent 查询）
  - `ErrorEvent(String message)` — 不可恢复错误
  - `RetryEvent(String reason, long waitMs)` — 重试预告（UI 显示等待状态）
  - 常量 `QUEUE_CAPACITY = 64`（事件队列背压上限）
- `src/test/java/com/acode/agent/AgentEventTest.java` — 各 record 构造与访问器冒烟

**依赖**：无

**参考资料**
- MewCode `src/main/java/com/mewcode/agent/AgentEvent.java`（sealed interface + records；本任务去掉它的 ThinkingText/UsageEvent/PermissionRequestEvent，thinking 不展示、无 token 统计、无权限确认）

---

### T2 Provider 层 stop_reason 透传

**目标**：`ChatListener` 增加带流结束原因的完成回调（反向委托，零破坏）；两端解析器捕获并透传；FakeProvider 支持指定结束原因。

**影响文件（修改）**
- `src/main/java/com/acode/provider/ChatListener.java` — 关键改法（见 R1）：
  - 原抽象方法 `void onComplete()` 改为 `default void onComplete() { onComplete(null); }`
  - 新增 `default void onComplete(String stopReason) { /* 默认忽略 */ }`
  - 现有实现类零改动；解析器改调带参版
- `src/main/java/com/acode/provider/anthropic/AnthropicSseParser.java` — 加字段 `stopReason`；`message_delta` 分支捕获 `delta.stop_reason`（当前 39-41 行被 default 吞掉）；`message_stop`（38 行）改调 `listener.onComplete(stopReason)`
- `src/main/java/com/acode/provider/openai/OpenAiSseParser.java` — 加字段 `lastFinishReason`；52-55 行 finish_reason 分支记录（含 length / content_filter，不止 tool_calls/stop）；`[DONE]`（30 行）改调 `onComplete(lastFinishReason)`
- `src/test/java/com/acode/provider/FakeProvider.java` — 新增 `static Action complete(String stopReason)`；现有 `complete()` 保持无参
- `src/test/java/com/acode/provider/anthropic/AnthropicSseParserTest.java` — 新增用例：message_delta 带 stop_reason=end_turn / max_tokens 被透传（新增覆写 `onComplete(String)` 的 listener，不动现有共享 listener）
- `src/test/java/com/acode/provider/openai/OpenAiSseParserTest.java` — 新增用例：finish_reason=stop / length 透传
- `src/test/java/com/acode/provider/FakeProviderTest.java` — 补 `complete(String)` 行为断言

**依赖**：无

**参考资料**
- AnthropicSseParser.java:30-48（事件分发）、OpenAiSseParser.java:27-61
- FakeProvider.java:16-35（Action 模型，加一个工厂方法即可）
- Anthropic message_delta.stop_reason 与 OpenAI finish_reason 语义：https://docs.anthropic.com/en/api/messages-streaming

---

### T3 TurnCollector 流式收集器

**目标**：一轮流式响应的收集器：累积文本 / 工具调用 / 结束原因，同时把文本增量与工具调用转发进事件队列；带取消守卫。

**影响文件（新建）**
- `src/main/java/com/acode/agent/TurnCollector.java` — `implements ChatListener`，构造 `(BlockingQueue<AgentEvent> events, AtomicBoolean cancelled)`；覆写 `onDelta`（累积 + 发 StreamText）、`onToolUse`（累积 + 发 ToolUseEvent）、`onComplete(String)`（收 stopReason）、`onError`（记录错误）；cancelled 置位后忽略一切回调（守卫模式照抄 ConversationController.java:488-520）；暴露 `text()` / `toolUses()` / `stopReason()` / `error()`
- `src/test/java/com/acode/agent/TurnCollectorTest.java` — FakeProvider 单轮脚本：文本累积、tool_use 累积、stopReason 捕获、事件入队顺序、取消后回调被忽略、错误记录

**依赖**：T1、T2

**参考资料**
- ConversationController.java:485-521（现有轮内匿名 listener 的收集与守卫逻辑，原样迁移）
- MewCode Agent 轮内收集三元组（text / toolCalls / stopReason）

---

### T4 StreamingToolExecutor 工具分区执行器

**目标**：按权限分区执行——读类并发（虚拟线程）、写类与命令类串行且保持声明顺序，全部读类先执行；结果对齐输入顺序；每个完成后发 ToolResultEvent；支持取消补位。

**影响文件（新建）**
- `src/main/java/com/acode/agent/StreamingToolExecutor.java` — 签名建议：
  ```java
  List<ToolResult> execute(List<ToolUseBlock> calls,
                           BlockingQueue<AgentEvent> events,
                           AtomicBoolean cancelled)
  ```
  - 分区：`tool.permission() == READ` 进读组，其余按声明顺序进串行组
  - 读组 >1 时用 `Executors.newVirtualThreadPerTaskExecutor()` 并发提交，全部 join；读组 ≤1 或串行组逐个执行
  - 结果 List 按输入 index 落位（回传顺序 = 声明顺序，执行顺序 = 读先并发、写后串行）
  - 单个调用复用 `ToolExecutor`（注册表查找、失败归 ToolResult；未注册/已禁用返回失败结果）
  - 每个完成发 `ToolResultEvent`；取消置位时未执行/未完成的补 `ToolResult.failure("已取消")`（见 R5）
- `src/test/java/com/acode/agent/StreamingToolExecutorTest.java` — 记录执行时序的桩工具 + CountDownLatch：混合批次执行顺序（读并发先于写串行）、结果对齐声明顺序、READ 真实并发（两桩同时运行）、取消后补位、空批次、未注册工具返回失败

**依赖**：T1

**参考资料**
- ToolExecutor.java:20-28（单工具执行语义）；BaseTool 自带超时与异常归结果（执行器无需再包超时）
- ConversationController.java:565-604（ch03 取消补位模式）；MewCode `StreamingExecutor.executeAll`（分区执行 + 事件推送）

---

### T5 Agent 循环本体

**目标**：ReAct 循环核心：五种终止条件、截断恢复（3 次上限）、重试（2 次上限）、取消信号、历史写入、终止原因查询。

**影响文件（新建）**
- `src/main/java/com/acode/agent/Agent.java` — 签名建议：
  ```java
  public Agent(ChatProvider provider, Conversation conversation,
               ToolRegistry registry, ToolContext context, int maxIterations)
  public BlockingQueue<AgentEvent> run()   // 虚拟线程跑循环，返回事件队列
  public void cancel()                     // AtomicBoolean + interrupt
  public Termination termination()         // NORMAL / MAX_ITERATIONS / CANCELED / PLAN_DELIVERED / ERROR
  ```
  循环体 `for (turn = 1; turn <= maxIterations; turn++)`：
  - 每轮：`conversation.buildRequest()`（T8 前先用静态工具列表）→ provider 在子线程跑 streamChat（保留 ch03 的 20ms 取消轮询模式，ConversationController.java:523-553）+ TurnCollector 入队
  - 终止判定：①本轮无 tool_use → 文本入 assistant 历史 → LoopComplete（NORMAL）；②轮数触顶且仍有 tool_use → 不执行工具、LoopComplete（MAX_ITERATIONS）；③取消 → 不发 LoopComplete、termination=CANCELED（历史一致性见 R5：执行中的工具补「已取消」结果入历史）；④stopReason 为 max_tokens/length → 截断恢复（见下）；⑤流错误 → 可重试（`RetryPolicy.isRetryable`，最多 2 次：发 `RetryEvent(reason, waitMs)` 后睡 `backoffMs` 再循环）否则 ErrorEvent + LoopComplete（ERROR）
  - 截断恢复：文本+toolUses 非空则入 assistant 历史（都空则跳过，见 R8）→ 执行工具回传结果 → 注入 user 消息「输出被截断，请从断点继续，不要重复已输出内容」→ 恢复计数 +1（超 3 次按正常终止处理）
  - 正常轮：assistant 消息（文本 + tool_use 块）入历史 → 分区执行（T4）→ tool_result 按声明顺序入历史 → 发 TurnComplete
  - 工具结果入历史前截断（迁移 ConversationController.truncateForHistory，384-390 行，2000 字符上限，迁到 agent 包或作为 Agent 静态方法）
- `src/test/java/com/acode/agent/AgentTest.java` — FakeProvider 脚本：单轮无工具、2 轮工具闭环、3 轮链、触顶（maxIterations=2）、取消（流中 / 工具中）、max_tokens 恢复（`complete("max_tokens")` 脚本 + 连续超限断言终止）、流错误 ErrorEvent、可重试错误 RetryEvent 后成功。注意脚本份数精确匹配请求次数（R2）

**依赖**：T2、T3、T4

**参考资料**
- MewCode `Agent.java`：`for(iteration=1;;iteration++)` 结构、max_tokens 恢复（去掉它的 token 升级逻辑，本设计明确不做）、终止分支（ExitPlanMode 分支留空位，T8 实现）
- ConversationController.java:412-604（被替代的两轮闭环；中断/守卫/重绘模式原样迁移）；RetryPolicy.java:17-26（isRetryable / backoffMs）

---

### T6 max_iterations 配置

**目标**：YAML 增加 max_iterations（默认 20），加载/校验/示例三处同步。

**影响文件（修改）**
- `src/main/java/com/acode/config/AppConfig.java` — `Integer maxIterations` + getter/setter
- `src/main/java/com/acode/config/ConfigLoader.java` — KNOWN_KEYS（23-24 行）加 `max_iterations`；apply 加分支，正整数校验风格同 max_context_tokens（97-103 行）
- `src/main/java/com/acode/config/ConfigValidator.java` — 默认 20、非正报错，风格同 40-44 行
- `examples/config.yaml`、`examples/config-project.yaml` — 加注释示例
- `src/test/java/com/acode/config/ConfigLoaderTest.java`、`ConfigValidatorTest.java` — 默认值 / 合法值 / 非法值用例

**依赖**：无（可与 T5 并行；Agent 构造先接受 int 参数，controller 在 T11 从 AppConfig 传入）

**参考资料**
- ConfigLoader.java:97-103、ConfigValidator.java:40-44

---

### T7 ExitPlanModeTool + ToolContext 扩展 + PlanWriter

**目标**：plan 模式交付工具与计划落盘能力；ToolContext 增加 plan 模式标记。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/tool/ToolContext.java`（改）— 加 `boolean planMode` 字段、重载构造 `ToolContext(Path, boolean)`、accessor；旧构造默认 false（现有工具零改动）
- `src/main/java/com/acode/agent/ExitPlanModeTool.java`（新）— `implements Tool`，`Permission.READ`；execute：`!context.planMode()` → `ToolResult.failure("只能在 plan 模式下调用")`；成功返回「计划将在本轮结束后交付，请勿再调用其他工具」的结果
- `src/main/java/com/acode/agent/PlanWriter.java`（新）— `Path savePlan(Path workingDir, String content)`：slug 由文本生成（清洗非字母数字、截断、兜底时间戳）、`Files.createDirectories` 建 `.acode/plans/`、写 `plan-<slug>.md`、返回路径
- `src/test/java/com/acode/agent/ExitPlanModeToolTest.java`、`PlanWriterTest.java`（新）— plan 模式成功 / 非 plan 模式 is_error；@TempDir 下目录创建、slug 清洗、内容一致

**依赖**：无

**参考资料**
- ToolContext.java:10-25（注释已预留「可按需扩展注入其他资源」）
- MewCode `ExitPlanModeTool`（READ 类、execute 校验 plan 模式）；spec 决策：Agent 代写计划文件，规划期不需要 WriteFile 工具

---

### T8 Plan Mode 编排

**目标**：plan 模式全链路：每请求动态工具过滤（读类 + 计划交付工具）、每轮提醒注入、计划交付调用 → 落盘 → 结束循环。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/conversation/Conversation.java`（改）— 新增重载：
  ```java
  ChatRequest buildRequest(List<Tool> tools, ChatMessage systemReminder)
  ```
  reminder 插在 trim() 结果之前、不进历史（见 R7）；旧 `buildRequest()` 委托保留
- `src/main/java/com/acode/agent/PlanModePrompt.java`（新）— `static String buildReminder(int iteration)`：iteration==1 完整提醒（只读探索 + 把计划写在回复文本里 + 完成后调用计划交付工具 + 计划将保存到工作目录计划目录）；之后每轮稀疏一行
- `src/main/java/com/acode/agent/Agent.java`（改，基于 T5）— `setPlanMode(boolean)`：
  - 每轮工具列表：plan 模式 = 可用工具中 `permission()==READ` 者 + ExitPlanMode 工具；普通模式 = 可用工具去掉 ExitPlanMode
  - plan 模式每轮 `buildRequest(tools, ChatMessage.of(Role.SYSTEM, reminder))`
  - 检测本轮 toolUses 含 ExitPlanMode → 执行该工具 → `PlanWriter.savePlan` 写本轮累积文本 → tool_result 入历史 → LoopComplete（PLAN_DELIVERED）+ `Path planPath()` getter
- `src/test/java/com/acode/agent/AgentPlanModeTest.java`（新）— FakeProvider 脚本断言：请求 tools 名称集合只含 READ+ExitPlanMode（`receivedRequests().get(i).tools()`）；ExitPlanMode 调用 → 计划文件落盘（@TempDir 工作目录）、LoopComplete、planPath 非空；iteration≥2 请求首条 SYSTEM 内容为稀疏版

**依赖**：T5、T7

**参考资料**
- MewCode `PlanModePrompt.buildReminder`（首轮完整、之后稀疏；本设计简化掉 plan 文件存在性分支）
- Conversation.java:83-91（现 buildRequest）；AnthropicProvider.java:74-95、OpenAiProvider.java:101-104（SYSTEM 两端已支持，无需改 provider）

---

### T9 CommandRouter /plan /do

**目标**：命令路由与帮助文案扩展。

**影响文件（修改）**
- `src/main/java/com/acode/ui/CommandRouter.java` — Action 枚举加 `PLAN, DO`；route() 加 `/plan`、`/do` 分支；HELP_TEXT 补两行
- `src/test/java/com/acode/ui/CommandRouterTest.java` — 两个路由用例 + help 文案断言

**依赖**：无

**参考资料**
- CommandRouter.java:9-42；注意：controller 的语句 switch（ConversationController.java:169-186）无 default，T11 接入前对 PLAN/DO 无动作属预期中间态，勿在 T9 完成后误判为功能可用

---

### T10 Agent 综合测试

**目标**：agent 层端到端假 provider 编排——N 轮循环、事件序列、历史结构、取消一致性、plan 全流程收口。

**影响文件（新建）**
- `src/test/java/com/acode/agent/AgentIntegrationTest.java` — 3 轮工具链（ReadFile → Bash → 最终文本）：
  - 事件序列断言（StreamText → ToolUseEvent → ToolResultEvent → TurnComplete → LoopComplete 的相对顺序）
  - `receivedRequests` 逐轮断言（每轮历史含前轮 tool_result、文本累积）
  - 历史消息块结构（assistant 消息文本 + tool_use 块、tool_result 对齐 id、超长结果截断生效）
  - 取消后历史无悬空 tool_use（R5）
  - plan 全流程（setPlanMode(true) → 交付 → 落盘 → setPlanMode(false) 后工具列表恢复）
- `src/test/java/com/acode/provider/FakeProvider.java`（视情况）— 仅当需要「同轮先 error 后成功」等编排时扩展 Action；现有模型已覆盖（重试消耗下一份脚本，见 R2），优先不改

**依赖**：T5、T8

**参考资料**
- ConversationControllerTest.java:38-73（现有两轮断言范式 → N 轮范式）；FakeProvider.java:104-107（receivedRequests 逐轮断言）

---

### T11 接入主流程（替代 handleExchange）

**目标**：ConversationController 从「两轮硬编码闭环」改为「订阅 AgentEvent 渲染」，存量测试迁移。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`：
  - 装配（89-104 行）：注册 ExitPlanModeTool（进注册中心但默认不进普通请求）；从 AppConfig 读 maxIterations；`conversation.setTools(...)`（101 行）可删（Agent 每轮显式传列表）
  - 删除两轮闭环：handleExchange 主体、streamRound、executeTools、RoundResult、ToolRunOutcome（412-613 行）
  - `handleExchange(input, ctrlC, repaint)` **保留签名**（测试依赖），改为：追加 user 消息与提示符 → `new Agent(...).run()` → 主线程事件消费循环（poll 20ms）：
    - StreamText → `StreamPrinter.onDelta`
    - ToolUseEvent → `StreamPrinter.onToolUse`（转成 ToolUseBlock）
    - ToolResultEvent → 收集进本轮结果列表
    - TurnComplete → 当前 printer `updateToolCalls(results)` 收尾后**新建实例**（R3）
    - RetryEvent → 输出状态行（「重试中：<原因>」）
    - ErrorEvent → 输出错误行
    - LoopComplete → 按 `agent.termination()` 补提示：MAX_ITERATIONS 提示「达到最大轮数」；PLAN_DELIVERED 读 planPath 展示计划 + 提示「输入 /do 退出 plan 模式开始执行」；CANCELED 提示「已中断」
    - ctrlC 命中 → `agent.cancel()` + 输出「已中断」
  - mainLoop（169-186 行）加 PLAN/DO 分支：planMode 状态放 controller 字段（Agent 每轮 exchange 新建，状态不能放 Agent），切换时输出进入/退出提示
- `src/test/java/com/acode/ConversationControllerTest.java`：
  - `singleStepToolLoopExecutesToolAndReturnsFinalText`（38-73）、`failedToolResultPassedBackWithErrorFlag`（75-98）、`hugeToolResultIsTruncatedBeforeEnteringHistory`（129-149）、`plainQuestionUsesSingleRoundWithoutTools`（151-163）断言基本平移（handleExchange 签名、receivedRequests、输出、历史结构不变）
  - `secondRoundToolUseShowsTextAndHintOnly`（100-127）**重写**：第二轮 tool_use 现在被真正执行——改 3 轮脚本，断言工具真实执行、历史含第二轮 tool_use + tool_result + 最终文本、不再有「连环工具调用暂不支持」
  - 新增：Ctrl+C 取消用例（注入 ctrlC 在工具执行时返回 true）、maxIterations 触顶用例（小值配置）
- `src/main/java/com/acode/ui/StreamPrinter.java` — 预期零改动（R3）

**依赖**：T5、T8、T9、T10

**参考资料**
- ConversationController.java:392-604（被替换代码）；StreamPrinter.java:44-72（渲染入口与方法语义）
- MewCode UI 订阅事件队列渲染模式（TUI 消费 BlockingQueue）

---

### T12 端到端验证

**目标**：全量回归 + 真实 provider 手动验收清单。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md` 追加阶段三小节 — 手测步骤：双后端（anthropic / openai）各过一遍：多轮工具链（读文件 → 改文件 → 跑命令 → 读回验证）自动闭环到自然收尾；流式中 Ctrl+C、工具执行中 Ctrl+C；/plan → 只读探索 + 交付计划 → 落盘 → /do 后可写；max_iterations 调小（如 2）验证触顶；退出后 resume 含工具轮次的会话继续对话；/help 含 /plan /do 文案
- 修 bug 产生的影响文件视情况

**依赖**：T11

**参考资料**
- 手测按 checklist.md 逐项打勾；联网问题用临时错误 base_url 模拟（沿用 ch02 做法）
