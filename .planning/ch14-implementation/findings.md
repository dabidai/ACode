# Findings

已有全量证据：死锁修复后 1287 tests / 0 failures / 0 errors / 1 skipped，186 秒；新增专项主循环测试通过。ch13 已实现并记录 1281 历史基线。ch14 文档为 2026-09-13 设计，源代码没有 team 包。
T1 复用 WorktreeNames 校验，额外拒绝团队/成员名称里的目录分隔符；不允许 .acode/teams 或配置文件链接逃逸。构造管理器不恢复旧团队，碰撞通过原子创建目录避免跨实例覆盖。配置更新先原子写盘再发布不可变内存快照；失败不发布半成品。读取坏配置告警并返回空快照，不覆盖原文件。

T2/T3：锁文件检查必须在稳定的 OS guard 之内，否则上一持有者释放时会导致 exists/toRealPath 竞态。单 JVM 多次连续写入可让其他线程耗尽十次随机重试，改成固定 256 分片公平信号量、最多等待 5 秒后再做跨进程重试。guard 持锁全程保留，不删除 guard 文件。
邮箱增加 UUID，按读取批次确认，避免 markAllRead 吞掉请求期间新到的消息。任务只存正向依赖，反向依赖与 blocked 派生；坏文件读空告警但写拒绝覆盖。移除成员先回滚任务再移除名册；两文件非原子事务，运行时必须先停止，配置写失败允许重试。

T4–T12 实际装配集中在 TeamSession，工具身份由宿主绑定，禁止普通子 Agent 继承 Lead 工具。消息先落盘再唤醒，成功模型响应后确认本批；失败保持未读。TeammateRuntime 保留 Conversation，监听 transcript 追加/重建，空闲续写前从盘恢复。审批按 tool+完整 input 匹配，Hook 只读防旁路。
权限执行器调用 check(tool,args,workingDirectory)，child 只覆盖二参会漏掉交集；已补三参覆盖。系统临时目录是既有允许根，测试的 @TempDir 父目录不能作为“越界”反例。LoadSkillTool 的 instanceof 分阶段执行不能被只读代理包装破坏。Worktree 创建抛错时禁止尝试清理同名既有目录，仅回收本次成功分配的路径。
