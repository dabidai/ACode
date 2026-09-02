# ACode 阶段六：MCP 工具生态 — 任务清单

> 最后更新：2026-08-26
> 依赖关系：`T1→T2/T3/T4`；`T4,T5→T6`；`T5,T6→T7`；`T7→T8→T9`；T2/T3/T4/T5 相互独立、可并行。
> 协议依据：MCP 规范 2025-06-18（stdio 换行分隔 JSON 帧、Streamable HTTP、initialize/tools/list/tools/call、isError 语义）。

## 约定

- 新增 MCP 包根 `com.acode.mcp`：主代码 `src/main/java/com/acode/mcp/`，测试 `src/test/java/com/acode/mcp/`；假 server 子进程 `src/test/java/com/acode/mcp/fakeserver/`
- 现有文件行号以 2026-08-26 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；**测试方法名用英文驼峰**（惯例）
- 需要复用的现有设施：`JsonRpcCodec` 用 Jackson `ObjectMapper`（参考 `BaseTool.java:24` 的单例写法）；HTTP 传输用 JDK `java.net.http.HttpClient`（参考 `provider/ProviderHttpClient.java`）+ 复用 `sse/SseParser.java`；并发用 `util/VirtualThreads.java` 的 `POOL`；本地 HTTP 测试用 JDK `com.sun.net.httpserver.HttpServer` loopback 随机端口（先例 `RetryPolicyTest.java`）；测试桩模式参考 `provider/FakeProvider.java`；临时目录用 JUnit `@TempDir`
- Windows 环境注意：`npx`/`uvx` 是 `.cmd`/`.bat`，`ProcessBuilder` 直接启动会失败，需经 `cmd /c` 包装
- MCP 规范关键常量：协议版本 `2025-06-18`；`initialize`/`notifications/initialized`/`tools/list`/`tools/call`；tools/call 响应 `result.isError==true` 表示远端执行失败；`result.nextCursor` 非空表示还有分页

---

### T1 JSON-RPC 2.0 协议类型与编解码

**目标**：请求/响应/错误/通知三种消息的类型建模与编解码，单入口按消息形态判别，id 归一为字符串。

**影响文件（新建）**
- `src/main/java/com/acode/mcp/JsonRpcMessage.java` — sealed interface + 嵌套 records：请求（method/params/id）、响应（id/result/error）、错误（code/message/data）、通知（method/params）
- `src/main/java/com/acode/mcp/JsonRpcCodec.java` — `parse(String)` 反序列化判别（有 method 无 id → 通知；有 method 有 id → 请求；无 method 有 id → 响应，error 存在则解析错误）；`serializeRequest/Notification/Response/Error(...)` 各类型序列化；`jsonrpc=="2.0"` 校验、非法 JSON 抛协议错误；id 一律 `asText()` 归一为 String
- `src/main/java/com/acode/mcp/McpException.java` — 运行时异常基类，静态工厂区分连接失败/协议错误/超时/远端错误
- `src/test/java/com/acode/mcp/JsonRpcCodecTest.java` — 各消息类型判别、数字 id 归一、序列化往返、非法 JSON 与缺 jsonrpc 抛协议错误

**依赖**：无

**参考资料**

- Jackson `ObjectMapper` 单例写法：`src/main/java/com/acode/tool/BaseTool.java:24`（`private static final ObjectMapper JSON`）
- JsonNode 节点 API（`readTree`/`asText`/`get`）惯用法见 `src/main/java/com/acode/config/ConfigLoader.java` 同仓库其他 JsonNode 使用处（`agent/PlanWriter.java`、`provider/ContentBlockListDeserializer.java`）
- 协议判别规则：MCP 规范 JSON-RPC 消息类型（请求带 id、通知不带 id、响应按 id 关联）

---

### T2 环境隔离 + Transport 抽象 + StdioTransport

**目标**：Transport 接口语义 + stdio 实现（子进程启动、换行帧读写、读线程分发、进程销毁）；子进程环境变量白名单隔离；测试假 server 子进程。

**影响文件（新建）**

- `src/main/java/com/acode/mcp/Transport.java` — `interface Transport extends AutoCloseable`：`start()`、`send(JsonRpcMessage)`、`setMessageHandler(Consumer<JsonRpcMessage>)`、`isAlive()`、`close()`
- `src/main/java/com/acode/mcp/ProcessEnv.java` — 平台判别的最小环境白名单（Windows：PATH/SystemRoot/windir/SystemDrive/ComSpec/PATHEXT/TEMP/TMP/USERPROFILE/HOMEDRIVE/HOMEPATH/APPDATA/LOCALAPPDATA，缺省项跳过；Linux/macOS：PATH/HOME/USER/LANG/TMPDIR）+ 显式声明 env 覆盖；其余环境一律不传
- `src/main/java/com/acode/mcp/StdioTransport.java` — 构造取命令列表与工作目录；`start()` 用 `ProcessBuilder` 启动（环境清空后白名单填充、`cmd /c` 包装 `.cmd/.bat`）；启动 stdout 读线程（虚拟线程，`BufferedReader.readLine()` 逐行 → 解码 → handler，EOF 置死）+ stderr 排空线程；`send()` 序列化 + 换行写入（synchronized 单写者）；`close()` 先 destroy、等两秒、再 destroyForcibly
- `src/test/java/com/acode/mcp/fakeserver/FakeMcpServer.java` — 可独立运行（`java -cp target/test-classes com.acode.mcp.fakeserver.FakeMcpServer`）的假 server：stdin 逐行读 JSON-RPC，处理 initialize（回协议版本 + capabilities）、notifications/initialized（忽略）、tools/list（回 2 个工具：`echo` 回显 text 参数、`echo_env` 返回指定环境变量的值）、tools/call（echo 回 text 内容、echo_env 回环境值、其余工具回 isError 失败）、未知方法回「不支持」错误；支持故障注入参数（如首轮 tools/call 返回 isError）
- `src/test/java/com/acode/mcp/ProcessEnvTest.java` — 纯单测直接断言 `build()` 输出：白名单保留必需项、未声明变量被清、显式 env 覆盖同名项（环境隔离核心契约在此覆盖，无需起子进程）
- `src/test/java/com/acode/mcp/StdioTransportTest.java` — 真实启动 FakeMcpServer 子进程 → send 收到回调；进程死亡 → isAlive=false 且挂起的请求异常完成；close → 进程销毁；`buildCommand` 的 `cmd /c` 包装（Windows 分支）；**环境隔离集成验证（一条用例）**：确认 `ProcessEnv.build` 产出的白名单环境被真正应用到了子进程——经 tools/call 调 `echo_env` 读子进程收到的 PATH 与本机未声明变量（如 `API_KEY`），断言白名单项在、未声明项不在

**依赖**：T1

**参考资料**
- 进程启动：`ProcessBuilder` API；`ProcessEnv.build` 与 `StdioTransport.start` 配合（先 `environment().clear()` 再白名单 put）
- 换行分隔 JSON 帧：MCP 规范 stdio 传输（消息以单个 `\n` 分隔，无 Content-Length 头）
- 测试先例：`src/test/java/com/acode/provider/RetryPolicyTest.java:36-52`（HttpServer loopback）；`@TempDir` 用作子进程工作目录

---

### T3 HttpTransport（Streamable HTTP）

**目标**：远程 server 的请求-响应传输：POST + 自定义头 + 会话头保持，响应体按内容类型分流 JSON / SSE。

**影响文件（新建）**
- `src/main/java/com/acode/mcp/HttpTransport.java` — 构造取 URL/请求头/超时；`send()` 同步 POST（`Content-Type: application/json`、`Accept: application/json, text/event-stream`、配置请求头、已持会话头则带上）；响应按 Content-Type 分流：`text/event-stream` → 复用 `SseParser` 逐事件解析（只取消息事件，一次响应可分发多条）；否则整个 body 按单条消息解析；initialize 响应头提取会话 id 保存并在后续请求回传；通知类消息期望 202 无 body；非 2xx 读错误体（限 8KB）抛连接失败；`isAlive()` 依据最近请求成败
- `src/test/java/com/acode/mcp/HttpTransportTest.java` — 纯 JSON 响应、SSE 响应（消息事件 + 多事件）、会话头提取与回传、202 通知、非 2xx 错误、Content-Type 缺失默认按 JSON

**依赖**：T1

**参考资料**

- JDK HttpClient 用法：`src/main/java/com/acode/provider/ProviderHttpClient.java`（连接超时/请求超时/错误体读取 ≤8192 字节的先例，但本任务需自定义头与内容类型分流，不复用其只 POST+JSON 的静态方法）
- SSE 解析复用：`src/main/java/com/acode/sse/SseParser.java:24-48`（`parse(InputStream, EventHandler)`，按空行切事件、`event:`/`data:` 行）
- 本地 HTTP 假 server：`src/test/java/com/acode/provider/RetryPolicyTest.java`（`com.sun.net.httpserver.HttpServer` loopback + 随机端口），按 Content-Type 用可编程 handler 返回 JSON 或 SSE

---

### T4 McpClient（握手 / 工具发现 / 工具调用 / 异步匹配）

**目标**：协议客户端：初始化握手、工具列表发现（含分页）、工具调用（含 isError 语义）、按 id 异步匹配的 pending 表与超时。

**影响文件（新建）**
- `src/main/java/com/acode/mcp/McpToolInfo.java` — 工具发现的单个工具描述 record（name/description/inputSchema）
- `src/main/java/com/acode/mcp/McpClient.java` — 构造取 transport 与超时；`initialize()`（发 initialize 请求、版本协商：server 返回不一致时警告不中断；随后发 initialized 通知）；`listTools()`（tools/list 循环携带 cursor 直到无 nextCursor，上限 10 页防死循环）；`callTool(name, arguments)`（tools/call，`result.isError` 转失败）；`sendRequest(method, params)` 内部：id 自增 → pending 表登记 CompletableFuture → transport.send → future.get(超时)；`dispatch(msg)` 分发：响应按 id 完成 pending、收到 server 请求回「不支持方法」错误、通知忽略记录日志；stdin EOF 时挂起的请求全部异常完成；`close()`
- `src/test/java/com/acode/mcp/McpClientTest.java` — 用内存假 transport（send 入队、测试注入响应调 handler）：初始化序列顺序、listTools 分页、callTool isError 抛错、按 id 乱序匹配、超时抛错、收到 server 请求自动回「不支持」、EOF 完成挂起请求

**依赖**：T1

**参考资料**
- 握手顺序：MCP 规范（initialize → 收到响应后发 notifications/initialized → 才能发其他请求）
- pending 表分发：`JsonRpcCodec.parse` 产物喂 `McpClient.dispatch`；超时用 `CompletableFuture.get(long, TimeUnit)` + 超时后 `remove` pending 并取消
- 假 transport 桩模式参考 `src/test/java/com/acode/provider/FakeProvider.java`

---

### T5 McpServerConfig + ConfigLoader 解析

**目标**：mcp_servers 配置段的模型与解析：server 列表声明、按名合并、解析即校验。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/config/McpServerConfig.java`（新）— 单个 server 的不可变模型：name、传输类型（stdio/http）、命令 + 参数 + 显式环境变量（stdio 型）、URL + 请求头（http 型）、超时（默认 60 秒）、权限档（默认最严档）、开关（默认开）；`fromYaml(name, Map, source)` 集中解析嵌套结构并校验（必填字段、类型、枚举值、超时正整数、name 字符集），错误消息带 `mcp_servers.<name>` 定位
- `src/main/java/com/acode/config/AppConfig.java`（改）— 新增 `Map<String, McpServerConfig> mcpServers` 字段 + getter
- `src/main/java/com/acode/config/ConfigLoader.java`（改）— `KNOWN_KEYS` 加 `mcp_servers`；`apply` 末尾调 `parseMcpServers`（值必须是 name→{…} 映射；逐项 `McpServerConfig.fromYaml`）；按名合并——同名整项覆盖、不同名追加（保序）
- `src/test/java/com/acode/config/McpServerConfigTest.java` — stdio 缺命令报错、http 缺 URL 报错、非法权限档报错、超时默认 60、开关默认开、非法名字符报错
- `src/test/java/com/acode/config/ConfigLoaderMcpTest.java` — mcp_servers 段解析、未知键仍报错、全局与项目级同名 server 合并（项目级覆盖）、ConfigValidator 不受影响

**依赖**：无

**参考资料**
- 白名单校验：`src/main/java/com/acode/config/ConfigLoader.java:27-29`（KNOWN_KEYS）、`:100-149`（apply 逐键处理）；`AppConfig.java` 现有字段模式
- 错误消息风格：`ConfigLoader.java` 现有 `ConfigException`（带文件路径 source）；`McpServerConfig.fromYaml` 内用 `source + ": mcp_servers.<name>: …"`

---

### T6 工具适配器 McpToolWrapper + 连接单元 McpServerConnection

**目标**：把 MCP 工具包装成 ACode Tool 接口（直接实现接口、不继承基类）；单 server 连接缓存单元与懒重连。

**影响文件（新建）**
- `src/main/java/com/acode/mcp/McpToolWrapper.java` — `implements Tool`：`name()` = `server名_工具名`（仅 Agent 可见注册名）；**内部持原始工具名，`execute` 调 `callTool(原始名, args)`——tools/call 发给远端的 name 必须不带前缀**；`description()` = 原描述 + 来源标注；`permission()` 按 server 权限档映射枚举；`inputSchema()` 原样透传 MCP 的 inputSchema；任何异常转 `ToolResult.failure`（不抛）
- `src/main/java/com/acode/mcp/McpServerConnection.java` — 单 server 活连接：`connect()` 幂等（关旧建新 → initialize → listTools 缓存工具）；`callTool(name, args)`（isAlive false 时先重连一次，重连失败抛连接错误）；`tools()` 返回已发现的工具适配器列表；`close()`。**并发契约**（验收见 checklist T6）：①重连单飞用「等锁」——callTool 遇连接死亡在重连锁上阻塞，同一时刻仅一个线程重建，其余等锁后复用新连接，等待受 McpClient 超时兜底；备选「快速失败」（并发 caller 立即返回失败），二者只改 callTool 的锁获取逻辑即可切换，当前选等锁；②wrapper 不绑定 client 实例，经 connection 的 `volatile currentClient` 路由——重连建新后旧 wrapper 仍指向新连接；③`close()` 置 `volatile closed` 标志并与重连互斥（防 close 后重建僵尸连接）；④`isAlive`/`closed` 用 volatile 保证跨线程可见性
- `src/test/java/com/acode/mcp/McpToolWrapperTest.java` — name 前缀、inputSchema 透传、permission 默认最严档、execute 成功/失败/异常 → ToolResult 且不抛异常、连接死亡触发重连一次、重连失败返回失败结果不无限重试

**依赖**：T4、T5

**参考资料**
- Tool 契约：`src/main/java/com/acode/tool/Tool.java:9-33`（name/description/permission/contentField/inputSchema/execute）；`ToolResult` 工厂：`src/main/java/com/acode/tool/ToolResult.java:22/26`（success/failure）
- **不继承 `BaseTool`**：`BaseTool.java:53-75` 的 execute 是 final 模板方法（10 秒默认超时 + ParamSpec 校验），MCP 工具需透传复杂嵌套 schema、超时由 McpClient 保证，直接实现 Tool 接口绕开
- 权限枚举：`src/main/java/com/acode/tool/Permission.java`（READ/WRITE/EXEC）；permission 默认最严档 → 权限体系「调用前确认、规划模式不可见」由 `Agent.planTools()` 只列 READ 工具自动满足（`src/main/java/com/acode/agent/Agent.java:398-406`）

---

### T7 McpManager（生命周期管理）

**目标**：启动连接全部 server、注册工具进工具中心、退出清理；单个失败容错。

**影响文件（新建）**
- `src/main/java/com/acode/mcp/McpManager.java` — 构造取 config 与工作目录；`connectAll()` 逐个连接 enabled server，失败捕获异常打 `System.err` 警告（含 server 名与原因）并继续；`registerTools(ToolRegistry)` 逐个注册工具适配器，同名冲突捕获异常打警告跳过该工具；`closeAll()` 逆序关闭全部连接（幂等）；`implements AutoCloseable`
- `src/test/java/com/acode/mcp/McpManagerTest.java` — 一个 server 连接失败不阻断其余注册成功、registerTools 重名跳过、closeAll 关闭全部、enabled=false 跳过、失败 server 警告可观测

**依赖**：T5、T6

**参考资料**
- 注册接口：`src/main/java/com/acode/tool/ToolRegistry.java:21`（register，同名抛 IllegalArgumentException）；`enabled` 过滤在 `McpServerConfig`（T5）
- 警告输出风格：对齐现有启动错误提示（`System.err.println`，见 `src/main/java/com/acode/ConversationController.java:138-141`）

---

### T8 接入主流程

**目标**：启动时装配 MCP Manager：连接、注册工具；退出时清理子进程。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`（改）—
  - 构造器 `L158-161`（DefaultToolset.registerAll → ExitPlanModeTool → AskUserTool）之后：`new McpManager(config, projectRoot)` → `connectAll()` → `registerTools(toolRegistry)`，字段保存 manager
  - `start()` `L186-202`：退出清理挂接——在 `try (AcodeTerminal terminal)` 的 finally 中调 `closeAll()`（或 manager 加入 try-with-resources 多资源），保证 `/quit` 与异常退出都清理 stdio 子进程

**依赖**：T7

**参考资料**
- 注册插入点：`src/main/java/com/acode/ConversationController.java:158-161`（工具中心唯一创建处）；`start()`：`:186-202`（try-with-resources 块）；`projectRoot` 字段：`:132`

---

### T9 端到端验证

**目标**：全链路验证：配置 → 连接 → 注册 → 工具调用 → 结果回填；懒重连；手动验收与文档。

**影响文件（新建 + 修改）**
- `src/test/java/com/acode/mcp/McpEndToEndTest.java`（新）— 端到端：FakeMcpServer 子进程（stdio）与 HttpServer 假 server（HTTP）→ McpManager → ToolRegistry → 工具适配器 execute 拿到结果；懒重连（杀掉子进程后再调用自动重建）
- `src/main/resources/config.yaml`（改）— 补 mcp_servers 段示例注释（stdio 型 + http 型写法）
- `README.md`（改）— 补 MCP 配置说明
- `docs/manual-test.md`（改）— 追加「阶段六」手测小节（见 checklist ⚑ 项）

**依赖**：T8

**参考资料**
- 假 server：T2 的 FakeMcpServer（可加故障注入参数）；HttpServer 复用 T3 假 server 处理器
- 构建验证：`JAVA_HOME="D:\java\jdk21" mvn test` 全绿后按 checklist.md ⚑ 项逐条手测
