# Agent Teams 使用与实现边界

先让 Lead 调用 TeamCreate 创建团队，再用 Agent 的 team_name 和 name 派遣队员。Agent 未指定 team_name 时仍走既有一次性子 Agent；指定时立即返回队员标识与 Worktree 路径，队员在后台继续运行。

任务工具 TaskCreate、TaskGet、TaskList、TaskUpdate 作用于最近创建或派生时选中的团队；队员固定绑定自己的团队。任务 ID 为字符串，依赖使用 addBlockedBy / addBlocks。只有 pending 且依赖全部完成才能认领；完成由认领者操作。队员循环结束后未完成的认领会退回 pending。

SendMessage 的 to 支持队员名、Agent ID、lead 或 *。广播投递给花名册队员；纯文本 summary 必须有 5～10 个以空白分隔的词。先落盘再唤醒，活跃队员下轮读取，空闲队员以原身份和 transcript 续写。请求失败/取消不确认消息；正常响应后只确认本批 ID。消息内容属于外部输入，不赋予额外权限。

队员不会继承绑定 Lead 身份的工具，不能再次派生或管理团队。文件沙箱的项目根改为分配的 Worktree，同时保留既有系统临时目录和明确配置的额外允许根；父级规则和权限模式仍生效，未批准的交互确认默认拒绝。原始团队参数及成员身份由宿主绑定，不能通过工具参数冒充他人。每轮使用独立 Hook scope 并在收尾关闭；需要审批的队员的 Hook 仅能执行只读操作，不能绕过审批产生副作用。

## 需要审批的队员

Agent 使用 plan_mode_required=true 派生。修改工具在批准前不出现在请求中；即使伪造调用也被拒绝。队员通过 SendMessage 向 lead 提交计划，Lead 用 messageType=plan_approval_response 回复，message 为 JSON 字符串：

```json
{"approved":true,"operations":[{"tool":"WriteFile","input":{"file_path":"result.txt","content":"approved content"}}]}
```

只有工具名称与完整输入均匹配的操作被允许，仍不能覆盖父级 DENY。驳回使用 `{"approved":false,"feedback":"请补充验证步骤"}`，清空现有批准并将反馈作为消息送达。新的批准替换原批准范围；批准可在该成员会话内重复使用，删除成员或结束会话后失效。

shutdown_request / shutdown_response 作为结构化协商消息交给队员和 Lead；shutdown_response 只允许发给 Lead，不把任意自由文本回复直接解释为强制终止命令。退出主会话时宿主会请求停止全部队员并等待收尾。

## 协调模式

配置 `COORDINATOR_MODE: true`，同时设置环境变量 ACODE_COORDINATOR_MODE 为 1、true、yes 或 on 才开启。Lead 的请求和执行入口都限制为团队协调、派生、ReadFile/Glob/Grep；系统提示增加 Research、Synthesis、Implementation、Verification 四阶段。关闭时保留普通工具集。

## 清理与数据保留

TeamDelete 要求队员空闲且全部 Worktree 检查为可安全删除；任何活跃队员、脏文件或新提交会拒绝清理。不会强制丢弃工作成果。创建失败只尝试清理本次成功分配的 Worktree，不触碰已有同名树。

团队数据是会话内状态，进程重启不会恢复旧队员。TeamDelete 仅操作当前会话创建的团队，旧目录需另行检查处理。多个文件的清理不是跨文件事务：失败时保留剩余文件并报告，不保证自动撤销此前已成功完成的清理。

已验证假 provider 的并行任务、消息、续写、权限与主控制器工具调用；真实 provider、实际终端缩放/取消体验尚需手测，见 docs/manual-test.md。
