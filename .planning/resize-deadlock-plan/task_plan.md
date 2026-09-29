# Resize 死锁修复规划

- [complete] 恢复上下文，审查旧报告、代码、线程转储和字节码。
- [complete] 用户确认范围，编写根目录 spec/tasks/checklist。
- [complete] 核对三文档边界、任务依赖和参考定位，交付计划。

本次规划已完成，T1–T8 实施尚未开始。用户确认：消除死锁、核查直接绘制与回调的锁顺序、补并发测试和进程超时兜底，保留现有界面行为。未运行测试、未修改生产代码或数据库、未提交。
根目录原 ch13 三文档已逐字备份到本目录 previous-*.md；根文档现描述本次修复。

## 错误与处理
- 历史规划读取输出过长被截断：采用定点读取补足本次需要的调用链，不把历史结果当本次验收。
- 查找 ui/InputFrame.java 不存在：已定位为 CommandProcessor.InputFrame 嵌套接口并修正文档引用。

## 实施开始
- [in_progress] T1–T3 锁审查与受控复现、进程兜底。
- [pending] T4–T6 修复、分支验收、压力测试。
- [pending] T7–T8 主流程和全量验收。
发现 JLine 3.30.16 的 lock 实为 protected final（原报告称 private 不准确）。优先复用该可重入锁，不增加 paintLock。统一顺序为 JLine lock → 上游 handleSignal monitor / ScreenRenderer / Status；所有应用覆写不再先占 reader monitor。readLine 等待输入阶段不持有额外锁；footer 回调在释放本次获取的锁后执行。


## 实施收尾
T1–T7 complete；T8 自动化与打包 complete，真实终端人工视觉验收 pending。全部结果见 docs/ui-resize-deadlock/test-review.md。用户 2026-09-29 heartbeat 授权继续章节开发，下一阶段进入 ch14 T1。
