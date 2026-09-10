# ACode 阶段八：记忆系统 — 任务清单

> 最后更新：2026-09-10
> 依赖关系：`T1→T2`；`T3→T4→T5`；`T4→T6→T7`；`T8→T9`；`T8→T10→T11`；`T2/T7/T9/T11→T12 装配`；`T12→T13`。T4/T5/T6 都改 `Conversation`，T4/T6/T7 都改 `ConversationController`，注意按顺序做避免行号错位。
> 源码依据：ACode 现有实现——本阶段**替换**旧的"一会话一 JSON、退出时整存、用户主目录"会话方案与配套旧格式角色修复逻辑；**复用**阶段七的 `Conversation` 清除钩子/整体估算/原子重建、`CompactExecutor`、`ContextManager`、虚拟线程池。数值口径见 `checklist.md` 顶部「默认值说明」。

## 约定

- 新增包根 `com.acode.memory`：主代码 `src/main/java/com/acode/memory/`，测试 `src/test/java/com/acode/memory/`
- 指令文件加载与展开放既有 `com.acode.prompt` 包；会话改动留在既有 `com.acode.session` 包
- 现有文件行号以 2026-09-10 的 HEAD 为准，改动时先 Read 确认
- 新测试方法名一律英文驼峰（惯例）；随任务写对应测试，可用 `mvn -Dtest=指定类 test` 单跑新增；**全套 `mvn test` 在收尾任务 T13 统一跑**
- 构建环境：`JAVA_HOME=D:\java\jdk21`（默认 jdk17 编不了 release 21）；运行时用 `target/acode.jar` 注意先重打包
- 需要复用的现有设施：假 provider `src/test/java/com/acode/provider/FakeProvider.java`（已支持捕获 `receivedRequests()`，按需扩展为"可注入提取回复"）；临时目录用 JUnit `@TempDir`；并发用 `util/VirtualThreads.POOL`；既有清除钩子 `Conversation.addClearHook`（阶段七引入）
- 与"多任务统一收尾"约定一致：实现过程中可跑新增用例，但**回归全量只跑一次**，在 T13 完成后进行

---

### T1 项目指令文件加载与 `@include` 展开

**目标**：ACODE.md 三层加载就绪，能把臃肿的指令文件按 `@include` 拆开并安全展开，输出一整段可直接进 system 提示的文本。

**影响文件（新建）**
- `prompt/ProjectInstructions.java`（新）— 三层加载：按「项目根/ACODE.md → 项目根/.acode/ACODE.md → ~/.acode/ACODE.md」顺序读取已存在的文件，逐个交给展开器，再按固定分隔线拼接（高优先级在前）；三层都缺失返回空；返回同时携带"跳过了哪些来源/为什么"的告警行列表（供 UI 输出）
- `prompt/InstructionExpander.java`（新）— `@include` 展开：
  - 整行匹配 `@include <路径>`（允许行首空白，行内不得有其他内容），路径相对**当前文件所在目录**解析
  - 固定深度上限 + canonical real path 集合防重复展开（兼防 A↔B 环）
  - 安全边界：**项目级来源**（项目根两处）的引用解析后必须落在项目根内；**用户级来源**（`~/.acode/`）的引用解析后必须落在用户主目录内；越界即拒绝展开（跳过 + 告警行）
  - 目标不存在 / 是目录 / 不可读 → 跳过 + 告警行；单文件超体量上限 → 截断 + 提示
  - 符号链接逃逸：一律按 canonical 真实路径判定（对齐 `PathSandbox` 既有 `toRealPath` 口径）
- `prompt/ProjectInstructionsTest.java` / `prompt/InstructionExpanderTest.java`（新）— 三层顺序与拼接（高优先级在前、缺失层跳过）；`@include` 原地展开；深度超限停止；A↔B 环不无限递归；同一文件被引用两次只展开一次；相对路径基于被引文件所在目录而非项目根；项目级 `../` 逃逸被拒；用户级逃逸出主目录被拒；不可读/不存在/目录跳过且不抛；超体量截断

**依赖**：无

**参考资料**
- 既有 `.acode/` 命名空间先例：`context/SpillStore.java:19`（`<工作目录>/.acode/tool-results`）、`agent/PlanWriter.java:20`（`.acode/plans`）、`config/ConfigLoader.java:24-25`（全局/项目两级配置路径写法）
- 路径安全感先例：`permission/PathSandbox.java:26-37`（`check`）、`:44-46`（`canonical`）、`:62-80`（解析符号链接 + 父目录兜底）
- 数值与文案：见 `checklist.md` 默认值说明

---

### T2 指令与记忆段接入 system 提示（会话启动一次）

**目标**：把项目指令段插进既有七段 system 提示，且保持"会话启动构建一次、会话内字节不变"。

**影响文件（新建 + 修改）**
- `prompt/PromptSections.java`（改）— 新增两个段工厂：项目指令段（优先级 5，位于 Identity(0) 与 Behavior(10) 之间）、记忆索引段（优先级 7，紧随其后）；空内容由 `PromptBuilder` 既有空段过滤逻辑自然丢弃（本任务只加项目指令段，记忆索引段在 T9 启用）
- `prompt/PromptBuilder.java`（改）— `buildSystemPrompt()`（`L36-46`）增加带参重载，接受"项目指令文本"（T9 再扩为也接受记忆索引文本）；**保留无参重载走空段**，存量测试与既有调用不回归
- `ConversationController.java`（改）— `initSessionState()`（`L192-195`）构建 system 提示时传入项目指令文本；把加载产出的告警行输出到终端（`output.appendLine` + `live.appendCommitted`，对齐既有提示写法）
- `prompt/PromptBuilderTest.java`（改）— 断言项目指令段出现在 Identity 之后、Behavior 之前；空指令文本时七段输出与改动前逐字相同（无回归）；非空时含该段内容

**依赖**：T1

**参考资料**
- `PromptBuilder.buildSystemPrompt` L36-46；`PromptSections` 段优先级常量（Identity 0 / Behavior 10 / … / OutputStyle 60，注释声明"interval 10 leaves room for later chapters"）
- 装配点：`ConversationController.initSessionState` L192-195；提示输出写法见 `ConversationController.handleManualCompact` L266-289
- system 提示语义（会话级、字节稳定、不进历史）：`conversation/Conversation.java:34-38`、`:94-97`

---

### T3 JSONL 会话存储核心（格式 + 编解码 + 项目级目录 + 列表）

**目标**：会话以 JSONL 落在项目级目录，一行一条消息；解析容错；列表按最后活跃排序并标记过期。

**影响文件（新建 + 修改 + 删除）**
- `session/SessionEntry.java`（新）— JSONL 行模型（角色 / 内容 / Unix 秒时间戳）：序列化把消息的 `role`/`content` 平铺成一行 JSON 并附 `ts`；反序列化把 `ts` 摘出、其余按既有 `ChatMessage` 规则构造（内容既可纯字符串也可是块数组，复用既有 `ContentBlockListDeserializer`）
- `session/SessionCodec.java`（新）— 逐行编解码：一行一 JSON；**解析失败返回"空"而非抛错**（调用方跳过）；编码一条消息为一行文本（不含换行）
- `session/SessionStore.java`（改）— 目录改为 `<项目根>/.acode/sessions/`（构造器收项目根，替换 `defaultDir()` 的 `user.home` 写法）；扩展名 `.jsonl`；`list()` 改为扫描 `.jsonl` + 逐行读末行时间戳作"最后活跃时间" + 按活跃降序排序 + 超期会话标记；id 分配改为「时间戳 + 随机十六进制后缀」（替换 `nextUniqueName()` 的毫秒自增方案）
- `session/Session.java`（改）— 时间字段语义从"创建毫秒"改为"最后活跃 Unix 秒"；带上过期标记（供列表渲染）；消息列表保持
- **删除**：`SessionStore.repairLegacyRoles`（`L110-136`）及其两个私有判定与 `containsToolUse`/`containsToolResult`——旧格式不再兼容，该修复逻辑失去依据；`save()`（`L47-59`）的"CREATE_NEW 整存"语义随 T4 一并移除
- `session/SessionStoreTest.java`（改）— 重写：JSONL 一行一条、坏行跳过且不影响其余行、全坏行/空文件按空会话、末行时间戳即最后活跃、排序与过期标记、id 格式与同秒多次创建不碰撞；旧用例（整存/覆盖拒绝/旧格式角色修复）删除
- `session/SessionCodecTest.java`（新）— 纯文本行与块数组行往返一致（含 tool_use/tool_result 块）、`ts` 为整型秒、非法 JSON / 缺字段 / `ts` 非整数均返回空、编码结果不含换行

**依赖**：无

**参考资料**
- 现行实现：`session/SessionStore.java:42-44`（目录）、`:47-59`（整存）、`:62-75`（list）、`:90-98`（read）、`:110-136`（旧格式角色修复，删）、`:146-151`（毫秒自增 id，替换）
- `session/Session.java:12-50`（字段与 Jackson getter/setter 风格）
- 消息与块模型：`provider/ChatMessage.java:30-37`（`@JsonCreator`）、`:44-66`（role/blocks/content）；`provider/ContentBlockListDeserializer`（兼容纯字符串与块数组）
- 数值与文案：见 `checklist.md` 默认值说明

---

### T4 活跃会话追加写入（句柄 + 消息写入联动 + 退出路径）

**目标**：会话在内存里是活跃对象、持有文件句柄；每条消息先写盘再更新内存；退出不再整存。

**影响文件（新建 + 修改）**
- `session/SessionRecorder.java`（新）— 活跃会话实例：首次追加时才创建会话文件（空会话不落盘，沿用现行语义）；持有追加写入句柄（UTF-8、逐行 flush）；`append(message)` 先写盘再更新内存，写盘异常只记告警并继续（不中断对话）；`bind(sessionFile)` 把句柄切到某个既有会话文件（T7 恢复续写用）；`rewrite(messages)` 供 T5；`close()` 幂等（退出/EOF/中断都要调）
- `conversation/Conversation.java`（改）— 新增消息写入监听：`addMessage(…)` 与 `addToolResults(…)` 的**所有重载**在写入成功后通知追加监听；`replaceAll`（`L85-88`）通知**重建监听**（不逐条追加，供 T5）；监听器异常被吞掉（记日志），不得让持久化失败打断 Agent 循环——与既有 `clearHooks`（`L41`、`L105-109`、`:112-117`）同一风格
- `session/SessionManager.java`（改）— 去掉"退出时把完整历史整存为新文件"（`saveSession()` `L138-150` 与 `isUnchangedReload()` `L152-167` 一并删）；改为在构造时把 `SessionRecorder` 注册进 `Conversation` 的两个监听；暴露关闭入口
- `CommandProcessor.java`（改）— 两处退出路径（`L65`、`L71`）由 `sessionManager.saveSession()` 改为关闭活跃会话句柄
- `ConversationController.java`（改）— `saveSession()`（`L422-425`）改为关闭句柄；构造处装配项目级会话目录（`L174` 的 `SessionStore` 装配改为传 `projectRoot`）
- `session/SessionRecorderTest.java`（新）— 空会话不创建文件；首条消息创建文件并落一行；第二条追加为第二行（文件不重写）；写盘失败路径不抛且内存仍有该消息；`bind` 后追加进目标文件；`close` 幂等
- `session/SessionManagerTest.java`（改）— 删除整存/未变更不重复存档相关用例，补"消息写入即落盘"断言

**依赖**：T3

**参考资料**
- `Conversation` 写入入口：`addMessage` L51-53 / `addMessage(long,…)` L56-60、`addToolResults` L63-65 / `(long,…)` L68-72、`replaceAll` L85-88、钩子先例 `addClearHook` L105-109 + `clear` L112-117
- `SessionManager` 现状：构造 L34-37、`saveSession` L138-150、`isUnchangedReload` L152-167
- 退出路径：`CommandProcessor.mainLoop` L60-102（`L65`/`L71` 两处）、`ConversationController.saveSession` L422-425、`ConversationController` 构造 L174
- `projectRoot` 字段与注入：`ConversationController.java:138`（默认当前工作目录）、`:343-345`（测试注入）

---

### T5 压缩重建后会话文件整段重写

**目标**：上下文压缩会原地重建整段历史（阶段七），此时追加语义失效——会话文件按重建后历史整段原子重写，崩溃也不会写坏。

**影响文件（修改 + 新建测试）**
- `session/SessionRecorder.java`（改）— 实现 `rewrite(messages)`：写同目录临时文件 → 逐行落盘 → 原子替换目标文件；句柄切到新文件；目标文件尚不存在时（从未追加过）按"首次落盘"处理；重写失败保持原文件不变并记告警（压缩已成功、历史不回退）
- `session/SessionRecorderTest.java`（改）— 重写后文件行数 = 重建后消息数、无旧行残留；重写后继续追加落在重写结果之后；重写失败时原文件逐行不变
- `context/SessionRoundTripTest.java`（改）— 阶段七既有"压缩 → 存会话 → 读回"用例改为在新持久化模型下断言：压缩触发整段重写 → 读回的行数与重建后历史一致、含摘要消息与边界提醒

**依赖**：T4（监听器）、阶段七 `Conversation.replaceAll` + `CompactExecutor`

**参考资料**
- `Conversation.replaceAll` L85-88（原子替换，压缩重建专用）；阶段七重建点 `context/CompactExecutor`、`context/CompactionPlanner`
- 既有往返用例：`src/test/java/com/acode/context/SessionRoundTripTest.java`（阶段七 T9 产出，本任务改写其持久化断言）

---

### T6 恢复四步（解析跳过 → 链截断 → 预算检查 → 时间跨度提示）

**目标**：恢复出的历史一定"可发请求"；长会话不会一恢复就撞墙；久未活跃时 Agent 知道文件可能已过期。

**影响文件（新建 + 修改）**
- `session/SessionLoader.java`（新）— 四步：
  1. 逐行读取（复用 T3 编解码），坏行跳过；
  2. **消息链完整性截断**：从尾部丢弃无结果的工具调用，截断到最后一个「全部 tool_use 都有配对 tool_result」的位置；复用既有配对判定口径（`Conversation.sanitize` 的 id 集合语义）；
  3. **预算检查信号**：算出整体估算并给出"是否已达自动压缩触发点"的判定结果（估算口径复用 `Conversation.estimateContextTokens` 家族，由调用方提供 system/环境已就位的 `Conversation`）；
  4. **时间跨度提示**：末行时间戳距今超过阈值 → 产出一段提醒文本（上次会话时间 + 中间可能有代码变更 + 建议重新读取相关文件），交给调用方按"恢复后首轮的轮次提醒"注入
  - 返回：截断后的消息列表 + 最后活跃时间 + 是否需压缩 + 可选提醒文本；文件名/末行非法时按空会话返回
- `ConversationController.java`（改）— `restoreIfResume()`（`L242-244`）与 `loadSession()`（`L300-306`）改走 `SessionLoader`：清空并**原子替换**历史（不是逐条 add，避免触发逐条追加）、按需触发一次压缩（走 `contextManager().executor().run(true)`，与 `/compact` 同一路径）、把提醒文本挂为恢复后首轮的轮次提醒；加载期间抑制重建监听（否则刚读出的历史会被原地重写一遍），加载完成后再把句柄绑到目标会话文件
- `ExchangeRunner.java`（改）— 支持一次性轮次提醒（恢复后的首轮注入一条 `<system-reminder>`，注入后清除；沿用阶段七"边界提醒/环境快照"的提醒范式与 `SystemReminder` 工具）
- `session/SessionLoaderTest.java`（新）— 坏行跳过；尾部悬空 tool_use 被截断到最后一个完整配对位置、其后的残缺对全部丢弃；完整历史不截断；超过 24h 产出提醒文本、未超过不产出；历史已达触发点 → 判定需要压缩；空文件/全坏行 → 空会话
- `session/SessionResumeTest.java`（新）— 集成（假 Provider + `@TempDir`）：写一个带悬空 tool_use 的会话文件 → 恢复 → 历史无悬空块、请求可发出；超长会话恢复 → 自动压缩一次；久未活跃恢复 → 首轮请求含时间跨度提醒、第二轮不再含

**依赖**：T4（读写模型）

**参考资料**
- 既有配对清洗：`conversation/Conversation.java:203-242`（`sanitize`，公开静态，阶段七摘要重建亦复用它）
- 整体估算与触发点：`Conversation.estimateContextTokens`（`L151-165`）、`context/ContextPolicy`；压缩入口 `ContextManager.executor().run(true)`（手动路径先例见 `ConversationController.handleManualCompact` L265-289）
- 提醒范式：`prompt/SystemReminder.java:17-23`（wrap/environment）、`agent/Agent.java:447-451`（plan 模式轮次提醒注入先例）
- 提示文案：见 `checklist.md` 默认值说明

---

### T7 `/resume` 列表、选择与续写

**目标**：会话列表从新目录读、过期可见、选中后**续写同一文件**。

**影响文件（修改）**
- `session/SessionManager.java`（改）— `selectSession()`（`L72-94`）与 `preview()`（`L96-104`）走新 `SessionStore` 列表（活跃在前、过期标记显示在条目上）；`loadSession()`（`L107-120`）改为走 T6 的恢复路径 + 绑定续写句柄；去掉 `loadedMessages` 字段（`L31-32`）与相关同一性比较
- `ConversationController.java`（改）— 恢复/加载路径与 T6 合并；`restoreIfResume` 无会话时的提示文案沿用
- `ui/CommandRouter.java`（改）— `HELP_TEXT`（`L13-23`）把 `/resume` 说明补为"加载历史会话（↑/↓ 选择，加载后继续写回该会话）"
- `session/SessionManagerTest.java`（改）— 列表条目含过期标记；选择后消息追加进被选会话文件（不新建文件）；无会话时输出既有提示

**依赖**：T6

**参考资料**
- `SessionManager.selectSession` L72-94、`preview` L96-104、`loadSession` L107-120、`appendHistoryMessage` L122-135、`restoreIfResume` L46-66
- 选择菜单与渲染：`ui/SelectionMenu`、`ui/HistoryRenderer.renderHistoryMessage`、`ui/LiveRegionRenderer.appendCommitted`（既有路径，无需改动）
- `ConversationController.selectSession/loadSession` L300-306

---

### T8 记忆存储与索引（目录 + 一记忆一文件 + MEMORY.md 截断）

**目标**：四类记忆能落盘、能被索引、索引超限能截断并警告。

**影响文件（新建）**
- `memory/MemoryType.java`（新）— 四类枚举（用户偏好 / 纠正反馈 / 项目知识 / 参考信息），每类带"归属根（用户级/项目级）"；提供"项目级在前、用户级在后"的固定顺序（与注入顺序同源）
- `memory/MemoryScope.java`（新）— 两个记忆根（用户级 / 项目级）的路径解析与"目标路径必须落在本根内"校验（canonical 判定，对齐既有沙箱口径）
- `memory/MemoryFile.java`（新）— 单条记忆的读写：结构化头部（名称/描述/类型）解析与生成、正文读写；头部残缺或类型非法的文件在列表时跳过并告警（不抛错）；文件名白名单化（`type-slug` 形态，非法字符一律拒绝，防路径穿越）
- `memory/MemoryStore.java`（新）— 组合两个根：列出全部记忆（按类型分组、项目级在前）、按名读取、创建/更新/删除一条记忆、重建 `MEMORY.md` 索引行、加载索引文本（行数与体积双上限，超限截断 + 警告行；悬空指针行在加载时忽略并告警）
- `memory/MemoryStoreTest.java` / `memory/MemoryFileTest.java` / `memory/MemoryScopeTest.java`（新）— 四类归到正确根；一记忆一文件往返；头部残缺文件被跳过；文件名含 `../` / 绝对路径 / 非法字符被拒；索引行格式正确；索引超行数上限、超体积上限分别截断并附警告行；悬空指针忽略；项目级根不可写时降级只写用户级且不抛

**依赖**：无

**参考资料**
- 命名空间与路径防穿越先例：`context/SpillStore.java`（幂等落盘 + 工作目录下 `.acode` 命名空间）、`permission/PathSandbox.java:26-46`
- 文档格式（结构化头部 + 正文 Why/How）对齐本仓既有记忆写法：`C:\Users\liuch\.claude\projects\D--code-claude-ACode\memory\*.md` 的 frontmatter 形态（`name`/`description`/`type` + 正文 Why/How）
- 数值与文案：见 `checklist.md` 默认值说明

---

### T9 记忆索引注入 system 提示 + `memory_auto` 配置

**目标**：索引与 ACODE.md 走同一条注入管线（会话启动一次、会话内不刷新）；自动提取可配置开关。

**影响文件（修改）**
- `prompt/PromptSections.java` / `prompt/PromptBuilder.java`（改）— 启用 T2 预留的记忆索引段（优先级 7）：内容为"项目级索引 + 用户级索引"依次拼接（与 ACODE.md「项目级在前」同序），空则整段丢弃
- `memory/MemoryManager.java`（新）— 记忆装配门面：持有两个 `MemoryScope` 与 `MemoryStore`，提供"索引注入文本"（会话启动构建一次）、`extractNow()`（T11 手动用）、`runAsyncExtraction(...)`（T10 自动用）、`reset()`（配合清除钩子）
- `config/AppConfig.java`（改）— 新增 `memoryAuto`（`Boolean`，null 视为默认开）
- `config/ConfigLoader.java` / `config/ConfigValidator.java`（改）— `memory_auto` 映射与默认值（缺省不报错）；`src/main/resources/config.yaml` 补该配置项注释，并**顺手修正 `max_context_tokens` 的过期注释**（现写"超过后自动丢弃最早消息"，阶段七已删除该行为）
- `ConversationController.java`（改）— `initSessionState()`（`L192-195`）把索引文本一并传入 system 提示构建；装配 `MemoryManager`；把"记忆根不可写/索引截断"类告警输出到终端
- `prompt/PromptBuilderTest.java`（改）/ `config/ConfigLoaderTest.java`（改）— 索引段位置与拼接顺序（项目级在前）、空索引不出段；`memory_auto` 缺省为开、显式 false 为关

**依赖**：T8、T2

**参考资料**
- 段装配：`prompt/PromptBuilder.java:36-46`、`prompt/PromptSections.java`（优先级区间与空段过滤）
- 配置三级加载：`config/ConfigLoader.java:18-30`（内置 → 全局 → 项目，逐级覆盖出现字段）；`config/AppConfig.java:9-20`（字段与 getter 风格）；`config/ConfigValidator.java:12`（默认值常量化先例）
- 注入语义（会话启动一次、字节稳定）：`Conversation.java:34-38`、`:94-97`

---

### T10 自动记忆提取（每轮结束异步、独立调用、结构化写回）

**目标**：每轮 Agent 循环结束后后台回顾本轮对话，按四类决定创建/更新/删除，写入记忆并同步索引。

**影响文件（新建 + 修改）**
- `memory/MemoryExtractionPrompt.java`（新）— 提取指令：要求分析"索引 + 现有记忆清单 + 最近一轮对话"，按四类分别决定创建/更新/删除，明确"已有同义记忆不要重复创建""没有值得记忆的内容就什么都不做"；要求输出结构化操作列表（每项含操作类型/类别/名称/描述/正文），只输出结构、不调工具
- `memory/MemoryExtractor.java`（新）— 独立模型调用（**不携带工具、`thinking` 关闭、独立 `max_tokens`、直接经请求构造器**，不经 `Conversation` 的历史组装，避免被裁剪/注入轮次提醒），收集完整回复 → 解析结构化操作列表 → 逐项落到 `MemoryStore` 并重建索引；解析失败即整轮放弃（不写任何文件）；单项落盘失败只跳过该项并记日志
- `memory/MemoryExtractionScheduler.java`（新）— 调度：同时最多一个任务（用单线程虚拟线程执行器或"运行中"标志）；任务运行中再有触发则跳过本轮；任务执行前记下会话代次，写回前校验代次（已被 `/clear`/加载会话作废的那一轮结果丢弃）；提取失败只记日志、不向用户报错；`reset()` 清运行态
- `memory/MemoryManager.java`（改）— `runAsyncExtraction` 接上调度器；提供"最近一轮对话"切片（从最后一条 user 消息到历史末尾）
- `agent/Agent.java`（改）— 在 `loop()`（`L182-227`）的 `NORMAL_END` 分支（`L191-196`，即"模型给出最终回复、不再调用工具"那一刻）触发一次异步提取；未装配记忆管理器时跳过
- `memory/MemoryExtractorTest.java` / `memory/MemoryExtractionSchedulerTest.java`（新）— 假 Provider 返回合法结构化列表 → 记忆文件与索引被正确写入（用户级/项目级各一例）；返回非法结构 → 零文件写入；创建/更新/删除三类操作分别生效且索引同步；提取请求断言（tools 为空、thinking=false、max_tokens 等于独立常量）；任务运行中再次触发 → 本轮跳过（调用计数为 1）；代次失效 → 结果不写；提取抛异常 → 不影响调用方、不产生用户可见错误

**依赖**：T8

**参考资料**
- 独立调用范式（阶段七同款）：`context/CompactExecutor`（摘要请求直接经 `ChatRequest.builder` 构造）、`provider/ChatRequest.java:56-106`（builder；不设置 tools 即不带工具）
- 循环结束点：`agent/Agent.java:182-227`（`loop`）、`:191-196`（`NORMAL_END` → `LoopComplete`）
- 虚拟线程池：`util/VirtualThreads.java`（`POOL`，应用级共享、永不关闭）
- 假 Provider 捕获请求：`src/test/java/com/acode/provider/FakeProvider.java:51`（`receivedRequests`）、`:78`（`streamChat`）、`:115`（访问器）

---

### T11 `/memory` 命令（列出 + 手动提取）

**目标**：用户能看见记忆现状，并在关掉自动提取后仍能手动跑一次。

**影响文件（修改）**
- `ui/CommandRouter.java`（改）— `Action` 枚举（`L10`）增 `MEMORY`；`HELP_TEXT`（`L13-23`）补 `/memory` 行；`route`（`L31-52`）增 `/memory` 与 `/memory run` 分支（带参数的命令按既有 `/permission-mode` 前缀匹配写法处理）
- `CommandProcessor.java`（改）— `mainLoop` switch（`L68-102`）加 `MEMORY` 分支：无参数输出两级记忆统计与索引状态（各根记忆条数、索引行数/体积、是否被截断）；`/memory run` 同步跑一次提取并输出结果行（新增/更新/删除条数，或"没有值得记忆的内容"、或失败原因）
- `ConversationController.java`（改）— 装配处注入记忆处理回调（对齐既有 `setCompactHandler` 的注入风格）
- `ui/CommandRouterTest.java`（改）/ `memory/MemoryCommandTest.java`（新）— `/memory` → `MEMORY`、`/memory run` 路由正确、`HELP_TEXT` 含 `/memory`；无记忆时输出零计数；手动提取后记忆与索引被写入且输出含条数；`memory_auto` 关闭时手动提取仍可用

**依赖**：T10

**参考资料**
- 命令扩展先例：`ui/CommandRouter.java:10`（枚举）、`:39-41`（带参前缀匹配）、`:42-51`（switch）；`CommandProcessor.handlePermissionMode` L109-129（命令输出写法）
- 回调注入先例：`CommandProcessor.setCompactHandler` L49-54、`ConversationController.commandProcessor()` L246-255
- 手动压缩的输出范式：`ConversationController.handleManualCompact` L265-289

---

### T12 接入主流程与状态一致

**目标**：三块能力在真实启动路径上串起来；历史重置点联动重置；文档补齐。

**影响文件（修改）**
- `ConversationController.java`（改）— 装配顺序与生命周期收敛：构造处装配项目级会话目录、`SessionRecorder`、`MemoryManager`；`start()`（`L205-224`）`finally` 里关闭会话句柄（与既有 `closeMcpManager()` 同处）；把记忆/会话的运行态重置挂进既有 `conversation.addClearHook`（`L178`）——`/clear` 与加载会话时联动复位提取调度与运行态；`initSessionState` 在恢复会话后再构建 system 提示（保证注入的是恢复后状态）
- `Conversation.java`（改，视需要）— 若 `clear()` 需要同时通知会话侧（而非只通知记忆侧），在钩子内一并处理，不改既有钩子签名
- `docs/manual-test.md`（改）— 追加「阶段八」手测小节（空 ⚑ 项，T13 填）
- `README.md`（改）— 简述三层 ACODE.md、`@include`、项目级会话池、四类记忆与 `/memory` 命令；提示 `.acode/sessions/` 建议加入项目 `.gitignore`（仅文档说明，不自动改用户文件）
- `session/SessionLifecycleTest.java`（新）— 集成（假 Provider + `@TempDir`）：启动 → 对话若干轮 → 退出关闭 → 重开读回；`/clear` 后新一轮仍追加进同一会话文件；加载另一会话后续写落在该文件

**依赖**：T2、T7、T9、T11

**参考资料**
- 装配与生命周期：`ConversationController` 构造 L154-180、`start()` L205-224、`closeMcpManager()` L226-231、`conversation.addClearHook` L178、`contextManager()` L258-263
- 既有清除钩子语义：`Conversation.addClearHook` L105-109、`clear` L112-117（阶段七 `/clear`、加载会话联动重置冻结/熔断即走此路）
- 文档先例：`docs/manual-test.md`（阶段六/七小节形态）

---

### T13 端到端验证 ⚑ 与统一收尾回归

**目标**：全链路验收：ACODE.md 展开 → 注入 → 会话追加/恢复续写 → 记忆提取 → 索引注入；收尾统一回归。

**影响文件（新建 + 修改）**
- `memory/MemorySystemEndToEndTest.java`（新）— 假 Provider + 真链路（`@TempDir` 项目根 + 隔离的用户级根）：
  1. 放好三层 ACODE.md（含一个 `@include` 与一个越界引用）→ 构建的 system 提示含展开内容、越界被跳过、无异常
  2. 对话若干轮 → 会话文件按行增长；压缩一次 → 文件被整段重写为重建后历史
  3. 退出 → 重开恢复 → 历史一致、可续写、会话文件不新增
  4. 恢复一个带悬空工具调用的会话文件 → 历史被截断到最后一个完整配对、请求可发出
  5. 一轮结束后触发异步提取 → 记忆文件与索引被写入；下个会话启动时索引出现在 system 提示里
- `docs/manual-test.md`（改）— 填阶段八 ⚑ 手测勾选
- **统一收尾**：`JAVA_HOME="D:\java\jdk21" mvn test` 全套全绿，记录总用例数（新增用例均本地假 Provider，无外部网络）

**依赖**：T12

**参考资料**
- 端到端测试先例：`docs/ch07/tasks.md` T10（`ContextManagementEndToEndTest` 的假 Provider + `@TempDir` 写法）、`docs/ch06/checklist.md` T9
- 断言口径全部对齐 `checklist.md` 顶部「默认值说明」
