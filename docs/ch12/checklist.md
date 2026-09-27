# ACode 阶段十二：SubAgent —— 子 Agent 与任务分发 — checklist

> 最后更新：2026-09-27
> 对应 `spec.md` 的能力清单与 `tasks.md` 的 T1-T10；所有阈值、内置定义、错误文案集中在本页顶部「默认值说明」，验收时以本页为准
> 需要真实 provider 密钥的项标记 ⚑；未运行手动验收前相应项保持未勾选

## 默认值说明

### Agent 工具

- **工具名**：`Agent`（唯一注册，参数选类型；Agent 类型动态加载，工具列表始终稳定）。
- **给模型的描述文案**（写入工具 description）：「派一个子 Agent 在独立的干净上下文里完成子任务，完成后只把结果返回，中间过程不进主对话。prompt 写任务说明；description 写这次派活的一句话说明；subagent_type 指定预定义 Agent 类型（如 Explore、Plan、general-purpose），留空则 Fork 一个继承当前对话历史的临时助手。固定角色、固定职责的子任务（探索、规划、审查）指定 subagent_type；与当前对话高度相关的临时任务留空走 Fork。」
- **参数 schema**（字段名与必填性）：

| 参数 | 类型 | 必填 | 语义 |
|---|---|---|---|
| `prompt` | string | 是 | 交给子 Agent 的任务 |
| `description` | string | 是 | 一句话说明这次派活干什么 |
| `subagent_type` | string | 否 | 预定义类型名；**留空走 Fork** |
| `model` | string | 否 | 覆盖定义里的模型；**对 Fork 无效**（Fork 强制父模型） |
| `name` | string | 否 | 给这次派活起名，便于回看 |

- schema **不含** `isolation` 参数（字段位留给 ch13 Worktree，避免模型误以为隔离已生效）。
- **权限档**：`write`；**内容字段**：`prompt`（权限规则与「始终允许」按 `Agent(任务文本)` 书写）。default 档下派活前弹三选一确认一次；acceptEdits/bypass 自动放行；**plan 模式工具表不可见**（只含 READ + ExitPlanMode）。
- **超时**：不继承 BaseTool 的 10 秒外壳、不设整体超时（同 `AskUserTool` 先例）；兜底 = 定义 `maxTurns` + 单请求/单工具超时 + 用户 Ctrl+C。

### 定义文件与加载

- **目录**（优先级从高到低）：项目级 `<项目根>/.acode/agents/` > 用户级 `~/.acode/agents/` > 内置级 classpath `agents/`（源码 `src/main/resources/agents/`）> 插件级（只留位置占位，无加载逻辑）。
- **文件**：任意 `*.md`；**类型名 = frontmatter `name`**（文件名不作数）；同名按优先级覆盖（项目盖内置；用户盖内置与插件、**不盖项目**）。
- **frontmatter 字段**：`name`（必填）、`description`（必填）、`tools`（可选，string 数组，工具白名单）、`disallowedTools`（可选，string 数组，工具黑名单）、`model`（可选：inherit/sonnet/opus/haiku，缺省 inherit）、`maxTurns`（可选，正整数，缺省 **20**）、`permissionMode`（可选：default/acceptEdits/plan/bypassPermissions，缺省 default）。body 全文 = 子 Agent 系统提示。
- **坏定义处理**：缺 name/description、YAML 损坏、未知字段（**含写错的 `allowedTools`**——Skill 的字段名在 Agent 定义里是未知字段）、工具列表结构非法 → 跳过该文件 + 启动告警（带路径与原因），不阻断启动。
- **可降级字段**：非法 model/maxTurns/permissionMode 分别回退 inherit/20/default 并告警；其余结构错误跳过文件。
- **加载时机**：启动加载一次 + UI 就位后惰性预热（与 MCP 连接同阶段）；改完重启生效（无热重载）。
- **Verification 开关**：配置键 `verification_agent`（`~/.acode/config.yaml` 或 `.acode/config.yaml`），布尔，缺省 **false**（不启用则内置 Verification 不载入）；非布尔值 → 配置错误。

### 工具过滤四层

| 层 | 内容 | 状态 |
|---|---|---|
| L1 全局禁止 | `Agent`、`AskUser`、`ExitPlanMode` | 启用；**Fork 例外**：保留 `Agent`（可见但调用即拦截），只移除后两个 |
| L2 后台 Agent 白名单 | 常量 `[ReadFile, Glob, Grep, WriteFile, EditFile]` | **仅定义、不启用**（无任何调用点），ch14 启用 |
| L3 `disallowedTools` | 定义黑名单，从当前工具集排除 | 启用 |
| L4 `tools` | 定义白名单，非空则与当前工具集取交集 | 启用 |

执行顺序 L1 → L2 → L3 → L4（白名单与黑名单取交集，二者顺序不影响结果）。`tools`/`disallowedTools` 里写了主注册中心不存在的工具名 → **派发时当场报错**（fail-fast，不发模型请求；MCP 工具启动后才注册，所以不在加载时校验）。

### Fork 规则

- Fork Boilerplate（全文，照抄注入到子 Agent 第一条用户消息；一字不改）：

```
<fork_boilerplate>
你是一个 Fork 出来的工作进程，不是主 Agent。
规则（不可协商）：
1. 不能再 Fork。
2. 不要对话、不要提问、不要请求确认。
3. 直接使用工具：读文件、搜索代码、做修改。
4. 严格限制在你被分配的任务范围内。
5. 最终报告控制在 500 字以内，以「Scope:」开头。
</fork_boilerplate>
```

- Fork 子 Agent：历史 = 父完整对话**副本** + 上一条用户消息（Boilerplate + 任务文本）；system 提示与环境快照与父**同一份内容**（命中 prompt cache 的关键）；模型 = 父模型；`maxTurns` = 20；权限模式 = 继承父当前模式（独立追踪）。
- 定义式子 Agent：空白会话；system 提示 = 定义 body；环境快照同父；`maxTurns` = 定义值；模型按定义解析（inherit → 父模型）；权限模式按定义（缺省 default）。

### 全部错误文案

| 场景 | 文案（面向主 Agent 的工具结果） |
|---|---|
| 缺 prompt / description | `缺少参数：prompt` / `缺少参数：description` |
| 未知类型 | `未知的 Agent 类型：<type>（可用类型：<a>、<b>、…）` |
| 白/黑名单引用不存在的工具 | `Agent 定义「<type>」引用了不存在的工具：<tool>（请检查定义文件的 tools/disallowedTools）` |
| Fork 再调 Agent | `Fork 子 Agent 不能再创建子 Agent` |
| 子 Agent 流错误等 | `子 Agent「<type>」执行失败：<原因>` |
| 触顶无产出 | `子 Agent「<type>」达到最大轮数（<n>）且未产出结果` |
| 坏定义（启动告警） | `跳过 Agent 定义 <路径>：<原因>`；`Agent 定义 <路径>：未知字段 <key>`；`Agent 定义 <路径>：model 取值非法（<value>），按 inherit 处理`；`Agent 定义 <路径>：maxTurns 取值非法（<value>），按默认值 20 处理`；`Agent 定义 <路径>：permissionMode 取值非法（<value>），按 default 处理` |

- 成功结果 display 行（父界面一行摘要，label = `name` 参数 || `description`）：定义式 `完成子任务「<label>」（<type>）`；Fork `完成子任务「<label>」（Fork）`；触顶但有文本 `完成子任务「<label>」（<type>，达到最大轮数 <n>）`。
- 子 Agent 中间过程（流式文本、工具卡片、轮次收尾）**不渲染到界面**。

### 内置 Agent 定义全文（以本清单为准）

**Explore**（`src/main/resources/agents/Explore.md`）：

```yaml
---
name: Explore
description: 只读的代码探索 Agent，用于搜索文件、理清调用链
disallowedTools: [Agent, EditFile, WriteFile]
model: inherit
maxTurns: 30
---

你是一个文件搜索专家。这是一个只读探索任务。

严禁：创建文件、修改文件、删除文件、执行任何改变系统状态的命令。

你的工具使用策略：
- 用 Glob 做文件模式匹配
- 用 Grep 搜索文件内容
- 用 ReadFile 读取已知路径的文件
- Bash 只用于只读操作（ls、git log、git diff、find、cat）
- 尽可能并行发起多个工具调用以提高效率

高效完成搜索请求，清晰报告发现。
```

**Plan**（`src/main/resources/agents/Plan.md`）：

```yaml
---
name: Plan
description: 只读的规划 Agent，产出分步实现方案
disallowedTools: [Agent, EditFile, WriteFile]
maxTurns: 15
---

你是一个软件架构师和规划专家。这是一个只读规划任务。

严禁：创建文件、修改文件、删除文件、执行任何改变系统状态的命令。

你的工作流程：
1. 理解需求，明确设计视角
2. 用搜索工具充分探索代码库：找到现有模式和约定，理解当前架构，识别可参考的类似功能
3. 设计方案：制定实现路径，考虑取舍和架构决策
4. 输出计划：提供分步实现策略，标明依赖和顺序，预判潜在挑战

回复末尾必须列出 3-5 个对实现最关键的文件路径。
```

**general-purpose**（`src/main/resources/agents/general-purpose.md`）：

```yaml
---
name: general-purpose
description: 通用子 Agent，拥有全部工具，适合需要独立上下文的复杂子任务
disallowedTools: []
---

你是 ACode 的 Agent。根据用户的消息，使用可用工具完成任务。
把任务做完，不要过度设计，但也不要做一半就停。

完成后用简洁的报告回复：做了什么、关键发现。
调用方会把结果转述给用户，所以只需要包含要点。

搜索策略：不确定位置时广泛搜索，确定路径时直接读取。
优先编辑现有文件，不要主动创建文档文件。
```

**Verification**（`src/main/resources/agents/Verification.md`，经 `verification_agent: true` 启用）：

```yaml
---
name: Verification
description: 验证实现是否真的可用，专找隐藏 bug
model: inherit
disallowedTools: [Agent, EditFile, WriteFile]
---

你是一个验证专家。你的目标是尝试打破实现，找到隐藏的 bug。

你有两个已知的失败模式。第一，验证回避：面对检查时，你找理由不去运行它，
你读代码、描述你会测什么、写下「PASS」然后继续。第二，被前 80% 迷惑：
你看到漂亮的输出或通过的测试套件就倾向于放行，没注意到一半分支没走通、
状态刷新后消失、或者边界输入直接崩溃。前 80% 是容易的部分。
你的全部价值在于找到最后 20%。

严禁：修改项目中的任何文件。

必须步骤：读项目配置了解构建/测试命令 → 跑构建 → 跑测试套件 →
检查回归。然后根据变更类型做针对性验证。

每项检查必须包含：实际执行的命令、观察到的输出、PASS 或 FAIL 判定。
读代码不算验证，必须运行它。

最终输出：VERDICT: PASS / VERDICT: FAIL / VERDICT: PARTIAL
```

> 说明：内置 Explore 使用 `inherit`，在默认 DeepSeek 等端点可运行；需要小模型时由用户在项目或用户级定义中明确覆盖。内置定义黑名单里的 `Agent` 与 L1 全局禁止冗余但无害。

---

## 自动化验收与证据

执行命令（PowerShell，JDK 21）：`$env:JAVA_HOME='D:\java\jdk21'; mvn test`。下面仅依据实际通过的测试勾选，真实模型项目单独列出。

- [x] AgentDefinitionParserTest：全字段映射、正文空行与缩进原样保留；缺必填字段、未知字段、重复键、坏 YAML、错误工具列表抛出解析错误。
- [x] AgentDefinitionParserTest：非法模型与权限模式降级告警；轮数 -1/abc/1.5/2147483648/0 均回退 20。
- [x] AgentRegistryTest：四内置类型可读；项目覆盖用户及内置；坏文件告警包含路径与原因；开关关闭无 Verification；加载后文件变更不热更新。
- [x] AgentRegistryTest：ForkBoilerplate.TEXT 在教材 prompt.md 中逐字出现。
- [x] ToolFilterTest：普通子任务移除 Agent/AskUser/ExitPlanMode，Fork 保留 Agent；黑白名单交集、未知引用、禁用工具与主表不变均有断言。
- [x] SubAgentRunnerTest：定义式请求仅含自身 system、父环境和任务；Fork 请求内容前缀等于父请求，忽略模型覆盖并追加完整 Boilerplate。
- [x] SubAgentRunnerTest：工具调用后继续到最终回复；空回复成功；触顶有文本返回摘要、无文本失败；provider 错误返回失败。
- [x] SubAgentRunnerTest：模型直接提交被过滤的写工具，实际执行次数为 0。
- [x] SubAgentRunnerTest：2000 个流式片段在 5 秒测试限时内排空，无队列背压死锁。
- [x] SubAgentRunnerTest：中断运行线程后，provider 收到中断、任务返回失败，线程在测试等待期限内退出。
- [x] AgentToolTest：缺参文案、未知类型清单、schema 五字段/两必填、WRITE/prompt 元信息与 Fork 拦截均有断言。
- [x] AgentToolTest：不存在的工具引用返回错误，provider 请求数为 0。
- [x] ConfigLoaderTest：verification_agent 缺省 false，true/false 可用；yes/no/on/1/null/'true'/[] 均报错。
- [x] SubAgentPermissionTest：父 default 不被子 bypass 提权；父会话批准不复制；显式范围内写调用执行一次，无授权时为 0。
- [x] SubAgentPermissionTest：rm -rf / 在 bypass 与显式批准下执行次数为 0；项目及系统临时目录之外路径被拒绝；额外记忆根透传。
- [x] SubAgentAdaptersTest：Skill full/recent/none 分别保留全部/最近 5 条/零条历史，渲染参数并应用模型；结果成功或失败均不激活父 Skill。
- [x] SubAgentAdaptersTest：Hook agent 实际调用同一 runner，失败保持失败；子 Hook 不再派发，测试在限时内结束。
- [x] SubAgentAdaptersTest：子工具触发提示 Hook，提示只出现在子请求，不进入父提示队列。
- [x] SkillCommandTest：/fork 实际多发一个子请求，包含 Skill 正文且输出返回结果。
- [x] AgentToolIntegrationTest：真实 Controller 装配的普通工具表含 Agent，plan 表不含；坏定义告警可见，主会话仍运行。
- [x] AgentToolIntegrationTest：拒绝派活后只有两次主请求，错误结果回填；批准后子结果以一行完成摘要呈现，中间文本不显示。
- [x] SubAgentEndToEndTest：主派发 → 定义式独立上下文/Fork 历史副本 → 子结果回填 → 主回复；父队列仅一张派发卡片，Fork 再派发收到固定拒绝文案。
- [x] 新增边界回归：父活动工具白名单、子 LoadSkill 状态隔离及间接递归、共享 once 标记的测试全部通过。
- [x] 实现收尾 `mvn test package` 通过；测试强化后 `mvn test`：1256 tests、0 failures、0 errors、1 skipped（2026-09-27）。
- [x] 打包成功；以 jar 为唯一依赖运行 Ch12JarSmoke，实际加载 Explore/Plan/general-purpose/Verification；资源逐字节对照源码通过。
- [x] `rg BACKGROUND_TOOLS src/main/java` 仅命中常量定义；Agent.java 无 AgentTool 专用分支；SubAgentRunner.java 无 MemoryManager。

## 真机端到端验收（尚未执行）

在独立临时项目和测试用户目录运行最新 jar，配置有效 provider，详见 docs/manual-test.md 阶段十二。

- [ ] ⚑ 让 Explore 查找工具实现类；最终报告引用真实文件，界面只显示父 Agent 卡片与完成摘要。
- [ ] ⚑ 多轮对话后留空类型派临时子任务；能引用上文，子任务报告以 Scope: 开头且不超过 500 字（检查工具结果，主模型可能转述）。
- [ ] ⚑ default 档派活出现确认，拒绝后任务不运行；批准派活不自动授权任意子写入。
- [ ] ⚑ /plan 下模型看不到 Agent；规划中的 fork Skill 也只能使用父级只读范围。
- [ ] ⚑ 放入缺 name 的定义，重启看到带路径告警，其余能力正常。
- [ ] ⚑ 开启 verification_agent 后可派 Verification 并得到 VERDICT；关闭重启后类型不可用。构建命令仍需配置明确的允许规则。
- [ ] ⚑ 子任务或 /fork-skill 运行时按 Ctrl+C，等待清理后可继续输入，无残影。

## 交付记录

- 产物：`target/acode.jar`。
- SHA-256：`ef0740f87886be12fbccc0f38e597965439acef3f086863774a4b8bd2598a32d`。
- `git diff --check` 通过；数据库未操作；未提交或推送。
- 原有 ch13/ch14、UI 诊断和其他未跟踪文件保持原状。

## 测试强化复核（2026-09-27）

- [x] 新增 20 个测试、强化 2 个原测试；完整说明见 docs/ch12/test-review.md。
- [x] SubAgentBoundaryTest：7 项通过，覆盖结构化历史深复制、重试污染、空最终回复、触顶、工具异常、缺权限及取消清理。
- [x] SubAgentPermissionTest：授权不同文件/工具负例、deny 优先级与 48 种权限组合通过；额外根测试增加默认沙箱必须拒绝的负对照。
- [x] AgentToolIntegrationTest：真实装配路径的 Skill 模型/范围、plan 中 slash fork、失败后恢复、取消后继续对话均通过。
- [x] SubAgentAdaptersTest/AgentRegistryTest：recent 工具块配对、Hook 展开与失败状态、外部动作子权限、坏覆盖定义回退均通过。
- [x] SubAgentEndToEndTest：兄弟任务历史隔离和按 id 顺序回填通过；子 token 用量不进入父事件队列。
- [x] ConfigLoaderTest：项目 false 覆盖用户 true，缺省继承通过。
- [x] 全量回归 1256 tests、0 failures、0 errors、1 skipped；git diff --check 通过。本轮仅改测试和文档，原生产 jar 校验值不变。
