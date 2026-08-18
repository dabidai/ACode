# ACode 阶段四：Prompt 工程体系 — 任务清单

> 最后更新：2026-08-18
> 依赖关系：`(T1,T2)→T3`；`T2,T3→T7`；`T3,T5,T6,T7→T8→T10`；`T4、T5、T6、T9` 相互独立可并行。
> 参考实现：MewCode Java（`F:/code/agent-doc-tech/agent-doc-tech/downloads/source/2_mewcode-java.zip` 内 `src/main/java/com/mewcode/prompt/{PromptSections,PromptBuilder,PlanModePrompt}.java`），任务中标注的参考文件用 `unzip -p` 直接读取，不必解压。

## 约定

- 包根 `com.acode`，新增 Prompt 层 `src/main/java/com/acode/prompt/`（七模块、组装器、环境收集、system-reminder、管线），测试 `src/test/java/com/acode/prompt/`
- 现有文件行号以 2026-08-18 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；测试方法名用英文驼峰
- 需要复用的现有设施：`Conversation.trim/estimateTokens`（上下文控制）、`FakeProvider`（测试桩）、`AgentPlanModeTest`（plan 断言范式）、`ToolSchemaConverter.toAnthropicTools`（tools 序列化）、`AnthropicProviderTest`/`OpenAiProviderTest`（buildBody JSON 断言范式）

### 全局集成风险（各任务参考资料会引用，编号 R1~R7）

- **R1** `ChatListener` 扩展必须用 default 方法（ch04 R1 同款反向委托）：新增 `default void onUsage(Usage usage) {}`，存量实现（StreamPrinter / FakeProvider / TurnCollector / 各处匿名类）零改动。若做成抽象方法，所有实现类都要改。
- **R2** `Conversation.buildRequest(List<Tool>, ChatMessage)` 语义变化：reminder 从「SYSTEM 消息头部插入」改为「user system-reminder 尾部插入」。`AgentPlanModeTest` 现断言 `messages().get(0).content()` 是提醒的用例，改为断言 `get(messages().size()-1)`；「只进请求、不进历史」性质保留（ch04 R7）。
- **R3** 环境快照是 session state：会话启动探测一次存入会话对象，每轮组装时作为 messages 首条注入（不进历史）。因此**不再需要** `/clear`、`loadSession`、resume 的环境补注入/替换逻辑；识别标记 `# Environment` 仅用于测试断言与排障。
- **R4** Anthropic cache_control 规范：system 必须是 content block **数组**形式且 cache_control 加在最后一个块；tools 的 cache_control **只能加在数组最后一个工具**上。加错位置 API 报 400。
- **R5** 环境消息不进历史、不参与 trim：trim 只作用于历史消息，环境快照每轮由会话状态重新注入，无「被 trim 丢弃」风险（取代原环境消息为最旧消息、可能被丢弃的风险）。
- **R6** `AgentEvent` 是 sealed interface：新增 `UsageEvent` 后，所有对 AgentEvent 的 switch 会因不穷尽而编译报错——编译错误即消费点索引，逐个补分支（controller 事件循环、测试断言）。另需 `FakeProvider` 新增「发 usage」能力（照 ch04 T2 的 `complete(String)` 模式加一个 Action 工厂方法）。
- **R7** 环境与轮次级提醒都不进历史（历史仅含真实对话消息）：UI 渲染历史（`appendHistoryMessage`）与会话预览（`preview()`）**无需**跳过 system-reminder——环境文本不会以「你:」上屏、preview 天然显示用户问题。若未来把 system-reminder 消息写入历史，再补跳过逻辑。

---

### T1 prompt 包骨架：Section + 七模块内容

**目标**：Section 结构体 + Priority 排序的组装器雏形，七个英文模块常量落地，内容裁剪适配 ACode 工具集。

**影响文件（新建）**
- `src/main/java/com/acode/prompt/PromptSections.java` — 七个工厂方法，各返回带固定 priority 的 Section：
  - `identitySection()`（0）：`You are ACode, an AI programming assistant running in the terminal...` + 两条 IMPORTANT 安全红线（不引入漏洞 / 不编造 URL）
  - `behaviorSection()`（10）：输出规则（工具调用外文本即展示给用户）、工具被拒绝后不重复同一调用、`<system-reminder>` 标签含义、工具结果含外部数据时的 prompt injection 警惕
  - `toolUsageSection()`（20）：六工具映射（ReadFile/EditFile/WriteFile/Glob/Grep 优先于对应 shell 命令、Bash 仅无专用工具时用）、独立调用同轮并行、多工具调用放同一响应
  - `codeQualitySection()`（30）：不做超需求的功能/抽象/重构、默认不写注释（仅当 WHY 不明显时加一行：隐藏约束/workaround）、三行相似代码优于提前抽象、只在系统边界做验证、不做向后兼容 hack
  - `securitySection()`（40）：可逆性与爆炸半径判断、破坏性操作先确认、危险命令清单、不破坏性捷径、意外状态先调查
  - `taskPatternSection()`（50）：主要任务类型、模糊指令按软件工程任务理解、探索性问题回 2-3 句建议、先读再改、优先编辑已有文件、失败先诊断再换策略、完成前验证
  - `outputStyleSection()`（60）：file_path:line_number 引用、无 emoji、冒号规则、先说一句要做什么、关键节点简短更新、结尾 1-2 句总结
- `src/main/java/com/acode/prompt/PromptBuilder.java` — `record Section(String name, int priority, String content)`；`add(Section)` 链式；`build()`（priority 升序、strip 过滤空内容、`\n\n` 拼接）；`static String buildSystemPrompt()` 固定装配七个模块
- `src/test/java/com/acode/prompt/PromptSectionsTest.java` — 七模块 name/priority 断言、内容非空且为英文、关键规则语句存在
- `src/test/java/com/acode/prompt/PromptBuilderTest.java` — 乱序 add 后按优先级输出、空内容过滤、分隔符为两个换行

**依赖**：无

**参考资料**
- MewCode `com/mewcode/prompt/PromptSections.java`（七段文本的直接来源，按上面模块划分裁剪：去掉 ACode 没有的 Agent 委派 / ToolSearch / Skill / thinking / hooks 等说明）
- MewCode `com/mewcode/prompt/PromptBuilder.java`（Section record + build 排序拼接）
- 参考书第 5 章理论篇「七个模块」逐条说明（本任务对应内容来源）

---

### T2 环境收集器 + system-reminder 机制

**目标**：环境快照收集器（git 探测、shell 兜底）+ XML 包裹消息工厂（会话级注入的基础）。

**影响文件（新建）**
- `src/main/java/com/acode/prompt/EnvironmentDetector.java` —
  - `record EnvironmentSnapshot(String workDir, String os, String arch, String shell, boolean isGitRepo, String gitBranch, String model, String date)`
  - `static EnvironmentSnapshot detect(String model)`：`System.getProperty("user.dir")`、`os.name`/`os.arch`、SHELL 环境变量（空则兜底 `"bash"`）、`git -C <workDir> rev-parse --is-inside-work-tree` + `--abbrev-ref HEAD`（失败静默、非仓库分支为空）、`LocalDate.now()`
  - `static String render(EnvironmentSnapshot)`：生成 `# Environment` 段落的字段列表（Working directory / Platform / Shell / Is git repo / Git branch / Model / Date）
- `src/main/java/com/acode/prompt/SystemReminder.java` —
  - 常量 `OPEN = "<system-reminder>"`、`CLOSE = "</system-reminder>"`
  - `static ChatMessage wrap(String content)`：USER role + 单 text 块，内容为 `OPEN\n<content>\nCLOSE`
  - `static ChatMessage environment(EnvironmentSnapshot)`：`wrap(render(snapshot))`
  - `static boolean isSystemReminder(ChatMessage)`：内容以 OPEN 开头（供测试断言与排障识别）
- `src/test/java/com/acode/prompt/EnvironmentDetectorTest.java` — @TempDir 下 `git init` 的仓库（isGitRepo=true、分支非空）/ 普通目录（false、不抛异常）；SHELL 置空兜底 bash；render 含全部 8 个字段行
- `src/test/java/com/acode/prompt/SystemReminderTest.java` — 包裹格式、role=USER、isSystemReminder 判定

**依赖**：无

**参考资料**
- MewCode `PromptBuilder.detectEnvironment`（zip 内 PromptBuilder.java 环境探测段：`git -C workDir` 两次探测、`ProcessBuilder` 用法、`LocalDate.now()`）

---

### T3 组装管线：Conversation 改造 + PromptPipeline

**目标**：system 提示词、环境快照（session state）、trim 后历史、轮次级 reminder 四段在每轮请求中按序组装；`PromptPipeline.assemble` 作为每轮请求唯一入口。

**影响文件（修改 + 新建）**
- `src/main/java/com/acode/conversation/Conversation.java`（改）— 状态持有者：加 `String systemPrompt` + setter、`ChatMessage environment`（渲染好的环境 system-reminder）+ setter；`trim()`/`estimateTokens` 保留；`buildRequest(List<Tool>, ChatMessage turnReminder)`（91-104 行）改为：
  - 结果列表 = `[SYSTEM（systemPrompt 非空时）]` + `[环境 system-reminder（environment 非空时，首条 user 消息，不进历史）]` + `trim()` + `[turnReminder（若非空，尾插）]`
  - reminder 语义从「头部 SYSTEM 消息」改为「尾部 user 消息」；环境消息从「历史首条持久化」改为「每轮注入 messages 首条、不进历史」（R3）；`buildRequest()` 无参委托保留
- `src/main/java/com/acode/prompt/PromptPipeline.java`（新）— 每轮唯一入口：`static ChatRequest assemble(Conversation c, List<Tool> tools, ChatMessage turnReminder)` 按「system → 环境 → 历史 → 轮次级」四段组装（环境取自 c 的会话状态；trim 逻辑留在 Conversation；后续 MEWCODE/记忆源在此插拔）
- `src/test/java/com/acode/conversation/ConversationTest.java`（改）— 新增：systemPrompt 未设置时请求与改前一致（存量用例零改动）；设置后请求首条为 SYSTEM 消息、`history()` 不含；environment 设置后请求 messages 首条为环境 system-reminder 且不进历史；turnReminder 尾插且不进历史；turnReminder 为 null 无额外消息
- `src/test/java/com/acode/prompt/PromptPipelineTest.java`（新）— assemble 产出「system → 环境 → 历史 → 轮次级」四段顺序断言；与 conversation.buildRequest 等价冒烟

**依赖**：T1、T2

**参考资料**
- Conversation.java:83-104（现 buildRequest；R2）；ch04 R7 注释（提醒不进历史）
- AnthropicProvider.java:74-95 / OpenAiProvider.java:109-127（SYSTEM 两端聚合已支持，无需改 provider）

---

### T4 工具描述强化

**目标**：六工具 description 补齐用法、优先级、配合关系，与 ToolUsage 模块双重强化关键规则。

**影响文件（修改，各文件 description 字段见行号）**
- `src/main/java/com/acode/tool/impl/ReadFileTool.java`（25-29 行）— 补：路径用绝对路径；默认前 2000 行、大文件用 offset/limit 只读需要部分；优先于 Bash `cat/head/tail`；编辑文件前必须先读
- `src/main/java/com/acode/tool/impl/EditFileTool.java`（28-33 行）— 补：编辑前必须先 ReadFile；old_string 必须与文件现有内容精确唯一匹配
- `src/main/java/com/acode/tool/impl/WriteFileTool.java`（27-32 行）— 补：创建新文件 / 整体重写用本工具而非 Bash `echo` 重定向
- `src/main/java/com/acode/tool/impl/BashTool.java`（33-39 行）— 补：仅在无专用工具时使用；command 参数写清命令用途；保留 Windows Git Bash 说明、60s 超时、30000 字符截断
- `src/main/java/com/acode/tool/impl/GlobTool.java`（28-33 行）— 补：文件查找用本工具而非 `find/ls`
- `src/main/java/com/acode/tool/impl/GrepTool.java`（31-37 行）— 补：内容搜索用本工具而非 `grep/rg`

**依赖**：无（description 只影响 schema 生成，无行为变化，现工具测试应全绿）

**参考资料**
- MewCode `PromptSections.java` 的 UsingTools 段落（优先级/配合关系的措辞来源）
- 参考书第 5 章理论篇「工具描述也是 Prompt 工程」（好描述 vs 差描述示例）

---

### T5 cache_control 输出

**目标**：Anthropic 请求 system 数组形式 + 双断点（system 整体 + tools 末工具）；OpenAI 不输出。

**影响文件（修改）**
- `src/main/java/com/acode/provider/anthropic/AnthropicProvider.java`（改 `buildBody` 74-98 行）—
  - system 改为数组：`[{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]`（原 93-95 行 `root.put("system", system.toString())` 改为 `root.putArray("system").addObject().put("type","text").put("text", ...).putObject("cache_control").put("type","ephemeral")`）
  - tools 非空时：`ToolSchemaConverter.toAnthropicTools(...)` 返回的数组**最后一个**工具元素加 `"cache_control":{"type":"ephemeral"}`（R4）
- `src/test/java/com/acode/provider/anthropic/AnthropicProviderTest.java`（改）— system 断言从纯字符串改为数组结构（text 值不变 + cache_control 存在）；tools 断言：末工具含 cache_control、其余工具不含；空 tools 无异常

**依赖**：无

**参考资料**
- AnthropicProvider.java:59-98（buildBody 现状）；ToolSchemaConverter.java:18-24
- Anthropic Prompt Caching 文档：system 文本块数组、tools 末元素放 cache_control（cache breakpoint 语义）

---

### T6 usage 解析 + 脚注展示

**目标**：两端解析 usage（含缓存命中字段）→ `onUsage` 回调 → `UsageEvent` → 终端脚注行（T8 接线展示）。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/provider/Usage.java`（新）— `record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens)`
- `src/main/java/com/acode/provider/ChatListener.java`（改）— 加 `default void onUsage(Usage usage) {}`（R1）
- `src/main/java/com/acode/provider/anthropic/AnthropicSseParser.java`（改）— `message_start` 分支（现 49 行 default 吞掉）解析 `message.usage`：`input_tokens` / `output_tokens` / `cache_read_input_tokens` / `cache_creation_input_tokens` → `listener.onUsage(...)`
- `src/main/java/com/acode/provider/openai/OpenAiSseParser.java`（改）— `node.path("usage")` 存在时解析：`prompt_tokens` / `completion_tokens` / `prompt_tokens_details.cached_tokens`（cacheCreation 恒 0）→ onUsage
- `src/main/java/com/acode/agent/AgentEvent.java`（改）— 新增 `record UsageEvent(Usage usage)`（R6：sealed switch 补分支）
- `src/main/java/com/acode/agent/TurnCollector.java`（改）— 覆写 `onUsage` → 发 `UsageEvent`
- `src/test/java/com/acode/provider/FakeProvider.java`（改）— 新增 `static Action usage(Usage)`（R6）
- 测试：`AnthropicSseParserTest`（录制 message_start 片段：带/不带缓存字段）；`OpenAiSseParserTest`（usage 块）；`TurnCollectorTest`（转发 UsageEvent）；`FakeProviderTest`（usage Action）

**依赖**：无（脚注接线在 T8）

**参考资料**
- AnthropicSseParser.java:33-51（message_start 现被忽略）；OpenAiSseParser.java:30-67（usage 块无 choices）
- ch04 R1 反向委托模式（ChatListener.java:7-35）

---

### T7 Plan Mode 改造

**目标**：plan 提醒从 SYSTEM 硬拼接改为轮次级 system-reminder（英文、尾插）；节奏第 1 轮完整、每 5 轮重复完整。

**影响文件（修改）**
- `src/main/java/com/acode/agent/PlanModePrompt.java`（改）— 英文 FULL/SPARSE 常量（保留 ACode 语义：只读探索、计划写在回复文本、完成后调用 ExitPlanMode 交付、计划落盘 `.acode/plans/`）；`buildReminder(int iteration)`：`iteration == 1 || (iteration - 1) % 5 == 0` → FULL，否则 SPARSE（即第 1、6、11…轮完整版；**注意**：参考实现 MewCode 的节奏公式 `(iteration-1)/5 %5==0` 有 bug 会在第 2~5 轮连续发完整版，按参考书文字意图实现）
- `src/main/java/com/acode/agent/Agent.java`（改 `buildPlanAwareRequest` 373-379 行）— reminder 改为 `SystemReminder.wrap(PlanModePrompt.buildReminder(turn))`（user 消息；尾插由 T3 的 buildRequest 完成）
- `src/test/java/com/acode/agent/AgentPlanModeTest.java`（改）— reminder 断言改为 `messages().get(size-1)` 且内容含 `<system-reminder>`（R2）；新增节奏用例：第 1、6 轮 FULL、第 2~5 与 7~10 轮 SPARSE

**依赖**：T2、T3

**参考资料**
- PlanModePrompt.java（现全文，中文）；MewCode `com/mewcode/prompt/PlanModePrompt.java`（zip 内；节奏公式按文字意图修正）
- 参考书第 5 章实战篇「Plan Mode 改造」（不再让缓存失效）

---

### T8 接入主流程

**目标**：controller 装配 system prompt 与环境快照（session state）、usage 脚注接线与日志；环境与轮次级提醒不进历史，UI 无需跳过渲染逻辑。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`（改）：
  - `start()`（150-155 行附近）：`conversation.setSystemPrompt(PromptBuilder.buildSystemPrompt())` + `conversation.setEnvironment(SystemReminder.environment(EnvironmentDetector.detect(config.getModel())))`；resume 时同样重新探测存入会话状态（每轮自动注入，不动历史）
  - `restoreIfResume()` / `loadSession()`（161-180 / 267-277 行）：环境快照不进历史，恢复/加载会话**无需**补注入或替换逻辑（R3：环境由会话状态每轮注入）
  - `mainLoop()` 的 CLEAR 分支（199-204 行）：`conversation.clear()` 只清历史，环境快照留在会话状态，下一轮请求仍注入
  - `handleExchange` 事件循环：新接 `UsageEvent` → 暂存本轮 usage；`TurnComplete` → 终端脚注行输出本轮 usage（格式见 checklist 默认值）并 `log.info` 写入既有文件日志（对齐 spec「脚注 + INFO 日志文件」）
  - `appendHistoryMessage` 与 `preview()`（256-264 行）：环境与轮次级提醒不进历史，无需跳过逻辑（R7），preview 天然显示用户问题
- `src/test/java/com/acode/ConversationControllerTest.java`（改）— 存量用例中请求 messages 首条断言因新增 SYSTEM 消息而调整；新增：请求 messages 首条为环境 system-reminder（不进历史）、CLEAR 后请求仍注入环境、resume 后环境为重新探测的新快照、usage 脚注行出现在输出

**依赖**：T3、T5、T6、T7

**参考资料**
- ConversationController.java:150-180（start/restore）、199-224（CLEAR）、256-277（preview/loadSession）、476+（handleExchange 事件循环）
- StreamPrinter 预期零改动（ch04 R3 语义不变）

---

### T9 评估场景文档 + 手测文档

**目标**：5 个定性评估场景成文，作为每次改 prompt 后的人工对照基准。

**影响文件（新建 + 修改）**
- `docs/ch05/eval-scenarios.md`（新）— 5 场景：①身份与安全红线；②工具选择（读→ReadFile、改前先读、写→WriteFile）；③行为准则与输出风格（探索性问题 2-3 句、file:line 引用、无 emoji、结尾 1-2 句总结）；④安全边界（危险操作确认、生成代码无注入漏洞、不编造 URL）；⑤任务模式（修 bug 最小修改、不写注释、不过度设计）。每条含输入示例 / 期望行为 / 对照判据；附录：缓存命中验证（每次跑场景看脚注 cache_read）
- `docs/manual-test.md`（改）— 追加「阶段四」小节：启动后环境注入可见、5 场景人工对照、第 2 轮起脚注 cache_read>0、plan 模式提醒为 system-reminder

**依赖**：无（可与开发并行）

**参考资料**
- 参考书第 5 章「常见陷阱和应对策略」与实战篇「功能验证过程」小节（场景措辞来源）

---

### T10 端到端验证

**目标**：全量回归 + 真实 provider 手动验收清单。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md` 完成阶段四手测勾选（见 T9）
- 修 bug 产生的影响文件视情况

**依赖**：T8

**参考资料**
- 手测按 checklist.md ⚑ 项逐条打勾；联网问题用临时错误 base_url 模拟（沿用 ch02 做法）
