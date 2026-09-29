# ch14 阶段性测试记录

## T1 团队模型与配置存储
新增 Team、TeammateInfo、TeamManager，以及 TeamManagerTest。代码尚未注册到模型工具列表，后续按 T2–T12 接入。

覆盖：完整配置字段与往返、旧会话不恢复、内存/磁盘重名、最长名称后缀、不可变快照、活跃状态、移除成员、所有成员字段、名字与 Agent ID 冲突、坏配置降级告警、写盘失败不发布内存更新、路径穿越、符号链接/junction、防并发丢成员、跨管理器原子创建。

测试全部使用 JUnit 临时项目目录。坏配置告警使用注入接收器断言，避免为了测试日志去创建用户目录中的文件。并发注册每次测试包括 3 轮、每轮 10 个虚拟线程，以 CountDownLatch 对齐；两管理器同名创建用 CyclicBarrier。

初次沙箱运行因 Windows 临时目录 toRealPath 被拒产生 10 个错误；未改弱路径验证，使用正常本机权限重跑。首版 12 个用例连续 3 轮：0 failures、0 errors、1 skipped（符号链接创建权限）。最终新增 junction 测试后：13 tests，0 failures，0 errors，1 skipped，连续 3 轮通过。Windows junction 测试实际执行并通过；跳过项仍为符号链接权限。日志目录 `target/resize-validation/20260929-110050-818/`。

本章文档约定在 T12 才跑全量。进入本章前死锁修复全量为 1287 / 0 / 0 / 1；这不是新增团队代码之后的全量验收结果。真实 Agent 团队交互与模型请求尚未验证。

最终打包通过：`target/resize-validation/20260929-110202-534/Package-1.*.log`，退出 0，32.98 秒。生成的 target/acode.jar 包含团队基础类与本轮 UI 死锁修复。尚未运行 ch14 全章的全量与真实团队端到端验收（T12 待实现）。
