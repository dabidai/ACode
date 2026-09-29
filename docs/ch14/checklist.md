# ACode 阶段十四：Agent Teams —— 从一次性子任务到长期协作团队 — 验收清单

> 最后更新：2026-09-13
> 本清单每一项必须可勾选、可观测。顶部「默认值说明」是**数值与文案的唯一口径**——实现与测试里的字段名、状态取值、错误文本一律从此处取，不允许出现第二份文案。
> ⚑ = 真机手测项（自动化验收替代不了）；「grep 断言」= 用 `grep` 对源码做可观测检查。
> 测试环境约定：`JAVA_HOME="D:\java\jdk21"`；全套 `mvn test` 只在 T12 收尾统一跑；队员/团队测试一律 `@TempDir` 项目根 + 关 `memory_auto`，假 provider 见 `src/test/java/com/acode/provider/FakeProvider.java`；并发断言不靠 sleep，用 CountDownLatch 对齐 + 超时轮询。

## 默认值说明（唯一口径）

### 1. 七个工具的参数 schema

| 工具 | 参数 | 必填 | 类型 | 权限 |
|---|---|---|---|---|
| `TeamCreate` | `team_name` | 是 | string（slug 字符集：字母/数字/点/连字符/下划线，每段 ≤64，拒绝独立成段的 `.` 与 `..`） | write |
| | `description` | 否 | string | |
| `TeamDelete` | `team_name` | 是 | string | write |
| `TaskCreate` | `title` | 是 | string | write |
| | `description` | 否 | string | |
| | `addBlockedBy` | 否 | string 数组（任务 ID） | |
| | `addBlocks` | 否 | string 数组（任务 ID） | |
| `TaskGet` | `taskID` | 是 | string | read |
| `TaskList` | （无参） | — | — | read |
| `TaskUpdate` | `taskID` | 是 | string | write |
| | `status` | 否 | `in_progress` / `completed` | |
| | `addBlockedBy` | 否 | string 数组 | |
| | `addBlocks` | 否 | string 数组 | |
| `SendMessage` | `to` | 是 | string：队友名 / Agent ID / `*`（广播） | write |
| | `summary` | 纯文本消息必填；结构化消息可省 | string，**5～10 个词**硬校验 | |
| | `message` | 是 | string | |
| | `messageType` | 否，默认 `text` | `text` / `shutdown_request` / `shutdown_response` / `plan_approval_response` | |

结构化消息的收发权限（prompt 原文表格）：

| 类型 | 含义 | 谁能发 |
|---|---|---|
| `shutdown_request` | 请求某个队员优雅退出 | 任何人 |
| `shutdown_response` | 对退出请求的回复（同意 / 拒绝 + 理由） | 只能发给 Lead |
| `plan_approval_response` | Plan 模式的审批回复（通过 / 驳回 + 反馈） | 只有 Lead 能发 |

### 2. 任务状态与依赖取值

- 状态三值（存储态）：`pending`（待认领）→ `in_progress`（进行中，= 已认领）→ `completed`（已完成）。**认领 = `TaskUpdate(status="in_progress")`**，不新增认领工具。
- 「被阻塞」是**派生态**不是存储态：任务有未完成的依赖时，`TaskList` 列表里标 blocked、认领被拒。
- 依赖字段两种互补写法：`addBlockedBy`（我被谁阻塞）与 `addBlocks`（我阻塞了谁），接受任务 ID 数组；A 加 `addBlocks=["B"]` ⇔ B 加 `addBlockedBy=["A"]`。
- 认领前置条件：状态为 `pending` 且全部依赖 `completed`，否则拒绝。
- 自身依赖与循环依赖（加依赖时按可达性检查）一律拒绝。
- 队员终止/崩溃时，其持有的 `in_progress` 任务**自动回滚为 `pending`、认领人清空**（spec 补白决策）。
- 任务 ID 为团队内顺序递增整数，对外转字符串。

### 3. 存储位置与格式

- 团队目录：`.acode/teams/{name}/`（`name` 为重名避让后的实际团队名）：
  - `config.json` — 团队配置：`name`、`leadAgentID`、`members`（花名册数组：队员名 / agentID / agentType / model / worktreePath / backendType / isActive / planModeRequired）、`createdAt`。整文件原子替换（临时文件 + ATOMIC_MOVE），坏文件按空团队处理并告警不抛。
  - `tasks.json` — 共享任务列表：JSON 数组，每项含 ID/标题/描述/状态/依赖/认领人。写 = 抢文件锁 → 读改写 → 原子替换；读容忍坏文件按空列表。
  - `mailbox/` — 每个规范收件人名称一个 `{recipient}.json`；消息信封：`id`（UUID）/ `from` / `to` / `summary` / `message` / `messageType` / `ts`（时间戳）/ `read`（已读标记）。读先取得未读列表，请求成功进入模型调用后才按本批 id 确认并标记已读（**消息不删除，可追溯**）；写 = 抢锁 → 整文件读 → 追加 → 整文件写回（临时文件 + ATOMIC_MOVE，不做流式追加）；收件箱不存在时读返回空；写失败抛业务异常。
  - `transcripts/` — 每队员一个 `.jsonl`，逐条追加（复用 session 包 `SessionCodec` 编解码）；压缩重建整段原子重写；坏行跳过；写盘失败只告警不打断。
- **团队状态会话内落盘、重启不恢复**：进程内队列表不读盘恢复，目录仅是会话内工作状态，由 TeamDelete 整目录清理。

### 4. 锁协议（prompt「文件邮箱」原文数值）

```
写之前：用 O_CREATE | O_EXCL 抢锁（"创建文件"本身就是原子操作）
抢不到：按 5～100 毫秒随机抖动后重试，最多 10 次
        （随机化是为了避免多个进程同步重试导致活锁）
超时：  超过 10 秒还没释放的锁，只触发持有者存活检查；持有者仍活跃或无法确认时不得删除；确认进程已退出后才能回收
```

释放 = 核对持有者标识后删除自己的锁文件；锁对象 `try-with-resources` 风格 AutoCloseable。同一套锁同时服务邮箱与任务存储两个消费者。

实现补充：年龄按锁内 createdAt（epoch 毫秒）计算，记录 pid、startedAt、token。持锁全程持有独立 `.guard` 文件的 OS 锁，该文件不删除。同 JVM 使用 256 个公平信号量分片，单次排队最多 5 秒；分片碰撞只增加等待，不影响互斥。取得本地许可后再执行上面的最多十次跨进程尝试。

### 5. 重名避让与 Worktree 命名

- 团队重名自动避让：`team` → `team-2` → `team-3`（内存 map + 磁盘目录双查）。
- 队员 Worktree 命名规则（prompt「派队员：六个步骤」第 2 步原文）：`team-{teamName}/{name}`；平铺时 `/`→`+`、分支名 `worktree-` 前缀（ch13 的 slug/平铺语义，本章只消费）。ch13 无可编程接口或分配失败时派生失败，不能共享主工作目录。

### 6. 队员系统提示三行附录（prompt 原文照抄）

```
IMPORTANT: You are running as an agent in a team.
Just writing a response in text is not visible to others
on your team - you MUST use the SendMessage tool.
The user interacts primarily with the team lead.
Your work is coordinated through the task system
and teammate messaging.
```

三条规则：用 `SendMessage` + `to: "<name>"` 给指定队友发消息；用 `to: "*"` 广播（谨慎使用）；纯文本回复对队友不可见，必须用 SendMessage。

### 7. `<task-notification>` 格式（prompt「结果投递与续写循环」原文照抄）

```
<task-notification>
  <task-id>{agentId}</task-id>
  <status>completed | failed | killed</status>
  <summary>Agent "Investigate auth bug" completed</summary>
  <result>{队员的最终文本回复}</result>
  <usage>
    <total_tokens>N</total_tokens>
    <tool_uses>N</tool_uses>
    <duration_ms>N</duration_ms>
  </usage>
</task-notification>
```

status 与终止原因的映射：正常结束（无工具调用/触顶）→ `completed`；provider 异常 → `failed`；被杀（取消/中断）→ `killed`。投递进 Lead 的收件箱。

### 8. Coordinator Mode 双锁与白名单（prompt 原文）

```
function isCoordinatorMode() -> bool:
    if not feature('COORDINATOR_MODE'): return false
    return isEnvTruthy(process.env.ACODE_COORDINATOR_MODE)
```

- 双锁：开发者开关 = `AppConfig` 的 `COORDINATOR_MODE` 布尔字段（三级配置键，默认关闭）；用户开关 = 环境变量 `ACODE_COORDINATOR_MODE`（truthy 判定）。**两把都开才生效**。
- 白名单（prompt 原文）：

```
COORDINATOR_MODE_ALLOWED_TOOLS = [
    Agent, SendMessage,                                   // 派人和通信
    TaskCreate, TaskGet, TaskList, TaskUpdate,            // 任务管理
    TeamCreate, TeamDelete,                               // 团队管理
    ReadFile, Glob, Grep,                                 // 读类
]
```

没有 `WriteFile` / `EditFile`。四阶段工作流（Research / Synthesis / Implementation / Verification）与「理解不能外包」进协调者系统提示，不硬化成分支逻辑。

### 9. 错误文案（唯一出处，实现照抄）

| 场景 | 文案 |
|---|---|
| 任务不存在 | `任务不存在：{taskID}` |
| 认领冲突（非 pending） | `任务 {taskID} 已被认领` |
| 依赖未满足认领 | `任务 {taskID} 存在未完成的依赖，不能认领` |
| 自身依赖 | `任务不能依赖自身` |
| 循环依赖 | `存在循环依赖：{链条}` |
| 更新参数全空 | `TaskUpdate 至少需要 status、addBlockedBy、addBlocks 中的一项` |
| 收件人不存在 | `收件人不存在：{to}` |
| 摘要词数 | `summary 必须为 5～10 个词` |
| 消息类型非法 | `不支持的消息类型：{messageType}` |
| 非 Lead 发审批回复 | `只有 Lead 能发送 plan_approval_response` |
| 退出回复发给非 Lead | `shutdown_response 只能发给 Lead` |
| 邮箱写入失败 | `邮件投递失败：{原因}` |
| 团队不存在 | `团队不存在：{team_name}` |
| 有队员活跃时删除 | `队员 {name} 仍在活跃中`（prompt 原文） |
| 有队员停不下来 | `队员 {name} 未能停止，取消删除` |
| 删除成功返回 | `已清理 {n} 名空闲队员：{names}，团队 {team_name} 已删除` |
| 创建成功返回 | `团队 {name} 已创建`（重名时返回实际带序号的名字） |

### 10. 其它数值

- 队员数量：**不设硬上限**（prompt 边界原文）。
- 后端类型：本章恒 `in-process` 一个取值（枚举只留这一个 + 将来 pane 后端的字段位）。
- 队员工具集硬剔除四类：Agent 工具、团队管理工具（TeamCreate/TeamDelete）、AskUser、ExitPlanMode。
- 确认门槛：队员操作以父级明确授权为上限；待确认而未获授权的操作拒绝，请求工具清单与执行入口均执行同一过滤。
- 消息信封时间戳用毫秒 epoch；邮件轮询在每轮循环开头注入（经 `buildRequest` 的 turnReminder 尾插，不进历史）。

---

## T1 团队模型与配置存储

- [x] `TeamManager.create("team", "lead", ...)` 建队后 `.acode/teams/team/config.json` 存在，字段 `name`/`leadAgentID`/`members`/`createdAt` 齐全
- [x] 重名三次依次得 `team` / `team-2` / `team-3`（内存与磁盘双查都命中，第三个目录真实存在）
- [x] 注册队员后 `members` 数组含该条目；翻 `isActive` true/false 生效；移除后清单不再含它（`grep` 断言无墓碑态字段/值）
- [x] 坏配置 JSON（截断/非法）不抛异常、按空团队处理且有告警日志
- [x] 并发注册 10 名队员，花名册恰好 10 条、无丢无重（CountDownLatch 对齐，反复跑 3 轮全绿）
- [x] `grep -rn "teams" src/main/java/com/acode/team/TeamManager.java` 的目录常量只有 `.acode/teams/` 一处

- [ ] 模型实际调用 `TeamCreate(team_name="team")` 建队（归 T6/T11 接入；当前尚无该工具）。

## T2 文件锁、文件邮箱与名称注册表

- [x] 两线程抢同一锁恰一胜；败者重试后拿到；锁释放后可再抢
- [x] 锁内 createdAt 超过 10 秒且持有者仍活跃时不得被抢占；确认持有进程已退出后可回收；无法确认状态时返回锁忙
- [x] 原持有者迟到的释放动作不会删除新持有者的锁；跨 JVM 持锁时另一进程获取失败，释放后成功
- [x] N=8 线程各写 M=5 条到同一收件箱 → 收件箱恰有 40 条、JSON 完整可解析（CountDownLatch 对齐，内部 3 轮）
- [x] 按读取批次 ID 确认后该批不再未读；期间新到消息保持未读；重复确认不删除消息
- [x] 坏 JSON 按空处理并告警；缺失邮箱读取为空；对坏文件投递/确认均拒绝覆盖
- [x] 信封往返 record 相等，覆盖 id/from/to/summary/message/messageType/ts/read；已读状态仅经确认改变
- [x] 名字/ID 解析等价，未命中返回空；重复名称、重复 ID 和名称/ID 交叉冲突拒绝；对用户的未命中错误留给 T5
- [x] 写入经 TeamJsonFile 全量序列化 + ATOMIC_MOVE，无文件追加路径

## T3 共享任务存储

- [x] **认领竞态**：两线程跨存储实例认领同一任务恰一成功（CyclicBarrier 对齐，内部 5 轮）
- [x] 依赖未满足认领被拒（文案「任务 {id} 存在未完成的依赖，不能认领」逐字）；依赖完成后可认领
- [x] addBlocks 与 addBlockedBy 两种写法的正反依赖一致，阻塞随依赖完成解除
- [x] 自身依赖、二节点及三节点循环被拒；失败更新与失败创建不改变原任务文件
- [x] 队员移除后其 in_progress 任务回到 pending、清空认领人、可再认领；已完成任务与其他成员任务不变
- [x] tasks.json 读回往返一致；坏文件读空并告警，写入与回滚拒绝覆盖
- [x] 所有写方法调用 TeamJsonFile.lock，统一返回 TeamFileLock
- [x] 非持有者不能完成任务；认领后不能增加该任务的依赖

## T4 任务工具四件套

- [x] 四个工具的 `inputSchema` 字段与必填性与顶部表格**逐字段一致**（schema 序列化后断言）
- [x] `TaskCreate(title="x")` 返回文本含新任务 ID；存储里可查到该任务
- [x] `TaskCreate` 带 `addBlockedBy` 后，`TaskGet` 详情含该依赖；`TaskList` 列出全部任务与状态/阻塞标记/认领人
- [x] `TaskUpdate(status="in_progress")` 后状态变化；`TaskUpdate(status="completed")` 后依赖它的任务阻塞解除
- [x] `TaskUpdate` 三参数全空报错（文案逐字）；`TaskGet` 不存在 ID 报错（文案逐字）
- [x] 认领冲突经工具路径恰一成功（两线程调 `TaskUpdate(in_progress)` 同一任务）
- [x] 权限级别：Create/Update 声明 write、Get/List 声明 read（`permission()` 断言）
- [x] TaskToolsTest 断言四个工具描述均含 FYI，TaskTool 统一附加任务/消息分工说明

## T5 SendMessage 工具

- [x] 按名字与按 ID 投递到同一规范名称收件箱（信封 to 统一为该名称、内容相同）；广播 `to="*"` 到达花名册全部队员收件箱
- [x] 摘要 4 词与 11 词都被拒（文案逐字）；5 词与 10 词都通过
- [x] 收件人不存在报错（文案逐字）；结构化消息类型非法被拒（文案逐字）
- [x] 非 Lead 发 `plan_approval_response` 被拒；`shutdown_response` 发给非 Lead 被拒（文案逐字）
- [x] 投递后收件箱信封与顶部格式逐字段一致
- [x] SendMessage 的基础投递与唤醒回调分离；独立工具测试不唤醒，TeamSession 接入 T8 后空闲成员会续写

## T6 TeamCreate / TeamDelete 工具

- [x] `TeamCreate` 后 `.acode/teams/{name}/` 下 config / tasks / mailbox / transcripts 目录齐全
- [x] 重名返回带序号的实际名字（返回文案含 `team-2`）
- [x] 全部队员空闲且 Worktree 可安全删除时，`TeamDelete` 后目录不存在，返回「已清理 {n} 名空闲队员：{names}，团队 {team_name} 已删除」；任一队员活跃或 Worktree 有未保存成果时删除被拒，文件保留
- [ ] TeamDelete 的不存在团队错误、活跃成员错误文案逐字断言（行为已有测试，逐字断言待补）
- [x] 后端仅 TeammateInfo.BackendType.IN_PROCESS，没有 pane 后端运行路径

## T7 队员运行时（in-process）

- [x] 队员首轮请求含协调五件套；构建器过滤 Agent/TeamCreate/TeamDelete/AskUser/ExitPlanMode，普通子 Agent 不继承 Lead 绑定工具
- [x] 伪造被过滤工具调用、或调用未获父级明确授权的待确认操作，执行入口均拒绝且文件与进程无副作用
- [x] 队员首轮请求系统提示含 TEAM_PROMPT 附录（逐字比对）
- [x] 邮箱消息进入轮次提醒；provider 失败保持未读，成功响应后按批次确认（构建失败与取消发生在确认之前）
- [x] 正常结束（脚本流末尾无工具调用）→ Lead 收件箱出现空闲通知 + `<task-notification>`：status=completed、`<summary>` 含队员标识、`<result>` 为最终文本、usage 三字段（total_tokens/tool_uses/duration_ms）齐全且数值 ≥0
- [x] provider 抛错 → status=failed；取消 → status=killed；花名册保留队员身份但标为不活跃，便于续写；其 `in_progress` 任务回滚为 `pending`
- [x] TeamReminderTest 比对未装配/空动态提醒的请求消息 JSON 和工具列表，结果一致；构建失败不确认邮件
- [x] TeammateRuntime 的监督任务由 VirtualThreads.POOL 提交，内部复用 Agent 循环

## T8 队员 transcript 持久化与空闲续写

- [x] 队员结束 → `transcripts/{name}.jsonl` 存在且行数 > 0；`SessionCodec.decode` 重建的 `Conversation` 消息数与结束前一致
- [x] 给已停止队员发消息 → 新循环启动、首轮请求含完整历史（消息数不变）且 turnReminder 含这条消息
- [x] 压缩重写后 transcript 内容与原历史一致（逐行比对）；坏行被跳过不抛
- [x] 用文件占用 transcripts 目录模拟写盘失败，保存不抛且告警，哨兵内容不变
- [x] 恢复后 agentID 不变，请求含先前结果与新消息，复用原 Conversation 并从 transcript 重建历史

## T9 队员派生管线

- [x] 派生成功：花名册有它、名称注册表有它、队员循环已起（provider 收到请求）、返回摘要含队员名与 Worktree 路径
- [x] 定义式（指定 agentType）：重建对话消息数为 0 + 任务消息；Fork 式（留空）：继承 Lead 历史（断言重建后消息数 > 0）
- [x] 注入假的可编程 Worktree 接口（测试桩）→ 队员 `ToolContext` 工作目录指向分配的路径；接口缺失或分配失败 → 派生失败且不留下名称与花名册条目
- [x] 注入注册后的 Agent 工厂失败，花名册清空、Worktree 被清理；重用同名队员再次派生成功，证明注册表未残留
- [x] TeamSession.FORBIDDEN 包含 Agent/TeamCreate/TeamDelete/AskUser/ExitPlanMode；请求及伪造 Agent 调用测试均验证隔离

## T10 Coordinator Mode 与 Plan 审批协议

- [x] 双锁：只开 `COORDINATOR_MODE` 配置不开环境变量 → `isCoordinatorMode()` 为 false；两把都开 → true
- [x] CoordinatorMode.ALLOWED 固定为文档列出的十一项，源码核对不含 Bash/WriteFile/EditFile
- [x] 协调模式请求工具被白名单过滤；伪造 WriteProbe 调用被执行入口拒绝，副作用计数为零
- [x] 审批流：`planModeRequired` 队员提交计划 → 计划到达 Lead 邮箱；Lead 驳回附反馈 → 队员下一轮提醒含该反馈；批准指定操作后只有该范围可执行，未获批准的修改仍拒绝
- [x] 四阶段系统提示注入后，Lead 请求系统提示含 Research/Synthesis/Implementation/Verification 四段与「理解不能外包」要求

## T11 接入主流程与文档补齐

- [x] TeamIntegrationTest 捕获主 Agent 请求，七个新工具全部可见；建队、建任务、列举、删除经主循环返回成功结果
- [x] TeamSession 输出回调绑定 emitLine 双写，TeammateRuntime 只积累 StreamText，提交收尾状态；终端实际视觉效果另列手测
- [x] start/finally、closeSession 与会话重置调用 TeamSession.close；取消测试验证队员空闲并回滚任务
- [x] `docs/manual-test.md` 有「阶段十四」小节；`README.md` 功能特性含 Agent Teams 一行

## T12 端到端验证 ⚑ 与统一收尾回归

- [x] **建队**：TeamCreate → `.acode/teams/{name}/` 三件套齐全
- [x] **拆任务**：经工具写 4 个任务并加依赖（T3 等 T1、T4 等 T2）；T1 未完成时认领 T3 被拒（文案逐字）
- [x] **并行队员**：两名队员经 TaskUpdate 认领/完成各自前置与后续任务，四任务均 completed；TaskList 另经主流程验证
- [x] **通信往返**：alice 发消息给 bob → bob 下一轮请求的 turnReminder 含该消息与摘要
- [x] **完成通知**：Lead 收件箱出现两条 `<task-notification>`（status=completed、result 与 usage 三字段齐全）
- [x] **空闲与续写**：给已空闲的 alice 发消息 → 恢复循环、首轮请求含历史与消息
- [x] **竞态**：两身份通过 TaskUpdate 工具并发认领同一任务恰一成功（CyclicBarrier，不 sleep）
- [x] **崩溃回滚**：一名队员 provider 抛错 → 通知 status=failed → 其进行中任务回滚待认领
- [x] **清理**：TeamDelete → 返回名单文案 → 目录消失；有活跃队员时清理被拒（文案逐字）
- [x] **全套回归**：1339 tests / 0 failures / 0 errors / 2 skipped，日志 20260929-172034-245；最后的边界补测另见 test-review.md

## 真机 ⚑ 手测（填 `docs/manual-test.md`「阶段十四」）

- [ ] ⚑ 打包后真机建队 + 派两名队员并行干活：终端提交行不乱、不互相覆盖，主循环可继续输入
- [ ] ⚑ 队员执行中 ESC / Ctrl+C：提交行不残留、主循环不卡死、无异常堆栈上屏
- [ ] ⚑ 协调模式开启（两把锁）：Lead 工具列表肉眼可见收窄，拒绝直接改代码；关闭后恢复
- [ ] ⚑ 杀进程后重启：残留目录不自动复活团队、不自动删除；TeamDelete 仅清理当前会话创建的团队


## T1 阶段验收补充（2026-09-29）

- [x] TeamManagerTest 最终 13 个测试连续 3 轮完成，0 failures、0 errors、1 skipped；并发注册测试内部每轮另含三次十线程起跑。
- [x] Windows junction 指向项目内其他目录时创建被拒，目标目录保持空；符号链接创建权限不足的用例保持 skipped，不当作通过。
- [x] 配置写入失败不发布内存快照；检查坏配置只告警并返回空，不清空当前会话或覆盖原文件。
- [ ] 真实 provider / 终端手测仍待完成，不把假模型自动化等同于真机验收。
