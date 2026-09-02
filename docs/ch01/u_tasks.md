# ch02: 让 AI 开口说话 Tasks

> 任务粒度: 每个任务可在一次会话内完成，可独立交付。

## T1: 定义 `LlmClient` 接口与静态工厂方法
- 影响文件: `src/main/java/com/mewcode/llm/LlmClient.java`
- 依赖任务: 无
- 完成标准: `src/main/java/com/mewcode/llm/LlmClient.java:10-20` 声明 `LlmClient` 接口（含 `stream(conv, tools)` 单实例方法）；`src/main/java/com/mewcode/llm/LlmClient.java:14-19` 实现 `static create(ProviderConfig cfg, String systemPrompt)`，用 switch 表达式按 protocol 路由，未知 protocol 抛 `IllegalArgumentException`。

## T2: 实现流式事件 sealed interface + records
- 影响文件: `src/main/java/com/mewcode/llm/StreamEvent.java`
- 依赖任务: T1
- 完成标准: `src/main/java/com/mewcode/llm/StreamEvent.java:5-22` 定义 `sealed interface StreamEvent` + 8 个 record（`TextDelta` / `ThinkingDelta` / `ThinkingComplete` / `ToolCallStart` / `ToolCallDelta` / `ToolCallComplete` / `StreamEnd` / `Error`），全部用 `implements StreamEvent`。

## T3: 实现异常分层（`LlmException` + 4 个嵌套子类）
- 影响文件: `src/main/java/com/mewcode/llm/LlmException.java`
- 依赖任务: T1
- 完成标准: `src/main/java/com/mewcode/llm/LlmException.java:3-41` 定义 `LlmException extends RuntimeException`，含双构造函数；`:13-17` `AuthenticationException`；`:19-28` `RateLimitException`（含 `retryAfter` 字段与 getter）；`:30-34` `ContextTooLongException`；`:36-40` `NetworkException`。

## T4: 实现 Anthropic 客户端
- 影响文件: `src/main/java/com/mewcode/llm/AnthropicClient.java`
- 依赖任务: T1, T2, T3, T6, T7
- 完成标准:
 - `src/main/java/com/mewcode/llm/AnthropicClient.java:31-46` 构造函数读取 `cfg.resolvedApiKey()`，空时抛 `AuthenticationException`，model 经 `ModelResolver.resolve` 解析；
 - `src/main/java/com/mewcode/llm/AnthropicClient.java:52-68` `stream()` 创建 `LinkedBlockingQueue<>(64)` + `Thread.startVirtualThread` 调 `doStream`；
 - `src/main/java/com/mewcode/llm/AnthropicClient.java:80-86` thinking=true 时根据 `ModelResolver.supportsAdaptiveThinking` 切换 adaptive / enabled（budget = maxTokens - 1）；
 - `src/main/java/com/mewcode/llm/AnthropicClient.java:132-234` SSE 主循环 `switch(eventType)` 处理 `message_start` / `content_block_start`（识别 thinking / tool_use）/ `content_block_delta`（识别 `thinking_delta` / `signature_delta` / `text_delta` / `input_json_delta`）/ `content_block_stop` / `message_delta`；
 - `src/main/java/com/mewcode/llm/AnthropicClient.java:236-238` 流结束 `queue.put(new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens))`；
 - `src/main/java/com/mewcode/llm/AnthropicClient.java:245-255` `classifyHttpError(status, body)` 按 413 / `prompt is too long` / 401 / 429 / default 分支返回 `LlmException` 子类。

## T5: 实现 OpenAI Responses 客户端
- 影响文件: `src/main/java/com/mewcode/llm/OpenAiClient.java`
- 依赖任务: T1, T2, T3, T7
- 完成标准:
 - `src/main/java/com/mewcode/llm/OpenAiClient.java:30-45` 构造函数读取 API key（空抛 `AuthenticationException`）；
 - `src/main/java/com/mewcode/llm/OpenAiClient.java:51-67` `stream()` 与 Anthropic 同形；
 - `src/main/java/com/mewcode/llm/OpenAiClient.java:84-86` thinking=true 时设置 `reasoning: { effort: "high", summary: "detailed" }`；
 - `src/main/java/com/mewcode/llm/OpenAiClient.java:125-203` SSE 主循环 `switch(type)` 处理 `response.output_text.delta` / `response.output_item.added`（function_call / reasoning）/ `response.reasoning_summary_text.delta/done` / `response.function_call_arguments.delta/done` / `response.completed`；
 - `src/main/java/com/mewcode/llm/OpenAiClient.java:211-222` `classifyHttpError` 覆盖 413 / 400+`context_length_exceeded` / 401 / 429 / default。

## T6: 实现 `ModelResolver`（短名映射 + 能力判断）
- 影响文件: `src/main/java/com/mewcode/llm/ModelResolver.java`
- 依赖任务: T1
- 完成标准: `src/main/java/com/mewcode/llm/ModelResolver.java:7-11` 定义 `ALIASES` Map（haiku / sonnet / opus）；`:13-15` `resolve(model)` 返回别名解析后的具体 ID；`:17-20` `supportsAdaptiveThinking(model)` 判断含 `opus-4-6` / `sonnet-4-6`；`:22-25` `supportsThinking(model)` 判断含 `claude`。

## T7: 实现 `ConversationManager` + Message + 三个 block record
- 影响文件: `src/main/java/com/mewcode/conversation/ConversationManager.java`、`Message.java`、`ThinkingBlock.java`、`ToolUseBlock.java`、`ToolResultBlock.java`
- 依赖任务: 无
- 完成标准:
 - `src/main/java/com/mewcode/conversation/ThinkingBlock.java:3` `record ThinkingBlock(String thinking, String signature)`；
 - `src/main/java/com/mewcode/conversation/ToolUseBlock.java:5` `record ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments)`；
 - `src/main/java/com/mewcode/conversation/ToolResultBlock.java:3` `record ToolResultBlock(String toolUseId, String content, boolean isError)`；
 - `src/main/java/com/mewcode/conversation/Message.java:5-32` 可变类 Message，字段 role / content / thinkingBlocks / toolUses / toolResults + 5 套 getter/setter；
 - `src/main/java/com/mewcode/conversation/ConversationManager.java:17-46` 实现 6 个 add 方法（含 `addSystemReminder` 包裹 `<system-reminder>\n...\n</system-reminder>`）；
 - `src/main/java/com/mewcode/conversation/ConversationManager.java:48-58` 实现 `getMessages()` 返回 `List.copyOf(history)`、`getMessagesMutable()`、`size()`；
 - `src/main/java/com/mewcode/conversation/ConversationManager.java:60-174` 实现 `serialize(protocol)` 分发到 `serializeAnthropic` / `serializeOpenAI`，含同角色文本消息合并逻辑。

## T8: 覆盖 Thinking + Reasoning 行为测试
- 影响文件: `src/test/java/com/mewcode/llm/ThinkingTest.java`
- 依赖任务: T4, T5, T6, T7
- 完成标准:
 - `testSupportsAdaptiveThinking` 验证 opus-4-6 / sonnet-4-6=true，opus-4-5 / sonnet-4-5=false，gpt-5=false；
 - `testAnthropicThinkingAdaptive` 断言 4.6 模型走 adaptive、`thinking.type="adaptive"`；
 - `testAnthropicThinkingEnabled` 断言非官方模型走 enabled、`budget_tokens = maxTokens - 1`；
 - `testAnthropicThinkingDisabled` 断言 `thinking=false` 时请求体无 thinking 字段；
 - `testAnthropicThinkingBlocksInConversation` 断言 thinking block 的 signature 能往返；
 - `testOpenAIReasoningEnabled` / `testOpenAIReasoningDisabled` 分别覆盖 OpenAI reasoning 开关。

## T9: 接入主流程
- 影响文件: `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/main/java/com/mewcode/agent/Agent.java`、`src/main/java/com/mewcode/subagent/AgentTool.java`、`src/main/java/com/mewcode/teams/TeammateRunner.java`
- 依赖任务: T1-T7
- 完成标准:
 - `src/main/java/com/mewcode/tui/MewCodeModel.java:391` 用 `LlmClient.create(selectedProvider, systemPrompt)` 构造 client；
 - `src/main/java/com/mewcode/tui/MewCodeModel.java:399` 把 client 传给 `new AgentTool(client, registry, protocol)`；
 - `src/main/java/com/mewcode/agent/Agent.java:126` Agent Loop 调用 `client.stream(conv, tools)`；
 - `src/main/java/com/mewcode/agent/Agent.java:150-179` `switch (event)` 模式匹配消费 8 种事件；
 - `src/main/java/com/mewcode/subagent/AgentTool.java:74` `setModelResolver(Function<String, LlmClient> modelResolver)` 接入短名解析。

## T10: 端到端验证
- 影响文件: 无（仅运行验证）
- 依赖任务: T9
- 完成标准:
 - `./gradlew build` 通过；
 - `./gradlew test --tests "com.mewcode.llm.*"` 通过（6+ thinking_test 全绿）；
 - 在 TUI 中发送任意一句话，能看到流式文本（`TextDelta`）被逐 token 渲染到对话窗口，证明 `BlockingQueue<StreamEvent>` 与事件渲染端到端打通。

## 进度
- [ ] T1
- [ ] T2
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6
- [ ] T7
- [ ] T8
- [ ] T9
- [ ] T10