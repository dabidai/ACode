# ch13 实施任务

Java 路径以 src/main/java/com/acode 为根；测试以 src/test/java/com/acode 为根。

| 任务 | 影响文件 | 依赖 | 参考定位 |
|---|---|---|---|
| T1 名称与 Git 子进程 | worktree/{WorktreeNames,GitRunner}.java；WorktreeManagerTest | 无 | BashTool；ProcessEnv；docs/ch13/checklist.md |
| T2 生命周期与身份核验 | worktree/WorktreeManager.java；WorktreeManagerTest | T1 | git worktree list --porcelain -z；PathSandbox |
| T3 元数据与会话恢复 | worktree/WorktreeManager.java；WorktreeManagerTest | T2 | SessionCodec；Jackson ObjectMapper |
| T4 配置与字段校验 | config/{AppConfig,ConfigLoader}.java；resources/config.yaml；WorktreeConfigTest | 无 | ConfigLoader.apply/load |
| T5 创建后设置 | worktree/{WorktreeSetup,IncludeMatcher}.java；WorktreeSetupTest | T2、T4 | GitRunner；Files.createSymbolicLink |
| T6 保守清理 | worktree/WorktreeManager.java；WorktreeManagerTest | T3、T5 | 元数据来源、基准提交、Git 状态与远端可达性 |
| T7 本地命令 | command/{BuiltinCommands,WorktreeCommand}.java；WorktreeEndToEndTest | T6 | Command.handler；CommandRegistry |
| T8 接入主流程 | ConversationController.java、ExchangeRunner.java、prompt/EnvironmentDetector.java、permission/PermissionChecker.java、agent/StreamingToolExecutor.java；README.md、docs/manual-test.md | T7 | exchangeRunner、resumeAtStartup、SkillForkAdapter、hookEngine |
| T9 端到端验证 | WorktreeEndToEndTest、worktree/*Test；根与章节三文档、test-review.md | T8 | FakeProvider、临时 git init；mvn test；mvn package |

## 纠正旧方案
- 不操作进程 cwd，也不调用 chdir 兜底。
- 临时名称与持久化 temporary 来源必须同时满足，命令创建始终 manual。
- 不执行共享 git config core.hooksPath；仅在已启用工作树配置扩展时写工作树专属配置，否则告警保留原配置。
- 已有目录恢复以 Git 注册、真实路径、公共 Git 目录和分支核验为准；未知基准禁止普通删除及自动清理。
- 创建失败不递归清除身份不明的残留；保留并提示检查。
- 清理不用强制参数，用户强制删除仍不能跳过路径及身份验证；当前会话先退出才可删除。
- 忽略文件逐文件枚举，复制不跟随链接且跳过管理目录；匹配器只实现声明的子集。

## 完成记录
T1–T9 已完成实现及自动化验收；全量 1281 tests，0 failures，0 errors，1 skipped，打包通过。真实交互终端项目保留在 checklist 待验项中。
