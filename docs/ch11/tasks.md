# ACode 阶段十一实施任务

主代码路径均相对 src/main/java/com/acode，测试路径相对 src/test/java/com/acode。详细验收见 docs/ch11/checklist.md。

1. **T1 配置加载与校验**：影响 hook/HookConfig.java、HookLoader.java、HookEvents.java、hook/HookLoaderTest.java。依赖：无。参考：docs/ch11/spec.md「配置与加载」、docs/ch11/checklist.md「默认值说明」。
2. **T2 条件与上下文**：影响 hook/HookCondition.java、HookContext.java、hook/HookConditionTest.java。依赖：T1。参考：HookCondition.parse、HookContext.expand；正则使用 find，glob 使用全串匹配。
3. **T3 分发引擎**：影响 hook/HookEngine.java、HookAction.java、hook/HookEngineTest.java。依赖：T2。参考：HookEngine.fire/loadOnceIds/close；顺序短路、失败隔离、异步代次与 once。
4. **T4 动作及权限**：影响 hook/HookActions.java、HookEngine.java、hook/HookActionsTest.java。依赖：T3。参考：permission/PermissionChecker.check、tool/impl/ShellDetector.commandPrefix。HTTP 权限工具名 HookHttp，命令沿用 Bash。
5. **T5 会话标记**：影响 session/SessionRecorder.java、SessionStore.java、hook/HookSystemEndToEndTest.java。依赖：T3。参考：SessionRecorder.addHookOnce/rewrite/bind、SessionStore.readHookOnceIds。
6. **T6 工具前后事件**：影响 agent/StreamingToolExecutor.java、hook/HookSystemEndToEndTest.java。依赖：T4。参考：StreamingToolExecutor.runCall；权限放行后 pre，实际执行返回后 post，交互工具同路。
7. **T7 轮次与提醒**：影响 agent/Agent.java、ExchangeRunner.java、conversation/Conversation.java、hook/HookSystemEndToEndTest.java。依赖：T3、T6。参考：Agent.run/emit/buildPlanAwareRequest、Conversation.buildRequestWithReminders；原重载继续保留，避免 null 调用歧义。
8. **T8 接入主流程**：影响 ConversationController.java、ConversationControllerTest.java、McpWiringTest.java、SessionResumeTest.java、README.md、docs/ch11/implementation.md。依赖：T5、T7。参考：Controller.hookEngine/start/startHookSession/activateSession/closeSession；初始化错误在全屏终端打开前打印，既有测试隔离 user.home。
9. **T9 端到端验证**：影响 HookControllerTest.java、hook/*Test.java、docs/manual-test.md、checklist.md、docs/ch11/checklist.md。依赖：T8。参考：真实 Controller + FakeProvider + 临时目录验证、mvn package 全量结果；未执行的真机项保持未勾选。

实施调整：动作分派集中在 HookActions，避免四个仅包装同一接口的小类；虚拟线程执行器归 HookEngine 所有，以便恢复/退出时取消；轮次事件在 Agent 线程执行，避免阻塞 UI 对取消与确认事件的处理。事件粒度不变。
