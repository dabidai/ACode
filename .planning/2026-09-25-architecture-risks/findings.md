# 架构风险核查

- `DangerousCommandDetector.isSafeCommand` 允许安全命令加空格前缀，但禁用字符未覆盖换行、单个 `&`；`PermissionChecker` 在规则与确认前直接放行。
- `McpServerConnection.callTool` 遇连接异常会重连并重发 `tools/call`，即使第一次请求可能已在远端执行。
- `MemoryExtractionScheduler` 在代次校验之后才调用 `extractor.apply`，重置可插入两者之间；`apply` 逐项落盘。
- `ExchangeRunner` 取消后最多等待 5 秒；`Conversation` 的 epoch 防止旧历史写入，但不限制旧工具外部副作用。
- 工作树已有用户改动，提交必须使用显式路径。
