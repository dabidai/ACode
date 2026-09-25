# ACode 执行边界与异步生命周期修复任务

## T1 确认现状与策略

- 影响文件：`spec.md`、`tasks.md`、`checklist.md`
- 依赖任务：无
- 参考资料：`DangerousCommandDetector.isSafeCommand`、`McpServerConnection.callTool`、`MemoryExtractionScheduler.triggerAsync`、`ExchangeRunner.awaitLoopEnd`
- 工作：固定四项风险的触发场景、策略和验收口径。

## T2 修复安全命令判定

- 影响文件：`src/main/java/com/acode/permission/DangerousCommandDetector.java`、对应测试
- 依赖任务：T1
- 参考资料：`DangerousCommandDetector.containsShellMetachar`、`PermissionChecker.check`
- 工作：只允许单条、无命令分隔或重定向的已知只读命令走自动放行。

## T3 验证并提交权限修复

- 影响文件：T2 文件、`checklist.md`
- 依赖任务：T2
- 参考资料：`DangerousCommandDetectorTest`、`PermissionCheckerTest`
- 工作：运行针对性测试，只暂存本项文件，提交并推送。

## T4 修复 MCP 断线语义

- 影响文件：`src/main/java/com/acode/mcp/McpServerConnection.java`、对应测试
- 依赖任务：T1
- 参考资料：`McpServerConnection.callTool`、`McpClient.sendRequest`
- 工作：连接前恢复可自动进行；写请求发出后连接失败按结果未知处理，避免盲目重发。

## T5 验证并提交 MCP 修复

- 影响文件：T4 文件、`checklist.md`
- 依赖任务：T4
- 参考资料：`McpToolWrapperTest`、`McpEndToEndTest`
- 工作：验证断线边界，只暂存本项文件，提交并推送。

## T6 修复记忆写回竞态

- 影响文件：`src/main/java/com/acode/memory/MemoryExtractionScheduler.java`、对应测试
- 依赖任务：T1
- 参考资料：`MemoryExtractionScheduler.triggerAsync/reset`、`MemoryExtractor.apply`
- 工作：同步代次校验、写回与重置，并保留异步提取能力；验证后独立提交推送。

## T7 修复取消生命周期

- 影响文件：`src/main/java/com/acode/ExchangeRunner.java`、`src/main/java/com/acode/agent/Agent.java`、`src/main/java/com/acode/agent/StreamingToolExecutor.java`、对应测试
- 依赖任务：T1
- 参考资料：`ExchangeRunner.run/awaitLoopEnd`、`Agent.cancel/executeTools`、`StreamingToolExecutor.runConcurrently`
- 工作：确保旧工具执行结束后再启动下一轮；验证后独立提交推送。

## T8 接入主流程

- 影响文件：`src/main/java/com/acode/ConversationController.java`、`src/main/java/com/acode/ExchangeRunner.java`、`checklist.md`
- 依赖任务：T3、T5、T6、T7
- 参考资料：`ConversationController.handleExchange`、`ExchangeRunner.run`
- 工作：确认四项行为从真实会话入口生效，必要改动并入对应风险提交。

## T9 端到端验证

- 影响文件：`checklist.md`、本任务执行记录
- 依赖任务：T8
- 参考资料：`AgentIntegrationTest`、`McpEndToEndTest`、`MemorySystemEndToEndTest`
- 工作：跑定向与全量回归，核对四次提交和远端分支状态。
