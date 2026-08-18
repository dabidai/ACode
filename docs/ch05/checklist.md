# ACode 阶段四：Prompt 工程体系 — 验收清单

> 最后更新：2026-08-18
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 默认值说明：七模块名称与优先级 Identity 0 / Behavior 10 / ToolUsage 20 / CodeQuality 30 / Security 40 / TaskPattern 50 / OutputStyle 60（间隔 10）；模块内容为英文；环境快照为 session state、system-reminder 包裹、含 `# Environment` 段落与 8 个字段（Working directory / Platform / Shell / Is git repo / Git branch / Model / Date）、每轮注入 messages 首条且不进历史；system-reminder 标签 `<system-reminder>` 与 `</system-reminder>`，是传输约定而非更高权限通道；Plan Mode 节奏为第 1、6、11…轮完整版（`iteration==1 || (iteration-1)%5==0`）、其余精简版（成本/复杂度 heuristic）；cache_control 为 `{"type":"ephemeral"}`、system 以 text block 数组形式输出、tools 仅最后一个工具带；usage 字段 Anthropic `input_tokens/output_tokens/cache_read_input_tokens/cache_creation_input_tokens`、OpenAI `prompt_tokens/completion_tokens/prompt_tokens_details.cached_tokens`（cacheCreation 恒 0）；usage 脚注行格式 `usage: in <input> · cache_read <n> · cache_write <n> · out <output>`；plan 完整版含「只读」「ExitPlanMode 交付」「.acode/plans/」要点。

## T1 prompt 包骨架：Section + 七模块内容

- [x] `mvn compile` 通过，`com.acode.prompt` 包编译无警告
- [x] 七个工厂方法返回非空 Section：`identitySection/behaviorSection/toolUsageSection/codeQualitySection/securitySection/taskPatternSection/outputStyleSection`
- [x] name 依次为 Identity/Behavior/ToolUsage/CodeQuality/Security/TaskPattern/OutputStyle，priority 依次 0/10/20/30/40/50/60（唯一且递增）
- [x] Identity 模块含两条 IMPORTANT 红线：「不引入安全漏洞（command injection/XSS/SQL 注入等）」「不编造/猜测 URL」
- [x] ToolUsage 模块含六工具映射：ReadFile 优先于 `cat`、EditFile 优先于 `sed`、WriteFile 优先于 `echo`、Glob 优先于 `find`、Grep 优先于 `grep`、Bash 仅无专用工具时用
- [x] CodeQuality 模块含「默认不写注释，仅当 WHY 不明显时加一行（隐藏约束/workaround）」「三行相似代码优于提前抽象」「不做超出需求的功能」
- [x] Security 模块含「破坏性操作前确认」「危险命令（rm -rf / force push / drop table）」「不跳过 git hook」
- [x] TaskPattern 模块含「探索性问题回 2-3 句建议、不主动做有副作用操作（只读探索允许）」「编辑前必须先读」「完成前验证」
- [x] OutputStyle 模块含 `file_path:line_number` 引用格式、无 emoji、结尾 1-2 句总结
- [x] 全部模块内容为英文（`grep -P '[\x{4e00}-\x{9fff}]' PromptSections.java` 零命中）
- [x] PromptBuilder.build()：乱序 add 后按 priority 升序输出；空内容 Section 被过滤；段落之间以两个换行分隔
- [x] PromptSectionsTest / PromptBuilderTest 全绿

## T2 环境收集器 + system-reminder 机制

- [x] @TempDir 下 `git init` 仓库 → isGitRepo=true、gitBranch 非空、不抛异常
- [x] 普通非 git 目录 → isGitRepo=false、gitBranch 空、不抛异常
- [x] SHELL 环境变量为空 → shell 兜底 `"bash"`
- [x] `render` 输出含 8 个字段行（Working directory / Platform / Shell / Is git repo / Git branch / Model / Date）
- [x] `SystemReminder.wrap` 内容形如 `<system-reminder>\n<content>\n</system-reminder>`，role=USER
- [x] `isSystemReminder` 对包裹消息返回 true、对普通 user 消息返回 false
- [x] EnvironmentDetectorTest / SystemReminderTest 全绿

## T3 组装管线：Conversation 改造 + PromptPipeline

- [x] systemPrompt 未设置 → 请求与改前完全一致（存量 buildRequest 用例零改动）
- [x] systemPrompt 设置后 → 请求首条为 SYSTEM 消息、内容等于 systemPrompt；`history()` 不含该消息
- [x] environment 设置后 → 请求 messages 首条为环境 system-reminder（内容含 `# Environment`）；`history()` 不含
- [x] turnReminder 非空 → 位于请求消息**最后一条**（`messages().get(size-1)`）；`history()` 不含
- [x] turnReminder 为 null → 无额外消息
- [x] trim 超限时 system / 环境 / reminder 消息不被丢弃（它们在 trim 之外独立拼接）
- [x] PromptPipeline.assemble 产出「system → 环境 → 历史 → 轮次级」四段顺序，与 conversation.buildRequest 等价
- [x] ConversationTest / PromptPipelineTest 全绿

## T4 工具描述强化

- [x] ReadFileTool description 含「绝对路径」「offset/limit 大文件分段」「优先于 Bash cat」「编辑前必须先读」
- [x] EditFileTool description 含「编辑前必须先 ReadFile」
- [x] WriteFileTool description 含「优先于 Bash echo」（创建/整体重写用本工具）
- [x] BashTool description 含「仅在无专用工具时使用」表述
- [x] GlobTool description 含「优先于 find/ls」
- [x] GrepTool description 含「优先于 grep/rg」
- [x] `mvn test` 全绿（schema 生成相关工具测试无回归）

## T5 cache_control 输出

- [x] buildBody JSON：`system` 为数组、首元素 `type=text`、`text` 等于原字符串、`cache_control.type=ephemeral`
- [x] tools 非空 → **最后一个**工具含 `cache_control`、其余工具不含
- [x] tools 为空 → 无 tools 字段、不抛异常
- [x] OpenAI buildBody 输出无 `cache_control` 字样
- [x] AnthropicProviderTest 更新后全绿（system 数组结构断言 + 末工具 cache_control 断言）

## T6 usage 解析 + 脚注展示

- [x] Anthropic 录制片段：`message_start` 带 `message.usage`（input_tokens=100、cache_read_input_tokens=80、cache_creation_input_tokens=20、output_tokens=1）→ onUsage 收到同值
- [x] Anthropic 片段无缓存字段 → cacheRead/cacheCreation 为 0
- [x] OpenAI 录制片段：usage 块带 `prompt_tokens_details.cached_tokens=50` → onUsage 收到、cacheCreation=0
- [x] `Usage` record 构造/访问器冒烟测试通过
- [x] FakeProvider `usage(Action)` → TurnCollector 发出 `UsageEvent`
- [x] ChatListener 存量实现（无 onUsage 覆写）编译零改动（R1）
- [x] AgentEvent 的 switch 消费点全部补上 UsageEvent 分支（编译通过）

## T7 Plan Mode 改造

- [x] `buildReminder(1)`=FULL、`buildReminder(6)`=FULL、`buildReminder(11)`=FULL（每 5 轮重复）
- [x] `buildReminder(2)`~`buildReminder(5)`、`buildReminder(7)`~`buildReminder(10)`=SPARSE
- [x] FULL/SPARSE 为英文且含「只读」「ExitPlanMode 交付」「.acode/plans/」要点
- [x] plan 模式请求：末条消息为 user 角色、内容含 `<system-reminder>` 与 plan 提醒文本（R2 尾插）
- [x] 请求中无 SYSTEM 角色的 plan 提醒（system 通道只剩七模块 system prompt）
- [x] AgentPlanModeTest 全部用例更新后全绿

## T8 接入主流程

- [x] 新会话：环境快照探测并存入会话状态；请求 messages 首条为环境 system-reminder（含 `# Environment`）、不在历史中
- [x] resume 恢复会话：重新探测环境快照存入会话状态，请求 messages 首条为环境消息（模型仍知道工作目录/OS）
- [x] `/clear` 后：环境快照仍在会话状态，下一次请求 messages 首条仍为环境消息
- [x] loadSession 加载旧会话：环境快照重新探测，首次请求 messages 首条为环境消息（无需补注入逻辑）
- [x] 环境与轮次级提醒不进历史，UI 无需跳过逻辑（appendHistoryMessage 历史渲染不含 system-reminder）
- [x] 会话列表 preview() 显示用户问题（环境不进历史，天然满足）
- [x] 每轮 TurnComplete 后终端出现 `usage: in ... · cache_read ... · cache_write ... · out ...` 脚注行（T9 前为集成测试断言，非手测）
- [x] 存量 ConversationControllerTest / AgentIntegrationTest 全绿（请求首条 SYSTEM 断言维持，环境断言在 T3/T8 测试中）

## T9 评估场景文档 + 手测文档

- [x] `docs/ch05/eval-scenarios.md` 含 5 个场景，每个场景有输入示例 / 期望行为 / 对照判据
- [x] eval-scenarios.md 附录含缓存命中验证步骤（看脚注 cache_read）
- [x] `docs/manual-test.md` 含「阶段四」小节

## T10 端到端验收 ⚑

- [ ] 真实 API（anthropic 一遍）：多轮工具任务自然收尾；在满足 provider 缓存条件下观察第 2 轮起脚注 `cache_read` > 0；若为 0，记录原因（模型/端点不支持、超 5 分钟 TTL 等），不作为代码失败唯一依据
- [ ] 真实 API（openai 一遍）：多轮工具任务正常完成（无 cache_control 输出、不报错）
- [ ] 真实 API：plan 模式 → 首轮完整版、行为只读、交付计划落盘；长对话第 6 轮（若到达）完整版重复
- [ ] 真实 API：按 eval-scenarios.md 跑 5 个场景，行为符合期望（人工对照）
- [ ] 真实 API：退出后 `--resume` 恢复会话继续对话正常（模型仍知道工作目录/OS，环境快照为 resume 时重新探测的新值，不显示在历史渲染中）
- [x] 全程 `mvn test` 全绿（构建环境 `JAVA_HOME=D:\java\jdk21`；新增 prompt 包测试均无网络依赖）
