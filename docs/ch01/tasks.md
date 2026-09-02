# ACode 阶段一：任务清单 — tasks

> 最后更新：2026-08-07
> 每个任务应能在一次专注会话内完成。依赖关系：1 → 2 → 3 → (4,5) → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13（4 与 5 可并行）。

## 约定

- 包根：`com.acode`，源码 `src/main/java/com/acode/`，测试 `src/test/java/com/acode/`
- 每个任务完成后跑 `mvn compile` 或 `mvn test` 确认不破坏已有代码

---

### T1 项目骨架

**目标**：Maven 工程可编译、可运行，打印启动横幅。

**影响文件（新建）**
- `pom.xml` — Java 21、打包方式 jar、依赖版本锁定（下文各任务用到的依赖一次性声明）
- `src/main/java/com/acode/App.java` — main 入口，解析启动参数（`--resume`），打印启动横幅
- `src/main/resources/logback.xml` — 日志到文件（`~/.acode/logs/`），避免污染终端输出

**依赖**：无（起点任务）

**参考资料**
- Maven 官方：https://maven.apache.org/guides/getting-started/index.html
- `maven.compiler.release` 属性（Java 21）：https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html

---

### T2 配置模块

**目标**：YAML 配置加载与两级合并（项目级覆盖全局），缺字段/类型错误报错定位到来源文件。

**影响文件（新建）**
- `src/main/java/com/acode/config/AppConfig.java` — 配置模型（provider 四字段 + 上下文窗口上限）
- `src/main/java/com/acode/config/ConfigLoader.java` — 加载全局 `~/.acode/config.yaml` → 项目级 `.acode/config.yaml` 合并（深合并：项目级只覆盖出现的字段）
- `src/main/java/com/acode/config/ConfigValidator.java` — 校验 protocol 枚举值、非空字段、base_url 格式
- `src/test/java/com/acode/config/ConfigLoaderTest.java`、`ConfigValidatorTest.java`
- `examples/config.yaml` — 两份示例配置（全局完整版、项目级覆盖版）

**依赖**：T1

**参考资料**
- SnakeYAML load/loadAs：https://github.com/snakeyaml/snakeyaml/wiki/Usage（构造器 `new Yaml()` 与 `yaml.loadAs(input, class)`）
- Jackson `ObjectMapper.readerForUpdating()` 实现「只覆盖出现的字段」的合并：https://github.com/FasterXML/jackson-databind#usage

---

### T3 Provider 抽象层

**目标**：统一接口 + 请求/响应模型 + 流式回调 + 错误分类，Anthropic/OpenAI 共用。

**影响文件（新建）**
- `src/main/java/com/acode/provider/ChatProvider.java` — 接口：`streamChat(request, listener)`，`listener` 回调三方法（onDelta / onComplete / onError）
- `src/main/java/com/acode/provider/ChatRequest.java` — 消息列表、model、thinking 开关、maxTokens 上限
- `src/main/java/com/acode/provider/ChatMessage.java` — role + content
- `src/main/java/com/acode/provider/ChatListener.java` — 回调接口定义
- `src/main/java/com/acode/provider/ProviderException.java` + 分类子类：`AuthException`（401/403）、`RateLimitException`（429）、`ServerException`（5xx）、`NetworkException`（连接失败/超时）、`InvalidRequestException`（4xx 其余）

**依赖**：T2

**参考资料**
- 接口先定义、两端实现随后跟进，先写 `src/test/java/com/acode/provider/FakeProvider.java` 测试桩便于上层联调

---

### T4 Anthropic Provider + SSE

**目标**：Claude 后端可用，流式解析 thinking 与正文，请求带 thinking 参数。

**影响文件（新建）**
- `src/main/java/com/acode/provider/anthropic/AnthropicProvider.java` — 请求构建（messages API、`thinking: {type:"enabled", budget_tokens}`、`max_tokens` > budget）、响应流解析
- `src/main/java/com/acode/provider/anthropic/AnthropicSseParser.java` — 按事件类型分发：`message_start`、`content_block_start`、`content_block_delta`（区分 `delta.text` 与 `delta.thinking`）、`content_block_stop`、`message_delta`、`message_stop`、`error`
- `src/main/java/com/acode/sse/SseParser.java` — 通用 SSE 帧解析（按 `\n\n` 切分事件，解析 `event:`/`data:` 行；Anthropic/OpenAI 复用）
- `src/test/java/com/acode/provider/anthropic/AnthropicSseParserTest.java` — 用录制的真实事件片段做测试（含 thinking 事件、中文文本、error 事件）

**依赖**：T3

**参考资料**
- 流式事件类型清单（含 thinking delta 示例）：https://docs.anthropic.com/en/api/messages-streaming
- thinking 参数与 budget_tokens 约束：https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking
- SSE 帧格式（event/data 行、空行分隔）：https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation

---

### T5 OpenAI Provider + SSE

**目标**：OpenAI 后端可用（普通 chat，无 reasoning）。

**影响文件（新建）**
- `src/main/java/com/acode/provider/openai/OpenAiProvider.java` — chat completions 请求构建、响应流解析
- `src/main/java/com/acode/provider/openai/OpenAiSseParser.java` — `data: {…}` 行解析、`data: [DONE]` 结束标记、`data: {"error":…}` 错误事件
- `src/test/java/com/acode/provider/openai/OpenAiSseParserTest.java`

**依赖**：T3

**参考资料**
- 流式响应格式与 [DONE] 约定：https://platform.openai.com/docs/api-reference/chat/streaming
- SSE 错误事件：`{"error": {"message": …}}` 出现在 data 行

---

### T6 重试与错误处理

**目标**：429/5xx 自动重试 3 次（1s/2s/4s 指数退避），其余错误直接抛出并转中文提示。

**影响文件（新建）**
- `src/main/java/com/acode/provider/RetryPolicy.java` — 判定重试条件、退避间隔、重试上限
- `src/main/java/com/acode/provider/ProviderHttpClient.java` — 统一 HTTP 客户端（连接超时、读超时设置），两端共用
- `src/test/java/com/acode/provider/RetryPolicyTest.java`

**依赖**：T4、T5

**参考资料**
- 用 JDK `HttpClient`（`java.net.http.HttpClient`，Java 21 内置，无需第三方 HTTP 库）；重试判定：HTTP 429 或 500~599

---

### T7 对话编排层

**目标**：消息列表维护、每次请求自动携带全部历史、超限丢弃最早消息。

**影响文件（新建）**
- `src/main/java/com/acode/conversation/Conversation.java` — 消息追加、组装请求、token 估算（按字符数 ÷ 4 估算）、超限时从最早消息开始丢弃直到放得下
- `src/test/java/com/acode/conversation/ConversationTest.java` — 覆盖：估算、丢弃边界（丢到刚好放下）、单条消息超限的兜底行为（清空历史只留当前问题）

**依赖**：T3、T4

**参考资料**
- 窗口上限从配置读取；兜底规则：若当前问题本身超限，清空历史只保留当前问题（避免死循环）

---

### T8 会话持久化

**目标**：会话保存为 JSON、`--resume` 恢复最近一次会话。

**影响文件（新建）**
- `src/main/java/com/acode/session/Session.java` — 会话模型（id、时间戳、消息列表）
- `src/main/java/com/acode/session/SessionStore.java` — 保存（追加为独立文件，不覆盖历史）、列出、读取最近一次
- `src/test/java/com/acode/session/SessionStoreTest.java`

**依赖**：T7（需要消息模型，可先按 T3 的 ChatMessage 存）

**参考资料**
- 存储目录 `~/.acode/sessions/`，文件名按时间戳；Jackson 序列化/反序列化

---

### T9 TUI 基础

**目标**：全屏布局（上输出区/下输入区）、多行输入、输入历史、Ctrl+C 中断、/quit 与 /help。

**影响文件（新建）**
- `src/main/java/com/acode/ui/AcodeTerminal.java` — 终端初始化（raw 模式）、窗口尺寸监听、退出恢复
- `src/main/java/com/acode/ui/OutputPane.java` — 输出区：文本追加、滚动、内容重绘（增量刷新，防闪烁）
- `src/main/java/com/acode/ui/InputPane.java` — 输入区：多行输入（Shift+Enter 换行、Enter 提交）、输入历史上下翻、光标移动
- `src/main/java/com/acode/ui/CommandRouter.java` — `/quit`、`/clear`、`/help` 与普通消息分流

**依赖**：T1、T8

**参考资料**
- JLine3 `TerminalBuilder` 与 `LineReaderBuilder`：https://github.com/jline/jline3（重点看 `LineReader` 的 `readLine`、multiline 模式、`Alt+Enter` 或自定义 key binding 提交）
- JLine3 内部使用 ANSI 转义序列：https://github.com/jline/jline3/blob/master/terminal-jansi/src/main/java/org/jline/utils/InfoCmp.java（终端能力查询）
- 输出区自绘：`\033[H`（光标回原点）、`\033[J`（清屏）配合全量重绘；Ctrl+C 用 `System.in` 读取或 JLine key binding 拦截

**注意**：JLine3 是「行式输入」库，全屏布局需要自己管理光标与输出区滚动；先实现「输入行固定底部 + 输出区简单滚动」的最小可用版，美观后置。

---

### T10 流式输出与 Markdown 着色

**目标**：异步接收 delta 边生成边打印，按 Markdown 子集增量着色（代码块、加粗、标题、行内代码）。

**影响文件（新建）**
- `src/main/java/com/acode/ui/MarkdownRenderer.java` — 增量解析器：维护状态机（是否在代码块内、是否加粗、标题行），输出 ANSI 着色文本；代码块用不同背景/前景色，标题加粗，行内代码单色
- `src/main/java/com/acode/ui/StreamPrinter.java` — 消费 ChatListener 的 onDelta → MarkdownRenderer → 追加到 OutputPane；onComplete 收尾；onError 显示错误
- `src/test/java/com/acode/ui/MarkdownRendererTest.java` — 覆盖：代码块跨多次 delta 不破色、标题/加粗/行内代码、普通文本原样

**依赖**：T9、T4、T5

**参考资料**
- CommonMark 语法子集定义：https://spec.commonmark.org/0.31.2/
- ANSI 颜色代码表：https://en.wikipedia.org/wiki/ANSI_escape_code#SGR_parameters

---

### T11 /clear 与上下文联动

**目标**：/clear 清空界面输出区、清空对话上下文；/help 列出全部命令。

**影响文件（修改）**
- `src/main/java/com/acode/ui/CommandRouter.java` — 补齐 `/clear`（回调清空 Conversation 与 OutputPane）、`/help` 文案
- `src/main/java/com/acode/conversation/Conversation.java` — 增加 `clear()`

**依赖**：T9、T10

**参考资料**：无（纯内部联动）

---

### T12 接入主流程

**目标**：把配置 → Provider → 会话 → TUI 串成完整对话循环：启动 → 恢复/新建会话 → 循环读输入 → 分流命令/消息 → 调 Provider 流式打印 → 保存会话。

**影响文件（新建）**
- `src/main/java/com/acode/ConversationController.java` — 主循环与装配
- `src/main/java/com/acode/App.java`（修改）— `--resume` 传入 Controller

**依赖**：T2~T11 全部

**参考资料**
- 按 spec.md「分层结构」逐层装配；异常在 Controller 层统一捕获转中文提示

---

### T13 端到端验证

**目标**：真实 API 跑通两家后端完整对话；错误场景、中断场景验证通过。

**影响文件**
- `docs/manual-test.md`（新建）— 手测步骤记录：两家真实 key 各一轮 ≥5 轮对话、错误 key、断网、429（mock 或降速）、Ctrl+C 中断、--resume 恢复、项目级配置覆盖生效
- 修 bug 产生的影响文件视情况

**依赖**：T12

**参考资料**
- 手测按 checklist.md 逐项打勾；联网问题（断网测试）用临时错误 base_url 模拟
