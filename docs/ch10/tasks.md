# ACode 阶段十：Skill 系统 — 实施任务

> 本文按当前代码核对（2026-09-26）。每项可在一次专注会话完成；只规划，不代表已经实现。具体默认值、文案和可观测断言见 `checklist.md`。阶段十文档位于 `docs/ch10/`；根目录同名文档目前属于另一项修复，未经确认不覆盖。

## T1 定义与解析

- **工作**：建立不可变定义与 YAML frontmatter 解析；校验必填字段、枚举、占位符和目录名一致性；异常含来源与字段。
- **影响文件**：新建 `src/main/java/com/acode/skill/SkillDefinition.java`、`SkillParser.java`；新建 `src/test/java/com/acode/skill/SkillParserTest.java`。
- **依赖任务**：无。
- **参考资料**：`docs/ch10/prompt.md`「定义文件」；`src/main/java/com/acode/config/ConfigLoader.java` 的 YAML 读取；`docs/ch10/checklist.md` T1。

## T2 三层发现与内置资源索引

- **工作**：扫描项目、用户和内置来源，确定覆盖关系；支持单文件和目录型；为 jar 内置资源建立显式可枚举清单；坏文件跳过，高优先级坏文件不遮住低优先级有效文件；建立按来源隔离的最近有效缓存。
- **影响文件**：新建 `src/main/java/com/acode/skill/SkillRepository.java`、`SkillSource.java`、`src/main/resources/skills/index.txt`；新建 `src/test/java/com/acode/skill/SkillRepositoryTest.java` 与测试资源。
- **依赖任务**：T1。
- **参考资料**：`docs/ch10/prompt.md`「三个地方找 Skill」「热加载」；`src/main/java/com/acode/ConversationController.java` 的 `projectRoot` 与 `userHome`；`docs/ch10/checklist.md` T2。

## T3 会话运行态与激活决策

- **工作**：把已激活定义、内容版本、来源、白名单和请求模型维护成会话快照；统一命令与模型路径的依赖检查、同版本去重、变更版本替换、参数替换与失败返回；定义多 Skill 叠加和重载后的行为，保证失败不改变状态。
- **影响文件**：新建 `src/main/java/com/acode/skill/SkillRuntime.java`、`SkillActivation.java`；新建 `src/test/java/com/acode/skill/SkillRuntimeTest.java`。
- **依赖任务**：T1、T2。
- **参考资料**：`src/main/java/com/acode/tool/ToolRegistry.java` 的 `available`/`availableList`；`src/main/java/com/acode/conversation/Conversation.java` 的历史代次；`docs/ch10/checklist.md` T3。

## T4 系统提示索引

- **工作**：按稳定顺序把名称与描述加入提示词；空索引保持原提示字节不变；对描述里的换行和控制字符做单行化，避免把文件内容变成额外的系统指令；支持重载重建。
- **影响文件**：修改 `src/main/java/com/acode/prompt/PromptBuilder.java`、`PromptSections.java`；新建 `src/test/java/com/acode/skill/SkillPromptTest.java`。
- **依赖任务**：T2。
- **参考资料**：`PromptBuilder.buildSystemPrompt`、`PromptSections` 的段落优先级、`src/test/java/com/acode/prompt/PromptBuilderTest.java`；`docs/ch10/checklist.md` T4。

## T5 动态命令与管理入口

- **工作**：注册 Skill 命令和 `/skill`；提供 list/info/reload；处理与正式命令及别名的冲突；重载只移除自己注册的命令，并使帮助、补全、来源和提示索引一致。命令处理先接运行态门面，实际发起对话在 T9 完成。
- **影响文件**：新建 `src/main/java/com/acode/skill/SkillManager.java`；修改 `src/main/java/com/acode/command/CommandRegistry.java`、`BuiltinCommands.java`、`CommandContext.java`；新建 `src/test/java/com/acode/skill/SkillCommandTest.java`。
- **依赖任务**：T2、T3、T4。
- **参考资料**：`CommandRegistry.register/find/visible`、`CommandDispatcher.dispatch`、`BuiltinCommands.review`、`src/main/java/com/acode/ui/SlashCompleter.java`；`docs/ch10/checklist.md` T5。

## T6 请求与执行双重工具边界

- **工作**：在请求构建前计算允许工具集合与请求模型；在执行入口用同一轮快照再次核验，拒绝未获允许的工具调用；与 Plan 模式的只读集合及既有权限检查取交集；原注册表不变。后连接的 MCP 工具在可用时参与依赖检查。
- **影响文件**：修改 `src/main/java/com/acode/agent/Agent.java`、`StreamingToolExecutor.java`、`src/main/java/com/acode/conversation/Conversation.java`（请求级模型覆盖入口）；新增 `src/test/java/com/acode/skill/SkillToolBoundaryTest.java`，必要时补 `src/test/java/com/acode/agent/AgentPlanModeTest.java`。
- **依赖任务**：T3。
- **参考资料**：`Agent.buildPlanAwareRequest/normalTools/planTools/executeTools`、`StreamingToolExecutor.runCall`、`ToolRegistry.availableList`；`docs/ch10/checklist.md` T6。

## T7 模型加载工具与历史顺序

- **工作**：实现加载工具；工具执行阶段只生成激活候选与简短结果，Agent 在同批所有工具结果写入后再追加独立正文消息并提交激活；新版本追加正文时注明取代旧版本。取消、失败、历史代次变化时不提交。覆盖并发只读工具与同批多个调用的顺序。
- **影响文件**：新建 `src/main/java/com/acode/skill/LoadSkillTool.java`；修改 `src/main/java/com/acode/agent/Agent.java`、`src/main/java/com/acode/conversation/Conversation.java`（仅必要的批次提交接口）；新建 `src/test/java/com/acode/skill/LoadSkillToolTest.java`、`SkillHistoryOrderTest.java`。
- **依赖任务**：T3、T6。
- **参考资料**：`Agent.runTurn/executeTools`、`StreamingToolExecutor.execute`、`Conversation.addToolResults/sanitize`、`src/test/java/com/acode/agent/AgentIntegrationTest.java`；`docs/ch10/checklist.md` T7。

## T8 内置提交与测试 Skill

- **工作**：编写两份 jar 内置定义；提交流程区分已暂存和未暂存变更，并遵守现有权限；测试流程依据项目构建方式选择命令，覆盖率仅在存在报告时给出实测值，否则明确未测量。
- **影响文件**：新建 `src/main/resources/skills/commit/SKILL.md`、`skills/test/SKILL.md`；更新 T2 的 `skills/index.txt`；新建 `src/test/java/com/acode/skill/BuiltinSkillTest.java`。
- **依赖任务**：T1、T2。
- **参考资料**：`docs/ch10/prompt.md`「两个内置 Skill」；`pom.xml`（当前未配置覆盖率插件）；`docs/ch10/checklist.md` T8。

## T9 斜杠命令执行与 fork 占位

- **工作**：让 Skill 命令复用激活逻辑，经现有对话入口开始 Agent 轮次；支持热解析和参数替换；定义 fork 委托接口并在当前阶段返回明确未实现结果，失败时不启动对话。
- **影响文件**：新建 `src/main/java/com/acode/skill/SkillExecutor.java`、`SkillForkHost.java`；修改 `SkillManager.java`；新建 `src/test/java/com/acode/skill/SkillExecutorTest.java`。
- **依赖任务**：T3、T5、T6、T7。
- **参考资料**：`BuiltinCommands.review` 与 `UIController.submitUserInput`、`CommandDispatcher.execute`；`docs/ch12/spec.md` 的 Skill fork 接口约定；`docs/ch10/checklist.md` T9。

## T10 生命周期、热加载和快照一致性

- **工作**：联动 `/clear`、压缩重建、会话恢复；失效时撤销白名单和模型覆盖并提示重新加载；重载原子更新下一次查找的仓库、命令和索引，不改变正在执行的定义快照。
- **影响文件**：修改 `SkillRuntime.java`、`SkillManager.java`、`src/main/java/com/acode/ConversationController.java`；新建 `src/test/java/com/acode/skill/SkillLifecycleTest.java`。
- **依赖任务**：T4、T5、T7、T9。
- **参考资料**：`Conversation.addClearHook/addRebuildListener/replaceAll`、`CompactExecutor.run`、`ConversationController.activateSession`、`ExchangeRunner.setPendingReminder`；`docs/ch10/checklist.md` T10。

## T11 接入主流程

- **工作**：在终端启动、MCP 连接完成和会话初始化的正确时点装配仓库、加载工具、命令、提示索引、Agent 过滤器和生命周期钩子；更新使用文档与手测步骤，保持现有 `/review` 行为。
- **影响文件**：修改 `src/main/java/com/acode/ConversationController.java`、`ExchangeRunner.java`、必要的 `command/CommandContext.java`；更新 `README.md`、`docs/manual-test.md`。
- **依赖任务**：T4～T10。
- **参考资料**：`ConversationController` 构造器、`connectMcp/initSessionState/commandProcessor`，`ExchangeRunner.run`；`docs/ch09/checklist.md`；`docs/ch10/checklist.md` T11。

## T12 端到端验证

- **工作**：使用假 provider 和临时项目核验命令、模型加载、工具边界、历史顺序、重载、清空和 fork 错误；运行定向与全量测试，按实际终端与真实 provider 手测；记录未执行的真机项，不提前勾选。
- **影响文件**：新建 `src/test/java/com/acode/skill/SkillEndToEndTest.java`；更新 `docs/ch10/checklist.md`、`docs/manual-test.md` 的实际验收状态。
- **依赖任务**：T11。
- **参考资料**：`src/test/java/com/acode/provider/FakeProvider.java`、`src/test/java/com/acode/command/CommandSystemEndToEndTest.java`、`src/test/java/com/acode/agent/AgentIntegrationTest.java`；`docs/ch10/checklist.md` T12。

## 实施状态（2026-09-26）

T1–T11 已落地，使用说明见 `implementation.md`。T12 的自动化与打包结果见 `checklist.md`；真实终端、真实 provider 三项手测保留待验收。

结合实际代码的调整：
- 内置工具实际名为 `ReadFile`，内置 Skill 使用该名称。
- 命令管理通过闭包持有门面，无需给 `CommandContext` 添加闲置依赖；原 `/review` 保留。
- `UIController.submitPreparedInput` / `ExchangeRunner.runPrepared` 将命令激活和独立正文写入放到同一个历史提交边界。
- 并发工具调用只暂存 Skill 候选，完整批次结果之后才按声明顺序注入正文。
- 测试按六个实际测试类聚合，而非为每个任务重复建立夹具；对应关系见验收清单。
