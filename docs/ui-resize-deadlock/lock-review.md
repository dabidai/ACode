# 锁顺序审查

## 实际依赖与证据
JLine 3.30.16 的 LineReaderImpl.lock 是 protected final ReentrantLock（实际字节码字段声明），不是原报告所称 private。readLine 在此锁内调用虚分派 redisplay；上游 handleSignal 是 synchronized 并调用 redisplay。
旧实现反转：输入 JLine lock → reader monitor；信号 reader monitor → JLine lock。现有 thread dump 证实信号路径，受控测试将补输入路径。

## 选定方案
复用继承的 lock，不新增 paintLock。覆写 handleSignal 先获取 lock，再调用上游 synchronized handleSignal；redisplay 只获取 lock。统一顺序为 lock → reader monitor（只在上游信号方法内），lock → ScreenRenderer → OutputPane，lock → Status/终端输出。
补全、frame 初始化与 finally、mouse 历史绘制也在同一 lock 下；buf、size、frameLines、frameCursor、historyOffset 与 frame suppliers 的读取/清理不再跨不同锁竞争。等待 readLine 输入时不能持外层 lock。
footerRedraw 在本次 handleSignal 临界区外调用，可同线程再入 reader 或等待另一线程重绘；如果调用者本来就在上游锁内触发信号，仍受调用者锁约束。测试覆盖常规独立信号线程的回调再入。
frame mode/footer/history suppliers 在已有输入锁内取值，合同为同步、非阻塞快照，禁止等待另一个 reader 操作；生产 mode/footer 读取权限/会话状态，history 复制 OutputPane.lines，未发现回调 reader 的路径。
全屏 ScreenRenderer.labels 的供应器也只读取业务状态。OutputPane 的同步方法不调用 reader 或 renderer。EditorTerminal 将 JLine 输出送入 sink。Status/终端输出对象不回调 reader。

## 审查边界
不保证任意用户回调跨线程同步等待 reader 都可用；不新增此类合同。readLine 生命周期仅支持既有单一输入者。测试中使用公开 widgets/bindings 调度真实 readLine，无生产反射或假锁模型。

## 状态保护与调用入口
| 状态/入口 | 保护规则 |
|---|---|
| buf、size、prompt、自定义 redisplay | JLine lock，与上游按键编辑共用 |
| frameMode/footer/history suppliers、frameLines、frameCursor、historyOffset、frameNeedsHistoryPaint | 初始化、信号读取、绘制、mouse、finally 均持 JLine lock |
| activePromptSupplier、activeFooterRedraw | 初始化、信号捕获及 finally 同锁；footer 捕获后锁外执行，其异常被既有降级处理 |
| completionBuffer 与 completionOverlay | 同 JLine lock；进入 ScreenRenderer 时保持 lock → renderer 顺序 |
| resizedDuringLastRead | 写入在 lock 中，volatile 支持主循环锁外读取；普通/fullscreen readLine 入口也复位 |
| screen 引用 | 构造装配后、启动输入线程之前设置，不支持并发更换 renderer |
| OutputPane | 独立同步快照，仅叶节点，无 reader 回调 |

readLine 阻塞等待用户输入时外层没有持有 lock；具体按键、信号和绘制仅用可重入临界区。供应器不跨线程等待 reader 是现有接口约束；本次未新增后台调度或轮询。
上游唯一 synchronized 方法为 handleSignal；信号注册调用虚分派覆写，先拿 lock 后进入其 monitor。同线程 reentrant redisplay 不再次竞争不同的锁。
小窗口降级补充：高度低于 6 时不再在上游降级完成后重画旧 frameLines，恢复大窗口后重新构造 frame。
