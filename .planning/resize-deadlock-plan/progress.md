# Progress

2026-09-28：用户要求制定修复计划；已提出范围选择、备份根三文档。仅读代码和诊断材料，未改生产代码或数据库，未执行测试。技能 catchup 脚本执行退出码为零，无输出。

2026-09-28：用户确认推荐范围；完成根 spec.md/tasks.md/checklist.md。人工核对 8 个任务依赖与最后两项角色；所有验收项保持未勾选。git diff --check 用于文档格式检查，未运行编译/测试。交付仅为修复计划。

受控复现完成：旧生产源码未修改时 3/3 次进入同一等待环，输入虚拟线程 BLOCKED 于 redisplay 的 reader monitor，信号线程 WAITING 于 JLine ReentrantLock。日志目录 target/resize-validation/20260928-203115-159、203124-542、203133-811。每次约 8.5 秒后隔离 JVM 返回 2。第一次探针误用了尚未设置的 getKeyMap，已改为 MAIN（这三次有效证据均为修正后的探针）。
已实施共享继承 lock 的修复；同锁保护 frame 初始化/结束、mouse、completionOverlay、redisplay 和 signal；footer 回调移至锁外。小窗口降级不重放过期 frameLines。

T1–T5 已完成实现和第一轮测试；T7 主流程测试已扩展并通过。T6 10 轮压力测试正在串行运行（每轮 59 tests），T8 全量和打包待执行。已检查生产供应器只读取业务状态及 OutputPane 快照，无反向 reader 调用。

2026-09-29：核对旧进程已结束、日志报告全量 1287/0/0/1；打包退出0。同步完成清单（保留真实终端待验）。转入 ch14 基础开发。
