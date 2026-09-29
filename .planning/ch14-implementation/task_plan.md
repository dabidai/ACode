# ch14 实施进度

目标：按现有章节文档逐项实现 Agent Teams。T1–T11 代码已接入，T12 自动化与打包已通过，真实 provider/终端体验仍需验收。

- [complete] T1 不可变团队与成员模型、线程安全管理器、原子配置与并发测试。
- [complete] T2 文件锁、邮箱和名称注册表。
- [complete] T3 共享任务存储。
- [complete] T4 任务工具四件套及工具路径测试。
- [complete] T5 SendMessage 工具。
- [complete] T6 TeamCreate/TeamDelete 工具。
- [complete] T7 队员运行时与权限过滤。
- [complete] T8 transcript 与空闲续写。
- [complete] T9 派生与 Worktree 隔离。
- [complete] T10 协调模式和审批。
- [complete] T11 主流程接入与文档补齐。
- [complete] T12 自动化端到端、全量回归、最终边界补测与打包。
- [pending] 真实 provider / 终端手测验收（docs/manual-test.md 阶段十四）。

约束：无数据库操作；保留全部既有改动；不提交。首次 T1 仅实现可编程接口，不宣称模型已有 TeamCreate 工具（T6/T11 尚待实现）。新测试使用临时项目目录。

本轮继续 T2 与 T3：使用持有者标识与进程身份的文件锁，先实现邮箱的显式确认，再实现原子任务认领、依赖图与成员退出回滚。文件损坏时读取告警返回空；写操作失败保留损坏文件，防止隐式清空历史。共享存储在整个持锁期间保留 OS guard，保护逻辑锁的比较/删除，避免旧锁回收与新持有者创建之间的竞态。同 JVM 写者有界公平排队，避免连续写入导致其他写者耗尽随机重试。
