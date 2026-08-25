# 测试补强发现的 Bug 清单

> 由专项测试补强（2026-08-25）发现并记录。凡标记「待修复」的 bug 均有对应 `@Disabled` 测试钉住（JUnit 统计为 skipped，不影响全绿）；修复后应摘掉 `@Disabled` 并确认测试转绿。

## 已钉住（有 @Disabled 测试）

### B1. ChatListener 双向 default 委托零覆写即 StackOverflowError
- **位置**：`src/main/java/com/acode/provider/ChatListener.java:18-31`
- **现象**：`onComplete()`（无参）默认实现委托 `onComplete(null)`，带参默认实现又委托回 `onComplete()`。任何只实现抽象方法、不覆写任一 `onComplete` 的监听器，收到完成信号即无限递归爆栈。
- **影响**：接口注释宣称「存量实现只覆写无参版仍能收到完成信号」，但零覆写场景（理论上合法）会崩溃。
- **钉住测试**：`ChatListenerTest.chatListenerDefaultMethodsDoNotRecurse`（`@Disabled`）
- **修复方向**：带参版默认实现改为空操作，无参版委托带参；或带参版 fallback 到无参版、无参版为空操作（二选一，避免环）。

### B2. PlanModePrompt FULL 提醒提到未注册工具名 AskUserQuestion
- **位置**：`src/main/java/com/acode/agent/PlanModePrompt.java:26`
- **现象**：FULL 版 plan 提醒指示模型「ask the user with **AskUserQuestion**」，但实际注册名是 **AskUser**（`AskUserTool.java`）。模型按提示调用会命中「未注册工具」错误。
- **钉住测试**：`PlanModePromptTest.fullReminderMentionsOnlyRegisteredToolNames`（`@Disabled`）
- **修复方向**：把文案中 `AskUserQuestion` 改为 `AskUser`。

### B3. BashTool timeout_ms 超过默认超时被 BaseTool 外壳静默截断
- **位置**：`src/main/java/com/acode/tool/BaseTool.java:62` + `src/main/java/com/acode/tool/impl/BashTool.java:63-70`
- **现象**：BashTool 描述承诺「缺省 60 秒超时可用 timeout_ms 调整」，但 `BaseTool.execute` 外壳固定用 `future.get(defaultTimeoutMillis())`（Bash 为 60s）掐表。传入 `timeout_ms > 60000` 时，命令在 60s 处被外壳杀死并报「执行超时（上限 60000 ms）」，长超时覆盖被静默吞掉。
- **钉住测试**：`BashToolTest.timeoutShellHonorsToolOverrideBeyondDefault`（`@Disabled`，测试把外壳默认压到 400ms 加速复现）
- **修复方向**：外壳超时应取 `max(defaultTimeoutMillis(), 参数 timeout_ms)`，或把超时读取下放到工具层、外壳只保留兜底上限。

### B4. GlobTool / GrepTool 不过滤 .git 与 target 目录
- **位置**：`src/main/java/com/acode/tool/impl/GlobTool.java:63`、`GrepTool.java:70`（`Files.walk` 全量遍历）
- **现象**：搜索结果混入 `.git/config`、`target/classes` 等仓库元数据与构建产物；GrepTool 还会尝试读取 .git 内部二进制文件（解码失败被跳过，但浪费遍历）。
- **钉住测试**：`GlobToolTest.globSkipsDotGitAndTargetDirectories`（`@Disabled`）
- **修复方向**：遍历时跳过 `.git`、`target` 等内部目录（参考实现均过滤），Glob 与 Grep 同步修。

## 已确认覆盖（无需改动，记录备查）

- **awaitLoopEnd 超时后旧 agent 残留写入被 epoch 忽略**：控制器级无法编排滞留 agent（`ToolRegistry` 私有无注入点，无法注册吞中断桩工具；provider 阻塞也不滞留 agent 线程——`stream()` 20ms 轮询取消即退）。该行为已由 `AgentTest.staleAgentCannotWriteIntoNextAgentTurn` 确定性覆盖；控制器侧可测部分（超时预算收缩后取消路径快速返回、UI 不挂死、新 exchange 立即可用）由 `ConversationControllerTest.staleAgentResultIgnoredAfterAwaitLoopEndTimeout` 覆盖。
- **Agent.stream() isAlive→join、双重重试预算（Agent MAX_RETRIES=2 vs RetryPolicy MAX_RETRIES=3）**：按任务约定不做补测，仅记录。
- **ReadFileTool offset/limit 全量读**：`ReadFileToolTest:69` 已覆盖 offset/limit 行为，全量读属已知实现缺陷，按任务约定不钉测。
- **PromptPipeline 等死代码**：按任务约定不动 `src/main/`，仅记录存在（`prompt/PromptPipeline.java` 未被主流程引用）。
