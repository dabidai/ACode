# ACode 阶段七：上下文管理 — 任务清单

> 最后更新：2026-09-03
> 依赖关系：`T1→T2/T4`；`T2→T3`；`T4→T5`；`T5→T6/T7/T8`；`T3/T5→T8 装配`；`T8→T9`；`T9→T10`。T3/T6/T7 都改 `Agent`，注意按顺序避免行号错位。
> 源码依据：ACode 现有实现（`Conversation` 最旧裁剪 + `Agent` 工具结果 2000 字符截断 + Anthropic cache_control）。数值口径见 `checklist.md` 顶部「默认值说明」。

## 约定

- 新增包根 `com.acode.context`：主代码 `src/main/java/com/acode/context/`，测试 `src/test/java/com/acode/context/`（既有测试文件路径不动）
- 现有文件行号以 2026-09-03 的 HEAD 为准，改动时先 Read 确认
- 新测试方法名一律英文驼峰（惯例）；随任务写对应测试，可用 `mvn -Dtest=指定类 test` 单跑新增；**全套 `mvn test` 在收尾任务 T10 统一跑**
- 构建环境：`JAVA_HOME=D:\java\jdk21`（默认 jdk17 编不了 release 21）；运行时用 `target/acode.jar` 注意先重打包
- 需要复用的现有设施：provider 流式 `ChatProvider.streamChat(ChatRequest, ChatListener)`；`ChatListener` 收集范式见 `agent/TurnCollector.java`；假 provider 见 `src/test/java/com/acode/provider/FakeProvider.java`（按需扩展：捕获每次 `ChatRequest`、可注入"上下文超长"错误与摘要回复）；临时目录用 JUnit `@TempDir`；并发用 `util/VirtualThreads.POOL`
- 与"多任务统一收尾"约定一致：实现过程中可跑新增用例，但**回归全量只跑一次**，在 T10 完成后进行

---

### T1 预算常量与估算、历史重建、clear 钩子

**目标**：上下文策略常量就位；Conversation 能估算"将携带进请求"的整体预算；能原子重建历史；清空时能通知外部重置状态。

**影响文件（新建 + 修改）**
- `context/ContextPolicy.java`（新）— 上下文管理的常量与按窗口计算触发点：单结果保留上限、同批聚合上限、预览长度、摘要输出预留、自动压缩安全余量、熔断阈值、保留近期轮次预算等；`maxContextTokens` 由外部注入（来自 config）
- `conversation/Conversation.java`（改）—
  - 新增整体估算：系统提示 + 环境快照 + 历史（沿用现有 `estimateTokens` 分块口径）加总；暴露 `maxContextTokens`
  - 新增 `replaceAll(List<ChatMessage>)`：加锁原子替换整段历史（重建/压缩用），与 `nextEpoch`/带代次写入同锁
  - `clear()`（`L94-97`）改为同时触发已注册的 Runnable 钩子（`addClearHook`），供状态重置（T9 用）
- `ConversationContextTest.java`（新）— 估算含系统/环境；`replaceAll` 原子性；clear 钩子被调用

**依赖**：无

**参考资料**
- Conversation 现有结构：`messages` L24、system `L34`、environment `L37`、构造 L39-44、`estimateTokens` L103-119、私有 `estimateTotal` L158、`trim`/`removeTurnUnit` L147-175、`sanitize` L181-220、`clear` L94-97、`nextEpoch` L71-73
- 窗口默认：`config/ConfigValidator.java:12`（DEFAULT 128K）；`AppConfig.getMaxContextTokens` `config/AppConfig.java:54`
- token 估算口径（字符÷4）已存在 `Conversation.java:103-106`

---

### T2 大结果落盘核心：SpillStore + 冻结状态 + ToolResultBudget

**目标**：把"超长工具结果"从"截断丢失"改为"全文落盘 + 定长预览含路径"，且决策只做一次、冻结整会话。

**影响文件（新建）**
- `context/SpillStore.java` — 幂等落盘：写入 `<工作目录>/.acode/tool-results/<toolUseId>.txt`，文件已存在即跳过并返回既有（写 `wx` 语义）；自动建目录；返回路径字符串
- `context/ContentReplacementState.java` — 两个记账结构：已决定集合（seen）与"已替换 id → 预览串"映射；提供决策记录、按 id 重放预览、`reset()`
- `context/ToolResultBudget.java` — 对**一批新产生的工具结果**（带 id/正文/是否错误）返回应入历史的内容列表：单条超过保留上限 → 落盘并用预览替换；同批合计（字符）超过聚合上限 → 从未落盘的最大者开始补落盘直至回落；同 id 重复传入原样重放已存预览、不重写文件不重新决策；≤ 上限的全文保留
- `SpillStoreTest.java` / `ContentReplacementStateTest.java` / `ToolResultBudgetTest.java`（新）— 阈值边界（恰好等于上限不落盘、超过才落盘）；同批聚合从大到小替换直到回落；同 id 二次调用返回同一预览且磁盘文件不重写；预览文本含路径与省略标记；错误结果同规则；`reset` 清空

**依赖**：T1（取常量）

**参考资料**
- 现行粗暴截断点与常量：`agent/Agent.java:47`（MAX_TOOL_RESULT_HISTORY_CHARS）、`executeTools` L363-377、`truncateForHistory` L424-430
- 消息/块模型：`provider/ToolResultBlock.java`（record：toolUseId/content/isError）、`provider/ToolUseBlock.java`、工具执行产物 `tool/ToolResult.java`
- 预览等文案与数值：见 `checklist.md` 默认值说明；预览生成规则（前 N 字符 + 路径 + 省略标记）作为常量放 `ToolResultBudget`

---

### T3 第一层接入 Agent（替换现行 2000 字符截断）

**目标**：Agent 每批工具结果入历史改走 `ToolResultBudget`，废掉"一律截断"；装配一次的状态跨会话存活。

**影响文件（修改）**
- `agent/Agent.java`（改）— 构造/装配注入 `ContextManager`（可空：存量构造/测试仍走旧路径，但新流程必有）；`executeTools`（`L362-377`）改调 budget：把 `toolUses`（id）与 `results`（正文/isError）对齐后交 `ToolResultBudget` 产出 `ToolResultBlock` 列表；删除对 `truncateForHistory`/`MAX_TOOL_RESULT_HISTORY_CHARS` 的使用（该方法可移除；确认无其他引用）
- `ExchangeRunner.java`（改）— `run()` `L100-105` 构造 `Agent` 时传入 contextManager
- `context/AgentToolResultBudgetTest.java`（新）— FakeProvider + `@TempDir` 集成：超上限结果 → 落盘文件存在且内容=原文、下一轮请求消息含预览与路径、不含旧截断后缀；≤ 上限 → 历史全文保留；同批聚合 → 大者被替换、小者保留

**依赖**：T2

**参考资料**
- `agent/Agent.java`：`executeTools` L362-377、`truncateForHistory` L424-430、`run()` L116、`buildPlanAwareRequest` L389、工作目录 `context.workingDirectory()`（见 L110 planContext 先例）
- `ExchangeRunner.java`：构造 Agent 处 L100-105
- `Conversation.buildRequest` L126-145（历史=入历史消息，tools 独立传递）

---

### T4 摘要指令与重建机制（SummaryPrompt + 分区 + Conversation 重建）

**目标**：摘要"怎么写"与"重建后长什么样"就绪，尚不含模型调用。

**影响文件（新建 + 修改）**
- `context/SummaryPrompt.java`（新）— 生成摘要系统指令：9 段结构（意图/技术点/文件与关键代码/错误修复/解决过程/所有用户消息尽量原文/待办/当前工作最详细/下一步）、两阶段产出（草稿丢弃、只留正文）、禁止工具调用只输出纯文本、用户消息原文优先
- `context/CompactionPlanner.java`（新）— 给定完整历史与预算，从尾部按"完整轮次单元"回溯选出保留区、其余为摘要区；保证 assistant tool_use 与其后 tool_result 成对不被拆、重建后首条用户角色边界合法；预算装不下最近一个完整轮次时保留区为空
- `conversation/Conversation.java`（改）— 暴露 `rebuildTo(List<ChatMessage>)`（即 T1 的 `replaceAll` 语义，压缩专用入口），供摘要成功后原子替换
- `SummaryPromptTest.java` / `CompactionPlannerTest.java`（新）— 9 段与原文优先、两阶段、禁工具；分区：预算内保留最近完整轮次、预算不足保留空、轮次不拆散、边界合法；重建后经既有 `sanitize`（`Conversation.java:181`）无孤儿工具块

**依赖**：T1

**参考资料**
- 轮次单元先例：`Conversation.removeTurnUnit` L167-175（tool_use + 紧邻 tool_result 成对删）——分区时从尾部对称处理
- 既有请求清洗：`Conversation.sanitize` L181-220；角色相邻约束见 provider 序列化（`AnthropicProvider.buildBody` 把 SYSTEM 抽出合并、user/assistant 依序，`anthropic/AnthropicProvider.java:87-137`）

---

### T5 摘要执行器：CompactExecutor + "上下文超长"判定

**目标**：把模型调用、两阶段解析、熔断、降级、重建串成一个可复用执行器；提供自动触发判断。

**影响文件（新建）**
- `context/ContextTooLong.java`（新）— 把 provider 异常判为"上下文超长"：匹配 Anthropic/OpenAI 错误文案中的典型短语（容错、不抛错返回 false）
- `context/CompactExecutor.java`（新）— 构造取 provider/conversation/policy/planner；方法：
  - `needsAutoCompact()`：整体估算 ≥ 触发点（窗口 − 摘要输出预留 − 安全余量）
  - `run(manual)`：manual=true 时无条件压缩（历史过短无可压缩则返回"无需"）；摘要请求 = 系统摘要指令 + 摘要区历史、**不携带工具**；解析只取正式正文（草稿丢弃，无标签则整段兜底）；成功 → `rebuildTo`（摘要消息含边界提醒 + 保留区）；摘要请求自身超长 → 按分组丢最旧重试，次数与降级比例见 checklist；连续失败计数到阈值即熔断（此后 `needsAutoCompact` 恒 false，直到手动成功或会话重置）
  - 返回结果带压缩前后估算（供 UI/命令展示）
- `context/CompactExecutorTest.java`（新）— FakeProvider 分别返回带标签/不带标签文本；超长一次后成功（丢最旧）；连续失败达阈值熔断、后续不再自动触发；手动成功清零熔断；失败时历史原样不动

**依赖**：T4

**参考资料**
- 模型通道：`ChatProvider.streamChat`（同步阻塞）；`ChatListener` 实现范式 `agent/TurnCollector.java:19-95`；请求构造 `ChatRequest.builder` `provider/ChatRequest.java:56-106`（tools 不设置即不带 → 模型不可调工具，见 `AnthropicProvider.buildBody` L128-132 只在非空才写 tools）
- 错误与重试现状：`ProviderHttpClient.classify` L92-101（400 → `InvalidRequestException`）；`RetryPolicy.isRetryable` `provider/RetryPolicy.java:17-21`
- 估算：T1 的整体估算；数值见 checklist

---

### T6 自动压缩接入 Agent 主循环（含熔断落地与 UI 反馈）

**目标**：每轮请求前自动判断并压缩，长会话不再撞墙；事件层给终端可见提示。

**影响文件（修改）**
- `agent/Agent.java`（改）— 注入 `ContextManager`（含 CompactExecutor）；`runTurn` 构建请求前调用执行器自动触发（manual=false）；若触发且失败则照常继续（下一轮按熔断不再尝试）
- `agent/AgentEvent.java`（改）— 按现有 record 风格新增"提示类"事件（携带文本，如"正在自动压缩…"）
- `ExchangeRunner.java`（改）— 事件分发加该事件分支 → 输出行（参考现 StreamText/RetryEvent 渲染 L136-160）
- `context/AutoCompactAgentTest.java`（新）— 预填逼近触发点的历史 + FakeProvider（先回摘要）→ 断言：压缩后该轮请求消息以摘要消息开头且含边界提醒；熔断：摘要连续失败达阈值 → 不再自动触发、后续请求用原历史、Notice 事件出现

**依赖**：T5、T3（Agent 注入模式）

**参考资料**
- `Agent.loop` L167-212、`runTurn` 起始 L214-220、`buildPlanAwareRequest` L389-395、事件队列 `run()` L116-131
- `AgentEvent` 各 record 声明处（`agent/AgentEvent.java`）；`ExchangeRunner` 分发 switch L136-166

---

### T7 紧急压缩：撞墙自救

**目标**：正常请求被"上下文超长"拒绝时，就地压缩一次并重试原请求一次。

**影响文件（修改）**
- `agent/Agent.java`（改）— `runTurn` 错误处理路径（现 L231-246：可重试分支、否则 ErrorEvent + error 终止）插入：不可重试且判定为"上下文超长"且本处尚未强制压缩 → 执行一次压缩 → 用新历史重试当前请求一次（走回循环内同一 turn）；仍超长则走既有错误终止，不无限重试
- `context/ForceCompactAgentTest.java`（新）— FakeProvider 首次抛"上下文超长"错误、其后成功 → 第二次请求历史含摘要、恰好重试一次；持续超长 → 只压缩一次，随后走正常错误终止

**依赖**：T5、T6（共享 ContextManager/判定）

**参考资料**
- `Agent.runTurn` 错误路径 L231-246；重试上限/策略 `RetryPolicy.java:10,17-21`；错误分类 `ProviderHttpClient.classify` L92-101
- "只压缩一次"用 Agent 内实例标志（不跨轮重置），防死循环

---

### T8 手动 /compact 命令

**目标**：用户空闲态随时压缩，展示压缩前后估算；/clear 语义不回归。

**影响文件（修改）**
- `ui/CommandRouter.java`（改）— `Action` 枚举（L10）增 `COMPACT`；`HELP_TEXT`（L13-22）补 `/compact` 行；`route`（L41-49）增 `/compact` 分支
- `CommandProcessor.java`（改）— 构造（L23-44）增一个"手动压缩处理"回调参数；`mainLoop` switch（L58-90）`COMPACT` → 同步执行该回调
- `ConversationController.java`（改）— 装配一次 `ContextManager`（provider/conversation/工作目录/策略）；经 `ExchangeRunner`（L332-337）注入 Agent；在 `commandProcessor()`（L235-242）把手动回调接上（空闲态同步跑 `CompactExecutor.run(true)`：先输出"正在压缩…"，成功输出前后估算与结果行，失败输出原因且历史不变，无可压缩内容输出提示）；把 contextManager 的清空钩子接到 Conversation（配合 T9 由 clear 触发重置）
- 测试：`ui/CommandRouterTest.java`（或既有 router 测试文件）补 `/compact → COMPACT`、HELP 含 `/compact`；`context/ManualCompactCommandTest.java`（FakeProvider + `@TempDir`）断言命令触发后历史被重建、输出含压缩前后估算数字；历史过短时输出"无需压缩"提示且历史不动

**依赖**：T5；装配还需 T3 已完成（Agent 注入走同一 ContextManager）

**参考资料**
- `CommandProcessor` 构造与 switch：L23-44、L58-90；`handlePermissionMode` 委托先例（L98）说明"控制器注入回调"的既有风格
- `ConversationController`：`commandProcessor()` L235-242、`exchangeRunner()` L332-337、`handleChat` L261-263、字段 L101-110
- 状态/输出：`OutputPane`/`LiveRegionRenderer.appendCommitted`（既有渲染路径）；估算展示用 T1 的整体估算

---

### T9 状态一致与接入主流程（会话/清空/存档联动 + 文档）

**目标**：压缩后历史随会话正常存档/恢复；/clear 与恢复会话等历史重置点联动重置冻结/熔断；补文档。

**影响文件（修改）**
- `Conversation.java`（改）— 已由 T1 加 clear 钩子；此任务把 contextManager.reset() 注册进去（在 ConversationController 装配处挂），重置冻结/熔断/触发器状态（落盘文件不删，见 spec Out of Scope）
- `SessionManager.java`（改，视需要）— 恢复/加载已走 `conversation.clear()`（L108）触发钩子；`restoreIfResume`（L46-66）不 clear，但新进程内会话本就新建，无需额外处理；确认 `isUnchangedReload`（L152-167）语义在"压缩后未新增消息"下不产生异常重复存档（可接受：压缩即内容变化，视为新内容存档一次）
- `docs/manual-test.md`（改）— 追加「阶段七」手测小节（空 ⚑ 项，T10 填）
- `README.md`（改）— 简述上下文管理能力与 `/compact` 命令（可选，跟随 ch06 先例）
- `context/SessionRoundTripTest.java`（新）— 压缩 → 存会话 → 读回 → 历史含摘要消息且可继续（再压缩正常触发）；clear 后熔断/冻结被重置（再次触发自动压缩不再被熔断拦）

**依赖**：T8（装配完毕）

**参考资料**
- `Conversation.clear` L94-97；`SessionManager` 加载/恢复/存档 L46-66、L107-120、L138-150；`SessionStore.save` L47-59、目录 `defaultDir()` L42-44
- 渲染恢复历史：`ui/HistoryRenderer.renderHistoryMessage`（摘要消息为长文本块，应可正常渲染，无特殊处理则无需改）
- `ConversationController` 装配点：构造 L149-173、`commandProcessor()` L235-242

---

### T10 端到端验证 ⚑

**目标**：全链路验收：大结果落盘 → 自动压缩 → 紧急压缩 → 手动 /compact → 会话往返；收尾统一回归。

**影响文件（新建 + 修改）**
- `context/ContextManagementEndToEndTest.java`（新）— 假 Provider + 真 Conversation/ContextManager/Agent 链路（`@TempDir` 工作目录）：
  1. 超大工具结果 → 工作目录 `.acode/tool-results/` 生成文件、历史含预览+路径、不含旧截断后缀
  2. 多轮逼近触发点 → 自动压缩 → 后续请求自摘要消息起、含边界提醒；压缩后不再被最旧裁剪静默删（断言请求消息数 = 重建后消息数）
  3. 撞墙"上下文超长" → 强制压缩一次并重试成功
  4. 手动 /compact 路径（controller 触发）→ 历史重建且输出含前后估算
- `docs/manual-test.md`（改）— 填阶段七 ⚑ 手测勾选（真实窗口下长任务观察压缩触发与 usage 变化；`/compact` 手测）
- **统一收尾**：`JAVA_HOME="D:\java\jdk21" mvn test` 全套全绿，记录总用例数（新增均无外部网络；FakeProvider 本地注入）

**依赖**：T9

**参考资料**
- 假 provider：`src/test/java/com/acode/provider/FakeProvider.java`（按需扩展捕获/注入）；测试先例：`docs/ch06/checklist.md` T9 端到端写法、`RetryPolicyTest`（本地 HttpServer）
- 触发/阈值/文案断言全部对齐 `checklist.md` 顶部「默认值说明」
