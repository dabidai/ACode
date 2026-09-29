# Resize 死锁修复任务

状态：T1–T7 及 T8 自动化验证、打包已完成，真实终端视觉验收待执行。按 T1 → T8 串行推进；每项可在一次专注会话内完成。
Java 主代码路径基于 `src/main/java/com/acode/`，测试基于 `src/test/java/com/acode/`。标为“按需”的生产文件仅在发现必要兼容调整时修改。

| 任务 | 工作内容与交付物 | 影响文件 | 依赖 | 参考资料定位 |
|---|---|---|---|---|
| T1 固化证据与锁依赖 | 保存可审查诊断摘要，列出 reader monitor、JLine lock、绘制锁、ScreenRenderer/Status 及回调间的获取方向；选定直接绘制与共享状态保护规则 | 新建 `docs/ui-resize-deadlock/lock-review.md` | 无 | `ui/ResizeAwareLineReader.java`：redisplay、handleSignal、mouse、paintHistory、readLineFramed；`ui/ScreenRenderer.java`：edit、refresh；`target/LineReaderImpl.javap.txt`：readLine、handleSignal、redisplay；`target/threaddump.txt`：main 栈；原始报告 `C:/Users/liuch/Desktop/ACode.txt` |
| T2 建立挂起隔离与超时兜底 | 新增只管理本次进程树的串行验证脚本；固定 JDK，提供命令总时限、诊断限时、失败退出码、日志；为故意阻塞的探针验证清理，不在常规测试 JVM 内留下死锁线程 | 新建 `scripts/verify-resize-deadlock.ps1`；新建测试辅助类 `ui/ResizeDeadlockProbe.java`；`docs/ui-resize-deadlock/test-review.md` | T1 | `pom.xml`：Surefire 配置；`ui/ResizeAwareLineReaderTest.java`：preservesEditedTextAcrossResizeStorm；`docs/ch13/test-review.md`：内存受限时运行参数 |
| T3 编写可控并发回归 | 用闩锁/屏障安排持有上游锁的输入线程与缩放信号交错；优先使用可注入终端/回调观察点；建立旧实现失败、新实现成功的同一用例；测试接缝如必需应保持包内且不能改变生产锁语义 | 新建 `ui/ResizeAwareLineReaderConcurrencyTest.java`；`ui/ResizeDeadlockProbe.java`；按需 `ui/ResizeAwareLineReader.java`；锁审查及测试报告 | T2 | JLine readLine 持锁调用 redisplay；`ui/InputPane.java`：构造器及 readLine 重载；现有 ResizeAwareLineReaderTest 的虚拟线程/管道终端 fixture |
| T4 修复锁顺序与绘制边界 | 移除造成反转的同步；建立窄绘制锁或等效规则；检查 mouse/finally/补全所有入口、buf/size/供应器快照；外部回调避免持绘制锁调用；复核小窗口回退及 CONT 信号路径 | `ui/ResizeAwareLineReader.java`；按需 `ui/ScreenRenderer.java`；`docs/ui-resize-deadlock/lock-review.md` | T3 | redisplay、handleSignal、paintHistory、paintFrameAndHistory、completionOverlay、readLineFramed；`CommandProcessor.InputFrame.resize`；ScreenRenderer 的 synchronized 方法 |
| T5 补分支与生命周期验收 | 覆盖普通/固定框/全屏、重复同尺寸信号、小窗口恢复、多行中文与补全、滚动与缩放、输入结束与 resize 交错、回调异常以及退出 | `ui/ResizeAwareLineReaderTest.java`、`ui/ResizeAwareLineReaderConcurrencyTest.java`、`ui/FullscreenLifecycleTest.java`、`ui/FullscreenBoundaryTest.java` | T4 | preservesEditedTextAcrossResizeStorm、preservesMultilineBufferAcrossResize、wheelScrollsConversationWhileKeepingTheInputBuffer；editorResizeSubmitDoesNotWriteJLineFramesToPhysicalTerminal、controlCAndEmptyControlDLeaveEditorNormally |
| T6 压力复跑与诊断收敛 | 串行复跑目标测试并记录每次结果；验证超时路径可收集虚拟线程信息或明确记录采集失败；复查锁图与实际实现一致，避免只凭无挂起判定 | `scripts/verify-resize-deadlock.ps1`；`docs/ui-resize-deadlock/{lock-review,test-review}.md` | T5 | T2 脚本；T3 受控并发用例；JDK Thread.dump_to_file 虚拟线程诊断 |
| T7 接入主流程 | 核对主循环各输入分支实际使用修复后读取器；验证 resize 标记复位、旧 pin 距离失效、异常退出与终端恢复；无必要不改主流程 | `ui/FullscreenCommandFlowTest.java`、`CommandProcessorTest.java`；按需 `CommandProcessor.java`、`ui/InputPane.java`、`ui/EditorTerminal.java`；`docs/manual-test.md` | T6 | CommandProcessor.mainLoop、unpinDistance；InputPane 构造器；EditorTerminal.wrap；realMainLoopDispatchesChatResumeMenuCancelCopyAndQuit |
| T8 端到端验证 | 真实主循环自动化链路、串行全量测试、打包和 Windows 真实终端缩放验收；记录新增/原有失败区别；只勾选实际完成项 | `docs/ui-resize-deadlock/test-review.md`；`checklist.md`；按需相关端到端测试 | T7 | 根 checklist 全部标准；`mvn test`、`mvn package -DskipTests`；`docs/manual-test.md` |

## 实施约束与方案决策
- 推荐窄范围私有绘制锁，但不能直接照搬报告：JLine lock → 绘制锁依然存在，必须排除绘制锁 → JLine lock 的直接和间接路径。
- 不在绘制锁内调用未知 footer 回调；供应器也要检查间接获取的锁。上游 handleSignal 自身仍有 monitor，同步声明删除后仍须核对完整调用链。
- 只锁输出不能保证编辑缓冲、尺寸和生命周期一致；T1/T4 必须明确哪些状态由上游保护、哪些用快照、哪些由本类保护。
- 受控复现无法在真实调用链稳定安排时，记录障碍并调整测试接缝；不得用“重复测试没挂”替代旧版失败证据，不复制一套假的锁逻辑冒充生产复现。
- 本地历史 ch13 全量结果为 1281 tests、0 failures、0 errors、1 skipped，只作历史参考；新增测试后统计实际数量。
- 全量出现无关失败时单独记录，不扩大修改范围；未通过不得勾选全量验收。
- 已按用户后续授权实施修复与自动化验证；不修改数据库，不自动提交。

## 实施记录
T1–T5 已实现并通过定向验收；T6 正在进行 10 轮串行复跑。T7 已扩展主循环端到端测试并通过。T8 待全量回归及打包；真实终端视觉验收单独保持待验。
最终采用继承自 JLine 的 protected final ReentrantLock，未增加第二把绘制锁；原报告对字段可见性的描述有误。所有 reader 状态与直接绘制同锁，进入上游 handleSignal 的 monitor 前先获取该锁，footer 回调在覆写方法释放锁后执行。


2026-09-29 收尾：10 轮各 59 个测试通过；专项主循环测试通过；全量 1287 / 0 failures / 0 errors / 1 skipped，打包通过。真实终端视觉验收仍待实测。
