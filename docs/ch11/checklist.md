# ACode 阶段十一：Hook 生命周期钩子与自动化 — 验收清单

> 最后更新：2026-09-27
> 对应 `docs/ch11/spec.md` 的能力清单与 `docs/ch11/tasks.md` 的 T1~T9；本清单钉死 spec 刻意留白的全部数值、文案与判定口径（落笔处若与 HEAD 不一致，先 Read 核对再改本表）。spec 的 Out of Scope 原样转为末尾「不验收」清单。
> ⚑ = 真机（真实 jar + 真实文件系统）验收项；其余为 `JAVA_HOME="D:\java\jdk21" mvn -Dtest=… test` 可单跑的单测项。⚑ 未跑之前不勾选。

## 默认值说明

### 1. 三处配置文件（路径精确拼写，全部勾选前不跑真机项）

- 用户级：`~/.acode/hooks.yaml`（`System.getProperty("user.home")`）
- 项目级：`<项目根>/.acode/hooks.yaml`
- 本地级：`<项目根>/.acode/hooks.local.yaml`（优先级最高）
- 加载顺序：**先本地级 → 再项目级 → 再用户级**；追加合并、三处全部生效（不是找到一个就停）；同文件内按声明顺序
- 「本地级优先级最高」只体现在**执行顺序**（最先执行），不覆盖项目级/用户级

### 2. 条目 schema 与校验

- 合法键：`id`（可选，字符串）、`event`（必填）、`if`（可选）、`action`（必填）、`reject`（可选，布尔）、`once`（可选，布尔）、`async`（可选，布尔）；action 内键：`type`（必填）、`command`、`timeout`、`message`、`url`、`method`、`body`、`prompt`（按类型取用）
- 校验失败 → 该条剔除、其余照常；错误行（打到终端 System.err，与既有启动期打印惯例同一条路，见 T1 核对项）：`<文件路径>: 第 <N> 条 Hook（id=<id>）：<原因>`（第 N 条从 1 起、含全部条目）
- **静默跳过（只 log.warn 落日志文件，不报终端）**：文件不存在 / 读取失败 / 整体 YAML 解析失败 / 根非 map / `hooks` 键缺失或非列表（整个文件跳过）；条目非 map（跳过该条）
- 需要报错的原因文案（= 校验原因，逐字验收）：
  - `事件名不合法：<值>（合法值：pre_tool_use / post_tool_use / session_start / turn_start / turn_end）`
  - `动作类型不合法：<值>（合法值：command / prompt / http / agent）`
  - `reject 只能用在 pre_tool_use 事件上`
  - `async 不能用在 pre_tool_use 事件上`
  - `动作缺少必填字段：<字段>`（command 缺 `command`、prompt 缺 `message`、http 缺 `url`、agent 缺 `prompt`）
  - `条件结构不合法：<原因>`
  - `正则无法编译：<pattern>`
  - `id 重复：<id>`
  - `timeout 格式不合法：<值>（应为 <正整数>ms|s|m）`
  - `顶层含未知字段：<键>`
  - `action 含未知字段：<键>`
  - `条件含未知字段：<键>`（`event` 不得作条件键，落入此项）
- 派生 id（无显式 `id` 时）：`<来源层>:<相对配置路径>#<N>`（N = 条目序号，从 1 起、含被跳过的条目；不同来源的同名文件不碰撞）；显式 `id` 优先于派生 id，跨来源重复 id 在加载期报错

### 3. 五个事件名（精确拼写，仅此五个）

`session_start`、`turn_start`、`pre_tool_use`、`post_tool_use`、`turn_end`（英文小写 + 下划线）。任何其他事件名在校验期被拒绝。

### 4. 条件（`if`）

- 字段来源：`tool` → 工具名；`args.xxx` → 工具参数**顶层**键 `xxx` 的值（文本原样；数值/布尔转字符串；对象/数组取 JSON 序列化；参数非对象或键不存在 → 空串）；事件名不作条件字段
- 匹配方式（写在键的取值处）：`字段: 值` 精确全等；`字段: { regex: "..." }` 正则（**find 语义**：局部命中即匹配，非全串）；`字段: { glob: "..." }` glob（翻译成正则后**全串匹配**：`*`→`[^/]*`、`**`→`.*`、`?`→`[^/]`、其余字符正则字面转义；匹配前把**值**里的 `\` 换成 `/`）
- 组合：多个字段键默认**且**；`all: [ … ]` 全部满足；`any: [ … ]` 任一满足；`not: { … }` 取反；三者任意嵌套、层级即优先级。`all`/`any` 的值必须是列表、`not` 的值必须是映射（否则校验报 `条件结构不合法`）
- 判定口径：空 `all` 视为满足；空 `any` 视为不满足；字段取不到值 → 任何匹配方式都不匹配、**不报错**

### 5. 四类动作

- `command`：`command` 必填（字符串模板，执行前变量展开）；`timeout` 可选，格式 `<正整数><ms|s|m>`（缺省单位 s），**缺省 30s**；超时强杀子进程按执行失败；非零退出码按执行失败但输出照常返回；输出捕获上限 **30000 字符**，超出截断并在末尾追加 `\n…（输出过长，已截断）`（沿用 `BashTool.java:24/151` 口径）
- `prompt`：`message` 必填；产出文本进注入队列，走系统提醒通道、**不进对话历史**
- `http`：`url` 必填；`method` 可选（GET/POST/PUT/DELETE，**缺省 POST**）；`body` 可选（缺省空）；连接/请求超时缺省 **10s**；响应体截断 **4000 字符**；连接失败或非 2xx 按执行失败
- `agent`：`prompt` 必填；本章为接口 + 调用骨架，执行时返回明确未实现失败（真实子 Agent 运行时由 ch12 接入），失败结果不触发 Hook 的拒绝效果。

### 6. 上下文变量（动作模板展开）

| 变量 | 替换成 |
|---|---|
| `$EVENT` | 事件名 |
| `$TOOL_NAME` | 工具名 |
| `$FILE_PATH` | 工具参数字段 `file_path` 的文本优先；不存在取 `path` 的文本；再不存在为空串 |
| `$MESSAGE` | 轮次事件的用户输入（其余事件为空串） |
| `$ERROR` | post_tool_use 时失败工具结果的文本（其余为空串） |
| `$TOOL_ARGS.xxx` | 工具参数顶层键 `xxx`（取值口径同条件字段） |

未定义变量替换为**空串、不报错**。

### 7. once 标记

- 格式：会话 JSONL 文件（`<项目根>/.acode/sessions/<id>.jsonl`）中追加一行 `{"hook_once":"<id>","ts":<epoch秒>}`——**无 `role` 字段**，时间单位与现有 `SessionRecorder` 一致；既有逐行解码（`SessionCodec.decode`）把它当坏行跳过
- 标记时机：同步 Hook 执行**之后**；异步 Hook 在**派发时**标记；执行失败也标记
- 判定：fire 时 `once` 且 id 已在标记集 → 跳过；恢复会话时读回标记（`readHookOnceIds`），已触发的 once Hook **不重触发**、未触发的照常触发；新建会话标记集为空、重新计一次
- `/clear` **不清**标记（仍是同一会话文件）；`/compact` 重建后标记行保留且排在最前；旧会话文件无标记行 → 按未触发过处理

### 8. 拒绝与执行顺序（判定口径）

- 判定顺序：权限链先判（**DENY 的调用 Hook 完全不参与**）；ASK 经确认放行后 Hook 再问；交互式工具（AskUser 等）同样经过 pre/post，无豁免
- 多条命中按执行顺序逐个执行，**任一 reject 即短路**，其后 Hook 不再执行
- 工具结果文案：`Hook 拒绝：<动作产出文本>`；产出为空时仅 `Hook 拒绝`（空文本的成功动作即此情形；agent 占位失败不会拒绝）——作为 tool_result 回给模型，模型据此换做法
- `post_tool_use` 只在工具**实际执行**之后触发；权限拒绝 / 用户拒绝 / Hook 拒绝 / 已取消的调用不触发；工具执行失败（error 结果）仍触发且 `$ERROR` 有值

### 9. 运行期日志文案（只落 `~/.acode/logs/acode.log`，不进终端不刷会话）

- 执行异常：`Hook 执行失败 [<id>]：<原因>`
- 命令超时：`Hook 命令超时 [<id>]：<timeout>`
- 命令非零退出：`Hook 命令非零退出 [<id>]：exit=<n>`
- agent 未实现：`Hook agent 执行器未实现（待 ch12 SubAgent 运行时对接）[<id>]`
- http 失败并入 `Hook 执行失败 [<id>]：<原因>`
- 引擎自身异常（fire 兜底）同样只记日志，**绝不外抛、不中断主流程**

### 10. 事件触发点与轮次语义

- `session_start`：非恢复启动（`initSessionState` 之后、主循环之前）；恢复激活（`activateSession` 末尾，**先读回 once 标记再 fire**；`--resume` 与 `/resume` 菜单同路，启动恢复路径不另 fire）
- `turn_start`：`ExchangeRunner` 装配后，`Agent.run` 工作线程开始（`$MESSAGE` = 用户输入）；`turn_end`：`LoopComplete` 之前（`$MESSAGE` = 用户输入）
- 内部迭代（`TurnComplete`：工具调用后继续请求模型）**不触发** turn_start/turn_end；取消路径（无 `LoopComplete`）不触发 turn_end
- `pre_tool_use`：权限链得出允许结论之后、工具执行之前；`post_tool_use`：工具执行返回之后
- prompt 注入通道：drain 后包成 `<system-reminder>` 标记的文本，**只进下一次请求、不进对话历史**；session_start/turn_start 的 prompt 出现在该会话下一轮请求；post_tool_use 的 prompt 出现在**同一 exchange 的下一轮请求**
- 引擎未装配（null）或三处配置皆无 Hook 时：链路行为与本章之前逐字一致

### 不验收（对应 spec Out of Scope，原样不写、不测）

- SubAgent 运行时（`agent` 动作只验收接口与骨架：校验通过、执行不抛、明确失败）
- 消息级事件（`pre_send` / `post_receive`）、系统级事件（`startup` / `shutdown` / `error` / `compact`）、场景化事件（`permission_request` / `file_change` / `command_execute`）
- `/hooks` 等查看 / 启停 / 编辑命令（配置文件由用户直接编辑，与权限规则立场一致）
- 配置热重载（改动配置需重启才生效）
- `once` 的跨会话记忆（新会话重新计）
- 条件表达式的字符串语法（`tool == "..." && ...`）
- Hook 放宽权限的任何路径（Hook 只能收紧）
- 更多匹配能力（数值比较、字段存在性、集合成员、正则捕获回填）
- 按目录/工具/轮次的作用域细分、Hook 之间的依赖编排与并行
- Hook 执行历史、耗时、成功率等可视化与统计
- `post_tool_use` 按工具输出内容过滤（后置事件只做通知，条件只看工具与参数）

---


- [x] 三处 hooks 配置各两条时读出六条，顺序 local → project → user，ID 跨来源唯一。
- [x] 坏条目报告文件、序号和 ID，其他条目继续加载；循环条件被拒绝。
- [x] regex 为 find；glob 中 * 不跨目录、** 跨目录、? 匹配单字符；缺失字段不匹配。
- [x] 未定义变量展开为空，路径、消息、错误与工具参数正确展开。
- [x] pre 拒绝后目标文件不存在，下一请求包含 `Hook 拒绝：blocked by policy`。
- [x] 权限拒绝、用户拒绝、已取消的工具不触发 pre/post；交互工具按确认 → pre → 执行 → post 排序。
- [x] command 默认 30s，HTTP 默认 10s；命令输出截断至 30000 字符并附提示，HTTP 响应限制为 4000 字符。
- [x] 命令非零退出和超时返回失败；agent 占位失败不能拒绝原工具；无交互入口的 ASK 动作执行次数为 0。
- [x] HTTP 默认 POST、正文变量展开，本地响应和非 2xx 正确处理；重定向不自动跟随。
- [x] post 的提示词进入同一次 exchange 下一请求，历史不含该提醒；turn_start/turn_end 不随内部迭代重复。
- [x] once 元数据无 role，压缩后排在消息之前；恢复后不重发，新会话重新触发，/clear 后标记仍存在。
- [x] 后台任务在恢复到新会话后不能注入旧提示；取消 exchange 不触发 turn_end。
- [x] 配置错误打印到 stderr，运行失败日志含 `Hook 命令非零退出 [fail]：exit=1`，stderr 无此日志。
- [x] JDK 21 下最终 `mvn package` 为 BUILD SUCCESS：1207 tests，0 failures，0 errors，1 skipped；日志 target/ch11-final-validation.log。
- [ ] HK1–HK6 真实终端／真实模型验收通过（步骤见 docs/manual-test.md；本轮尚未实跑）。

## 实施证据定位

- T1：HookLoaderTest，三来源六条叠加、稳定 ID、跨来源重复 ID、非法条目定位、缺字段、非法类型、timeout、未知键、非法 YAML 和循环条件。HookLoader 不打印；Controller 在开全屏前打印 errors。
- T2：HookConditionTest，组合逻辑、缺字段、数值与对象口径、路径归一、正则 find 和变量中的美元符号/反斜杠。条件深度限制 64。
- T3：HookEngineTest，过滤、顺序、短路、执行失败仍 once、drain、异步不阻塞与恢复后旧结果丢弃、坏条件隔离。
- T4：HookActionsTest，本机实际 shell 的输出/退出/超时/截断、JDK HTTP 的 POST/body/响应上限/非 2xx/重定向/慢正文；进程树超时由 HookSleepProcess 的父子 PID 验证。agent 明确失败。权限边界在 HookEngine.authorize 集中检查。
- T5：HookSystemEndToEndTest.onceSurvivesRewriteAndResumeAndNewSessionResets，标记无 role、旧解码跳过、原子 rewrite 保留、恢复不重发、新会话重计。
- T6：HookSystemEndToEndTest，拒绝回传模型、文件不落盘、权限/用户/取消拒绝均无事件、交互工具同路、执行失败 post 有 ERROR。
- T7：HookSystemEndToEndTest 与 HookControllerTest，内部迭代不重复轮次、取消无 turn_end、一次性提醒不入历史、恢复首轮提醒与 once、/clear 保留。
- T8：HookControllerTest.threeLayersExecuteInOrderThroughRealController；ConversationControllerTest/McpWiringTest/SessionResumeTest 已补 user.home 隔离；其余构造 Controller 的测试已逐一核对临时项目与临时 home。
- T9：HookControllerTest.configurationErrorVisibleAndRuntimeFailureOnlyLogged + noConfigurationAddsNoRemindersAndProducesSameMessages；全量 package 结果记录在下方。

## 审核更正

- 正则 find 对 `echo rm -rf` 也命中；旧验收“不命中”已更正。
- 无显式 ID 统一为 `来源层:.acode/配置文件#序号`；旧 `<文件名>#N` 已废弃。
- once 按 Hook ID 记忆；同一个 Hook 改发其他事件不会绕过事件过滤，也不会重置 once。
- 多提醒通过 Conversation.buildRequestWithReminders 接入，旧方法委托；避免旧调用传 null 时重载歧义。
- 四类动作统一在 HookActions 分派，HookAction 为可替换接口。无新增第三方依赖。
- 后台线程池归引擎拥有，恢复/退出可取消；这比共享静态线程池更便于隔离会话。

## 真机验收

- [ ] HK1：真实 jar 打开全屏前显示 hooks.yaml 条目错误，继续启动。
- [ ] HK2：真实模型写 Java 文件，后置命令生成路径标记。
- [ ] HK3：真实模型收到 Hook 拒绝并换做法，锁文件未被写入。
- [ ] HK4：退出再 --resume 不重发 once，新会话重发。
- [ ] HK5：失败动作只在 acode.log 出现，界面无日志污染。
- [ ] HK6：三来源标记按 local → project → user 顺序落盘。

以上真机项本轮未实跑：自动化使用 FakeProvider 与临时目录，不把替身验证视为真实模型/终端验收。

## 2026-09-27 最终验证

- [x] Hook 专项六组共 26 tests，在最终全量构建中全部通过。
- [x] Windows + JDK 21 的 `mvn package`：1207 tests / 0 failures / 0 errors / 1 skipped，BUILD SUCCESS。完整日志：target/ch11-final-validation.log。
- [x] 仅依赖 target/acode.jar 的 HookJarSmoke 输出 PASS，覆盖配置加载、变量提醒、once 元数据、压缩重写与恢复；测试数据位于 target/ch11-jar-*，user.home 指向 target/ch11-smoke-home。
- [x] git diff --check 无空白错误；pom.xml 未改变，无新增依赖；docs/ch11/spec.md 已确认范围保持原样。

产物：target/acode.jar。ch10 既有 Skill 测试随全量回归通过，原三项真实模型验收继续保留未勾选。ch11 的 HK1–HK6 同样未实跑，不宣称真机验收完成。
