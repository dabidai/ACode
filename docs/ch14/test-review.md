# ch14 阶段性测试记录

## T1 团队模型与配置存储
新增 Team、TeammateInfo、TeamManager，以及 TeamManagerTest。代码尚未注册到模型工具列表，后续按 T2–T12 接入。

覆盖：完整配置字段与往返、旧会话不恢复、内存/磁盘重名、最长名称后缀、不可变快照、活跃状态、移除成员、所有成员字段、名字与 Agent ID 冲突、坏配置降级告警、写盘失败不发布内存更新、路径穿越、符号链接/junction、防并发丢成员、跨管理器原子创建。

测试全部使用 JUnit 临时项目目录。坏配置告警使用注入接收器断言，避免为了测试日志去创建用户目录中的文件。并发注册每次测试包括 3 轮、每轮 10 个虚拟线程，以 CountDownLatch 对齐；两管理器同名创建用 CyclicBarrier。

初次沙箱运行因 Windows 临时目录 toRealPath 被拒产生 10 个错误；未改弱路径验证，使用正常本机权限重跑。首版 12 个用例连续 3 轮：0 failures、0 errors、1 skipped（符号链接创建权限）。最终新增 junction 测试后：13 tests，0 failures，0 errors，1 skipped，连续 3 轮通过。Windows junction 测试实际执行并通过；跳过项仍为符号链接权限。日志目录 `target/resize-validation/20260929-110050-818/`。

本章文档约定在 T12 才跑全量。进入本章前死锁修复全量为 1287 / 0 / 0 / 1；这不是新增团队代码之后的全量验收结果。真实 Agent 团队交互与模型请求尚未验证。

最终打包通过：`target/resize-validation/20260929-110202-534/Package-1.*.log`，退出 0，32.98 秒。生成的 target/acode.jar 包含团队基础类与本轮 UI 死锁修复。尚未运行 ch14 全章的全量与真实团队端到端验收（T12 待实现）。

## T2/T3 文件锁、邮箱与任务存储（2026-09-29）

新增 TeamFileLock、TeamJsonFile、FileMailbox、AgentNameRegistry、TeamTaskStore 及四个测试类；TeamManager.removeMember 接入任务回滚。

最终定向命令：`pwsh -NoProfile -File scripts/verify-resize-deadlock.ps1 -Mode Target -Tests TeamManagerTest,TeamFileLockTest,FileMailboxTest,AgentNameRegistryTest,TeamTaskStoreTest -Rounds 3`。

三轮分别 72.91 / 61.41 / 62.10 秒；每轮 **31 tests / 0 failures / 0 errors / 1 skipped**。共 93 次用例执行、其中 3 次环境跳过；跳过仍是 T1 的符号链接创建权限，junction 实际通过。日志与 XML 快照：`target/resize-validation/20260929-140116-045/`。

覆盖：同线程进程内竞争与跨 JVM 互斥、存活/退出/无法确认持有者、替换 token 释放保护、锁忙超时和中断；邮箱每轮内部 3 次八线程各写五条，条数/唯一 ID/发送者正文组合均为 40；仅确认已读取批次、迟到邮件未读、重复确认、字段往返、坏文件写保护；名称/ID 冲突；任务内部 5 次双线程跨实例认领、依赖双向写法、二/三节点循环、自依赖、所有权、失败事务不落盘、成员移除仅回滚其未完成任务。

测试发现并修正两个问题：
- 锁文件在 exists 与 toRealPath 之间被上任持有者删除，导致偶发 NoSuchFileException；将逻辑锁检查放入稳定 OS guard 的保护范围。
- 单 JVM 写者竞争随机重试产生饥饿；加固定分片的公平有界排队，不增加跨进程重试次数。所有并发测试均由 latch/barrier 或进程标准输入输出握手控制，无 sleep 猜时序。

首次沙箱执行因临时目录真实路径访问受限报错；未弱化路径防护，后续以正常本机权限运行。没有修改数据库或用户配置。T7 尚未把确认接到模型请求交接点，当前仅验证存储 API；跨文件成员移除不是事务，配置写失败后可重试。T4–T12、全章端到端及全量回归仍待完成。

本轮打包退出 0，44.06 秒，更新 target/acode.jar；日志 `target/resize-validation/20260929-140549-467/Package-1.*.log`。git diff --check 通过；测试源码无 Thread.sleep，生产邮箱无文件 APPEND 写入。
