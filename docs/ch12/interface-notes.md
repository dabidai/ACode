# ch12 跨章接口与审核结论

## 已修正的规划冲突

- 保留实际存在的 `SkillForkHost` 并实现它，删除旧清单中要求其零引用的矛盾验收。
- 必填、未知键、YAML 和工具列表结构错误跳过文件；model/maxTurns/permissionMode 的非法值告警后降级，以原清单详细断言为准。
- 复用真实 `HookActions`，不新增文档早期假定的 `AgentAction` 类。
- 规范只描述能力与边界，具体参数、阈值及错误文本集中在 checklist.md；根目录同步当前章节，旧根目录三文档保存在 `.planning/ch12-implementation/`。
- ch14 的任务管理器与后台设施不在本章实现，仅保留后台工具常量和插件来源位置。

## Skill 链路

`SkillRuntime.prepare` → `SkillForkHost.execute` → `SkillForkAdapter` → `SubAgentRunner.run`。

适配器把渲染后的 Skill 正文作为任务消息，保留父系统提示与环境；allowedTools 作为定义白名单，model 作为请求模型；full 复制全部历史、recent 复制最近 5 条并清理孤立工具块、none 不复制历史。上下文选择不修改父历史。

模型通过 LoadSkill 触发时，最终成功或失败进入普通 tool_result；fork 返回的 activation 不携带父级待激活定义，避免正文和范围泄漏。斜杠命令通过 UI 的前台任务委托运行，主线程轮询 Ctrl+C，等待子任务清理后显示最终结果。两种入口均使用同一适配器。

子 Agent 中的 LoadSkill 使用独立 SkillRuntime；inline 只影响子会话，fork 返回递归拒绝。父活动 Skill 的工具范围与模型覆盖在装配时取快照，子定义不能扩张能力范围。

## Hook 链路

`HookActions.execute(AGENT)` → 装配的回调 → `SubAgentRunner.run(general-purpose)`。

Hook prompt 经上下文字段展开后作为独立任务；ToolResult 的成功标志和正文一并转换成 HookAction.Result，失败不被空字符串掩盖。没有装配回调的独立测试入口返回明确错误，不假装成功。

父 Hook 调度器可能在同步派发时持锁。子任务创建 Hook 子作用域，复用配置、动作实现和线程安全的会话 once 标记，独立维护锁、提醒队列与后台生命周期，避免父线程等子线程而子线程等父锁。子作用域不执行 AGENT 动作，防止绕过工具过滤间接递归；其他动作继续经过子权限检查。子作用域结束时关闭其待处理后台动作。

## 权限与运行结果

独立 PermissionChecker 保留规则引擎、工作根、临时目录及额外记忆根，但不复制会话内批准。父模式和定义模式取更严格者，规则拒绝、危险命令和路径沙箱继续生效。无交互 ASK 默认拒绝。

runner 的显式 per-dispatch approval 谓词仅供可信装配端传入精确范围；模型无法通过 Agent schema 填写它。生产派发不从任务自然语言猜测授权范围，也不把“批准 Agent 工具”解释为任意子调用获批。需要非交互写入或构建时，应配置明确工具规则或相应父权限模式，且仍受定义模式限制。

子事件在独立队列排空，父 UI 与统计不会混入子流式事件；最大轮数有文本返回成功并加摘要附注，无文本失败；普通空回复成功；provider 错误和取消失败。未注册自动记忆提取。Fork 报告格式由 Boilerplate 约束，不修改模型返回的原文。

## 验证入口

- `SubAgentAdaptersTest`：三种 Skill 历史选择、模型和参数映射、父激活不变、失败传播、Hook 嵌套防护、提示隔离、子 LoadSkill 隔离与 once 标记。
- `SubAgentEndToEndTest`：主 Agent 工具派发、子请求、tool_result 回填及父级最终回复；普通定义与 Fork 均覆盖。
- `AgentToolIntegrationTest`：真实 Controller 的派发、审批拒绝、启动告警、摘要显示和 plan 工具表。
- `SubAgentRunnerTest` / `SubAgentPermissionTest`：工具范围、父级能力上限、取消、队列背压、权限及沙箱。
- 全量统计和 jar 验证结果记录在 checklist.md；真实 provider 与终端项目保留待验收。
