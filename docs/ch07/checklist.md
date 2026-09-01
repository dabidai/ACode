# ACode 阶段六：MCP 工具生态 — 验收清单

> 最后更新：2026-08-26
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 默认值说明：协议版本 `2025-06-18`；工具调用/握手超时默认 60s（config `timeout` 覆盖，单位秒）；工具注册名 `server名_工具名`；server 权限档未声明默认 exec（调用前确认、plan 模式不可见）、声明 read 则 plan 模式可见；server 开关默认开启；tools/list 分页上限 10 页；收到 server→client 请求回错误码 `-32601`（Method not found）；stdio 子进程关闭先 destroy、等 2s、再 destroyForcibly；Windows 下 `.cmd/.bat` 命令经 `cmd /c` 包装；子进程环境白名单（Windows：PATH/SystemRoot/windir/SystemDrive/ComSpec/PATHEXT/TEMP/TMP/USERPROFILE/HOMEDRIVE/HOMEPATH/APPDATA/LOCALAPPDATA，缺省项跳过；其他平台：PATH/HOME/USER/LANG/TMPDIR）+ 配置显式 `env` 段。

## T1 JSON-RPC 2.0 协议类型与编解码

- [ ] `JsonRpcCodecTest` 全绿：请求/响应/错误/通知四类消息判别正确（有 method 无 id→通知；有 method 有 id→请求；无 method 有 id→响应，error 存在则解析错误）
- [ ] 数字 id 归一为字符串：`parse("{\"id\": 42, ...}")` 后 id 为 `"42"`；序列化往返后 parse 结果一致
- [ ] 非法 JSON（`not-json`）抛协议错误；缺 `jsonrpc: "2.0"` 抛协议错误
- [ ] `serializeRequest/Notification/Response/Error` 产出的 JSON 含 `jsonrpc: "2.0"` 与正确字段

## T2 环境隔离 + Transport + StdioTransport

- [ ] `ProcessEnvTest` 全绿（纯单测、不起子进程）：Windows 白名单保留 PATH/SystemRoot/ComSpec/TEMP/USERPROFILE 等必需项；`API_KEY` 类未声明变量被清；显式 env 段覆盖同名项；缺省白名单项不报错跳过
- [ ] `StdioTransportTest` 全绿：启动真实 FakeMcpServer 子进程 → send 请求 → 收到响应回调
- [ ] 杀子进程（外部 kill）→ `isAlive()` 为 false 且挂起的请求异常完成（不永久阻塞）
- [ ] `close()` 后子进程被销毁（2s destroyForcibly 兜底，任务管理器无残留 java 子进程）
- [ ] `buildCommand` 在 Windows 分支对 `.cmd/.bat` 命令（如 `npx`）产出 `cmd /c` 包装（测试断言）
- [ ] 环境隔离集成验证（`StdioTransportTest` 一条用例）：经 tools/call 调 `echo_env` 读子进程收到的环境 → 断言 PATH/SystemRoot 等白名单项在、本机未声明变量（如 `API_KEY`/`ACODE_*`）不在（确认 `ProcessEnv.build` 产出的环境被真正应用到子进程，而非仅单测通过）

## T3 HttpTransport（Streamable HTTP）

- [ ] `HttpTransportTest` 全绿：Content-Type `application/json` 响应直接解析成功
- [ ] Content-Type `text/event-stream` 响应（`event: message` + data 为消息 JSON）解析成功；一次响应含多个事件时全部被分发
- [ ] initialize 响应带 `Mcp-Session-Id` 头 → 后续请求自动携带该头（假 server 断言收到）
- [ ] 通知类消息（无 id）POST 期望 202 无 body 成功
- [ ] 非 2xx（如 401）抛连接失败且错误消息含状态码/原因；Content-Type 缺失默认按 JSON 解析
- [ ] `isAlive()`：成功请求后为 true；一次失败请求后为 false

## T4 McpClient

- [ ] `McpClientTest` 全绿：初始化顺序正确（先 initialize 请求、收到响应后发 initialized 通知，用假 transport 记录列表断言顺序）
- [ ] 协议版本协商：假 server 返回 `protocolVersion` 与 `2025-06-18` 不一致 → 不抛错、有告警、后续 listTools 仍成功
- [ ] `listTools()` 分页：假 server 首页返回 2 工具 + `nextCursor` → 第二页返回 1 工具无 cursor → 结果共 3 工具；超过 10 页仍无 cursor 时停止（防死循环）
- [ ] `callTool` 正常响应返回 result；`result.isError==true` → 抛远端失败错误（错误消息含远端错误文本）
- [ ] 按 id 异步匹配：假 transport 乱序注入两条响应 → 各自完成对应 pending、不串台
- [ ] 超时：send 后 handler 不回调 → `future.get(超时)` 抛超时错误、pending 被清理
- [ ] 收到 server→client 请求（如 notifications/initialized 之外的 method+id）→ 自动回错误码 `-32601`（假 transport 断言收到该错误响应）
- [ ] transport EOF → 挂起请求全部异常完成

## T5 McpServerConfig + ConfigLoader

- [ ] `McpServerConfigTest` 全绿：stdio 型缺 `command` 抛 ConfigException（消息含 `mcp_servers.<name>` 定位）；http 型缺 `url` 抛错；非法权限档/负超时/非法名字符抛错
- [ ] 超时默认 60 秒、开关默认开启（不写键时）
- [ ] `ConfigLoaderMcpTest` 全绿：`mcp_servers` 段解析出全部 server；未知键仍抛 ConfigException（白名单未被破坏）；全局与项目级同名 server → 项目级整项覆盖；不同名 → 追加
- [ ] `mcp_servers` 值为非映射（如字符串）→ 抛 ConfigException
- [ ] 内置默认 classpath 配置不配置 mcp_servers（仓库受控内容不绑定外部 server）

## T6 工具适配器 + 连接单元

- [ ] `McpToolWrapperTest` 全绿：`name()` = `server名_工具名`；`inputSchema()` 与 MCP 声明的 schema 逐字段一致（透传）；`description()` 含原描述与来源 server 名
- [ ] 未声明权限档的 server 工具 `permission()==EXEC`；声明 `read` 的工具 `permission()==READ`
- [ ] `execute` 成功 → `ToolResult.success` 且内容为远端 text 段拼接；`result.isError` → `ToolResult.failure`（含远端错误文本）；连接异常 → `ToolResult.failure` 不抛异常
- [ ] 连接死亡时调用 → 触发重连一次后成功；重连失败 → 返回失败结果、不无限重试
- [ ] 工具适配器不继承 `BaseTool`（直接实现 Tool 接口）——嵌套 schema（含 properties 层级）原样进入 Agent 请求
- [ ] **并发重连竞态（等锁单飞）**：两个线程同时打到死连接（如子进程被杀后并发调两个工具）→ 只重建一次连接（断言 stdio 只拉起一个子进程 / 假 server 只收到一次 initialize），两个 callTool 均正常返回（阻塞等待后复用同一新连接）
- [ ] **重连后旧 wrapper 仍可用**：连接死亡重连（建新 client）后，重连前已从 `tools()` 拿到的 McpToolWrapper 再 `execute` → 经 connection 路由到当前活 client、调用成功（不指向旧 client）
- [ ] **close 与重连互斥**：`close()` 置 closed 标志后，并发 callTool/重连不重建连接（无僵尸连接）；close 时 in-flight 请求异常完成、不悬挂

> **并发决策（T6 重连）**：重连采用「**等锁**」方案——`callTool` 检测到连接死亡后，在重连锁上阻塞获取，同一时刻只有一个线程执行重建（`connect()` 幂等 + closed 标志），其余并发 caller 等锁后复用新连接，等待受 McpClient 超时兜底。
> 备选「**快速失败**」：并发 caller 不等待，检测到重连进行中立即返回 `ToolResult.failure`（更快、不阻塞，但瞬时失败更多）。
> **切换方式**：只改 `callTool` 的锁获取逻辑（等锁→尝试失败），上述验收用例与契约不变。当前选型：**等锁**（重连多发生在进程被杀等低频场景，优先减少失败）。

## T7 McpManager

- [ ] `McpManagerTest` 全绿：server A（可连接）+ server B（url 指向未监听端口）→ 启动后 A 的工具注册成功、B 跳过且输出「警告：MCP server B 连接失败…」
- [ ] `registerTools` 时与内置工具重名（如 MCP 工具恰好叫 `Bash`，注册名 `server_Bash` 不与内置冲突）→ 全部注册成功；同名 MCP 工具（同一 server 内重名）→ 后者被跳过、打警告、应用不崩
- [ ] `enabled: false` 的 server 不连接、不注册、不警告
- [ ] `closeAll()` 关闭全部连接（含已死的连接，幂等不抛异常）

## T8 接入主流程

- [ ] ACode 启动时构造 `McpManager`，配置了 mcp_servers 则连接并注册；未配置则无副作用、启动行为与之前一致
- [ ] `/quit` 或异常退出后 stdio 子进程被清理（任务管理器无残留子进程）——`start()` 的 finally 挂接 closeAll
- [ ] 工具注册时机在 Agent 首次构建前（注册后 Agent 请求的工具列表已含 MCP 工具名）
- [ ] 启动日志/终端可见 MCP server 连接结果（成功/失败警告）

## T9 端到端验收 ⚑

- [ ] `McpEndToEndTest` 全绿：stdio（FakeMcpServer 子进程）与 HTTP（HttpServer 假 server）两条链路——配置 → McpManager → ToolRegistry → 工具适配器 execute → 拿到远端结果
- [ ] 端到端懒重连：stdio 子进程被杀 → 再次调用该 server 工具 → 自动重连成功且能拿到结果；重连失败返回 `ToolResult.failure`
- [ ] 配置一个声明 `permission: read` 的 server → 其工具在 /plan 模式工具表可见；默认（未声明）server 的工具 plan 模式不可见、普通模式调用前弹确认
- [ ] `tools/call` 返回 `isError: true` → Agent 收到失败工具结果（界面显示远端错误文本）、Loop 继续不中断
- [ ] `grep -rn "mcp" src/main/java` 返回 ≥10 条（MCP 包落地，含各文件 package 行）；`grep -rn "JsonRpc" src/main/java` 返回 ≥5 条
- [ ] 真实社区 server 手测：`mcp_servers` 声明 `command: npx, args: [-y, "@modelcontextprotocol/server-everything"]` → 启动 ACode → Agent 请求的工具列表含 `everything_工具名` 格式工具 → 调用成功返回
- [ ] 全程 `mvn test` 全绿（构建环境 `JAVA_HOME=D:\java\jdk21`；新增 mcp 包测试均无外部网络依赖——假 server 均本地）
