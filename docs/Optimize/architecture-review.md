# ACode 架构审查报告

> 修复状态（2026-08-25）：22/24 条已修复（严重 1-3、中等 4/8/9/10/11/12/13、轻微 14/15/16/17/18/19/20/21/22/23，commit 见 git log 各修复条目）。剩余：#7 ConversationController 上帝类拆分、#24 线程模型统一，记录待办未做。

- 审查日期：2026-08-25
- 审查范围：
  - 主代码 `src/main/java/com/acode/` 全部 88 个 .java 文件（13 包）
  - 测试 `src/test/java/com/acode/` 抽样（61 个文件，重点读 ConversationControllerTest / AgentIntegrationTest / AgentTest / ConversationTest / EnvironmentDetectorTest）
  - 设计文档 `docs/ch02` ~ `docs/ch06` 对照（以代码实际为准）
  - 审查方式：只读分析，未修改任何 src/ 代码

严重程度说明：**严重** = 会导致数据损坏、核心流程（对话/安全）失效或长期损害产品价值；**中等** = 有明确触发场景的功能性缺陷或明显的架构负债；**轻微** = 死代码、文档不一致、卫生问题。

---

## 严重

### 1. 环境快照与真实执行 shell 脱节：模型被喂假情报（已知问题 + 关联补充）

**位置**：`src/main/java/com/acode/prompt/EnvironmentDetector.java:56-58`、`src/main/java/com/acode/tool/impl/ShellDetector.java:33-41`、`src/main/java/com/acode/tool/impl/BashTool.java:35-39`

**问题描述**：`EnvironmentDetector.resolveShell()` 在 `SHELL` 环境变量缺失（Windows cmd/PowerShell 下的常态）时默认返回 `"bash"`；而实际执行命令的 `ShellDetector` 找不到 Git Bash 时回退 `cmd /c`。两处各测各的：`EnvironmentDetectorTest.java:43-44` 把「null → bash」固化为测试断言，`ShellDetectorTest` 则验证 cmd 回退，互不知晓对方。

**为什么是问题**：模型看到的系统提示是「Shell: bash」，BashTool 描述还承诺「命令在 Git Bash 下以 Unix 风格运行」——但真实命令以 `cmd /c` 执行。模型据此写出 `ls`/`cat`/`rm -rf` 等 Unix 语法命令在 cmd 下直接失败，或 worse：在 cmd 下 `del /q` 之类的命令模型又不会写。这是对模型推理的持续污染，且回退发生时**没有任何警告**，用户和模型都无感知。本项目的核心价值就是模型执行质量，Windows 上每个会话都在被喂假情报。

**修复建议**（方向）：
1. `EnvironmentDetector` 直接复用 `ShellDetector` 的探测结果（或共享一个探测服务），`resolveShell` 不再拍脑袋默认 "bash"；
2. 回退 cmd 时在环境快照中显式标注 `(fallback: cmd)`，并在 BashTool 描述中如实说明当前 shell；
3. 删掉/改写 `EnvironmentDetectorTest.java:43-44` 对假默认值的固化。

---

### 2. Conversation.trim() 可拆散 tool_use/tool_result 配对 → API 400，长会话猝死

**位置**：`src/main/java/com/acode/conversation/Conversation.java:129-138`

**问题描述**：`trim()` 在历史超窗时从 index 0 逐条删除消息，**按条**删除而非按「轮」删除。一条 assistant 消息（含大段叙述文本 + 多个 tool_use 块）被删掉后，如果总量恰好降到窗口内，循环停止——其后的 user tool_result 消息就留下了，引用一个已不存在的 tool_use_id。

**为什么是问题**：Anthropic 与 OpenAI 均要求 tool_result / tool 消息必须引用对话中真实存在的 tool_use / tool_call id，孤儿 tool_result 会被 API 以 400 拒绝。触发场景很现实：长会话（默认窗口 128k token）中某一轮 assistant 消息文本很长、而工具结果很短（如「已写入」）时，删除该 assistant 消息即可让总量跌破预算，留下孤儿结果。后果：本轮 400 报错终止，**且历史中孤儿仍在，之后每一轮都重复 400**，只有 /clear 能逃逸。`ConversationTest` 只测了「窗口内完整保留」（`trimKeepsToolBlocksWhenFitsWindow`，第 133-147 行），没有测超窗拆散配对的情形。

**修复建议**：
- trim 改为按「消息对」删除：删除 assistant(tool_use) 时一并删除其引用的 tool_result 消息；或
- 组装请求后做一次一致性校验，发现孤儿 tool_result 则连带删除；并补对应测试（构造超窗历史，断言无孤儿）。

---

### 3. 取消收尾的 5 秒上限不是硬保证 → 旧 agent 线程与新 exchange 并发写共享历史

**位置**：`src/main/java/com/acode/ConversationController.java:672-682`、`src/main/java/com/acode/agent/Agent.java:131-137, 356-370`

**问题描述**：Ctrl+C 时 `agent.cancel()` 置位 + 中断循环线程，UI 侧 `awaitLoopEnd()` 只轮询 5 秒。但中断发生时循环线程可能正卡在工具执行里（Bash 最长 60 秒、`StreamingToolExecutor.runConcurrently` 的 `future.get()` 不吃中断、`BaseTool.execute` 的 `future.get()` 只响应其自身超时）。5 秒后 UI 放弃等待，用户输入下一句，`handleExchange` 新建 Agent 复用**同一个 `Conversation` 实例**。旧线程稍后从工具返回，`executeTools()` 在 `if (cancelled.get())` 检查**之前**已经调用了 `conversation.addToolResults(blocks)`（Agent.java:369）——与新 exchange 的 `addMessage`/`trim` 并发写同一个 `ArrayList`。

**为什么是问题**：`ArrayList` 非线程安全，并发 add 可能丢更新、错乱甚至抛 ConcurrentModificationException；丢更新意味着历史残缺（下一轮请求缺 tool_result，触发问题 2 的 400），错乱则污染整个会话。触发场景：长命令执行中按 Ctrl+C 且命令在取消后仍运行超过 5 秒（构建、测试、下载都很常见）。

**修复建议**：
1. `awaitLoopEnd` 改为等待「旧 agent 线程真正结束」（无限等待或显著加长 + 完成后才允许下一次 exchange），或
2. 给 `Conversation` 的写操作加锁/用线程安全列表，并把「取消后不再写历史」做成硬约束（executeTools 在写历史前再查一次 cancelled）；至少把 addToolResults 挪到 cancelled 检查之后。

---

## 中等

### 4. BashTool 的 timeout_ms > 60s 被 BaseTool 固定超时外壳静默截断

**位置**：`src/main/java/com/acode/tool/BaseTool.java:60-62`、`src/main/java/com/acode/tool/impl/BashTool.java:57-59, 64-67`

**问题描述**：`BaseTool.execute()` 用 `future.get(defaultTimeoutMillis(), ...)` 包住 `doExecute`，BashTool 覆写 `defaultTimeoutMillis()` 恒为 60000。模型传 `timeout_ms: 300000` 时，内部 `process.waitFor(300s)` 还没到点，外层 60 秒先触发 `TimeoutException → future.cancel(true)`，命令被中断，返回「执行超时（上限 60000 ms）：Bash」。

**为什么是问题**：工具描述明确承诺「缺省 60 秒超时可用 timeout_ms 调整」，但任何 >60s 的调整都失效且报错信息误导（说上限 60000 而非真实原因）。模型会以为命令超时了，可能重试同一命令或误判问题。同一参数名在两个地方各生效一半，属于典型的双重超时设计缺陷。

**修复建议**：BaseTool 的超时外壳应只做「无内部超时的兜底」，或把超时判断下沉为子类可覆盖的一次性逻辑（BashTool 已自带 waitFor(timeout)，外层再包一层是冗余）；至少让外层上限 = max(类默认值, 本次调用 timeout_ms)。

### 5. Agent.stream() 用 isAlive 轮询代替 join，工作线程的内存可见性无 JMM 保证

**位置**：`src/main/java/com/acode/agent/Agent.java:304-329`

**问题描述**：`stream()` 起 worker 线程跑 `provider.streamChat`，主循环 `while (worker.isAlive() && !cancelled.get()) sleep(20)`，isAlive 变 false 后直接读 `collector.text()/toolUses()/error()`。`Thread.isAlive()` 轮询**不是**同步操作，不建立 happens-before 边；JMM 层面 worker 线程的写入对读取线程不保证可见（正确做法是 `worker.join()`）。

**为什么是问题**：理论上读取方可能看到 `text()` 的部分写入或 null 值（编译器/CPU 重排下 StringBuilder 内容可能非一致可见）。HotSpot 上极难复现，但这是教科书级错误模式，且本项目其他同步点（事件队列、AtomicBoolean）都做对了，唯独这里靠轮询——一旦未来重构（如把 streamChat 改成非阻塞）就会显形。

**修复建议**：isAlive 轮询改 `worker.join()` + 取消中断配合（join 会被 interrupt 打断，正好复用现有取消路径）。

### 6. Agent.loop() 没有顶层异常捕获 → 意外异常时用户无感知地静默失败

**位置**：`src/main/java/com/acode/agent/Agent.java:159-204`、`src/main/java/com/acode/ConversationController.java:582-585`

**问题描述**：`loop()` 只处理业务分支；任何未预期的 RuntimeException（如 `runConcurrently` 的 `IllegalStateException`、未来新代码的 NPE）直接杀死虚拟线程。UI 侧事件循环的退出条件 `if (!agent.isRunning() && events.isEmpty()) break;`（ConversationController.java:583）恰好把这个死因当作正常结束——**没有 LoopComplete、没有 ErrorEvent、没有任何提示**。

**为什么是问题**：用户发出指令后屏幕毫无反应，对话无声死亡；历史停留在半成品状态，用户不知道发生了什么。错误被完全吞掉是调试噩梦。

**修复建议**：`loop()` 最外层 `try/catch (Throwable)` 里补发 `ErrorEvent`（含堆栈摘要）并置 `Termination.ERROR`；UI 侧对「无 LoopComplete 但非取消」的收尾补一条失败提示。

### 7. ConversationController 上帝类：740 行、11 类职责、7 个测试注入 setter

**位置**：`src/main/java/com/acode/ConversationController.java:80-740`

**问题描述**：一个类同时承担：配置加载入口、Provider 构建、终端装配、主循环命令路由、会话保存/恢复/加载、历史消息渲染、确认/选择应答、Tee 诊断、Agent 装配与事件分发（if-else 链 597-627 行）、权限装配与 /permission-mode、Ctrl+C 检测、usage 脚注。测试可测性全靠 7 个 setter（`setOutput/setLive/setScreenWriter/setConfirmAnswerer/setChoiceAnswerer/setPermissionChecker/setProjectRoot`，404-436 行）+ 5 个包可见钩子。

**为什么是问题**：每一章的改动都落在这个类上（git log 显示它常被修改），职责继续膨胀；测试注入口数量本身就是「类内功能过多」的度量。事件分发 if-else 链（597-627 行）每加一种事件就要动这个类，与「Agent 事件契约」的扩展方向冲突。

**修复建议**（按收益排序）：
1. 抽出 `SessionManager`（saveSession/restoreIfResume/loadSession/selectSession/preview，约 150 行）；
2. 抽出 `ExchangeRunner` 或把事件 if-else 链换成 `AgentEvent` 上的 visitor/switch 表达式放到独立类（处理 StreamText/ToolUse/ToolResult/TurnComplete 等渲染逻辑）；
3. `TeeWriter`（501-537 行）独立成文件（或复用 StreamPrinter/OpenAiProvider 已有的诊断日志模式）。
不需要一次做完，但每章新增功能应首先落到新类而不是这个类。

### 8. 权限层用字符串硬编码工具名：新工具静默绕过防线

**位置**：`src/main/java/com/acode/permission/PermissionChecker.java:36-45, 97, 114-117`

**问题描述**：`CONTENT_FIELDS`、`PATH_TOOLS`、`"Bash".equals(toolName)`、`toolName.equals("WriteFile") || toolName.equals("EditFile")` 全部以字符串匹配工具名。工具名与权限包之间没有任何类型层面的契约。

**为什么是问题**：新增一个工具（如 `RmFile`、`MoveFile`）时，如果忘记同步改 PermissionChecker，路径沙箱、危险命令检测、plan 例外全部对该工具失效——写类工具直接落进「模式矩阵」这一层（默认 DEFAULT 下写类 ASK，尚可兜底），但沙箱硬边界没了。这是安全相关代码里最危险的一类耦合：**默认失败的是漏配置，而不是拒绝**。工具名也是注册表级契约，却散落在 PermissionChecker 一处字符串集合里。

**修复建议**：把「内容字段」「是否路径工具」作为工具自身的元数据（如 Tool 接口加 `contentField()` / `category()`），PermissionChecker 按类型查询而非按名字比对；至少将三处字符串集合收敛为单一常量类并加「工具注册时校验集合完整性」的测试。

### 9. ReadFileTool 描述承诺「大文件用 offset/limit 只读需要的部分」，实现却是全量读

**位置**：`src/main/java/com/acode/tool/impl/ReadFileTool.java:44-53`（描述在第 26-30 行）

**问题描述**：`doExecute` 先 `Files.readAllLines(file)` 把整个文件读进内存，再按 offset/limit 取片段展示。工具描述（第 26-28 行）明确告诉模型「大文件用 offset/limit 只读需要的部分」。

**为什么是问题**：100MB 日志文件读一次就是数百 MB 堆（String 开销），模型按描述读大文件切片反而触发全量读，与承诺相反；内存峰值完全不可控。

**修复建议**：改为 `Files.lines(...).skip(offset).limit(limit)` 流式读取（并处理读取异常），或至少读前先 `Files.size()` 超阈值时用流式路径。

### 10. PlanModePrompt 提示模型调用不存在的工具 AskUserQuestion

**位置**：`src/main/java/com/acode/agent/PlanModePrompt.java:26` vs `src/main/java/com/acode/agent/AskUserTool.java:30`

**问题描述**：plan 模式提醒文案写着「ask the user with AskUserQuestion before finalizing」，而注册的工具名是 `AskUser`（AskUserTool.name() 返回 "AskUser"，在 ConversationController.java:146 注册）。文档（docs/ch04/followups.md:164、docs/ch06/spec.md:43）一致使用 AskUser。

**为什么是问题**：plan 模式下模型照提示调用 `AskUserQuestion` → ToolExecutor 返回「工具「AskUserQuestion」未注册」→ 模型困惑、plan 流程的澄清环节失效。这属于提示词与代码契约脱节（疑为参考实现 MewCode 的旧名残留）。

**修复建议**：改 PlanModePrompt 文案为 AskUser；顺带全文 grep 确认无其他残留旧名。

### 11. tool ↔ provider 包循环依赖

**位置**：`src/main/java/com/acode/tool/ToolExecutor.java:3`（import provider.ToolUseBlock）与 `src/main/java/com/acode/provider/ChatRequest.java:3`（import tool.Tool）

**问题描述**：`com.acode.tool` 依赖 `com.acode.provider` 的消息类型（ToolUseBlock），而 `com.acode.provider` 的 ChatRequest 携带 `List<Tool>` 依赖 `com.acode.tool`——两个包互相依赖。同样被迫的还有 `InteractiveTool` 接口被放在 agent 包（其 javadoc 明说「避免 tool → agent 包依赖循环」，InteractiveTool.java:12）。

**为什么是问题**：包之间形成编译期环，任何一侧都无法独立抽取/复用（比如将来把 provider 抽成独立库或换 SSE 客户端，会连带拖进整个 tool 包）；「把接口放到调用方包里」的规避手法让概念归属错位，新开发者按包名找工具契约会找不到。

**修复建议**：把 `ToolUseBlock` 这类「工具调用参数载体」下沉到中立位置（如 tool 包内定义、provider 引用它），让依赖方向统一为 provider → tool；或反过来让 ChatRequest 通过函数式接口接受工具描述。二者取其一，打破环即可。

### 12. Glob/Grep 不忽略 .gitignore / .git / target，搜索质量与性能双输

**位置**：`src/main/java/com/acode/tool/impl/GlobTool.java:63`、`src/main/java/com/acode/tool/impl/GrepTool.java:70`

**问题描述**：两个工具都是 `Files.walk(base)` 全量遍历，不过滤 `.git/`、`target/`、`node_modules/` 等。`**/*.java` 会命中 `target/` 下的编译产物副本；Grep 会扫 `.git` 对象目录（二进制行基本被解码异常跳过，但浪费 IO），且命中上限（500 条）可能被 build 产物占满。

**为什么是问题**：模型拿到重复/垃圾结果，行为与 Claude Code 等同类工具的 gitignore 感知相悖；大仓库上遍历 `.git` 是明显的性能浪费。

**修复建议**：遍历时跳过 `.git` 目录（硬性）+ 按 .gitignore 规则过滤（可用轻量 glob 近似或引入现成库）；至少在描述中如实说明不忽略。

### 13. 双重嵌套重试：单轮最多可发 12 次请求

**位置**：`src/main/java/com/acode/provider/ProviderHttpClient.java:37-47`（HTTP 层重试，`RetryPolicy.MAX_RETRIES = 3`，RetryPolicy.java:10）与 `src/main/java/com/acode/agent/Agent.java:223-237`（轮级重试，`MAX_RETRIES = 2`，Agent.java:50）

**问题描述**：同一错误（NetworkException/RateLimitException/ServerException）先在 ProviderHttpClient 内重试 3 次，仍失败再冒泡给 Agent 重试 2 次，每次 Agent 重试内部又各带 3 次 HTTP 重试。且两个重试上限常量（3 vs 2）分散定义、互不知晓。

**为什么是问题**：最坏情况单轮 12 次请求、最长退避序列 1s+2s+4s（×3 轮），用户看到的是长时间的「重试中」；若真的限流，12 次请求只是把限流打得更狠。重试策略分散两层、语义重叠，属于同一关注点被两处实现。

**修复建议**：明确职责边界——HTTP 连接层重试（幂等、短退避、上限 1-2 次）与轮级重试（整轮语义、上限 1-2 次）任一保留其一，或统一收敛到 RetryPolicy 一个出口；至少共享同一常量与文档化策略。

---

## 轻微

### 14. StreamPrinter implements ChatListener 却是被手动驱动的，onComplete() 是死代码

**位置**：`src/main/java/com/acode/ui/StreamPrinter.java:25, 90-97`

**问题描述**：StreamPrinter 声明实现 ChatListener，但生产路径从不作为 listener 接入 provider——ConversationController.handleExchange（597-622 行）手动调用它的 `onDelta/onToolUse/onError`。`onComplete()`（90-97 行）全代码库无调用方（已 grep 确认），轮次收尾实际走 `finishTurn()`。

**为什么是问题**：接口契约是「要么 onComplete 要么 onError 结束」，但这里 onComplete 永远不会被调——误导后续维护者以为有回调驱动，且死代码与 finishTurn 的双实现让收尾逻辑有两份真相。

**修复建议**：删除 `implements ChatListener` 与 `onComplete()`，收尾只留 `finishTurn()` 一个入口（或反过来把它真正接入 provider 回调，二选一）。

### 15. ChatListener.onComplete 双向 default 委托是无限递归地雷

**位置**：`src/main/java/com/acode/provider/ChatListener.java:18-31`

**问题描述**：`onComplete()`（无参）默认调 `onComplete(null)`，`onComplete(String)` 默认调 `onComplete()`。当前两个实现（TurnCollector 覆写带参、StreamPrinter 覆写无参）恰好各覆写一个不炸，但**任何覆写零个的实现类**调用任意一个版本都会无限递归 StackOverflowError——而接口文档只说「实现方保证互斥调用」。

**为什么是问题**：默认方法之间的互委托把「忘覆写」从编译错误变成运行时爆栈，且报错信息与根因毫无关系（排查时完全看不出是 ChatListener）。这是典型的"便利"换来陷阱。

**修复建议**：二选一作为唯一抽象方法（带参版），无参版作为 final 的便利重载不可被覆写；或把 stopReason 改为可选字段不再有第二个方法。

### 16. 生产死代码：PromptPipeline、Conversation.buildRequest() 无参、setTools、TurnCollector.usage()

**位置**：`src/main/java/com/acode/prompt/PromptPipeline.java:19-21`、`src/main/java/com/acode/conversation/Conversation.java:52-55, 99-101`、`src/main/java/com/acode/agent/TurnCollector.java:98-101`

**问题描述**：`PromptPipeline.assemble` 只是转发 `conversation.buildRequest`（生产无调用，只有 PromptPipelineTest 用它）；`Conversation.buildRequest()` 无参版与 `setTools` 只有测试用（Agent 实际走 `buildRequest(tools, reminder)` 且自带工具列表）；`TurnCollector.usage()` 生产从未读取（用量经 UsageEvent 流动）。

**为什么是问题**：四段「为未来章节预留」的入口（PromptPipeline 的 javadoc 明说「后续章节来源在此插拔」）目前都是空转层，增加理解成本且让 grep 出多处 buildRequest 入口分不清哪个是生产路径；尤其是 Conversation 里存了 tools 字段但请求从不使用它，状态冗余。

**修复建议**：删除或真实接通（若下一章确实要加项目指令来源，届时再启用 PromptPipeline 也不迟）；`Conversation.tools` 字段与无参 buildRequest 一并清理。

### 17. tee 诊断不对称：anthropic 协议下 acode-sse.log 不写，与 config.yaml 文档承诺不符

**位置**：`src/main/java/com/acode/ConversationController.java:162-168`（tee 只传给 OpenAiProvider）、`src/main/java/com/acode/provider/openai/OpenAiProvider.java:75-86`（sseDiag）、`src/main/resources/config.yaml:28-31`（承诺「原始 SSE … 记录到 acode-sse.log」）

**问题描述**：`buildProvider` 里 `new AnthropicProvider(baseUrl, apiKey)` 不接收 tee 开关，SSE 诊断日志只有 OpenAI 实现有。config.yaml 的注释却承诺三种日志都会产生。

**为什么是问题**：用户在 anthropic 协议下开 tee 排查时发现 acode-sse.log 不出现，以为是功能坏了；诊断功能与文档脱节。

**修复建议**：把 teeEnabled 传给 AnthropicProvider（sseDiag 逻辑可抽到公共类，顺带消除与 StreamPrinter.diag 的重复，见第 18 条）。

### 18. 诊断日志与遗留产物污染仓库根

**位置**：`ConversationController.java:508`（acode-terminal.log）、`StreamPrinter.java:161`（acode-streamprinter.log）、`OpenAiProvider.java:81`（acode-sse.log），均相对 CWD 写文件

**问题描述**：tee 日志按「启动目录」落盘，在 git 仓库里运行就会在仓库根产生诊断文件（`*.log` 已被 .gitignore 覆盖，但仓库根目前残留 `acode-sse.txt`、`acode-streamprinter.txt`、`acode-tee.txt`、`hello_dijkstra.exe` 四个未忽略产物——后三者来自手测/早期版本，`.txt` 后缀不在 .gitignore 中）。

**为什么是问题**：仓库根成为垃圾场，未忽略的 .txt 每次 git status 都在刷存在感，hello_dijkstra.exe 是不该进仓库的二进制。未来 .log 变 .txt 或换后缀同样漏网。

**修复建议**：日志统一写到 `.acode/logs/`（.acode 已在 .gitignore 中）或系统临时目录；把 `*.txt` 加入 .gitignore 或直接删除 `acode-*.txt`、`hello_dijkstra.exe` 残留。

### 19. splitLines 逻辑三处重复

**位置**：`EditFileTool.java:119-132`、`WriteFileTool.java:101-114`、`StreamPrinter.java:182-189`

**问题描述**：三个类各自实现「按 \n 拆行、剥 \r、去尾空段」的逻辑，行为细节略有差异（EditFile/WriteFile 保留末尾空段处理不同）。

**为什么是问题**：同一文本处理约定分散三处，修一处不修另两处就会产生行为漂移（当前 EditFile 与 WriteFile 的拆分语义就已有细微差别）。

**修复建议**：抽一个 `TextLines.split(String)` 工具方法（放 tool.impl 或公共 util），三处共用。

### 20. 「五层防线」与八步检查点的注释计数错位

**位置**：`src/main/java/com/acode/permission/PermissionChecker.java:13-16`（javadoc 说「五层防线决策链」后列 ①-⑧）

**问题描述**：实现 javadoc 与 docs/ch06/spec.md:17 都称「五层防线」，但枚举了 8 个检查点（内容提取/危险命令/安全命令/沙箱/plan 例外/规则/始终允许/模式矩阵）。

**为什么是问题**：安全模型是「纵深防御」叙事，五层 vs 八步的计数不一致让读者无法判断哪些是「层」哪些是「步」，影响对新工具该过哪几道防线的判断（与第 8 条耦合）。

**修复建议**：统一为「八步决策链」或明确「五层 = 黑名单/沙箱/规则/模式/HITL，其中前四层展开为 8 个检查点」的说法。

### 21. thinking 开关硬编码为 protocol 名

**位置**：`src/main/java/com/acode/ConversationController.java:141`（`boolean thinking = "anthropic".equals(config.getProtocol())`）

**问题描述**：是否开启 extended thinking 由协议字符串决定，而不是配置项；AnthropicProvider 的预算还硬编码 `Math.max(1024, Math.min(2048, maxTokens / 2))`（AnthropicProvider.java:66-67）。

**为什么是问题**：协议与能力硬绑定，将来 openai 也支持 thinking 或 anthropic 用户想关掉它都无从下手；预算魔法数 [1024, 2048] 未配置化。

**修复建议**：加 `thinking: true/false` 配置项（缺省按协议推断），预算算法保留但注明依据。

### 22. 残留的章节编号注释

**位置**：`src/main/java/com/acode/ui/CommandRouter.java:12`（「T11 补齐 /clear 文案时同步更新」）

**问题描述**：代码注释引用历史任务编号（T11），`ConversationController.java:75-79` 的类 javadoc 也引用「T12 主循环与装配」。

**为什么是问题**：章节制开发结束后，这些编号对后续维护者没有信息量，且文档归档后无法追溯；属于「注释引用不该存在的上下文」。

**修复建议**：清理为描述性注释（保留设计意图，去掉编号）。

### 23. Agent 构造器对共享 ToolRegistry 有注册副作用

**位置**：`src/main/java/com/acode/agent/Agent.java:106-108`、`src/main/java/com/acode/ConversationController.java:144-147`

**问题描述**：ExitPlanMode 的注册发生在**每个 Agent 构造器**里（判空后注册），而 AskUser 注册在 ConversationController 构造器——同一个共享 registry 被两个地方按不同时机修改。

**为什么是问题**：构造器做「幂等补注册」是隐式副作用；将来若并行创建两个 Agent（如多会话），注册不再是幂等安全的；工具集归属被拆散，新增工具时不知道该往 DefaultToolset 还是控制器还是 Agent 里放。

**修复建议**：所有工具（含 AskUser、ExitPlanMode）统一在 ConversationController 装配时注册，Agent 构造器只读 registry。

### 24. 三套线程模型并存且无统一策略

**位置**：`BaseTool.java:26`（静态虚拟线程池，永不关闭）、`Agent.java:305`（每轮 new Thread 平台线程）、`StreamingToolExecutor.java:111`（每批 newVirtualThreadPerTaskExecutor）

**问题描述**：工具执行、流式驱动、并发读分别使用三套不同的线程创建方式，互不共享取消/超时策略。

**为什么是问题**：每个新功能都要重新决定「该用哪种线程」，且取消语义（interrupt 传播）在三套之间行为不一致——这正是问题 3/5 的土壤。

**修复建议**：收敛为一个执行器入口（如应用级虚拟线程 ExecutorService），统一取消与超时包装，至少写进文档作为约定。

---

## 做得好的地方

以下设计值得保持，后续开发不要破坏：

1. **事件驱动的 Agent ↔ UI 解耦**：`AgentEvent` sealed interface + 有界队列（64）+ `putSafe` 背压 + 取消感知（`Confirmation`/`Choice` 的 50ms 轮询应答通道），Agent 不感知 UI，UI 不感知循环内部。扩展新事件只需加 record 分支。

2. **历史一致性纪律**：tool_use/tool_result 严格配对（取消时补「已取消」结果、触顶不执行不入历史、超长截断），且有 `assertNoDanglingToolUses` 测试护航——这是对话型产品最容易烂掉的地方，ACode 做得很扎实。

3. **权限纵深防御**：fail-closed 决策链（沙箱解析失败按拒绝）、拒绝原因契约（权限层只回纯原因、Agent 统一加前缀，杜绝重复前缀）、规则引擎的原子写（临时文件 + ATOMIC_MOVE）与极致容错读、跨层 deny 不可翻转。docs/ch06/spec.md 的「诚实预期」（单层可被绕过、靠叠加）是清醒的安全观。

4. **工具抽象**：BaseTool 统一参数校验/超时/异常兜底 + ParamSpec 声明式生成 schema，具体工具只需声明参数与实现逻辑；ToolResult 的 content/display 分离（回传模型 vs 界面展示）是很实用的设计。

5. **UI 追加式渲染**：StreamPrinter 每行只写一次进原生回滚（可划选、不重绘）、LiveRegionRenderer 只用相对移动 + 清屏（绝不绝对定位）、reflow 重锚定、wcwidth 宽字符折行、`\r` 化解 pending-wrap 幻影行——这套终端渲染纪律让复杂 TUI 保持了可测试性（LiveRegionTerminalSimTest）。

6. **Provider 抽象与错误分类**：ChatProvider 接口 + Anthropic/OpenAI 双实现，RetryPolicy 按异常类型分类重试（瞬时 vs 确定），SSE 解析与 HTTP 分层清晰。

7. **配置三级加载**：classpath 默认 → 全局 → 项目级，逐级覆盖 + 逐级校验带文件路径定位，未知键/类型错误即时报错；内置默认含占位 key 保证启动可进入。

8. **测试质量**：FakeProvider 脚本化编排（delta/toolUse/complete/阻塞回调）、事件相对顺序断言、取消/截断/触顶/权限拒绝/plan 交付等边界路径都有覆盖，测试替身接口（MenuKeySource、confirmAnswerer）注入点小而干净；权限五个组件（DangerousCommandDetector/PathSandbox/RuleEngine/PermissionMode/PermissionRule）各自独立可测。

9. **章节化演进**：每章 spec/tasks/checklist 三文档制 + 功能粒度提交 + manual-test 手测清单，代码与文档共同演进——本次审查能快速对照设计意图，得益于这套流程。
