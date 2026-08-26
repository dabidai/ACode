# 测试补强发现的 Bug 清单

> 由专项测试补强（2026-08-25）发现并记录。B1–B4 已修复（commit 见各条），原 `@Disabled` 已摘除转绿；「已确认覆盖」部分中的 3 项已知缺陷已随架构审查修复（#5/#9/#13/#16），仅保留 1 项纯覆盖说明。

## 已修复（原 @Disabled 已摘除转绿）

### B1. ChatListener 双向 default 委托零覆写即 StackOverflowError
- **位置**：`src/main/java/com/acode/provider/ChatListener.java:18-31`
- **现象**：`onComplete()`（无参）默认实现委托 `onComplete(null)`，带参默认实现又委托回 `onComplete()`。任何只实现抽象方法、不覆写任一 `onComplete` 的监听器，收到完成信号即无限递归爆栈。
- **影响**：接口注释宣称「存量实现只覆写无参版仍能收到完成信号」，但零覆写场景（理论上合法）会崩溃。
- **修复**（commit a5aa97e）：改为带参默认回落到无参、无参默认空操作；存量实现只覆写无参版仍收到信号，零覆写安全结束。
- **回归测试**：`ChatListenerTest.chatListenerDefaultMethodsDoNotRecurse`

### B2. PlanModePrompt FULL 提醒提到未注册工具名 AskUserQuestion
- **位置**：`src/main/java/com/acode/agent/PlanModePrompt.java:26`
- **现象**：FULL 版 plan 提醒指示模型「ask the user with **AskUserQuestion**」，但实际注册名是 **AskUser**（`AskUserTool.java`）。模型按提示调用会命中「未注册工具」错误。
- **修复**（commit 8dab489）：文案 `AskUserQuestion` 改为 `AskUser`。
- **回归测试**：`PlanModePromptTest.fullReminderMentionsOnlyRegisteredToolNames`

### B3. BashTool timeout_ms 超过默认超时被 BaseTool 外壳静默截断
- **位置**：`src/main/java/com/acode/tool/BaseTool.java:62` + `src/main/java/com/acode/tool/impl/BashTool.java:63-70`
- **现象**：BashTool 描述承诺「缺省 60 秒超时可用 timeout_ms 调整」，但 `BaseTool.execute` 外壳固定用 `future.get(defaultTimeoutMillis())`（Bash 为 60s）掐表。传入 `timeout_ms > 60000` 时，命令在 60s 处被外壳杀死并报「执行超时（上限 60000 ms）」，长超时覆盖被静默吞掉。
- **修复**（commit b7ae6b6）：外壳超时改用可覆盖的 `timeoutMillis(input)`，BashTool 覆盖为 `max(默认, timeout_ms)`。
- **回归测试**：`BashToolTest.timeoutShellHonorsToolOverrideBeyondDefault`

### B4. GlobTool / GrepTool 不过滤 .git 与 target 目录
- **位置**：`src/main/java/com/acode/tool/impl/GlobTool.java`、`GrepTool.java`（`Files.walk` 全量遍历）
- **现象**：搜索结果混入 `.git/config`、`target/classes` 等仓库元数据与构建产物；GrepTool 还会尝试读取 .git 内部二进制文件（解码失败被跳过，但浪费遍历）。
- **修复**（commit ef3b480）：改用 `Files.walkFileTree`，`preVisitDirectory` 对 `.git`/`target` 返回 `SKIP_SUBTREE`。
- **回归测试**：`GlobToolTest.globSkipsDotGitAndTargetDirectories`

## 已修复（阶段二遗留，本次专项修复）

### ChatMessage.role() 序列化缺陷（会话恢复丢角色）

- **位置**：`src/main/java/com/acode/provider/ChatMessage.java`（`role()` 访问器）、`src/main/java/com/acode/session/SessionStore.java`（存量修复）
- **现象**：`role()` 是普通类上的 record 风格 getter（无 `get` 前缀）且无 `@JsonProperty`，Jackson 对非 record 类只自动识别 `getX()/isX()` 命名，序列化时静默丢弃 role 字段（`blocks()` 因有 `@JsonProperty("content")` 未受影响）。
- **影响**：会话恢复后所有消息角色为 null——渲染丢 `● ` 前缀、user/assistant 显示混淆；`AnthropicProvider.buildBody` 的 `switch(message.role())` 与 `OpenAiProvider` 纯文本路径 `role().name()` 直接 NPE，恢复的会话发不出任何请求。ch03 改 class 后用户本机 21 个会话文件全部缺 role（ch02 record 时代正常）。
- **修复**（commit 9b4c436）：`role()` 补 `@JsonProperty("role")`，序列化恢复写出 role。回归测试：`ChatMessageTest.roleRoundTripsForAllRoles` + 既有 3 个往返测试补 role 断言（原测试漏掉 role 断言正是缺陷漏网原因）；`SessionManagerTest.restoreIfResumeTrueRestoresMessagesAndBanner` 恢复渲染断言改为修复后正确行为（用户消息带 ● 前缀）。
- **存量数据修复**（commit 6390310）：`SessionStore.read()` 汇聚处一次性推断角色——已带 role 原样保留、含 tool_use 块→ASSISTANT、含 tool_result 块→USER、纯文本按首条 USER 交替推断。**只在内存中修复，不改写任何会话文件**；下次 `/resume` 自动生效，重新保存后落盘新格式。回归测试：`SessionStoreTest.legacySessionWithoutRoleFieldRestoresInferredRoles` / `legacyTextOnlySessionAlternatesRolesFromUser` / `legacySessionWithRoleFieldUnaffected` / `savedSessionContainsRoleField`。
- **已知轻微边缘**：紧跟 tool_result 的截断续写提示（真实角色 USER）会被交替规则判成 ASSISTANT；Anthropic 合并相邻同角色消息，影响仅为该提示渲染无 ● 前缀，且仅存在于已缺 role 的存量文件，新保存文件不受影响。

## 已确认覆盖（记录备查）

- **awaitLoopEnd 超时后旧 agent 残留写入被 epoch 忽略**：控制器级无法编排滞留 agent（`ToolRegistry` 私有无注入点，无法注册吞中断桩工具；provider 阻塞也不滞留 agent 线程）。该行为由 `AgentTest.staleAgentCannotWriteIntoNextAgentTurn` 确定性覆盖；控制器侧可测部分由 `ConversationControllerTest.staleAgentResultIgnoredAfterAwaitLoopEndTimeout` 覆盖。
- **Agent.stream() isAlive→join**：已修复（commit 59d7119，#5），测试无需补。
- **双重重试预算**：已修复（commit c066b69，#13，流式请求改 sendNoRetry），HTTP 层 `send()` 保留给非流式路径与 RetryPolicyTest。
- **ReadFileTool offset/limit 全量读**：已修复（commit eb4e8c1，#9，单遍流式读），`ReadFileToolTest:69` 为回归防护。
- **PromptPipeline 等死代码**：已修复（commit 02b9206，#16），`PromptPipeline` 及其测试已删除。
