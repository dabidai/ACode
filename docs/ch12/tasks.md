# ch12 实施任务

每项在一次专注会话内完成；具体验收见 checklist.md。

| 任务 | 影响文件 | 依赖 | 参考定位 |
|---|---|---|---|
| T1 定义解析和工具过滤 | subagent/AgentDefinition.java、AgentDefinitionParser.java、ToolFilter.java；对应测试 | 无 | SkillParser.parse；ToolRegistry.availableList |
| T2 定义来源与内置角色 | subagent/AgentRegistry.java、resources/agents/*；AgentRegistryTest | T1 | AgentRegistry.loadDirectory；docs/ch12/checklist.md 默认值说明 |
| T3 会话与调用方上下文 | conversation/Conversation.java、tool/ToolContext.java；SubAgentRunnerTest、AgentToolTest | 无 | Conversation.buildRequestWithReminders、sanitize |
| T4 非交互执行底座 | subagent/SubAgentRunner.java、ForkBoilerplate.java；SubAgentRunnerTest | T1、T3 | Agent.run、cancel、awaitTermination；AgentEvent |
| T5 派发工具与递归边界 | subagent/AgentTool.java、agent/StreamingToolExecutor.java；AgentToolTest、SubAgentEndToEndTest | T2、T4 | StreamingToolExecutor.runCall；Tool 接口 |
| T6 配置开关 | config/AppConfig.java、ConfigLoader.java；ConfigLoaderTest | 无 | ConfigLoader.parseYaml、apply；ConfigValidator.validate（复用） |
| T7 权限与父级能力上限 | permission/PermissionChecker.java、subagent/SubAgentRunner.java；SubAgentPermissionTest | T4 | PermissionChecker.child、check；PathSandbox |
| T8 Skill 与 Hook 适配 | subagent/SkillForkAdapter.java、skill/{SkillRuntime,LoadSkillTool,SkillExecutor}.java、hook/{HookActions,HookEngine}.java；SubAgentAdaptersTest、既有 Skill 测试 | T4、T7 | SkillForkHost.execute；HookActions.execute；HookEngine.childScope |
| T9 接入主流程 | ConversationController.java、ui/{UIController,TerminalUIController}.java、README.md、docs/manual-test.md；AgentToolIntegrationTest | T5、T6、T8 | initSkills、connectMcp、subAgentRunner、commandProcessor；runForegroundTask |
| T10 端到端验证 | src/test/java/com/acode/subagent/*、docs/ch12/{checklist,interface-notes}.md；根目录三文档 | T9 | SubAgentEndToEndTest；AgentToolIntegrationTest；mvn test；mvn package |

表中 Java 相对路径均以 src/main/java/com/acode 为根，测试以 src/test/java/com/acode 为根；资源以 src/main/resources 为根。主 Agent 循环不增加子任务专用分支。前台命令取消适配仅扩展现有 UI 委托接口。
