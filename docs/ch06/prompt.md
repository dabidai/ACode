要让ACode从封闭工具集变成开放工具生态，做完之后，用户在配置文件里声明一个MCP Server，MewCode就能自动接入它提供的工具，不用改代码。GitHub Issue 查询、数据库操作、Slack 消息，社区写好了 MCP Server，直接接进来就能用。

具体要新增：

- JSON-RPC 2.0协议类型：请求、响应、通知三种消息的编解码
- Transport抽象 + 两种实现：stdio(子进程管道通信)和Streamable HTTP(远程 Server)
- MCP Client：初始化握手、工具发现、工具调用、请求-响应异步匹配
- MCPToolWrapper：适配器，把MCP工具包装称ACode内部的Tool接口
- MCP Manager：连接缓存、配置合并、生命周期管理
- 环境变量隔离：子进程只拿到PATH+显示声明的变量，不泄露敏感信息

不做：SSE流式推送、Resources/Prompts消费、Sampling/Elicitation 等Client侧高级能力

```text
# 我的初步想法
- 实现一个客户端，按 JSON-RPC 2.0 的消息格式跟外部 server 通信
- 至少支持两种传输方式：本地子进程 stdio、远程 Streamable HTTP
- 一次会话分三个阶段：连接初始化握手 → 工具列表发现 → 工具调用
- 消息是双向的，需要处理请求-响应的异步匹配（每个请求带 id，回包按 id 关联）
- 写一个适配层把发现到的远端工具包装成 ACode 已有的 Tool 接口，注册进工具中心，Agent 调用时无感
- 多个 server 的连接做缓存或池化，避免每次工具调用都重连
- 配置在哪里声明 server 列表（命令、URL、env、超时）需要在 spec 阶段定下来
```

