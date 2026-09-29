# ACode 阶段十四：Agent Teams —— 从一次性子任务到长期协作团队 — 任务清单

> 最后更新：2026-09-13
> 依赖关系：`T1→T3`；`T2→T3`；`T3→T4`；`T2→T5`；`T1/T2/T3→T6`；`T1/T4/T5→T7`；`T7→T8`；`T6/T7/T8→T9`；`T5/T7/T9→T10`；`T6/T9/T10→T11→T12`。可并行：T1 ∥ T2；T4 ∥ T5（T4 只需 T3，T5 只需 T2）；T8 与 T9 的前置准备可穿插。
> 源码依据：ACode 现有实现——**复用** `Tool`/`BaseTool`/`ParamSpec`/`ToolRegistry`（新工具全部是 `Tool` 实现）、`VirtualThreads.POOL`（in-process 队员的虚拟线程落点）、`Conversation.buildRequest` 的轮次级提醒位（邮箱轮询注入点）、session 包的 JSONL 编解码（队员 transcript）、`PermissionChecker` 独立实例（队员权限追踪）、输出双写范式（`emitLine`：`ConversationController.java:259-267`，结果行通道）；**改动** `Agent` 循环加一轮次提醒来源与循环结束回调、`AppConfig`/`ConfigLoader` 加协调模式开关；**消费** ch12 的 Agent 工具与定义加载（`team_name` 参数扩展）、ch13 的可编程 Worktree 创建/删除。
> 数值口径、五个工具（及 TeamCreate/TeamDelete）的精确 schema、任务状态与依赖字段取值、存储位置与格式、消息信封与 `<task-notification>` 格式、全部错误文案见 `checklist.md` 顶部「默认值说明」。
> **跨章提示**：T9 必须在 ch12 的 Agent 能力及 ch13 的可编程 Worktree 接口落地后实施。接口缺失应阻止队员派生；T12 不降级为仅验证编程接口。

## 约定

- 新增包根 `com.acode.team`：主代码 `src/main/java/com/acode/team/`，工具放 `com.acode.team.tools`，测试 `src/test/java/com/acode/team/`
- 团队持久化目录 `.acode/teams/{name}/`（**只许本章代码创建与清理**；测试一律 `@TempDir` 隔离，绝不读写用户真实 `~/.acode/`）
- 现有文件行号以 2026-09-13 的 HEAD 为准，改动时先 Read 确认
- 新测试方法名一律英文驼峰；随任务写对应测试，单跑 `mvn -Dtest=指定类 test`；**全套 `mvn test` 只在收尾任务 T12 统一跑**
- 构建环境：`JAVA_HOME="D:\java\jdk21"`（默认 jdk17 编不了 release 21）；运行时用 `target/acode.jar`，注意先重打包
- **并发测试硬约定：可重复、不靠 sleep 赌时序**——锁竞争用多线程 + `CountDownLatch`/`CyclicBarrier` 对齐起跑，断言用超时轮询（`await`）等确定性条件，绝不 `Thread.sleep` 猜时序（ch09 教训：并发验收要能反复跑绿）
- 队员测试的 `Agent` 构造全部用假 provider（`src/test/java/com/acode/provider/FakeProvider.java`，已支持 `receivedRequests()` 捕获与脚本流）；构造 `ConversationController` 的测试必须设 `@TempDir` 项目根并关 `memory_auto`（ch09 踩过的坑，队员测试同款要求）
- 复用 session 包的 `SessionCodec` 静态编解码（`.jsonl` 行格式），队员 transcript 的追加/重写句柄在 team 包内自建（`SessionStore` 的目录硬编码 `.acode/sessions`，是主会话池语义，不硬改）
- **错误文案与格式统一从 `checklist.md` 顶部取**，实现里不允许出现第二份文案
- 团队名、队员名走 ch13 的 slug 校验语义（字母/数字/点/连字符/下划线、每段 ≤64、拒绝独立成段的 `.` 与 `..`）——**本章不重复实现**，T9 消费 ch13 时由其校验；本章自己落盘的团队目录名用同样的字符集约束（在 T1 校验）

---

### T1 团队模型与配置存储

**目标**：团队的数据结构落地——组名、负责人、花名册三要素；活跃状态两值、终止即移除（不留墓碑）；团队配置按「会话内工作状态」落盘；重名自动避让。

**影响文件（新建）**
- `team/Team.java`（新）— 不可变结构：名称、Lead 标识、花名册、配置路径。花名册条目含：队员名、Agent 实例标识、类型（可空）、模型（可空）、Worktree 路径（可空）、后端类型（**本章恒 in-process，枚举只有这一个取值**）、活跃状态（可空 boolean：null/true=活跃、false=空闲）、是否需要审批（默认 false）
- `team/TeammateInfo.java`（新）— 花名册条目 record（同 Team 嵌套或独立，与 `permission/PermissionRule` 的不可变风格一致）
- `team/TeamManager.java`（新，本任务只做生命周期壳）— 内存队列表（线程安全）：
  - 创建：重名检测（内存 map + 磁盘目录双查）→ 自动追加序号（`team` → `team-2` → `team-3`）→ 建目录 → 写配置 JSON
  - 注册/移除队员、翻活跃状态、按名字/标识查队员、全部队员清单
  - 配置 JSON 读写：整文件原子替换（临时文件 + ATOMIC_MOVE），坏文件按空团队处理并告警，不抛
  - **队列表不做跨会话恢复**：构造时只读内存，磁盘目录仅供队员共享与 Lead 查看（spec 决策表「会话内落盘」）
- `team/TeamManagerTest.java`（新）— 创建后配置文件存在且字段齐全；重名三次依次得 `team` / `team-2` / `team-3`（内存与磁盘双查都命中）；注册队员后花名册可见；翻活跃状态生效；移除队员后清单不再含它（无墓碑态）；坏配置 JSON 不抛错；并发注册多名队员不丢不重（`grep` 断言用了并发容器或锁）

**依赖**：无

**参考资料**
- 不可变定义对象风格：`permission/PermissionRule.java`、`session/Session.java`
- 原子替换先例：`session/SessionRecorder.rewrite`（`SessionRecorder.java:82-107`，临时文件 + move 兜底）
- 线程安全注册表先例：`tool/ToolRegistry.java:17-19`（LinkedHashMap，本章队列表需更强的并发语义，自行选锁或并发容器）
- 目录约定：`.acode/` 下既有 `config.yaml` / `plans/` / `sessions/`（`session/SessionStore.dir` `SessionStore.java:67-69`）

---

### T2 文件锁、文件邮箱与名称注册表

**目标**：队员通信的底座——一套文件锁机制（本章两个消费者：邮箱与任务存储），文件收件箱（读改写 + 原子替换 + 读完标记），名称注册表。**并发正确性在这里立住**：两个写者并发写同一收件箱不丢消息、不损坏文件。

**影响文件（新建）**
- `team/TeamFileLock.java`（新）— 锁文件协议（数值见 checklist 顶部）：
  - 抢锁 = 原子创建锁文件（`O_CREATE|O_EXCL`）；抢不到随机抖动重试、上限次数
  - 锁记录唯一持有者；超过阈值仅触发存活检查，确认持有进程已退出后才回收；无法确认时返回锁忙，不抢占
  - 释放时核对持有者标识，仅删除自己持有的锁；`try-with-resources` 风格 AutoCloseable
- `team/FileMailbox.java`（新）— 收件箱文件（每个收件人一个）：
  - 信封结构见 checklist 顶部（from / to / 摘要 / 正文 / 类型 / 时间戳 / 已读标记）
  - 写入：抢锁 → 整文件读 → 追加 → 整文件写回（临时文件 + ATOMIC_MOVE，**不做流式追加**）
  - 读取：未读消息列表；模型请求成功进入调用后才确认投递并标记已读，失败或取消时仍保留未读（消息不删除，可追溯）
  - 收件箱不存在时读取返回空；写失败抛业务异常（文案见 checklist）
- `team/AgentNameRegistry.java`（新）— 名字 → Agent 标识的并发映射；注册冲突（名字已存在）报错；按名字/标识解析；广播不查注册表
- `team/TeamFileLockTest.java` / `team/FileMailboxTest.java` / `team/AgentNameRegistryTest.java`（新）—
  - **并发写不丢消息**：N 个线程同时向同一收件箱各写 M 条 → 收件箱恰有 N×M 条（CountDownLatch 对齐起跑，反复跑多轮）
  - 两个线程抢同一锁恰一胜；败者重试后拿到；超龄但持有者仍活跃时不得偷锁，确认死进程后可回收
  - 请求构建失败时未读消息仍在；成功接收请求后确认投递并标记已读；坏 JSON 行按空处理不抛
  - 名字解析命中/未命中；重名注册报错；按标识解析等价

**依赖**：无

**参考资料**
- 锁协议与信封数值：见 `checklist.md` 顶部（prompt 原文照抄）
- `ToolContext.resolve` 的路径基准语义（`ToolContext.java:31-35`）——收件箱路径一律绝对化
- 原子替换先例：`session/SessionRecorder.java:144-151`（`move` 的 ATOMIC_MOVE 兜底）

---

### T3 共享任务存储

**目标**：团队级任务列表的状态机与依赖图——三值状态、两种互补写法的依赖字段、认领原子性、依赖未满足拒绝认领、进行中任务回滚。**两个队员同时认领同一任务恰一人成功**，这是本任务的头号验收。

**影响文件（新建）**
- `team/TeamTaskStore.java`（新）— 每团队一个实例（存储位置与格式见 checklist 顶部）：
  - 创建任务（顺序递增 ID）、按 ID 查、全量列（带阻塞标记与认领人）、更新状态、加依赖（两种写法互补）
  - 认领 = 状态置「进行中」，**原子操作**（同步/锁保证恰一人成功）：任务必须处于「待认领」且依赖全部「已完成」，否则按 checklist 文案报错
  - 依赖操作自检：不允许把任务依赖到自身（循环依赖在加依赖时按可达性检查拒绝，错误文案见 checklist）
  - 持久化：写 = 抢文件锁 → 读改写 → 原子替换（复用 T2 的锁）；读容忍坏文件按空列表处理
  - 队员移除回调：该队员持有的进行中任务回滚为待认领、认领人清空
- `team/TeamTaskStoreTest.java`（新）—
  - **认领竞态**：两线程同认领一任务恰一成功（CountDownLatch 对齐，反复跑）
  - 依赖未满足认领被拒（错误文案）；依赖完成后可认领；两种写法等价（A 阻塞 B ⇔ B 被 A 阻塞）
  - 循环依赖被拒；自身依赖被拒
  - 回滚：队员移除后其进行中任务回到待认领、可被再认领
  - 落盘/读回往返一致；坏文件不抛

**依赖**：T1、T2

**参考资料**
- 状态与依赖取值、存储格式、错误文案：见 `checklist.md` 顶部
- 文件锁：T2 的 `team/TeamFileLock.java`
- 原子认领先例（单写者语义）：`tool/ToolRegistry` 与 `session/SessionStore.reserve`（`SessionStore.java:98-100` 的 CAS 风格）

---

### T4 任务工具四件套

**目标**：把任务存储暴露成四个 `Tool` 实现，注册进队员工具集即可用。工具参数 schema、返回文本、错误文案与 checklist 顶部逐字对齐。

**影响文件（新建）**
- `team/tools/TaskCreateTool.java` / `team/tools/TaskGetTool.java` / `team/tools/TaskListTool.java` / `team/tools/TaskUpdateTool.java`（新）— 全部继承 `BaseTool`（参数校验/超时/异常兜底免费获得）：
  - 创建：必填标题、可选描述与依赖字段；返回新任务 ID 与摘要
  - 查看：必填任务 ID；返回完整详情（含依赖、被依赖、认领人）
  - 列举：无参；列出全部任务与状态/阻塞标记/认领人
  - 更新：任务 ID + 可选状态（认领/完成）+ 可选依赖字段；参数至少含一项才有效（全空按参数提示报错，文案见 checklist）
  - 权限级别：创建/更新 = write，查看/列举 = read
  - 工具描述必须写清：**任务要追踪状态、消息是 FYI**（SendMessage 与 Task 的分工一句话，来自 prompt「什么时候用哪个」一节）
- `team/tools/TaskToolsTest.java`（新）— 每个工具：schema 字段与必填性（与 checklist 顶部逐字段一致）、成功路径返回文本含 ID、失败路径返回错误文案；依赖字段经工具写入后存储里可查；认领冲突经工具路径恰一成功

**依赖**：T3

**参考资料**
- 工具范式：`BaseTool.java:22-114`（`paramSpecs` / `doExecute` / `validate` / 默认 `inputSchema`）、`ParamSpec.java:7-18`
- 既有工具的 schema/文案风格：`tool/impl/GlobTool.java`（简单无参）、`tool/impl/WriteFileTool.java`（多参数）
- 注册范式：`tool/DefaultToolset.java:19-26`

---

### T5 SendMessage 工具

**目标**：队员之间第一次有了直接通信——按名字/ID/广播寻址、摘要词数校验、三类结构化消息的类型与收发权限。**本任务不包含「目标已停止自动恢复」**（那是 T8 挂接运行时后的联动）。

**影响文件（新建）**
- `team/tools/SendMessageTool.java`（新，继承 `BaseTool`）—
  - 参数 schema 见 checklist 顶部：收件人、摘要、正文、消息类型、结构化负载
  - 纯文本：摘要词数硬校验（文案见 checklist）；收件人经名称注册表解析，查不到报错
  - 广播：不查注册表，投递给花名册全部队员
  - 结构化消息：类型白名单校验；退出回复只能发给 Lead、审批回复只有 Lead 能发（**越权当场拒绝**，文案见 checklist）——本任务只落盘到收件箱，消费方（退出/审批的执行）在 T10
  - 权限级别：write
- `team/tools/SendMessageToolTest.java`（新）—
  - 按名字/按 ID 投递到同一收件箱；广播到达全部队员收件箱
  - 摘要词数不足/超限被拒（文案逐字）
  - 收件人不存在报错；结构化消息类型非法被拒；非 Lead 发审批回复被拒；退出回复发给非 Lead 被拒
  - 投递后收件箱里消息信封字段齐全（与 checklist 顶部信封逐字段一致）

**依赖**：T2

**参考资料**
- 信封与结构化消息取值：见 `checklist.md` 顶部（prompt 原文照抄）
- 工具范式：`BaseTool.java` 同上；权限级别枚举 `tool/Permission.java`
- 名称解析：T2 的 `team/AgentNameRegistry.java`；花名册：T1 的 `team/TeamManager.java`

---

### T6 TeamCreate / TeamDelete 工具

**目标**：团队生命周期的两个顶层工具——创建（后端检测恒 in-process、写配置、初始化任务与邮箱目录、注册 Lead）与安全删除（确认队员空闲和成果已保留后清理、返回名单）。

**影响文件（新建）**
- `team/tools/TeamCreateTool.java`（新）— 参数见 checklist 顶部；执行：名称校验（T1 的字符集约束）→ 检测后端（**本章恒 in-process**，为将来 pane 后端留字段位）→ 建团队（T1）→ 初始化任务存储与邮箱目录 → 返回实际团队名（可能带序号后缀，文案见 checklist）
- `team/tools/TeamDeleteTool.java`（新）— 参数见 checklist 顶部；执行：查团队 → 检查所有队员均已空闲 → 请求 ch13 安全删除各队员 Worktree → 清理团队目录。任一队员活跃或任一 Worktree 不满足安全删除条件时，拒绝删除并保留状态；会话退出时的进程终止由 T11 单独处理。
- `team/tools/TeamToolsTest.java`（新）— 创建后 `.acode/teams/{name}/` 下 config/tasks/mailbox 目录齐全；重名返回带序号的名字；删除后目录不存在、返回文案含队员数量与名字；团队不存在报错；`grep` 断言本章代码里检测后端只可能返回 in-process 一个取值

**依赖**：T1、T2、T3

**参考资料**
- 团队生命周期语义：spec「能力清单一」与 prompt「团队的生命周期」第一、五步
- 停止队员的钩子：T7 的运行时（本任务用接口占位，`Agent.cancel()` 语义见 `Agent.java:192-198`）
- 保守清理先例：ch13 prompt「自动清理 fail-closed」与「过期清理三层漏斗」

---

### T7 队员运行时（in-process）

**目标**：让一个 Agent 实例以「队员」身份跑起来——工具集注入、三行附录系统提示、每轮开头邮箱轮询、停止时的两步通知、任务完成通知回传 Lead。**这是本章的枢纽任务**。

**影响文件（新建 + 修改）**
- `team/TeammateToolsetBuilder.java`（新）— 队员工具集 = 获父级明确授权的基础工具 + 协调五件套（T4 四件 + T5 一件）；**硬剔除**：Agent 工具、团队管理工具、向用户提问、退出规划。过滤同时作用于模型请求清单与实际执行入口，不能通过手写工具调用绕过。
- `team/TeamSystemPrompt.java`（新）— 三行附录文本（逐字见 checklist 顶部）
- `team/TeammateRuntime.java`（新）— 队员的装配与收尾：
  - 构造队员 `Agent`：独立 `Conversation`、独立 `PermissionChecker`；执行范围以父级对本次操作的明确授权为上限，需确认而未获授权的操作拒绝；工作目录必须是 T9 分配且校验过的 Worktree 路径
  - 运行：`VirtualThreads.POOL` 上跑队员循环；**每轮开头邮箱轮询**（见下）
  - 收尾钩子：循环结束（正常/触顶/错误/被杀）→ 翻活跃状态为 false → 向 Lead 收件箱写空闲通知 → 组装 `<task-notification>`（完整格式见 checklist 顶部，状态取值 completed/failed/killed 与终止原因映射）写进 Lead 收件箱 → 该队员的进行中任务回滚（T3 的回调）
- `agent/Agent.java`（改）— 两个小切口：
  1. **轮次级提醒来源**：新增可选回调，每轮组装请求前调用，返回的提醒作为尾插轮次级提醒传入 `buildRequest`（复用既有 `turnReminder` 位：`Agent.java:486-493` 的 `buildPlanAwareRequest`）。队员运行时把「邮箱未读消息」经此位注入；未装配时行为与现在逐字一致
  2. **循环结束通知**：`loop()` 各终止分支（`Agent.java:220-266`）在 `LoopComplete` 之前回调一次「结束监听」（可选），把终止原因与最终文本交给运行时收尾。**不改变五种终止条件的判定**，只是加观察点
- `team/TeammateRuntimeTest.java`（新，假 provider 脚本流）—
  - 队员请求的 tools 列表**含协调五件套、不含** Agent/AskUser/ExitPlanMode/团队工具；伪造被过滤工具调用在执行入口同样拒绝
  - 队员首轮请求**含三行附录**系统提示、含邮箱未读消息；构建失败或调用取消时消息仍未读，确认模型调用后才标记已读
  - 正常结束 → Lead 收件箱出现空闲通知与 `<task-notification>`（status=completed、含 result 与 usage 三字段）
  - provider 抛错 → status=failed；取消 → status=killed；花名册保留队员身份并标为空闲，进行中任务回滚为待认领
  - 未装配轮次提醒来源时请求与改动前一致（无回归）

**依赖**：T1、T4、T5

**参考资料**
- 队员循环 = 既有 `Agent` 循环：`Agent.java:169-184`（`run()` 虚拟线程 + 事件队列）、`:220-266`（`loop()` 五终止）、`:486-493`（`buildPlanAwareRequest` 的 turnReminder 位）、`:381-403`（`stream`）
- 事件队列与收尾：`agent/AgentEvent.java:13-66`；结束通知的语义参考 `Agent.notifyTurnComplete`（`Agent.java:118-130`）的「收尾失败只记日志」风格
- 独立权限：`permission/PermissionChecker.java:54-66`；父级授权边界参考 ch12 T7
- 通知与信封格式：见 `checklist.md` 顶部

---

### T8 队员 transcript 持久化与空闲续写

**目标**：队员的上下文持久化到磁盘、可随时唤醒续写——这是队员与普通子 Agent 的分界线。同时把「给已停止队员发消息自动恢复」接上 T5。

**影响文件（新建 + 修改）**
- `team/TeammateTranscript.java`（新）— 每队员一个 `.jsonl` 文件（位置见 checklist 顶部）：
  - 逐条追加（复用 `session/SessionCodec` 静态编解码，行格式与主会话池一致）；压缩重建时整段原子重写（对照 `SessionRecorder.rewrite` 的挂起/重写语义：`SessionRecorder.java:82-107`）
  - 写盘失败只告警不打断（同 session 包约定）
  - 挂接方式：队员 `Conversation` 的追加/重建监听（`Conversation.java:91-103` 的 `addAppendListener` / `addRebuildListener`）
- `team/TeammateRuntime.java`（改）— 队员构造时挂 transcript；新增**恢复入口**：读 transcript → 重建 `Conversation` → 带上原工作目录与工具集重新起循环（续写 = 老队员带着完整历史接着干，不是新建）
- `team/tools/SendMessageTool.java`（改）— 投递前查收件人状态：**已停止 → 从磁盘恢复并重启循环，再把消息投进收件箱**（自动唤醒）；活跃 → 直接投递
- `team/TeammateResumeTest.java`（新，假 provider）—
  - 队员结束 → transcript 文件存在且行数 > 0；重建的 `Conversation` 消息数与结束前一致
  - 给已停止队员发消息 → 新循环启动、首轮请求含完整历史与这条消息（经 turnReminder）
  - 压缩重写后 transcript 与原历史一致；坏行被跳过
  - 写盘失败（只读目录）不抛、不影响循环

**依赖**：T7

**参考资料**
- 同构实现：`session/SessionRecorder.java:28-163`、`session/SessionStore.java:28-170`、`session/SessionCodec.java`（encode/decode 静态方法）
- 挂接点：`Conversation.java:91-103`（追加/重建监听）、`:116-130`（代次，恢复后的迟到写入防护）
- prompt「队员空闲与续写」一节的语义（两步通知 + 磁盘恢复）

---

### T9 队员派生管线

**目标**：把「派一个子 Agent」升级成「往团队里加一个队员」的六步管线；消费 ch13 的 Worktree、扩展 ch12 的 Agent 工具契约。**这是与 ch12/ch13 的接口缝合点，先读两边 prompt 的对应节再动手。**

**影响文件（新建 + 修改）**
- `team/TeammateSpawner.java`（新）— 六步管线：① 加载 Agent 定义（类型非空 → 定义式；留空 → ch12 的 Fork 式）→ ② 通过 ch13 可编程接口分配并校验独立 Worktree，缺失或失败即停止派生 → ③ 注入协调工具（T7 的构建器）→ ④ 后端分流（恒 in-process，调用 T7 运行时）→ ⑤ 注册名称（T2 注册表）→ ⑥ 登记花名册（T1）
  - 步骤失败的回滚：已建的 Worktree 与已注册的名字/花名册条目必须回滚（保守方向：宁可留目录不删，但注册表必须干净，防止「幽灵收件人」）
- **ch12 的 Agent 工具契约扩展**：`Agent` 工具新增「指定团队」参数（可选）——非空时委托 `TeammateSpawner`，返回队员名与 Worktree 路径摘要；为空时走 ch12 原路径。
- **防扩张**：队员的工具集（T7）本就剔除 Agent 工具与团队工具——「队员不能 spawn 队员」在能力层已成立，本任务补一条单测兜底：以队员身份构造的工具集里查无 Agent/团队工具、查无「指定团队」参数的通道
- `team/TeammateSpawnerTest.java`（新，假 provider + `@TempDir`）—
  - 派生成功：花名册有它、名称注册表有它、队员循环已起（provider 收到请求）、返回摘要含队员名
  - 定义式与 Fork 式的区别：定义式对话为空、Fork 式继承 Lead 历史（断言重建后消息数）
  - Worktree 步骤：注入假的可编程接口（测试桩）→ 工作目录指向分配的路径；接口缺失、路径不合法或创建失败 → 派生失败，名称和花名册无残留
  - 失败回滚：注册表无幽灵收件人

**依赖**：T6、T7、T8

**参考资料**
- 六步管线：prompt「派队员：六个步骤」一节
- ch13 契约：`docs/ch13/prompt.md`「创建：六步」与「Slug 安全验证」（可编程创建、嵌套名 `team-refactor/alice`、`worktree-` 分支前缀）
- ch12 契约：`docs/ch12/spec.md`「定义式与 Fork 式」及 `tasks.md` T8（Agent 工具参数、留空即 Fork）
- 工作目录分流：`ToolContext` 一次构造即分流（`ToolContext.java:8-36`；`ExchangeRunner.java:128-129` 是主 Agent 侧的对照）
- 回滚的保守方向：ch13 prompt「判断出错时，往保守的方向错」

---

### T10 Coordinator Mode 与 Plan 审批协议

**目标**：可选的协调模式（双锁 + 白名单 + 四阶段系统提示）与高危队员的审批协议（计划经邮箱上报、Lead 审批、权限模式传递）。

**影响文件（新建 + 修改）**
- `team/CoordinatorMode.java`（新）— 双锁判定（配置开关 + 环境变量，键名与取值见 checklist 顶部）；不可变白名单集合（名单见 checklist 顶部），同时约束模型请求和执行入口；系统提示由上层管理
- `team/CoordinatorPrompt.java`（新）— 四阶段工作流系统提示（Research/Synthesis/Implementation/Verification；「理解不能外包」的要求与正反示例，正文见 checklist 顶部语义要点）
- `config/AppConfig.java`（改）— 新增协调模式开关字段（getter/setter，风格同既有字段）
- `config/ConfigLoader.java`（改）— 解析该键（三级配置加载机制不动，只加一个键的读写：`ConfigLoader.java:18-26` 的三级覆盖语义照旧）
- `team/TeammateRuntime.java`（改）— **Plan 审批工作流**：需要审批的队员在执行修改操作前，把计划经邮箱发给 Lead 并挂起；Lead 用结构化审批回复（T5 的消息类型）放行/驳回（可附反馈）；仅对批准的操作范围开放，且不得超过父级原有授权；驳回时反馈文本注入队员下一轮提醒
- `team/CoordinatorModeTest.java` / `team/PlanApprovalTest.java`（新，假 provider）—
  - 双锁：只开配置不开环境变量 → 未生效；两把都开 → 生效
  - 白名单：写类、编辑类和可写入文件的 shell 工具不在名单；读类与团队工具在名单（逐项断言）
  - 协调模式开启时 Lead 请求的工具列表被收窄；伪造调用在执行入口同样被拒
  - 审批流：计划到达 Lead 邮箱 → 驳回附反馈 → 队员下一轮提醒含反馈 → 批准指定操作 → 仅该授权范围可执行，其他修改操作仍被拒
  - 未开启协调模式时请求与改动前一致（无回归）

**依赖**：T5、T7、T9

**参考资料**
- 双锁与白名单、四阶段语义：prompt「Coordinator Mode」一节
- 审批流五步：prompt「Plan 审批工作流」一节
- 权限模式传递：`permission/PermissionMode.java:9-64`（四档与 `fromConfig`）、`PermissionChecker.setMode`（`PermissionChecker.java:68-70`）
- 配置键先例：`config/AppConfig.java` 既有布尔开关字段（如 `memoryAuto` 的 getter 风格）

---

### T11 接入主流程与文档补齐

**目标**：七个新工具注册进主工具集、TeamManager 与 Coordinator Mode 装配进主循环、队员结果行进入终端输出；文档同步。

**影响文件（修改）**
- `ConversationController.java`（改）—
  1. 构造期（`:197-218` 装配区）：新建 TeamManager 并装配；`DefaultToolset.registerAll` 之后注册七个工具（TeamCreate/TeamDelete/TaskCreate/TaskGet/TaskList/TaskUpdate/SendMessage）——**注册进主工具集**，主 Agent 直接可见（协调工具由 T7 的构建器按需发给队员，主 Agent 的请求不额外过滤）
  2. 团队结果行输出：TeamManager 挂一个通知回调，队员完成/失败/空闲/消息摘要经既有输出双写（`emitLine` 范式，`ConversationController.java:259-267`）提交为终端行——**队员中间过程不上屏**（spec 决策）
  3. 会话收尾：`start()` 的 `finally`（`:292-296`）里终止全部未清理的队员（in-process 生命周期绑 Lead，Lead 退出队员必须一起消失）
  4. 装配 CoordinatorMode（双锁判定 + 开启时收窄 Lead 工具列表与执行范围 + 注入四阶段提示）
- `docs/manual-test.md`（改）— 追加「阶段十四」手测小节（空 ⚑ 项，T12 填）
- `README.md`（改）— 功能特性补一行 Agent Teams（团队协作、任务列表、队员通信、协调模式）；路线图阶段十四状态 ✅（若完成）
- `src/main/resources/config.yaml`（改）— 协调模式键的默认注释（默认关闭）

**依赖**：T6、T9、T10

**参考资料**
- 装配区与收尾：`ConversationController.java:197-218`（工具注册与依赖装配）、`:273-299`（`start()` 与 `finally`）
- 输出双写：`ConversationController.emitLine`（`:259-267`）、`ExchangeRunner.run` 的 `output.append + live.appendCommitted` 双写范式（`ExchangeRunner.java:121-127`）
- 文档先例：`docs/manual-test.md`「阶段九」小节形态、`README.md:69-84`（命令表）

---

### T12 端到端验证 ⚑ 与统一收尾回归

**目标**：全链路验收——Lead 建队 → 拆任务 → 派两队员并行 → 依赖拦截 → 通信 → 完成通知 → 续写 → 清理；真机手测（多队员并行时终端输出）；统一收尾回归。

**影响文件（新建 + 修改）**
- `team/TeamEndToEndTest.java`（新）— 假 provider 脚本流 + `@TempDir` 项目根 + 关 `memory_auto`，装配真实 TeamManager 与七个工具：
  1. **建队**：TeamCreate → `.acode/teams/{name}/` 三件套齐全
  2. **拆任务**：经工具写 4 个任务并加依赖（T3 等 T1、T4 等 T2）；T1 未完成时认领 T3 被拒（文案逐字）
  3. **并行队员**：派生两名队员（各自脚本：TaskList → 认领 → 完成）→ 两人分别完成 T1/T2 → T3 才可认领（**依赖放行**）
  4. **通信往返**：alice 发消息给 bob → bob 下一轮请求的 turnReminder 含该消息与摘要
  5. **完成通知**：Lead 收件箱出现两条 `<task-notification>`（status=completed、含 result 与 usage 三字段）
  6. **空闲与续写**：给已空闲的 alice 发消息 → 恢复循环、首轮请求含历史与消息
  7. **竞态**：两队员并发认领同一任务恰一成功（CountDownLatch，**不 sleep**）
  8. **崩溃回滚**：一名队员 provider 抛错 → 通知 status=failed → 其进行中任务回滚待认领
  9. **清理**：TeamDelete → 返回名单文案 → 目录消失；有活跃队员时清理被拒（文案逐字）
- `docs/manual-test.md`（改）— 填阶段十四 ⚑ 手测勾选
- **统一收尾**：`JAVA_HOME="D:\java\jdk21" mvn test` 全套全绿，记录总用例数（新增用例均本地假 provider，无外部网络）

**真机 ⚑ 项（本任务完成，见 checklist）**：打包后真机两队员并行 + 中途继续输入 + ESC/Ctrl+C，验证提交行不乱、主循环不卡。

**依赖**：T11

**参考资料**
- 端到端先例：`docs/ch09/tasks.md` T13、`src/test/java/com/acode/MemorySystemEndToEndTest.java`（假 provider + `@TempDir` 写法）
- 断言口径全部对齐 `checklist.md` 顶部「默认值说明」


## 实施进度（2026-09-29）

T1–T11 的代码已接入，T12 的假模型集成测试已覆盖并行队员、四任务依赖图、消息、续写、审批、取消与主控制器工具闭环。全量与最终专项数据见 test-review.md，真实 provider / 终端手测仍独立待验收，不宣称真机已完成。

结合当前代码作以下细化：
- 名称复用 ch13 WorktreeNames 的实际规则，再限制为单目录段；因此采用更严格的 Windows 保留名称、末尾点与 Git 段校验。自动后缀仍保持总长度不超过 64。
- 团队配置 createdAt 使用 epoch 毫秒；description 为可选字符串。构造管理器不扫描磁盘；readConfiguration 仅读取快照，不注册或恢复旧团队。
- 配置损坏/字段非法返回 Optional.empty 并告警；不会覆盖损坏文件或清空当前内存成员。缺失文件返回空，不输出损坏告警。
- 配置更新先完成原子替换再发布内存快照。文件系统不支持原子替换时明确失败，保留现有数据，不降级为可能被并发读者看到半成品的写入。
- 创建目录用原子 createDirectory 避免不同管理器实例重名覆盖；拒绝符号链接与 junction，成员地址的名称/ID 交叉冲突也拒绝。
- T1 阶段不删除整个团队，不创建成员 Worktree，不恢复成员线程；这些仍归 T6–T9。

T2/T3 的实际实现约定：
- TeamJsonFile 复用整文件读取与原子替换；坏文件读取告警返回空，修改拒绝覆盖。
- TeamFileLock 在持锁期间保留 OS guard，防止旧锁检查/删除与新锁创建竞态；guard 文件保持存在。同 JVM 使用固定大小的公平信号量分片排队，等待最多 5 秒；跨进程仍按 5–100ms 抖动、最多十次尝试。
- 消息增加 UUID id，读取与 acknowledge 分离，只确认本批 ID；期间新到的消息保持未读。模型请求交接点属于 T7，尚未接入。
- 名称注册表未命中返回 Optional.empty，面向模型的错误由 T5 负责。收件箱以注册表解析后的规范名称定位。
- 任务只落盘 blockedBy，blocks 与 blocked 由图派生；认领后不再新增其依赖，完成只能由持有者操作。
- removeMember 先回滚任务再移除花名册；任务写失败保留成员。两文件不是跨文件事务，配置写失败时任务可能已回滚，可重试移除；T7/T9 调用前必须停止该成员运行时。

T4–T11 实际落点与补充约定：
- 工具在 team/tools，TaskTool 统一字符串数组 schema 与业务错误翻译，调用者身份由宿主绑定。
- TeamSession 汇总原设计的派生器、运行时装配与会话清理职责；TeamMessaging 按当前花名册解析名称/ID，lead 是保留地址，AgentNameRegistry 记录注册冲突。
- Agent 的 team_name / name 进入长期队员路径，plan_mode_required 控制审批；未传 team_name 保持 ch12 路径。Worktree 使用 ch13 create/verifyRemovable/remove，不复用主工作目录。
- TeammateRuntime 以 VirtualThreads.POOL 消费既有 Agent 队列，输出结果行；每轮 Hook scope 在收尾关闭。结束保留身份并标空闲，未完成任务回滚。
- TeamTranscriptStore 监听追加/重建，续写前读回原 Conversation。先投递邮件再唤醒，避免首轮抢跑；不按旧设计的“先恢复再投递”执行。
- 邮件在成功模型响应且未取消后按本批 ID 确认，比“刚进入调用即确认”更保守；模型失败可重试，确认失败可能重复投递，不提供 exactly-once 模型消费承诺。
- TeamApproval 只接受 Lead 的结构化 JSON 审批，按 tool + 完整 input 精确匹配，批准替换旧范围；Hook 始终只读，不能借 Hook 绕审批。详见 usage.md。
- 协调开关的四阶段提示进入 SYSTEM 段，请求工具名单与执行入口共同执行白名单；关闭时不增加协调提示。
- 清理先检查全部队员空闲和全部 Worktree 安全，再逐个移除；多文件/多 Worktree 清理不是事务，失败报告并保留剩余数据。TeamDelete 不清理此前进程留下的未知团队。
- ConversationController 注册七工具，ExchangeRunner 配置 Lead 邮箱及协调模式，退出/重置会话关闭队员；普通子 Agent 过滤全部 Lead 绑定团队工具。
