# ACode 阶段二：工具调用 — 任务清单

> 最后更新：2026-08-13
> 依赖关系：`T1→T3→(T4,T5,T6,T7)→T10`；`T2→T8→T9→T10→T11→T12→T13→T14`；T2 与 T1/T3 可并行，T4~T7 相互独立可并行，T11 与 T12 顺序执行（T12 复用 T11 的展示）。

## 约定

- 包根 `com.acode`，新增工具层 `src/main/java/com/acode/tool/`（框架）、`src/main/java/com/acode/tool/impl/`（六个具体工具），测试 `src/test/java/com/acode/tool/`
- 现有消息/编排/UI 文件行号以 2026-08-13 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn compile` 或 `mvn test` 确认不破坏已有代码；测试方法名用英文驼峰
- 需要复用的现有设施：`ProviderHttpClient`（HTTP）、`SseParser`（帧解析）、`Conversation.estimateTokens`（字符估算）、`FakeProvider`（测试桩）、JUnit `@TempDir`（文件工具测试）

---

### T1 工具框架核心

**目标**：统一的工具接口 + 基类 + 结果模型，六个工具共享同一契约。

**影响文件（新建）**
- `src/main/java/com/acode/tool/Tool.java` — 接口：名称、描述、权限级别（read/write/exec 元信息）、参数 JSON Schema、执行方法（入参为解析后的参数 + 执行上下文）
- `src/main/java/com/acode/tool/ToolResult.java` — 执行结果：正文输出、是否成功、是否错误、错误信息（失败与超时统一带 is_error 标记，不抛给上层）
- `src/main/java/com/acode/tool/ToolContext.java` — 执行上下文：工作目录（相对路径的基准）、可选注入
- `src/main/java/com/acode/tool/BaseTool.java` — 抽象基类：参数解析/校验（缺失/类型错 → 返回失败结果并带参数名）、执行超时包装、运行时异常 → 失败结果
- `src/main/java/com/acode/tool/ToolExecutionException.java` — 供 BaseTool 内部包装，不跨层抛出
- `src/test/java/com/acode/tool/BaseToolTest.java` — 参数缺失/类型错返回失败结果、正常执行返回成功、内部异常转失败

**依赖**：无

**参考资料**
- 参考 ch02 T3 Provider 接口「先定义接口 + 测试桩」的做法：`src/main/java/com/acode/provider/ChatProvider.java`
- 权限元信息：参考 Claude Code 工具分级语义（只读 / 写 / 命令执行），本章仅标记不拦截

---

### T2 消息模型结构化

**目标**：`ChatMessage` 从纯文本升级为 content block 列表（text / tool_use / tool_result），Anthropic 工具协议与后续章节的地基。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/provider/ContentBlock.java`（新）— sealed 接口，三个实现
- `src/main/java/com/acode/provider/TextBlock.java`（新）— 正文文本
- `src/main/java/com/acode/provider/ToolUseBlock.java`（新）— 模型发起：id、工具名、参数 JSON
- `src/main/java/com/acode/provider/ToolResultBlock.java`（新）— 回传：tool_use_id、内容、是否错误
- `src/main/java/com/acode/provider/ChatMessage.java`（改）— record 改为持 `List<ContentBlock>`，保留 `of(Role, String)` 工厂（内部包 TextBlock）保证阶段一代码零改动；Jackson 多态序列化（按 type 字段）
- `src/main/java/com/acode/conversation/Conversation.java`（改）— `estimateTokens` 遍历所有 block（tool_use 参数、tool_result 内容都要估）
- `src/test/java/com/acode/provider/ChatMessageTest.java`（新）— `of()` 兼容、含 tool_use/tool_result 的消息 Jackson 往返一致

**依赖**：无

**参考资料**
- Anthropic content block 结构（text/tool_use/tool_result）：https://docs.anthropic.com/en/api/messages
- Jackson 多态序列化 `@JsonTypeInfo`（按显式 type 字段），现有 `ObjectMapper` 统一在工具内配置
- `SessionStore` 目前按字段直接序列化 `ChatMessage`，T2 改完需跑 `mvn test` 确认 `SessionStoreTest` 不破

---

### T3 工具注册中心

**目标**：集中管理工具注册/启用/禁用/查询，并转换为 Anthropic tools 参数格式。

**影响文件（新建）**
- `src/main/java/com/acode/tool/ToolRegistry.java` — register / enable / disable / get / list；未注册或已禁用时调用方返回错误
- `src/main/java/com/acode/tool/ToolSchemaConverter.java` — 单个 Tool → Anthropic tools JSON 节点（name / description / input_schema）
- `src/main/java/com/acode/tool/DefaultToolset.java` — 组装六个内置工具的注册入口（后续任务逐个填充）
- `src/test/java/com/acode/tool/ToolRegistryTest.java` — 注册/禁用/转格式断言

**依赖**：T1

**参考资料**
- Anthropic tools 参数格式（`tools` 数组、`input_schema` JSON Schema）：https://docs.anthropic.com/en/docs/build-with-claude/tool-use
- `input_schema` 每个工具自己定义（在 Tool 的 inputSchema 里声明），转换器只做包装

---

### T4 文件读写工具

**目标**：ReadFile / WriteFile 两个文件工具可用，均基于 ToolContext 工作目录解析相对路径。

**影响文件（新建）**
- `src/main/java/com/acode/tool/impl/ReadFileTool.java` — 读文本文件：支持限定行范围；超长文件按上限截断并附提示
- `src/main/java/com/acode/tool/impl/WriteFileTool.java` — 覆盖写整个文件：缺失父目录自动创建；写入前备份父目录是否存在校验
- `src/test/java/com/acode/tool/impl/ReadFileToolTest.java`、`WriteFileToolTest.java` — 用 `@TempDir`：正常读、读不存在文件返回失败、大文件截断、写后磁盘内容一致、自动建目录

**依赖**：T1、T3（注册进 DefaultToolset）

**参考资料**
- `java.nio.file.Files`（readString / writeString / createDirectories）；路径解析：相对路径基于 `ToolContext` 工作目录，绝对路径直接用
- 行范围/截断上限的具体值进 checklist（T4 节），实现先取可配置常量

---

### T5 多段编辑工具

**目标**：EditFile 一次调用做多段精确替换，整体原子性。

**影响文件（新建）**
- `src/main/java/com/acode/tool/impl/EditFileTool.java` — 入参含文件路径 + 多个替换段（每段 old/new）；所有段必须各自恰好匹配一处，任一不匹配或匹配不唯一 → 整体失败、文件字节不变；全部匹配后按段顺序一次写回
- `src/test/java/com/acode/tool/impl/EditFileToolTest.java` — 多段一次成功、一段不匹配整体失败且文件未变、old 出现多处报不唯一、替换后新旧内容正确

**依赖**：T1、T3

**参考资料**
- 语义对齐 Claude Code Edit：old_string 精确匹配、重复匹配拒绝：https://docs.anthropic.com/en/docs/claude-code/（Edit 工具行为，参考其幂等与原子性）
- 实现要点：先读全文 → 对每段查找（第 1 次出现 → 替换；查找到 >1 次 → 失败）→ 全部通过才写回，保证原子性

---

### T6 搜索工具

**目标**：Glob 匹配文件路径、Grep 正则搜内容，模型能定位代码。

**影响文件（新建）**
- `src/main/java/com/acode/tool/impl/GlobTool.java` — 按模式递归匹配路径（支持 `**`），返回匹配的路径列表
- `src/main/java/com/acode/tool/impl/GrepTool.java` — 按正则搜索文件内容：可限目录、可限文件名匹配；返回命中的路径 + 行号 + 行内容
- `src/test/java/com/acode/tool/impl/GlobToolTest.java`、`GrepToolTest.java` — 临时目录造数据：`**/*.java` 命中、正则命中行含行号、无命中返回空、忽略目录跳过

**依赖**：T1、T3

**参考资料**
- 递归遍历用 `Files.walk`；glob 匹配用 `FileSystems.getDefault().getPathMatcher("glob:"+pattern)`（对 `**` 的支持依赖 matcher 行为，需测）
- Grep 按行读取用 `Files.lines`，正则用 `Pattern`/`Matcher`，命中行数上限进 checklist（T6 节）

---

### T7 命令执行工具

**目标**：Bash 工具执行 shell 命令，Windows 上优先 Git Bash 回退 cmd，带超时与输出截断。

**影响文件（新建）**
- `src/main/java/com/acode/tool/impl/BashTool.java` — 拼 shell 调用（Git Bash：`bash -lc <cmd>`；cmd：`cmd /c <cmd>`）、进程启动、超时杀进程（`destroyForcibly`）、stdout+stderr 合并、超长输出截断
- `src/main/java/com/acode/tool/impl/ShellDetector.java` — 运行时检测：`where bash` / 常见安装路径，命中 Git Bash 则用，否则回退系统默认
- `src/test/java/com/acode/tool/impl/BashToolTest.java` — `echo` 输出正确回传、`sleep` 配短超时被杀返回超时错误、超长输出截断、shell 检测结果可用

**依赖**：T1、T3

**参考资料**
- `java.lang.ProcessBuilder`（重定向到临时收集）、`Process.destroyForcibly()`（超时终止）、`readAllBytes` 上限
- Windows 上 Git Bash 常见路径：`C:\Program Files\Git\bin\bash.exe`；检测顺序：环境变量 → 常见路径，找不到回退
- 超时与截断具体值进 checklist（T7 节），实现先取可配置常量

---

### T8 Anthropic 请求侧

**目标**：请求体携带工具定义，消息 content 输出为结构化 block 数组。

**影响文件（修改 + 新建）**
- `src/main/java/com/acode/provider/ChatRequest.java`（改）— 增加可选 tools 字段（List<Tool> 或已序列化 schema），builder 对应
- `src/main/java/com/acode/provider/anthropic/AnthropicProvider.java`（改）— `buildBody()`（当前 57~94 行）：messages 的 content 由字符串改为 block 数组（text 直接出文本、tool_use 出 id/name/input、tool_result 走 user 消息的 tool_result block）；根节点在有 tools 时加 `tools` 数组
- `src/test/java/com/acode/provider/anthropic/AnthropicProviderTest.java`（改）— 断言：请求 JSON 含 `tools` 数组且每条含 name/description/input_schema；含 tool_use 的 assistant 消息 content 为数组；含 tool_result 的 user 消息 content 含 `tool_result` 块

**依赖**：T2、T3

**参考资料**
- Anthropic messages API：tool_use 在 assistant content 数组、tool_result 在 user content 数组（`{"type":"tool_result","tool_use_id":..,"content":..,"is_error":..}`）：https://docs.anthropic.com/en/api/messages
- `buildBody` 现有实现已把 SYSTEM role 收进根 system 字段（72~77 行），改造时保留该逻辑

---

### T9 Anthropic 响应侧

**目标**：流式解析 tool_use 块，JSON 参数碎片逐段拼接成完整 JSON。

**影响文件（修改 + 新建）**
- `src/main/java/com/acode/provider/ChatListener.java`（改）— 增加 tool_use 完成回调（携带 id/name/完整参数），与 onDelta/onComplete 互斥关系重新说明（onDelta 与 tool_use 可交替）
- `src/main/java/com/acode/provider/anthropic/AnthropicSseParser.java`（改）— `handle()`（当前 24~48 行）：
  - `content_block_start`：`type=tool_use` → 记录 id/name，开始累积参数碎片；`type=text` → 进入文本模式
  - `content_block_delta`：`input_json_delta` 拼接进当前 tool_use 的参数缓冲；`text_delta` 走原 onDelta
  - `content_block_stop`：tool_use 块结束 → 把拼接的 JSON 字符串解析为参数，触发新回调
- `src/test/java/com/acode/provider/anthropic/AnthropicSseParserTest.java`（改）— 新增录制片段：一个 tool_use 的参数跨多次 `input_json_delta` 碎片，拼接后与完整 JSON 一致；thinking 与 tool_use 混排不串块

**依赖**：T8

**参考资料**
- Anthropic 流式事件（content_block_start / content_block_delta.input_json_delta / content_block_stop）：https://docs.anthropic.com/en/api/messages-streaming
- 参数碎片用 `StringBuilder` 累积，`content_block_stop` 时用 `ObjectMapper.readTree` 校验可解析；解析失败按协议错误处理

---

### T10 工具执行与回传

**目标**：把 tool_use block 变成真实执行并构建 tool_result 回传消息。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/tool/ToolExecutor.java`（新）— 输入 ToolUseBlock → 查注册表（未注册/已禁用 → 构造错误结果）→ 带超时执行 → ToolResult；一个会话内多次调用串行
- `src/main/java/com/acode/conversation/Conversation.java`（改）— 增加「追加 assistant 工具调用 + 对应 tool_result 用户消息」的便捷入口（工具结果进入完整历史，供下一轮请求携带）
- `src/test/java/com/acode/tool/ToolExecutorTest.java`（新）— FakeRegistry：注册的工具被执行、未注册返回错误、失败工具返回 is_error

**依赖**：T2、T3、T4~T7、T9

**参考资料**
- tool_result 回传格式见 T8 参考资料；ToolExecutor 不感知 UI/Provider，只做「block → 结果」
- 工具结果截断：回传给模型前与进入历史前都截断超长文本（长度上限进 checklist T10 节）

---

### T11 UI 工具调用展示

**目标**：终端可视化工具调用过程：名称、参数摘要、状态、结果摘要。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/ui/ToolCallDisplay.java`（新）— 在输出区渲染工具卡片：`▸ 工具名(参数摘要)` 一行 + 状态（进行中/成功/失败）+ 结果摘要（多行截断为前几行，带折叠标记）
- `src/main/java/com/acode/ui/StreamPrinter.java`（改）— 适配新的 tool_use 回调：把文本渲染与工具卡片渲染串接（文本继续走 MarkdownRenderer）
- `src/test/java/com/acode/ui/ToolCallDisplayTest.java`（新）— 给定 tool_use/结果，输出区出现工具名与状态字样，结果被截断

**依赖**：T9、T10

**参考资料**
- 参考现有 `StreamPrinter.replaceTail`（48~59 行）「删除尾巴再重绘」的模式，工具卡片需要稳定占用若干行（不随流式文本抖动）
- 卡片行数/截断行数具体值进 checklist（T11 节）

---

### T12 接入主流程（单步闭环）

**目标**：完整单步闭环，ACode 首次「能干活」。

**影响文件（修改 + 新建）**
- `src/main/java/com/acode/ConversationController.java`（改）— `handleChat()`（当前 311~388 行）重构：
  1. 请求①携带工具定义（从注册中心转出）流式发起
  2. 无 tool_use → 文本直接展示（回归阶段一）；有 tool_use → 经 ToolCallDisplay 展示 → ToolExecutor 串行执行 → 结果追加进历史 → 请求②（历史含 tool_result）→ 展示最终文本并保存
  3. 请求②仍返回 tool_use → 不执行，仅取文本展示并提示「连环调用未支持」
  4. 每轮请求独立绑定一次回复流，Ctrl+C 中断当前流；工具执行阶段 Ctrl+C 同样可中断
- `src/test/java/com/acode/ConversationControllerTest.java`（新）— 用 FakeProvider 模拟两轮：第一轮返回 tool_use（真实触发 ReadFileTool 执行）、第二轮返回最终文本 → 断言会话历史含 tool_use 与 tool_result、最终文本已展示

**依赖**：T9、T10、T11

**参考资料**
- 现有 handleChat 的 worker 线程 + watch loop 模式（352~387 行）保留；两次请求各走一次该模式
- 会话保存沿用 `saveSession()`（404~413 行），工具调用与结果随历史一起落盘（落盘细节 T13）

---

### T13 会话持久化与上下文适配

**目标**：含工具块的会话可保存/恢复，上下文截断覆盖结构化消息。

**影响文件（修改）**
- `src/main/java/com/acode/session/Session.java`、`SessionStore.java` — 序列化/反序列化支持 content block 消息（随 T2 的消息模型，落盘格式含 type 字段）
- `src/main/java/com/acode/conversation/Conversation.java`（改）— `trim()` 对 tool_use/tool_result block 按内容估算；超长工具结果在进入历史前先截断（T10 复用同一上限）
- `src/main/java/com/acode/ConversationController.java`（改）— `restoreIfResume`（101~120 行）与 `loadSession`（292~309 行）：工具块以一行摘要展示（如「〔工具调用 ReadFile〕」），文本块照常显示

**依赖**：T2、T12

**参考资料**
- 现有会话文件为消息列表 JSON，T2 的多态序列化保证向后兼容（旧纯文本会话仍能读）
- 恢复时「工具块显示摘要」与 ch02「菜单用输出区尾部块」一致：不污染主文本，用一行摘要即可

---

### T14 端到端验证

**目标**：真实 API 验证单步闭环可用，六种工具都被模型真实调用过。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md`（新）— 手测步骤：真实 key 下分别让模型调用每种工具（读/写/改/执行/匹配/搜索）、单步闭环、超时、错误工具参数、Ctrl+C 中断、含工具会话的保存/恢复
- 修 bug 产生的影响文件视情况

**依赖**：T12、T13

**参考资料**
- 手测按 checklist.md 逐项打勾；联网问题用临时错误 base_url 模拟（沿用 ch02 T13 做法）
