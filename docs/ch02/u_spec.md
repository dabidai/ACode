# ch02: 让 AI 开口说话 Spec

## 1. 背景

Agent 落地的第一步是让上层（Agent Loop / TUI / SubAgent）能用同一套接口和 LLM 收发，不必各自面对 SSE 流、Extended Thinking 签名回传、Provider 间消息差异。本章把 LLM 通信、流式响应、Extended Thinking、Token 统计以及两层消息模型封装到 `com.mewcode.llm` 与 `com.mewcode.conversation`，是 ch03+ 工具循环的前置依赖。

Java 版与 Go 版的核心架构一致，差异主要在惯例：用 `sealed interface + record` 替换 Go 的 `interface + struct`，用 `BlockingQueue<StreamEvent>` 替换 Go 的 `chan StreamEvent + chan error`（Error 作为一种事件入队），用 `Thread.startVirtualThread` 替换 goroutine，用 `LlmException` 子类替换 Go 的 error 类型断言。

## 2. 目标

交付统一的 `LlmClient` 流式接口和两个内置 Provider 实现（`AnthropicClient`、`OpenAiClient` Responses API），加上 `ConversationManager` 两层消息模型（内部带 thinking / tool use / tool result 的 `Message`，序列化到具体 Provider 的请求体）。上层（Agent、TUI 装配点、AgentTool、ContextCompactor、TeamManager）拿一个 `LlmClient` 就能跑，不再触碰 SSE 细节。

## 3. 功能需求

- F1: `LlmClient` 统一暴露流式接口，输入是会话管理器和工具 schema，输出是 `BlockingQueue<StreamEvent>`，错误作为 `StreamEvent.Error` 入队。
- F2: 客户端通过接口内置静态工厂方法 `LlmClient.create(cfg, systemPrompt)` 按 Provider Protocol 路由到 Anthropic 或 OpenAI 实现，未知 protocol 抛 `IllegalArgumentException`。
- F3: 事件流覆盖 8 种信号：`TextDelta` / `ThinkingDelta` / `ThinkingComplete`（含签名）/ `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` / `StreamEnd`（含 stop reason 与 token 用量）/ `Error`。所有事件用 `sealed interface` + `record` 收口，`switch` 模式匹配时编译器保证穷尽。
- F4: Anthropic 客户端基于手写 `HttpClient` + SSE 解析，支持 Extended Thinking 两种模式：高版本模型（opus-4-6 / sonnet-4-6）走 Adaptive Thinking，低版本回退到固定 budget 的 Enabled Thinking，能力判断由 `ModelResolver.supportsAdaptiveThinking` 完成。
- F5: OpenAI 客户端基于 Responses API（非 Chat Completions），支持把 `reasoning_summary_text.delta/done` 还原成 `ThinkingDelta` / `ThinkingComplete` 事件，让上层看到的事件形状和 Anthropic 一致。
- F6: 两个客户端都通过 `HttpRequest.timeout(5min)` + `sendAsync().get(90s)` 兜底 SDK / 网络静默阻塞，HTTP 非 200 状态走错误分类后抛 `LlmException`。
- F7: 错误分类有 5 类：基类 `LlmException` 以及 4 个静态嵌套子类：`AuthenticationException`、`RateLimitException`（带 `retryAfter`）、`ContextTooLongException`、`NetworkException`。各客户端把 HTTP 错误归类到这 5 类之一，上层只面对统一异常。
- F8: `Message` 是可变类（mutable POJO），字段含 role / content / thinkingBlocks / toolUses / toolResults；`ThinkingBlock` / `ToolUseBlock` / `ToolResultBlock` 是不可变 `record`。所有写操作走 `ConversationManager` 方法，外部通过 `getMessages()` 拿到 `List.copyOf` 的只读视图。
- F9: `ConversationManager` 提供 `serialize(protocol)` 按 Protocol 序列化（`serializeAnthropic` / `serializeOpenAI`），序列化时不丢字段（thinking signature、tool arguments、tool result isError 都要原样回到下一轮请求）。
- F10: `ConversationManager.addSystemReminder(content)` 把内容包成 `<system-reminder>\n{content}\n</system-reminder>` 作为 user 消息追加，供 ch06 Plan Mode、ch08 Compact、ch09 Memory 复用。
- F11: `ModelResolver` 暴露 `ALIASES` 短名映射（haiku / sonnet / opus → 具体模型 ID）和 `resolve(model)` / `supportsAdaptiveThinking(model)` / `supportsThinking(model)` 三个静态方法，供 ch13 SubAgent 切模型。

## 4. 非功能需求

- N1: 事件队列 `LinkedBlockingQueue<StreamEvent>(64)` 有缓冲，SSE 读取与事件分发用独立虚拟线程解耦，事件写入 `queue.put()` 时不阻塞主消费者。
- N2: 调用方通过 `Thread.interrupt()` 取消（如 TUI ctrl+c）时，SSE 读循环检测到中断并清理；Agent Loop 侧用 `poll(30s, TimeUnit.SECONDS)` 兜底，超时即 `Stream timeout` 退出。
- N3: HTTP 请求设置 5 分钟超时 + 90 秒连接超时，避免任何一路静默阻塞拖死整个 agent loop。
- N4: 序列化层不丢字段（thinking signature / tool arguments / tool result isError 全部往返保留），Anthropic 把 thinking + text + tool_use 合并到同一条 assistant content 数组里。
- N5: `ConversationManager` 不加锁——单消费者模型，调用方（Agent Loop 单线程顺序追加）负责串行化；`getMessages()` 返回 `List.copyOf` 不可变视图。

## 5. 设计概要

- 核心数据结构:
 - `LlmClient`（接口 + 静态工厂方法 `create()`）
 - `StreamEvent` sealed interface + 8 个 record
 - `LlmException` 基类 + 4 个静态嵌套子类
 - `ModelResolver`（含 `ALIASES` Map 与三个静态方法）
 - `ConversationManager`（私有 `List<Message> history`）
 - `Message`（可变 POJO）+ `ThinkingBlock` / `ToolUseBlock` / `ToolResultBlock`（不可变 record）
- 主流程（每轮 LLM 请求）:
 1. `Agent.agentLoop` 调 `client.stream(conv, tools)`，拿到 `BlockingQueue<StreamEvent>`
 2. 客户端把 `ConversationManager.serialize(protocol)` 序列化成请求体，调 `HttpClient.sendAsync`
 3. 启动虚拟线程读 SSE，主线程 `queue.poll(30s)` 消费事件
 4. 按 SSE 事件类型 `queue.put()` 对应 `StreamEvent` record
 5. 流结束 put `StreamEnd`；异常经 `classifyHttpError` 分类后 put `StreamEvent.Error`
- 调用链（模块层级）:
 - TUI 装配 → `LlmClient.create(provider, systemPrompt)` → 传给 `new Agent(client, registry, protocol)`
 - Agent loop → `LlmClient.stream` → `switch (event)` 模式匹配消费 → 写回 `ConversationManager`
 - `AgentTool` / `ContextCompactor` / `TeamManager` worker / `MemoryManager` 复用同一 `LlmClient` 接口
- 与其他模块的交互:
 - 依赖 `com.mewcode.config.ProviderConfig`（Provider 配置、API key、token 上限）
 - 被 `com.mewcode.agent`、`com.mewcode.subagent`、`com.mewcode.compact`、`com.mewcode.tui`、`com.mewcode.teams`、`com.mewcode.memory` 调用
 - 与 `com.mewcode.tool` 解耦：`stream` 只接 `List<Map<String, Object>>` schema，工具注册中心由 `ToolRegistry` 提供

## 6. Out of Scope

- 多模态输入（image / PDF）的请求体构造：当前 `Message.content` 仅 `String`，未来章节再扩
- 自动重试与指数退避：rate limit 的重试在 ch04 Agent Loop 处理（`Thread.sleep(5000)`），不在 ch02 范围
- Provider 抽象细分（Bedrock / Vertex / Azure-OpenAI）：当前只支持原生 Anthropic 与原生 OpenAI Responses
- Prompt caching / Cache breakpoints：目标设计已有，本仓库暂未实现
- 官方 SDK 接入：当前手写 `HttpClient` + Jackson 解析，未来可替换为 `anthropic-java` / `openai-java` SDK

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。