# ACode 阶段九：Slash Command 命令框架 — 任务清单

> 最后更新：2026-09-13
> 依赖关系：`T1→T2`；`T1→T5`；`T5→T6`；`T6→T7/T8/T9`（三者都在改 T6 新建的 `command/BuiltinCommands.java`，必须依次做、不可并行）；`T3/T4→T7`；`T6/T7/T8/T9→T10`；`T2→T11`；`T10/T11→T12→T13`。T3/T4 相互独立，可与 T2 并行；**T11 只依赖 T2**，可与 T8/T9/T10 并行。
> 源码依据：ACode 现有实现——本阶段**替换**现有路由类（枚举式分类 + 主循环分支的硬编码命令路径），**复用**既有的输出提交范式（`output.appendLine` + `live.appendCommitted`）、选择菜单、权限/记忆/会话/上下文四套子系统，并**修复**长期记忆对 Agent 的可及性缺陷（见 T3）。
> 数值口径、别名表与全部文案见 `checklist.md` 顶部「默认值说明」。

## 约定

- 新增包根 `com.acode.command`：主代码 `src/main/java/com/acode/command/`，测试 `src/test/java/com/acode/command/`
- 界面操作接口与补全器放既有 `com.acode.ui` 包；命令调度器与内置命令定义放 `com.acode.command`；依赖补齐改动留在各自既有包（`memory` / `permission`）
- 现有文件行号以 2026-09-13 的 HEAD 为准，改动时先 Read 确认
- 新测试方法名一律英文驼峰（惯例）；随任务写对应测试，可用 `mvn -Dtest=指定类 test` 单跑新增；**全套 `mvn test` 在收尾任务 T13 统一跑**
- 构建环境：`JAVA_HOME="D:\java\jdk21"`（默认 jdk17 编不了 release 21）；运行时用 `target/acode.jar`，注意先重打包
- 需要复用的现有设施：假 provider `src/test/java/com/acode/provider/FakeProvider.java`（已支持 `receivedRequests()` 捕获）；临时目录用 JUnit `@TempDir`；构造 `ConversationController` 的测试必须设 `@TempDir` 项目根并关 `memory_auto`，否则会写进真实仓库
- **会话侧无新增后端能力**：`/resume` 直接复用既有的会话选择入口（已完整实现"列出 → 菜单选择 → 加载"），本章不为它新增存储或管理方法
- 迁移后**删除**：`ui/CommandRouter.java` 及其测试（枚举式路由被注册中心取代，不留兼容层）
- **不再存在**：`/session` 命令（会话恢复统一走 `/resume`）；`/permission mode`、`/permission rules` 等子命令写法（改为"无参查看、带参操作"）
- **长期记忆不做命令族**：`/memory` 只服务三层指令文件（外加 `run` 一个保留词）；长期记忆的增 / 改 / 删由 Agent 在对话中用文件工具完成，命令层不提供 `add` / `del` / `clear`，也不做"选作用域写记忆"的菜单
- **与 PR #2 的关系（用户已定）**：仓库里的开放 PR #2（`dabidai/ACode#2`）已经写了斜杠补全（`ui/SlashCommandCompleter.java`，走 JLine 内建 `Completer`）与状态栏（`ui/StatusBar.java`，自带 ANSI 色板），并新增 `/model` 命令。**本章不合并它、也不以它为前提**：命令清单与补全器照本清单自己实现；实现完成后再回头参考它的配色做风格统一（见 `spec.md` 非功能要求「界面风格统一」）。两点提示：① 它的补全器是 T2「内建补全 vs 自定义下拉」这个未知项的**现成对照组**，本地 `review/pr-2` 分支可直接查看；② 它同时改了 `CommandProcessor.java` 与 `ConversationController.java`，与 T7/T10/T12 是同一批文件——若它在本章实现期间合并，这三处会需要解冲突

---

### T1 命令模型、解析器与注册中心

**目标**：命令的"定义"能有地方放、放得下、查得到；输入能被稳定切成命令名与参数；重名与别名冲突当场暴露；注册中心并发安全。

**影响文件（新建）**
- `command/CommandType.java`（新）— 三类枚举：本地命令 / 本地界面命令 / 提示词命令。**仅作元数据**（帮助分组与说明用），框架不对它做执行派发
- `command/Command.java`（新）— 命令定义：名称、别名列表、描述、用法示例、类型、**参数用法说明**（供帮助详情与参数不合法时提示）、是否隐藏、处理函数。用 record 或不可变类（与既有 `PermissionRule`、`Session` 的不可变风格一致）。**不设"参数是否必需"**——本章没有"不带参数就没法用"的命令，无参数一律有确定含义（查看）
- `command/CommandResult.java`（新）— 处理结果枚举：继续主循环 / 退出主循环
- `command/CommandParser.java`（新）— 纯静态解析，无状态无终端依赖：
  - 返回"是否命令 + 命令名（小写化）+ 参数原文"
  - 非斜杠开头 → 不是命令
  - 裸斜杠（仅一个 `/`）→ 标记为"仅斜杠"，供调度器走帮助分支
  - 第一个空白之后的全部内容为参数（含内部空白原样保留）；命令名后只有空白视为无参数
- `command/CommandRegistry.java`（新）— 读写锁保护：
  - 注册一条命令：名称与所有别名都做占用检测，冲突抛错（错误信息含冲突的那个名字与已占用它的命令名）
  - 按名查找：**先精确匹配正式名，未命中再遍历所有命令的别名**
  - 可见清单（供帮助与补全）：按**注册顺序**排列、排除隐藏命令
  - 全部命令清单（含隐藏，供内部使用）
  - 并发：读读并发、读写/写写互斥；用 `ReentrantReadWriteLock`
- `command/CommandRegistryTest.java` / `command/CommandParserTest.java`（新）—
  - 注册后按正式名查到；别名查到同一条命令；正式名与别名大小写不敏感（`/HELP`、`/H` 均命中）
  - 重名冲突抛错；别名撞别名抛错；别名撞正式名抛错；失败后注册中心状态不变
  - 可见清单**保持注册顺序**；全部清单与可见清单的关系正确（本章没有隐藏的内置命令，"隐藏不进可见清单"这条用**测试里注册的隐藏命令**来断言）
  - 同一个处理函数可以注册成多条命令且互不干扰（`/resume` 与将来的同类命令依赖这一点）
  - 并发冒烟：多线程并发查找 + 单线程注册不抛异常、结果一致（用虚拟线程池或固定线程池）
  - 解析：`/compact 保留 A B` → 名 `compact`、参数 `保留 A B`；`/HELP` → 名 `help`；`/` → 仅斜杠标记；`/memory   ` → 名 `memory`、无参数；`abc` → 非命令

**依赖**：无

**参考资料**
- 现有路由的纯逻辑写法（本任务取代它）：`ui/CommandRouter.java:10`（枚举）、`:13-24`（手写帮助文本，随迁移删除）、`:32-56`（大小写敏感的分支匹配，正是本任务要修的问题）
- 不可变定义对象风格：`permission/PermissionRule.java`、`session/Session.java`
- 并发池：`util/VirtualThreads.java`（`POOL`，应用级共享、永不关闭）
- 文案：见 `checklist.md` 默认值说明

---

### T2 Tab 补全最小验证 ⚑

**目标**：在**投入正式实现之前**，用最小实现确认输入组件的补全行为在真实终端里到底长什么样，据此锁定方案（内建补全 vs 自定义下拉），把结论写回 `spec.md`。

**背景**：现有输入组件（JLine 3.27.1，见 `pom.xml`）有内建补全接口，多候选时会在输入行下方自绘候选列表并循环选择——但它与 ACode 的主屏渲染模型（活跃区重绘 + 原生回滚 + 接受输入时擦行）的交互**没有先例**。这是本章唯一的未知项。

**影响文件（新建 + 修改）**
- `ui/SlashCompleter.java`（新，最小版）— 实现输入组件的补全接口：输入以斜杠开头时，取注册中心的可见命令清单按前缀过滤；仅斜杠时给全部；候选项带命令名与描述。此时可先接硬编码候选列表，T11 再换成真实注册中心
- `ui/InputPane.java`（改）— 构造时挂上补全器（`InputPane.java:24-32` 的 `LineReaderBuilder` 链）
- `docs/ch09/spec.md`（改）— **把验证结论写进「关键设计决策」表的 Tab 补全行**：选了哪个方案、真机上 Tab 的三种行为（仅斜杠、唯一前缀、多候选）分别观察到什么、与主屏渲染是否有冲突

**验证项（必须在真实终端手动观察，不能只靠单测）**
1. 输入 `/` 后按 Tab → 是否列出全部可见命令
2. 输入 `/com` 后按 Tab → 是否直接补全为 `/compact`
3. 输入 `/` 后连续按 Tab → 多候选时如何呈现、能否循环、接受后输入行是否干净（无残留、无错位）
4. 补全后继续输入参数 → 输入行是否正常
5. 补全后按 Esc / Ctrl+C → 输入行与回滚区是否被正确清理
6. 补全操作**不得**污染原生回滚区（滚动上去不应看到补全列表残影）

**产出结论（三选一，写回 spec）**
- 内建补全行为可接受 → T11 走内建路线
- 内建补全与主屏渲染冲突但可绕过 → T11 走内建路线 + 记录绕法
- 内建不可接受 → T11 走自定义下拉路线（复用选择菜单的渲染样式与按键抽象，在活跃区渲染候选，选中后回填输入缓冲）

**依赖**：T1

**参考资料**
- 输入组件构造与按键绑定：`ui/InputPane.java:24-32`（builder 链，含 `ERASE_LINE_ON_FINISH`）、`:34-43`（`bindKeys`，已有自定义 widget 与按键映射的先例）
- 终端与读取器：`ui/AcodeTerminal.java`（暴露裸 `Terminal`，`terminal().reader()` 是非阻塞读取器）
- 选择菜单（自定义路线的样式与按键来源）：`ui/SelectionMenu.java`（反显选中、两空格缩进、活跃区 overlay）、`ui/MenuKeySource.java` / `ui/TerminalMenuKeySource.java`
- 活跃区渲染：`ui/LiveRegionRenderer.java`（`appendCommitted` / `clearScreen` / `redraw`）

---

### T3 记忆入口重定义与 Agent 可及性修复

**目标**：为"`/memory` 管指令文件"准备查询与创建能力；同时修好两处 **ch08 遗留缺口**，让 Agent 真正能读写长期记忆文件（用户级与项目级都要能碰到）。

**背景**：ch08 设计了"某条记忆相关时由 Agent 自行读取对应文件"，但实际不成立——① 注入的索引只有**文件名**没有路径，Agent 不知道去哪找；② 路径沙箱只放行**项目根 + 系统临时目录**（`PathSandbox.java:19-22`），用户级记忆根 `~/.acode/memory/` 在项目外，一律被拒。本任务把这两点修掉，长期记忆才可能交给对话维护。

**影响文件（修改 + 新建）**
- `prompt/ProjectInstructions.java`（改）— 新增**三层文件状态**查询（**缺口**：现在只有 `load()` 把三层拼成一段文本）：暴露三层各自的**路径、菜单显示标签、是否存在与行数**（菜单要按 checklist 显示"已存在 12 行 / 未创建"，选中已存在的层后也要报行数）。**路径定义必须单一来源**——三层路径现在硬编码在 `load()` 内（`:28-30`，boundaryLabel 为"项目/项目/用户主目录"），要抽成一处定义供 `load()` 与菜单共用，否则菜单和加载各写一份、将来分叉；菜单要的标签（项目指令 / 本地指令 / 用户指令）与 boundaryLabel 不是一套，在同一定义里一并给出。另提供**创建空文件**能力（`/memory` 选中不存在的层时用）
- `memory/MemoryStore.java`（改）— 注入文本的**分节标题带上该层记忆根路径**（`injectionText()`（`:242`）与 `heading()`（`:254`）是标题的唯一来源，当前只输出 `## Project` / `## User`）
- `prompt/PromptSections.java`（改）— ① 记忆索引段（`memoryIndexSection`，`:52-61`）现在只说"Read the linked file"却不给路径，配合上一条把路径信息补进语境；② **行为段**（`behaviorSection`）补一句约定：用户要求记住某事时，写入对应记忆文件并同步索引
- `permission/PathSandbox.java`（改）— 构造接受**一组额外允许根**；`check`（`:26-37`）在项目根与系统临时目录之外一并比对（记忆根在项目外，是既有设计的盲区）
- `permission/PermissionChecker.java`（改）— 构造透传额外允许根给沙箱（`new PathSandbox(projectRoot)` 处）
- `ConversationController.java`（改）— `buildPermissionChecker()`（`:552-562`）装配时传入记忆根（用户级 + 项目级，来源同 `MemoryManager` 的 `MemoryScope`）
- 测试：
  - `prompt/ProjectInstructionsTest.java`（改）— 三层状态查询：路径正确、存在与否正确、**行数正确**（空文件记 0 行）、层级缺失不抛错
  - `memory/MemoryStoreTest.java`（改）— 注入文本里含两级记忆根路径；`prompt/PromptSectionsTest.java`（改）— 行为段含"记住"约定
  - `permission/PathSandboxTest.java`（改）— 额外允许根内的路径被放行；根外路径仍被拒；不给额外根时行为与改动前一致（无回归）
  - `permission/PermissionCheckerTest.java`（改）— 用户级记忆根下的文件工具调用不再被沙箱拒绝；项目外其他路径仍被拒

**依赖**：无

**参考资料**
- 三层指令文件加载：`prompt/ProjectInstructions.java`（`load` 返回文本 + 告警行列表；三层顺序与路径解析已就位）
- 索引注入文本：`memory/MemoryStore.java:242`（`injectionText`）、`:254`（`heading`）；`memory/MemoryScope.java`（`root` / `label` / `indexPath`）
- 索引段包装：`prompt/PromptSections.java:52-61`；行为段内容 `PromptSections.java:65-79`
- 沙箱：`permission/PathSandbox.java:17-37`（构造与 `check`，`allowedRoots` 目前是项目根 + `java.io.tmpdir`）、`:48-55`（`resolveRoot` 解析符号链接的口径）
- 装配点：`ConversationController.buildPermissionChecker`（`:552-562`）；记忆根来源 `memory/MemoryScope.project(...)` / `MemoryScope.user(...)`（`ConversationController` 构造 `:194-197`）

---

### T4 权限规则三态与只读列举

**目标**：把规则效果从两态扩展为三态（放行 / 拒绝 / 询问），跨层按 **deny > ask > allow** 三级判定，并让 `/permission` 的无参列举拿到三层的规则清单与文件路径。**规则保持只读**——不做新增 / 修改 / 删除的入口。

**注意**：这是一次**权限系统的语义扩展**（求值从"两态取第一个非 null"变成三级判定，决策链的规则层从"放行 / 否则拒绝"变成三态派发），四处要一起改到，漏一处就会出现"文件里写着 ask 但行为是拒绝"这类静默不一致。**三级判定是本任务的语义核心**：deny 优先于一切，ask 优先于 allow 且跨层穿透——上层更宽的 allow 不能盖掉下层的 ask。**第五处、也是最容易漏的一处是决策链的层间次序**：询问不能像拒绝那样就地返回，否则第 ⑦ 层「会话级始终允许」永远轮不到，用户点了「始终允许」还会被反复追问（详见下面 `PermissionChecker` 一条）。

**影响文件（修改 + 新建）**
- `permission/PermissionRule.java`（改）— **效果枚举扩为三态**：`RuleEffect`（`:13`）现只有 `ALLOW` / `DENY`，新增"询问"态
- `permission/RuleEngine.java`（改）—
  - 求值：`evaluate`（`:43`）改为**三级判定**——任一层为 DENY → DENY；否则任一层为 ASK → ASK；否则 local > project > user 取第一个非 null。放行 / 拒绝之间的既有相对关系（拒绝不可翻转、本地优先）不变，新增的是"询问插在拒绝与放行之间并跨层穿透"
  - 规则文件解析：`parseRule`（`:157`）的效果字符串解析（`:166-170`）补 "ask"——否则用户手写在文件里的 ask 规则会被当坏条目**静默丢弃**
  - 规则文件写回：`appendLocalRule`（`:78`）落盘效果的三元表达式（`:90`）改成三态映射——**这是个容易漏的坑，后果是数据损坏而非写错新值**：`:88-91` 会把本地文件里**已有的**规则逐条回写，所以用户手写的 ask 规则会在下一次「始终允许」触发时被静默改写成 deny。`:92` 那条新规则仍固定写 allow（「始终允许」专用通道，本任务不改变它的语义）
  - 暴露**规则统计与清单**（**缺口**：`userRules` / `projectRules` / `localRules`（`:31-33`）都是 private）：三层各自的**文件路径、条数与规则摘要**（工具名 + 模式 + 效果），供命令层逐层展示
- `permission/PermissionChecker.java`（改）— 决策链第 ⑥ 层规则处理（`check` :129-137）从"ALLOW 放行 / 其余拒绝"的二分支改为三态派发。**询问态不能就地 return**：现实现是规则层命中即返回，会跳过第 ⑦ 层「会话级始终允许」（`:139-142`），而「始终允许」写回的两条路（会话集合 `addAllowAlwaysRule` `:69-71` 与本地规则文件 `appendLocalRule` `:74-76`）在新语义下都盖不住一条 ask（ask 跨层穿透），结果是用户点了「始终允许」还会被反复追问。定为：命中询问 → **继续落到第 ⑦ 层**，命中则放行；未命中则返回 `CheckResult.ask()`（**该方法已存在**，见 `:31-34`，无需新增）穿透到既有 HITL 确认环节，且**不再走第 ⑧ 层模式矩阵**（更宽的放行不得盖掉规则层的询问，与跨层穿透同一条道理）。**拒绝维持现状**——命中即返回，不往下走。规则清单查询委托 `RuleEngine` 的新入口
- `permission/PermissionCommandSupportTest.java`（新）— 规则清单与计数反映三层文件内容且**带文件路径**；allow/deny/ask 三种规则的 `evaluate` 返回值分别正确；命中 ask 规则的 `check` 返回**询问态**（既不是放行也不是拒绝）；**三级判定**：跨层 deny 不被任何层的 ask 翻转、**任一层 ask 不被另一层更宽的 allow 盖掉**（穿透生效）、三层皆无结果时才返回 null；YAML 里手写的 ask 规则能被读回（不被丢弃）；非法效果与格式不合法的条目被丢弃、其余照常生效；**命中 ask 后「始终允许」仍然有效**——同一工具 `addAllowAlwaysRule` 之后再 check 返回放行（不再反复询问），这正是"询问不就地返回"要保住的行为；**模式矩阵不吞询问**——在本来会放行的权限模式下（如 bypassPermissions），规则层的 ask 仍返回询问态；「始终允许」路径写入的规则在枚举扩为三态后**仍写成 allow**，且**本地文件里已有的 ask 规则不会在回写时被改写成 deny**（数据不损坏——注意改写成 deny 的后果比"写错新值"重得多：deny 是任一层即全局拒绝，等于那条工具被永久锁死）

**依赖**：无

**参考资料**
- 权限检查器现状：`permission/PermissionChecker.java:61`（`mode`）、`:65`（`setMode`）、`:74`（`appendLocalRule`）、`:129-137`（决策链第 ⑥ 层规则处理，三态派发的落点）
- 规则引擎：`permission/RuleEngine.java:43`（`evaluate`，含跨层 deny 不可翻转的优先级）、`:63-72`（`layerEffect`，同层后匹配者覆盖先匹配者）、`:78-115`（`appendLocalRule`，转义与原子写回；**`:88-91` 会逐条回写已有规则，是 ask 被篡改成 deny 的落点**）、`:131`（`loadRulesFile`，包可见而非 private）、`:157-179`（`parseRule` 的效果字符串解析）、`:31-33`（三层 private 规则列表，统计/清单缺口的所在）
- 规则模型：`permission/PermissionRule.java:13`（`RuleEffect` 两态枚举）、`:15-23`（`matches`，先精确后 glob）、`:49`（`escapeGlob`）
- 三层规则的加载与文件路径：构造处接收三个路径，见 `ConversationController.buildPermissionChecker`（`ConversationController.java:552-562`）——依次为 `~/.acode/permissions.yaml`（用户级）、`<项目根>/.acode/permissions.yaml`（项目级）、`<项目根>/.acode/permissions.local.yaml`（项目本地级）
- 现有切档命令的用法先例：`CommandProcessor.handlePermissionMode`（`CommandProcessor.java:121-141`）、`ConversationController.handlePermissionMode`（`ConversationController.java:402-404`）

---

### T5 命令上下文与界面操作接口

**目标**：处理函数拿到一个打包好依赖的上下文；命令通过受控接口影响界面，而不是直接操作终端对象。

**影响文件（新建 + 修改）**
- `command/CommandContext.java`（新）— 打包命令**实际需要**的依赖：参数原文、界面操作接口、权限检查器、上下文管理门面、记忆门面、会话管理、工作目录、**工具注册表**（`/status` 数工具用）、**版本**（`/status` 用）。用构造器注入的不可变对象。**不收 Agent 与裸会话对象**——本章没有任何命令直接使用它们（提示词类命令经界面操作接口提交、压缩与状态经各自门面），收进来只会是空壳字段
- `ui/UIController.java`（新）— 界面操作接口：追加系统消息、把文本当用户输入发给 Agent、切换规划模式、读取当前上下文占用、**弹出交互式选择菜单**（形状是"给一组条目 → 返回选中下标，取消返回 -1"，`/resume` 与 `/memory` 共用这一个方法）。**菜单接口不能只服务会话**：`/memory` 的条目是三层指令文件加一项"查看长期记忆"、标题是 `（↑/↓ 选择，回车确认，Esc 取消）`，都与会话菜单不同——两个命令只是共用这一个方法，条目与标题各自由命令拼。**不设刷新状态栏**——本章没有任何命令需要它，状态栏由主循环自己维护
- `ui/TerminalUIController.java`（新）— 真实实现：追加消息走既有"输出区 + 活跃区提交"双写范式；发用户输入委托既有的对话入口；切规划模式委托既有开关；读上下文占用委托既有估算；弹菜单委托选择菜单组件（`/resume` 落到既有的会话选择入口，`/memory` 直接给条目）
- `ui/SelectionMenu.java`（改）— **支持不可选的分隔行**（**缺口**：现在 `options` 是 `List<String>`，每一项都会加 `> ` / `  ` 前缀、都能被上下键停留并被回车选中，见 `:52-63`）。`/memory` 菜单要在三层指令文件与"查看长期记忆"之间画一条纯分隔行：不高亮、上下键跳过它、回车不会落到它身上；同时**不改变** `/resume` 那种"全部可选"的既有用法（不传分隔行时行为与改动前逐字相同）
- `command/CommandContextTest.java` / `ui/TerminalUIControllerTest.java`（新）、`ui/SelectionMenuTest.java`（改）— 上下文各依赖可取到且不可变；界面接口追加消息时输出区与活跃区都收到（活跃区 writer 用 `StringWriter` 注入断言）；发用户输入会走到注入的假对话入口；弹菜单会走到注入的假选择入口；**分隔行不可被选中**（上下键跳过、回车不返回它的下标），不传分隔行时行为与改动前一致

**依赖**：T1

**参考资料**
- 输出双写范式：`ConversationController.emitLine`（`ConversationController.java:241-247`）、`:376-399`（`handleManualCompact` 的三段式输出：先提示、再执行、再结果）
- 界面渲染注入点（测试用）：`ui/RenderContext.java`（`liveRenderer` / `screenWriter` / `attachTui`）、`ConversationController.setLive`（`:465-467`）、`setScreenWriter`（`:470-472`）
- 对话入口与规划模式开关：`ConversationController.handleExchange`（`:522-530`）、`planMode` 字段（`:119`）与 `planModeSetter` 传参（`:360`）
- 会话选择入口（`/resume` 要调的）：`SessionManager.selectSession`（`SessionManager.java:67`）、UI 绑定 `ConversationController.sessionManager()`（`:307-313`）
- 上下文占用与上限：`Conversation.estimateContextTokens()`（`Conversation.java:207`）、`maxContextTokens()`（`:181`）
- 界面对象：`ui/OutputPane.java`（`appendLine`）、`ui/LiveRegionRenderer.java`（`appendCommitted`）
- 选择菜单（本任务要扩展的组件）：`ui/SelectionMenu.java:12-50`（构造与环形移动，**没有"不可选项"概念**）、`:52-63`（渲染：选中加 `> ` 反显、未选加两个空格，分隔行的落点）；现有测试 `src/test/java/com/acode/ui/SelectionMenuTest.java`

---

### T6 本地命令：帮助、状态与退出

**目标**：帮助完全由元数据生成（含"给某个命令看详细用法"的分支）；状态一屏聚合六项信息；退出命令纳入框架并**保留为可见命令**（归本地段末位）。

**影响文件（新建）**
- `command/BuiltinCommands.java`（新，本任务先放三条）—
  - 帮助：无参数列出全部可见命令（名称、别名、描述，等宽对齐）；带参数时按名查找该命令并输出描述、用法示例、参数说明、别名；参数指向的命令不存在时给"未找到 + 指向帮助"的引导
  - 状态：一屏输出当前权限模式、上下文占用与上限及占比、已启用工具数、**四类记忆各自的条数**（user / feedback / project / reference，两级合并计数、顺序固定）、工作目录、版本
  - 退出：**可见命令**（归本地段末位）、返回"退出主循环"的结果；不产生任何输出、不接受参数、无别名（沿用既有的静默退出语义）
- `command/HelpStatusCommandTest.java`（新）— 帮助列表含全部可见命令（**含退出命令**）；帮助列表条数与注册中心可见清单一致（元数据驱动而不是写死）；帮助列表**按类型分三段**且段内顺序与注册顺序一致；`/help compact` 输出含该命令描述与用法；`/help 不存在` 给出引导；状态输出的六项都在且占比按占用除以上限计算；工具数与注册表实际数量一致；**四类记忆条数**分别与存储实际条数一致；退出命令返回"退出"、**出现在帮助输出与补全候选中**且不产生任何输出

**依赖**：T5

**参考资料**
- 工具计数：`tool/ToolRegistry.java:61`（`availableList`，已注册且未禁用）
- 版本：**现有版本串只嵌在 `BANNER` 的 ASCII 文本里**（`ConversationController.java:107` 的 `ACode v0.1.0`），**并不存在独立常量**。本章把它抽成一个版本常量、让 `BANNER` 与 `/status` 共用（抽常量这件事在 T12 落地），装配处作为字符串放进上下文——命令层不直接依赖 `BANNER`，更不从艺术字里截取
- 记忆计数：**不能用 `indexViews()`**——`IndexView.memoryCount` 是**按根**计数（项目级 / 用户级各一条，见 `MemoryStore.java:155-157`），给不出 user / feedback / project / reference 四类。四类要走 `MemoryStore.listAll()`（`MemoryStore.java:71`）逐条读 `MemoryFile.type()`、按 `MemoryType` 的四个值归并（两级合并已由 `listAll` 完成）
- 工作目录与配置：`ConversationController.projectRoot`（`:148`）、`config/AppConfig.java`（getter 齐全）
- 状态栏文案：见 `checklist.md` 默认值说明

---

### T7 本地命令：压缩、恢复、记忆、权限

**目标**：四条本地命令就位。权限的调用形态收敛为最简的两种（无参查看、带参切档；规则只读）；记忆是指令文件入口（弹菜单 + 一个保留词），长期记忆本身不做命令。

**影响文件（新建 + 修改）**
- `command/BuiltinCommands.java`（改，本任务加四条）—
  - **压缩**：无参数走既有手动压缩路径；带参数时把参数作为"需要保留的重点"传给压缩流程；上下文占用低于 **5000 token** 时直接提示无需压缩、不发起压缩。**这个阈值是本章新增的判断，不是既有行为**——现有 `handleManualCompact`（`ConversationController.java:376-399`）与 `CompactExecutor.run`（`CompactExecutor.java:87`）里没有任何阈值检查、无条件压缩，别当成"已在、只需搬运"；它也不复用自动压缩的比例触发点（`needsAutoCompact` 走 `ContextPolicy.triggerPointFor`，两套口径各管各的）。**读占用走界面接口的"读取当前上下文占用"**（与 `/status` 同一个数、口径同源），不要再经上下文管理门面另取一遍。既有三段式输出（先"正在压缩…"、再执行、再报结果）与失败兜底一并从 `handleManualCompact` **搬进本命令**
  - **恢复**：调既有的会话选择入口弹交互式菜单（列出历史会话、↑/↓ 选择、回车加载、Esc 取消）。**不做子命令、不接受任何参数**——带参数时输出该命令自己的参数提示语（文案见 `checklist.md`）
  - **记忆**：无参数**弹菜单**（复用既有选择菜单；三层指令文件与"查看长期记忆"之间那条**不可选的分隔行**依赖 T5 对 `ui/SelectionMenu.java` 的扩展）——列出三层指令文件（项目根 / 项目本地 / 用户级）并标注各自的**路径、是否存在与行数**，末尾附一项"查看长期记忆"。选中某一层 → 输出该层路径、**行数与完整内容**；文件不存在则创建（空文件即可，创建后同样输出路径）；选中"查看长期记忆" → 列出长期记忆条目（类别、名称、描述，按类别分组、项目级在前）。带保留词 `run` → 立即触发一次长期记忆提取（沿用现有语义与输出文案）
  - **权限**：无参数**列举**——当前权限模式、三层规则各自的文件路径与条数、每条规则的内容与效果（标明来源层级）；带参数时参数必须是**四档模式之一**（大小写敏感）→ 切换模式；否则 → 提示该命令的用法与合法取值。**没有保留词，规则只读**——增 / 改 / 删由用户直接编辑规则文件
- `context/SummaryPrompt.java`（改）— 摘要指令支持"保留重点"：现有 `instruction()` 是无参固定指令，补一个带重点关注项的重载，在指令末尾追加"压缩时请特别保留：<重点>"；不传时输出与改动前**逐字相同**
- `context/CompactExecutor.java`（改）— `run`（`:87`）增加"保留重点"入参（可空），透传到 `summaryRequest`（`:167`）构造摘要指令；既有调用方（自动压缩、恢复时压缩）传空，行为不变
- `ConversationController.java`（改）— **删除搬走的旧命令实现，迁移后同一逻辑只应有一份**：`handleManualCompact`（`:376-399`，逻辑进压缩命令）、`handlePermissionMode`（`:402-404`，转发壳一并删）、`handleMemoryCommand`（`:407-414`）与它调用的 `memoryCommandLines`（`:417-446`，新 `/memory` 是三层指令文件菜单，这段"长期记忆状态"输出语义已完全不同）、`selectSession`（`:452-454`，命令层直接用会话管理门面）。**不得新旧并存**——同时删掉 `commandProcessor()` 里对应的 `setCompactHandler`（`:361`）与 `setMemoryHandler`（`:362`）调用（**这两处调用在本任务删除**：本任务已删掉它们指向的方法，留着编译不过；T12 只负责把 `commandProcessor()` 整体改成交给调度器，不重复处理）
- `command/CompactCommandTest.java` / `command/ResumeCommandTest.java` / `command/MemoryCommandTest.java` / `command/PermissionCommandTest.java`（新）— 见 `checklist.md` 对应小节逐条断言。**重点是形态与副作用**：权限——四档模式命中并切档、非法值给用法提示、无参数时不改模式且列举里能看到三层规则的文件路径与效果、**任何参数都不写入规则文件**；记忆——菜单列出三层并标注存在与否、选中已存在的层输出其内容、选中不存在的层会创建文件、`run` 保留词走提取而不是被当作未知参数、菜单取消不产生副作用。恢复——只弹菜单、不发模型请求。压缩——带参数时假 provider 收到的摘要请求提示里含该重点文本；`/compact` 在占用低于阈值时**不产生模型调用**（假 provider 请求计数为 0）
- `context/CompactExecutorTest.java`（改）— 补"带保留重点时摘要提示含该文本、不带时与改动前逐字相同（无回归）"

**依赖**：T3、T4、T5

**参考资料**
- 手动压缩现状：`ConversationController.handleManualCompact`（`ConversationController.java:376-399`）、`context/CompactExecutor.java:26-43`（`Result` 五个字段：changed / failed / reason / beforeEstimate / afterEstimate）、`:64`（`needsAutoCompact`，**比例触发点，与手动压缩的 5000 固定阈值是两套口径**）、`:87`（`run`）、`:167-177`（`summaryRequest`，摘要指令的注入点）、`context/SummaryPrompt.java`（`instruction` 与两阶段标签常量）
- 会话选择入口（复用，无需改动）：`SessionManager.selectSession`（`SessionManager.java:67`）→ `ui/SelectionMenu.java`；提示输出：`SessionManager.notice`（`:129`）
- 记忆展示范式：`ConversationController.memoryCommandLines`（`ConversationController.java:417-446`）
- 权限切档范式：`CommandProcessor.handlePermissionMode`（`CommandProcessor.java:121-141`）
- 选择菜单（复用，记忆菜单与恢复菜单同款）：`ui/SelectionMenu.java`、`ui/MenuKeySource.java` / `ui/TerminalMenuKeySource.java`
- 三层指令文件的路径与存在性：T3 新增的查询能力
- 文案与保留词：见 `checklist.md` 默认值说明

---

### T8 本地界面命令：清空、规划、执行

**目标**：会改变界面与交互模式的命令迁移完毕，其中"执行模式"补上"按已交付计划开始执行"的行为。

**影响文件（新建 + 修改）**
- `command/BuiltinCommands.java`（改，本任务加三条）—
  - **清空**：当前对话保存后开启新会话 → 清屏 → 输出提示（沿用现有三步语义与文案）
  - **规划模式**：切换规划模式开关；带参数时把参数作为任务描述发给 Agent 开始规划；不带参数时只切模式并输出提示
  - **执行模式**：切回非规划模式；**若本次会话最近一次规划交付留下了计划落盘位置，则读取该计划正文并作为任务发给 Agent 开始执行；否则只切模式并如实告知没有可执行的计划**
- `ConversationController.java`（改）— 把"最近一次规划交付的计划落盘位置"记为会话内状态：Agent 循环结束、计划已交付时取到落盘位置并保存。**本任务只加这个字段与它的读写**；"/clear 与加载会话时复位"的挂钩动作归 T12（挂进既有清除钩子链），此处不重复写
- `agent/Agent.java` / `ExchangeRunner.java`（视需要改）— 让"计划已交付且落盘成功"这件事能传到外层（`Agent.planPath()` 已存在（`Agent.java:216`），需要的是把结果带回给调用方）
- `command/PlanDoCommandTest.java`（新）— `/plan` 无参数只切模式、不带 Agent 调用；`/plan 设计用户认证` 切模式且把该文本发了出去；`/do` 在无计划时只切模式并输出"没有可执行的计划"；模拟一次规划交付后再 `/do` → 发出去的任务文本含计划正文；`/clear` 之后再 `/do` → 回到"无计划"分支

**依赖**：T5

**参考资料**
- 规划模式现状：`ConversationController.planMode`（`:119`）、`planModeSetter` 传参（`:360`）、`CommandProcessor` 的 PLAN/DO 分支（`CommandProcessor.java:95-104`，本任务取代）
- 清空现状：`CommandProcessor` 的 CLEAR 分支（`CommandProcessor.java:83-89`）——`conversation.clear()` + `live.clearScreen` + `output.clear()` + 提示三步
- 计划落盘：`agent/PlanWriter.java:19`（`savePlan`）、`Agent.java:337-349`（plan 交付分支）、`Agent.planPath()`（`:216`）
- 对话入口：`ConversationController.handleExchange`（`:522-530`）
- 文案：见 `checklist.md` 默认值说明

---

### T9 提示词命令：审查

**目标**：审查命令只负责构造一段预设提示词，实际工作交给 Agent。

**影响文件（新建）**
- `command/ReviewPrompt.java`（新）— 预设审查提示词：要求分析当前工作区的未提交变更（`git diff` 语义），按"问题 / 风险 / 建议"组织，指明具体文件与位置，不要泛泛而谈；明确要求**只审查不修改**。带参数时把参数作为额外关注点并入（形如"额外关注：并发安全"）
- `command/BuiltinCommands.java`（改，加这一条）— 审查命令：构造提示词后经界面操作接口的"把文本当用户输入发给 Agent"提交
- `command/ReviewCommandTest.java`（新）— 无参数时构造出的文本含"未提交变更"与"只审查不修改"语义；带参数时含该参数；命令本身**不产生本地输出**（提示词走对话通道而不是系统消息通道）；用假对话入口断言收到的文本

**依赖**：T5

**参考资料**
- 既定提示词范式（要求模型做某件事、给输出结构、划边界）：`agent/PlanModePrompt.java`、`context/SummaryPrompt.java`
- 发用户输入：T5 的界面操作接口（`ui/UIController.java`）
- 提示词正文：见 `checklist.md` 默认值说明

---

### T10 命令调度器替换主循环

**目标**：把主循环里的命令分支整段换成调度器；现有手写路由类与帮助文本删除；所有命令从新路径进出。

**影响文件（新建 + 修改 + 删除）**
- `command/CommandDispatcher.java`（新）— 一次输入的执行链：解析 → 裸斜杠走帮助 → 查找 → 未命中给"错误 + 指向帮助" → 命中则建上下文并执行 → 执行异常兜底为一行错误。返回"继续 / 退出"。**参数判定（保留词 / 合法取值）与参数不合法时的用法提示都不在调度器里，由各命令自己负责**——调度器只管解析、查找与错误兜底
- `command/CommandDispatcherTest.java`（新）— 空输入不执行任何命令；非命令走对话通道；裸斜杠等价于帮助；未知命令输出含用户输入的命令名**且含帮助引导**；参数原文原样交给处理函数（调度器不做参数语义判断）；处理函数抛异常时输出一行错误且返回"继续"；处理函数返回"退出"时调度器如实返回
- `CommandProcessor.java`（改）— `mainLoop`（`:66-115`）里的命令分支整段删除，改为"读一行 → 交给调度器 → 按返回值决定是否退出"；删除 `handlePermissionMode`（`:121-141`）与 `currentPermissionModeName`（`:143-145`）以及为命令加的两个回调 setter（`:53-64`）与对应字段（`:32-36`）
- `ui/CommandRouter.java`（**删除**）及其测试（删除）——枚举式路由被注册中心与调度器取代
- `CommandProcessorTest.java`（改）— 删除针对旧分支的用例，保留/改写为"经调度器执行"的断言

**依赖**：T6、T7、T8、T9

**参考资料**
- 待替换的现状：`CommandProcessor.mainLoop`（`CommandProcessor.java:66-115`）、`:53-64`（注入 setter，逐个删除）、`:121-145`（旧切档方法）
- 会话关闭路径：`mainLoop` 两处退出分支（`CommandProcessor.java:74-77`、`:79-82`）——调度器返回"退出"后仍要关闭会话
- 现有测试：`src/test/java/com/acode/CommandProcessorTest.java`、`src/test/java/com/acode/ui/CommandRouterTest.java`（后者随源文件删除）

---

### T11 Tab 补全落地

**目标**：按 T2 的验证结论把补全做成正式能力，候选来自真实注册中心；隐藏项不进候选（本章没有隐藏的内置命令，这条由单测注册的隐藏命令覆盖）。

**影响文件（修改 + 新建）**
- `ui/SlashCompleter.java`（改）— 候选从**注册中心的可见清单**取（替换 T2 的硬编码列表），每条候选带名称与描述；仅斜杠给全部、有前缀按前缀过滤。可见清单的顺序即注册顺序，补全列表与帮助列表同序
- `ui/InputPane.java`（改）— 补全器装配定型（若 T2 结论是自定义路线，这里是自定义输入组件与按键绑定的落地）
- 若 T2 结论为自定义下拉：`ui/CompletionMenu.java`（新）— 复用选择菜单的渲染样式与按键抽象，在活跃区渲染候选、选中后把命令名回填输入缓冲并清掉菜单
- `ui/SlashCompleterTest.java`（新）— 仅斜杠 → 候选等于可见清单（**含 `/quit`**）；`/com` → 候选恰为以 com 开头的命令；测试里注册的隐藏命令不出现在候选中；大小写不敏感（`/HELP` 也能匹配）；0 候选时不给候选；每条候选带描述文本

**依赖**：T2

**参考资料**
- T2 结论：`docs/ch09/spec.md`「关键设计决策」表
- 候选来源：T1 的 `command/CommandRegistry.java` 可见清单
- 自定义路线的样式与按键：`ui/SelectionMenu.java`、`ui/MenuKeySource.java`、`ui/TerminalMenuKeySource.java`

---

### T12 接入主流程与文档补齐

**目标**：注册中心在内置命令集中声明后装配进主流程；生命周期与既有清除钩子对齐；文档补齐。

**影响文件（修改）**
- `command/BuiltinCommands.java`（改）— 提供"一次性注册全部内置命令"的入口，命令清单集中一处可见，**注册顺序即帮助与补全的展示顺序**（按类型分三段：`help compact resume memory permission status quit` → `clear plan do` → `review`；本章没有隐藏的内置命令，`hidden` 字段留着但无人使用）
- `ConversationController.java`（改）— ① 构造期装配注册中心并注册全部内置命令；② `commandProcessor()`（`:356-365`）整体改为把注册中心、上下文依赖、界面操作接口交给调度器（命令回调的 setter 及其调用已随 T7 删除旧命令实现一并消失，此处不再处理）；③ 上下文的**版本串取自新增的版本常量**、**工具注册表直接用 `toolRegistry`（`:113`）**；④ 把 T8 新增的"最近一次规划交付落盘位置"的复位挂进既有清除钩子链（`:201` 处已有 `addClearHook` 先例，`:206-209` 是现成范例）
- `ConversationController.java`（同文件，③ 的版本常量）— 把 `BANNER` 里嵌着的版本串（`:107`，艺术字里那行 `ACode v0.1.0`）抽成一个版本常量：**`BANNER` 改为引用它**，装配处把同一个常量放进上下文。不新增版本机制，只是让横幅与 `/status` 读同一处——否则状态命令只能去截取多行艺术字
- `docs/manual-test.md`（改）— 追加「阶段九」手测小节（空 ⚑ 项，T13 填）
- `README.md`（改）— 重写「命令」表格（`README.md:69-84`）：删除 `/permission-mode` 与**整行 `/session`**，改为 `/permission`（无参列规则、带参切档）与 `/resume`（弹菜单恢复）；`/memory` 说明改为"查看 / 创建三层指令文件；`/memory run` 手动提取长期记忆"；补齐别名列与新增命令（`/status` `/review`）；**「功能特性」里记忆那一节要改写**（`:42` 现有描述含"`/memory` 查看、`/memory run` 手动提取"，需补上"长期记忆由 Agent 在对话中维护"）；另补一行命令框架与 Tab 补全；路线图阶段九状态改为 ✅（若本阶段完成）
- `src/main/resources/config.yaml`（视需要改）— 若补全相关行为需要配置项（本阶段默认不需要，确认无遗留开关）

**依赖**：T10、T11

**参考资料**
- 装配点：`ConversationController.commandProcessor()`（`:356-365`）、构造期装配（`:170-203`）、清除钩子（`:200-209`）
- 生命周期收尾：`start()`（`:273-297`）的 `finally`（`:292-296`）
- 文档先例：`docs/manual-test.md`（阶段八小节形态）、`README.md:69-84`（命令表）

---

### T13 端到端验证 ⚑ 与统一收尾回归

**目标**：全链路验收：输入分流 → 命令查找（含别名）→ 执行 → 各类命令的效果；补全候选正确；旧路径已彻底消失；统一收尾回归。

**影响文件（新建 + 修改）**
- `command/CommandSystemEndToEndTest.java`（新）— 假 provider + `@TempDir` 项目根 + 关 `memory_auto`，装配真实注册中心与调度器：
  1. 逐个走一遍全部可见命令，**按 `checklist.md` 顶部「命令类型与通道」表逐条对齐**（该表是唯一口径）：
     - 本地命令只出系统消息、不发**对话**请求；`/compact` 与 `/memory run` 会发各自的专用请求（摘要 / 提取），不要把它们的请求计数也断言成 0
     - 提示词命令**只发对话请求**、不出本地结果
     - 本地界面命令改变对应状态：`/clear` 不发请求；`/plan` 不带参数不发、带参数发对话请求；`/do` 无计划不发、有计划发对话请求
     - 每条命令断言前清零假 provider 的请求计数，避免上一条的请求串到下一条
  2. 别名与大小写：`/h`、`/HELP`、`/c`、`/s` 分别命中正确命令
  3. 参数判定与菜单：`/permission acceptEdits` 切档；`/permission nosuchmode` 给用法提示；`/permission` 无参数时列举三层规则（含各层文件路径）且**不写任何文件**；`/memory` 弹出三层菜单（经注入的假选择入口断言选项与存在标注），选中不存在的层会创建文件、选中已存在的层输出内容；`/memory run` 走提取而不是被当作未知参数
  4. Agent 可及性（ch08 缺口修复的验证）：system 提示的索引段含两级记忆根路径；用户级记忆根下的文件工具调用不再被沙箱拒绝，项目外其他路径仍被拒
  5. 错误路径：未知命令输出含帮助引导；参数无法解释时输出用法提示；两者都不中断主循环（后续命令仍可执行）
  6. 退出命令：出现在帮助输出与补全候选中、归本地段末位；调用后返回"退出"、主循环如实退出并关闭会话、不产生任何输出。隐藏机制改用**测试里注册的一条隐藏命令**验证（不出现在帮助与补全候选、但仍可调用）
  7. 状态一致性：切权限模式后状态输出随之变化；测试里直接写一条记忆后 `/status` 的**对应类别**条数增加；测试里手写一条 ask 规则到规则文件后，命中该规则的检查走确认通道（不是放行或拒绝）、**另一层更宽的 allow 不能把它盖掉**、且 `addAllowAlwaysRule` 之后再检查同一工具返回放行（第 ⑦ 层没有被规则层的询问跳过）
  8. 恢复命令：只弹菜单、不产生模型请求
  9. 执行模式链路：规划模式带参数 → 切模式并发出任务；模拟一次计划交付 → 执行模式把计划正文发给 Agent
  10. 补全：候选来自可见清单、按前缀过滤、顺序与注册顺序一致；隐藏项由测试注册的隐藏命令验证缺席
- `docs/manual-test.md`（改）— 填阶段九 ⚑ 手测勾选
- **统一收尾**：`JAVA_HOME="D:\java\jdk21" mvn test` 全套全绿，记录总用例数（新增用例均本地假 provider，无外部网络）

**依赖**：T12

**参考资料**
- 端到端测试先例：`docs/ch08/tasks.md` T13（`MemorySystemEndToEndTest` 的假 provider + `@TempDir` 写法）、`src/test/java/com/acode/MemorySystemEndToEndTest.java`
- 断言口径全部对齐 `checklist.md` 顶部「默认值说明」
