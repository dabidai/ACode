# SubAgent —— 子 Agent 与任务分发

> 让主 Agent 学会「派活」：把子任务递给一个干净的上下文，只把结果收回来

## 为什么需要它

ACode 到这一章之前，是一个**全能选手**。用户说什么它就做什么，所有事都在**同一个 Conversation** 里干完。

一路走来它已经挺能干了：读文件、写代码、跑命令、管上下文、记偏好、按权限边界办事。看上去没什么缺的。

但用着用着，你会撞上一个根本性的问题。

想象这个场景：你让 Agent 重构一个模块。它读了 20 个文件了解结构，改了 5 个文件，中间产生了一大堆工具调用和中间结果——文件内容、diff、编译错误、修复过程。一切顺利。

然后你随口补了一句：「顺便帮我写一下这个函数的单元测试。」

问题来了。写测试真正需要的，只是那个函数现在长什么样。但 Agent 的上下文里堆满了重构过程的**噪声**：翻过的文件、试错的 diff、报错的堆栈。它得在一大堆无关信息里翻找写测试要的上下文。**Token 消耗飙升，响应质量下降。**

这个问题有个名字，叫**上下文污染**。

更糟糕的是这种：你让 Agent 同时干两件不相干的事。「一边帮我查这个 bug，一边帮我更新 README。」两个任务的上下文完全不搭，却被塞进了同一个对话窗口。查 bug 读进来的堆栈干扰 README 的写作，README 的格式讨论又干扰 bug 分析。

就好比**让一个人同时接两个电话。理论上可以，实际上两边都听不清。**

怎么解决？思路朴素得不能再朴素：**既然一个 Agent 的上下文脏了，那就新建一个干净的 Agent，让它专门去做那件事，做完把结果拿回来。**

子任务那些中间过程留在外面，主 Agent 的上下文干干净净。这就是 SubAgent。

## 一个决定架构走向的洞察：Agent 也是一种工具

在设计之前，先看一个决定了整章走向的观察。

回忆一下 ACode 现有的 `Tool` 接口（`tool/Tool.java`）。用伪代码说，它长这样：

```
interface Tool:
    name()        -> string          // 工具名，模型用它调用
    description() -> string          // 给模型看的用途说明
    permission()  -> Permission      // read / write / exec
    inputSchema() -> JsonNode        // 参数定义
    execute(input, context) -> ToolResult
```

你仔细看看这个接口描述的是什么：**一个有名字、有描述、接受参数、返回结果的可执行单元。**

现在想想一个 Agent。它是什么？**也是一个有名字、有描述、接受一个任务（就是参数）、返回一个结果的可执行单元。**

发现了吗？**Agent 和 Tool 的抽象是同构的。**

所以设计就出来了：**把 Agent 包装成一个普通的 Tool，注册进 `ToolRegistry`。** 主 Agent 在推理时如果判断某个子任务该派出去，就调用这个 `Agent` 工具——跟调 `Bash`、`ReadFile` 完全一样自然。

具体设计是：注册**一个** `Agent` 工具，通过参数选择不同的 Agent 类型。

```
class AgentTool implements Tool:
    function name():
        return "Agent"

    function inputSchema():
        return {
            prompt:            {type: string, required: true},   // 交给子 Agent 的任务
            description:       {type: string, required: true},   // 一句话说明这次派活干什么
            subagent_type:     {type: string, optional: true},   // 指定预定义类型；留空走 Fork
            model:             {type: string, optional: true},   // 覆盖定义里的模型
            name:              {type: string, optional: true},   // 给这次派活起名，便于回看
        }

    function execute(input, context):
        definition = agentRegistry.resolve(input.subagent_type)
        child      = createAgent(context, definition, input)
        return child.runToCompletion(input.prompt)
```

`prompt` 和 `description` 必填，其余可选。`subagent_type` 指定预定义类型，**留空就走 Fork 路径**，下一节展开。

**为什么是一个统一的 `Agent` 工具，而不是给每种类型注册一个 `agent_explore`、`agent_plan`？**

因为 Agent 类型是**动态加载**的——用户在项目里新建一个定义文件，下次调用就该能用。要是每种类型注册一个独立工具，工具列表会随定义文件增减而变化，系统提示也得跟着重新渲染。统一成一个 `Agent` 工具、用 `subagent_type` 选类型，**工具列表始终稳定**。

还有个容易忽略的好处：主 Agent 完全不需要知道「调子 Agent」和「调普通工具」有什么区别。在它眼里，`Agent` 和 `ReadFile` 都是工具，调用方式一模一样。**子 Agent 在独立上下文里干活，返回结果；主 Agent 的上下文不被污染。** 隔离是在底层完成的，上层调用者无感。

顺便说一句横向定位。Coding Agent 之外的多 Agent 框架（CrewAI、AutoGen）大多走**平等协作或群聊**路线，多个 Agent 互相发消息商量。ACode 的 SubAgent 不一样——它是**主从分发**：主 Agent 是唯一调度者，子 Agent 收任务、做完、回报。

为什么选主从？因为 coding 场景里主从更稳定：**主 Agent 一直掌握全局状态**，不会陷入多 Agent 互相等待的循环。

## 两种创建模式：你要专家，还是助手

派活有两种派法，适用场景完全不同。搞清楚什么时候用哪种，是用好 SubAgent 的关键。

### 定义式：预定义的专家

第一种叫**定义式**（Definition-based）。你**预先写好**一个 SubAgent 的角色、能力和行为规范。它是谁、能做什么、不能做什么，全部白纸黑字写清楚。

比如定义一个专门做代码安全审查的子 Agent：

```yaml
# .acode/agents/security-reviewer.md
---
name: security-reviewer
description: 专注于代码安全审查的子 Agent
disallowedTools: [Agent, EditFile, WriteFile, Bash]
maxTurns: 20
permissionMode: bypassPermissions
---

你是一个专注于代码安全审查的 Agent。

## 职责
- 检查代码中的安全漏洞
- 识别敏感信息泄露风险
- 评估输入验证和输出编码

## 规则
- 只读取代码，不修改任何文件
- 按严重程度分级报告
- 给出具体的修复建议
```

注意 `disallowedTools`：排除了 `Agent`、`EditFile`、`WriteFile`、`Bash`。意思是这个子 Agent **不能写文件、不能执行命令、不能再 spawn 子 Agent**。它是一个**只读的专家**。

为什么要这么限制？因为安全审查这件事本来就不该改代码。限制工具集有两个好处：一是减少出错的可能，二是**你可以放心地让它自动批准工具调用，不弹审批窗**。

这就像公司里的**安全审计员**：他可以查看任何代码和文档，但一个字都不能改。**权限和角色匹配，才能各司其职。**

**它的对话上下文是空的**，只有这一次的任务。

### Fork 式：继承上下文的临时助手

第二种叫 **Fork 式**（Fork-based）。调用 `Agent` 工具时**不指定 `subagent_type`**，就走 Fork 路径。

Fork 和定义式有几个根本区别，最直接的一个是：**Fork 子 Agent 继承父 Agent 的完整对话历史。**

```
function fork(parentAgent, task):
    child = new Agent(
        provider:     parentAgent.provider,             // 共享
        conversation: forkFrom(parentAgent.conversation), // 继承父 Agent 的对话历史
        tools:        parentAgent.tools,                // 共享全部工具
        checker:      new PermissionChecker(...),       // 独立权限追踪
        systemPrompt: parentAgent.renderedSystemPrompt, // 同一份系统提示
    )
    child.conversation.addUserMessage(forkBoilerplate(task))
    return child
```

和定义式一样，**权限追踪是隔离的**。但关键区别在 `conversation`：Fork 拿到的是父 Agent 的完整对话，定义式拿到的是空白对话。

为什么 Fork 要继承对话历史，而不是从空白开始？两个原因。

**第一个是实际用途。** Fork 的典型场景是：你跟 Agent 聊了一阵子，它已经理解了你的需求和当前代码状态，然后你说「顺便帮我把这个也做了」。Fork 出来的子 Agent 继承了之前的对话，**所以它知道你们在聊什么，不需要你把背景再讲一遍。**

**第二个是成本优化。** LLM API 有 **prompt cache** 机制：如果两次请求的前缀完全相同，第二次请求可以命中缓存，大幅降低输入 token 的计费。Fork 子 Agent 使用和父 Agent 相同的系统提示和对话前缀——这意味着**它第一次 API 调用几乎可以 100% 命中缓存**，之前那一长串对话不需要重新处理。

> 类比：前几页已经复印过了，不用再读一遍。

ACode 的 `Conversation` 已经把这件事变得很省事：`history()` 能取出完整消息列表，`replaceAll()` 能灌进一份新列表，`setSystemPrompt()` 能换系统提示。Fork 的构造就是这几个现成能力的组合。

注意，**Fork 子 Agent 不能再调 `Agent` 工具**——想再 Fork 也好，想 spawn 定义式也好，都不行。原因和做法在「不能无限套娃」一节展开。

Fork 的行为靠一段叫 **Fork Boilerplate** 的指令来约束，注入到它收到的第一条消息里：

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

**为什么需要这么强硬的指令？** 因为 Fork 子 Agent 继承了父 Agent 的系统提示，而那份提示里可能写着「必要时向用户确认」这类默认行为。Boilerplate 的作用是**覆盖掉这些默认行为**，把它从「交互式助手」强制切换成「执行型工人」。规则 1 从根源上防止递归 Fork，规则 5 强制结构化输出、方便父 Agent 解析。

### 什么时候用哪种

| | 定义式 | Fork 式 |
|---|---|---|
| 场景 | 固定角色、固定职责 | 临时任务、和当前工作高度相关 |
| 上下文 | 空白，只有任务 | 继承父 Agent 全部对话 |
| 能力边界 | 精确可控，可给更小更快的模型 | 接近全集 |
| 需要对话历史吗 | 不需要 | 必须有 |
| 典型例子 | 安全审查、代码探索、出计划 | 「顺便把这个也做了」 |

判断依据很简单：

- 任务是**固定角色、固定职责**的 → 指定 `subagent_type`。因为你能精确控制它的能力边界，还能给它一个更小更快的模型。
- 任务是**临时性、和主 Agent 正在做的事高度相关**、需要共享之前的对话 → 留空走 Fork。它什么都能做，而且知道你们之前在聊什么。

## 边界：隔离什么，共享什么

子 Agent 有了，但一个边界问题马上浮出来：子 Agent 和主 Agent 之间，**什么该隔离，什么该共享？**

什么都不共享的话，子 Agent 要从零开始，连 LLM 客户端都要重建，太浪费。什么都共享的话，那跟在主 Agent 里直接干有什么区别？

所以关键在于分清两类东西：**运行时状态要隔离，基础设施可以共享。**

| 隔离 | 共享 |
|---|---|
| **对话历史**：定义式空白 / Fork 继承 | **LLM 客户端**：同一个 API Key 和连接池，没必要重建 |
| **权限追踪**：父 Agent 批准了 ≠ 子 Agent 也批准 | **Hook 引擎**：子 Agent 写文件也该触发格式化 |
| **Token 用量**：分开统计，你能看出每个 Agent 花了多少 | **工具集**：工具本身无状态，共享没问题（除非定义限制了工具集） |
| **文件缓存**：各自独立，不互相污染 | **文件系统**：除非显式要求隔离，否则操作同一个工作目录 |

**运行时状态为什么要隔离？** 因为每个 Agent 的工作场景不同。

文件缓存是典型例子：主 Agent 读过的文件缓存和子 Agent 无关，**尤其是后面引入 Worktree 之后**，子 Agent 可能工作在完全不同的目录里，共享缓存会读到错误内容。

权限追踪也一样：主 Agent 批准了某个工具，不意味着子 Agent 也自动获得批准。**每个 Agent 该有独立的审批记录。**

**基础设施为什么可以共享？** 因为它们本身无状态。LLM 客户端共享同一个连接池；工具集共享不会有副作用；Hook 引擎更是**应该**共享——你在项目里定义了「写完 Java 文件自动格式化」，不管文件是主 Agent 写的还是子 Agent 写的，都该自动执行。

落到 ACode 上，一次定义式子 Agent 的构造大致是这样：

```
function createFromDefinition(parent, definition, input):
    child = new Agent(
        provider:     selectProvider(parent, input, definition),  // 共享或按 model 新建
        conversation: new Conversation(model, thinking, maxTokens, maxContextTokens), // 空白
        tools:        filterTools(parent.tools, definition),      // 过滤
        checker:      new PermissionChecker(...),                 // 独立
        maxIterations: definition.maxTurns,
    )
    child.setWorkdir(parent.workdir)          // 文件系统共享
    child.setConfirmationGate(ALWAYS_ALLOW)   // 见「RunToCompletion」一节
    return child
```

**顺便提一句成本。** 多 Agent 听上去更费 token——多次 API 调用、多份系统提示开销。但实际算下来通常**反而更省**：

- 子 Agent 的上下文**短得多**（只装一个任务的相关信息）
- 它常用的模型**更小更便宜**（比如 haiku 而不是顶配）
- Fork 路径还能**命中 prompt cache**，对话前缀几乎不用重新付费

综合下来，把任务分发出去，通常比主 Agent 背着一身脏上下文硬扛更便宜。

## 定义文件：又是 Markdown + YAML

到这里你可能注意到了，定义式 SubAgent 的配置文件长得**很眼熟**：YAML frontmatter 定元信息，Markdown body 写系统提示。

对，它和 Skill 那种定义**结构相同，但字段不同**。

> 说明：ACode 目前还没有 Skill 系统（Skill 章内容待补）。下面这几段对比先按**教材的语义**读，用来理解「为什么 Agent 要另设一套字段」；等 Skill 章补齐后，字段细节以那边为准。

结构上都是 frontmatter + body，解析逻辑可以复用 ACode 现成的 YAML 加载（`ConfigLoader` 已经在用 snakeyaml）。但字段有关键区别，这个区别来自**使用场景**：

| | 白名单语义 | 适合谁 |
|---|---|---|
| Skill | `allowedTools`：「你只能用这几个工具」 | 干一件具体的事，范围窄，**白名单更安全** |
| Agent | `tools` 白名单 + `disallowedTools` 黑名单：「排除这几个危险的，其余都能用」 | 扮演一个角色，**能力接近全集** |

为什么不统一？因为 Agent 扮演角色，能力接近全集——**逐个列出几十个允许的工具不现实**，反过来排除少数危险工具方便得多。

字段差异只是表象。**更关键的是 body 的用途不同：**

- Skill 的 body 是**加载后留在对话里的活跃指令**——告诉主 Agent「现在要做这件事，按这套流程走」
- Agent 的 body 是**子 Agent 启动时的系统提示**——决定这个新 Agent **是谁**、能做什么、按什么风格干活，注入一次后伴随它整个生命周期

一个面向**当前对话上下文**，一个面向**新 Agent 的身份**。这是两套完全不同的东西，只是恰好都用了 Markdown。

`AgentDefinition` 的字段结构：

```
AgentDefinition:
    agentType:       string     // frontmatter 的 name
    whenToUse:       string     // frontmatter 的 description
    tools:           string[]   // 工具白名单（可选）
    disallowedTools: string[]   // 工具黑名单（可选）
    model:           string     // inherit / sonnet / opus / haiku
    maxTurns:        int        // 最大循环轮数
    permissionMode:  string     // default / acceptEdits / plan / bypassPermissions
    systemPrompt:    string     // Markdown body
    filePath:        Path       // 定义文件路径
    source:          string     // project / user / builtin / plugin
```

注意 frontmatter 里写的是 `name` 和 `description`（和 Skill 一致、和用户直觉一致），加载后映射成内部的 `agentType` 和 `whenToUse`。

`tools` 和 `disallowedTools` **可以组合使用**：白名单先确定可用范围，黑名单再从中排除。另外提醒一句：**Agent 的白名单字段叫 `tools`，不叫 `allowedTools`**，跟 Skill 的字段名不同，加载和校验的时候别写混。

### 四个来源，一个优先级链

Agent 定义支持多来源加载。这里直接沿用 ACode 配置体系已经确立的**「离使用场景越近，越有话语权」**原则（`ConfigLoader` 的三级加载就是这个思路）：

```
1. 项目级：{projectDir}/.acode/agents/     ← 优先级最高
2. 用户级：~/.acode/agents/
3. 内置级：classpath agents/（编译嵌入）
4. 插件级：通过插件系统加载                 ← 优先级最低
```

前三个来源和 ACode 现有的 `.acode/` 约定完全一致，实现上可以复用同一套目录解析。

第四个「插件级」目前**只留接口占位**——ACode 还没有插件机制，这一档先定义好优先级位置和加载钩子，等插件系统落地再接上。提前留位的意义在于：**优先级链一旦定下来就不该再改**，否则第三方插件的定义行为会随版本漂移。插件级排最后，是为了防止第三方插件覆盖用户自己的定义或系统内置定义。

举个覆盖的例子：你觉得内置的 `Explore` 不够好，可以在项目的 `.acode/agents/explore.md` 里写一个更适合你项目的版本，项目级会覆盖内置级。反过来，用户级能覆盖内置级和插件级，**但不能覆盖项目级**——项目定义跟着仓库走、会被 review，它才该说了算。

## RunToCompletion：子 Agent 怎么跑

主 Agent 的运行方式你已经很熟悉了：等用户输入 → 执行 → 再等下一轮输入。这是**交互式**的。

子 Agent 不一样。**它没有用户在屏幕前等着输入。** 它拿到一个任务，从头到尾执行完，返回结果。这是**非交互式**的。

所以需要给 Agent 加一个新方法支持这种模式。这段逻辑和主循环几乎一样，**区别就两点**：

```
function runToCompletion(agent, task) -> string:
    agent.conversation.addUserMessage(task)     // 任务直接注入，不等用户输入
    lastText = ""

    for turn in 1..agent.maxIterations:
        response = agent.provider.send(agent.conversation.buildRequest(agent.tools, null))
        agent.conversation.addMessage(response)

        if response.text != "":
            lastText = response.text
        if response.toolCalls is empty:
            break                                // 不再调工具 → 任务完成
        executeToolCalls(agent, response.toolCalls)

    return lastText
```

**第一，它不等待用户输入**，任务直接从参数传进来。**第二，当 LLM 不再调用工具的时候，循环就结束**，把最后的文本作为结果返回。

工具调用的过程跟主循环完全一样：权限检查 → 执行工具 → 记录结果。**Hook 在子 Agent 里仍然生效**（Hook 引擎是共享的，上一节说过）。

> 类比：派出去的人办完事回来汇报，**中途不会打电话问你**。

ACode 现有的 `Agent.run()` 已经是「循环 + `maxIterations` + `Termination` 枚举 + 事件队列」的结构，`runToCompletion` 是它的一种**非交互式变体**——共用工具执行和上下文管理，只是把「等用户输入」换成「任务参数传入」，把「等下一轮」换成「没工具调用就收工」。

### 权限：怎么做到不弹窗

有个细节值得单独讲：**RunToCompletion 里的工具调用怎么审批？**

子 Agent 没有前台，弹审批窗没人点。所以要么自动批准，要么直接失败。ACode 的 `ConfirmationGate` 默认值就是 `ALWAYS_ALLOW`，子 Agent 保持这个默认值即可。

**但自动批准必须安全，否则就是灾难。** 安全性从哪来？从**能力边界**来：

```
能力边界：disallowedTools 已经把写操作、命令执行锁死
    ↓
它根本调不了这些工具
    ↓
自然不需要审批
```

以 `security-reviewer` 为例：`disallowedTools` 排除了 `EditFile`、`WriteFile`、`Bash`，它**就算想搞破坏也没有工具可用**。这时候自动批准是安全的。

**能力边界由 `disallowedTools` 锁死，审批行为由 `ConfirmationGate` 放开，两者配合才能全自动运行。** 缺了前者的自动批准是危险的，缺了后者的全自动是空谈。

顺带说一句 ACode 的 `PermissionMode` 与这件事的关系：`BYPASS` 档「全部放行，仅限完全受控环境；**黑名单 + 沙箱仍生效**」——注意这个注释，它正是子 Agent 需要的语义。定义里写 `permissionMode: bypassPermissions`，配合 `disallowedTools`，就是「放行但翻不了天」。

## 不能无限套娃

有个问题得正面回答：主 Agent 调子 Agent A，A 再调子 Agent B，B 再调 C……**这条链会不会无限延伸下去？**

会，所以要限制。**但关键在于怎么限制。**

ACode 的做法是：**通过工具过滤隐式限制，而不是用一个硬编码的深度阈值。**

为什么不用深度数字？因为「最多嵌套 3 层」这种规则有两个毛病：整数阈值是拍脑袋定的，说不清为什么是 3；而且它只能拦住超深的情况，拦不住「宽度爆炸」——一层派 50 个子 Agent 照样能把资源吃光。**能力层面的约束比数字阈值牢靠得多。**

具体来说，系统在两个层面做硬限制：

**第一，Fork 不能再 Fork。** 如果子 Agent 的对话历史里已经带了 Fork 标记，再次 Fork 直接报错。这防止 Fork 链条无限延伸导致上下文爆炸。

**第二，后台 Agent 不能再 spawn Agent。** 后台工具白名单里不包含 `Agent` 工具本身，从根源上切断后台嵌套的可能性。

> 类比：不写「最多嵌套 3 层」这种死规矩，而是**根本不给它那把钥匙**。

落到 ACode 的实现上，有一个**天然优势**值得指出：`Conversation.buildRequest(requestTools, turnReminder)` 是**按请求传入工具列表**的——工具列表不是绑死在注册中心里的全局状态。这意味着：

**工具过滤不需要改 `ToolRegistry`，只要给子 Agent 传一份不同的工具列表。**

主 Agent 传全集，子 Agent 传过滤后的子集，两者共用同一个注册中心、互不干扰，也不需要任何「保存-修改-恢复」的临时状态。这比「改全局注册表再改回来」干净得多。

定义式和 Fork 走的是不同路径：定义式子 Agent 在**构造时**就把 `Agent` 工具从工具列表里过滤掉了，它根本看不到这个工具；Fork 路径继承了父 Agent 的完整工具集（所以看得到 `Agent` 工具），但**调用时会被拦截**——检测到调用方是 Fork 产生的子 Agent 就直接报错，另外还有 Boilerplate 标记扫描作为兜底。

## 工具过滤的四层防线

子 Agent 拿到的工具集，应该和主 Agent 一模一样吗？显然不行。

如果子 Agent 能用所有工具、包括 `Agent` 工具本身，那 A 能 spawn B，B 能 spawn C，无限套娃。如果后台 Agent 也能随意调用任何工具，一个失控的后台任务可能造成不可预期的后果。

所以过滤要**多层叠加，每层防不同的风险**：

```
第 1 层：全局禁止列表
         → 所有子 Agent 都不能用的工具：Agent、AskUser、ExitPlanMode

第 2 层：后台 Agent 白名单
         → 后台运行的 Agent 只能用基础工具（本阶段仅定义、不启用）

第 3 层：Agent 定义的 disallowedTools 黑名单
         → 排除这个角色不该有的能力

第 4 层：Agent 定义的 tools 白名单
         → 若定义了，取交集作为最终范围
```

**每层都很简单，组合起来覆盖所有风险场景：**

- **全局层防递归失控**：子 Agent 不能再 spawn Agent（防嵌套爆炸），也不能问用户问题（`AskUser` 在子 Agent 里没有意义——没有前台，问了没人答，只会阻塞循环）
- **后台层防资源失控**：后台 Agent 的工具集进一步收窄到基础读写
- **定义层是业务能力约束**：安全审查员不该有写文件的能力

执行顺序就是从上往下依次过滤，最终得到子 Agent 实际可用的工具集。

**ACode 的工具全集本身很小**，这让四层过滤实现起来相当轻（`DefaultToolset` 注册 6 个 + 会话层注册 2 个）：

| 类别 | 工具 | 子 Agent 默认 |
|---|---|---|
| 文件读 | `ReadFile`、`Glob`、`Grep` | 可用 |
| 文件写 | `WriteFile`、`EditFile` | 按定义，审查类角色排除 |
| 命令 | `Bash` | 按定义排除 |
| Agent | `Agent` | **全局禁止** |
| 交互 | `AskUser`、`ExitPlanMode` | **全局禁止** |

`ExitPlanMode` 也在全局禁止列表里：规划模式是主 Agent 的状态，子 Agent 没有「退出规划模式」这个概念。

## 内置 Agent 类型

ACode 内置几种预定义 Agent，覆盖最常见的使用场景。你可以直接用，也可以在项目里覆盖它们的定义。

### Explore —— 代码探索

只有读取和搜索能力，不能修改文件。让它了解项目结构、查找某个功能的实现、理清调用链，它很擅长。

```yaml
---
name: Explore
description: 只读的代码探索 Agent，用于搜索文件、理清调用链
disallowedTools: [Agent, EditFile, WriteFile]
model: haiku
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

注意两个设计选择。

**第一，它用 `disallowedTools` 黑名单而不是 `tools` 白名单。** 这样系统以后新增一个只读工具时，Explore **自动就能用**，不需要手动往白名单里加。代价是新增工具默认可见——万一哪天加进来一个有写副作用的工具，Explore 也会自动获得它。之所以还能接受，是因为它的黑名单已经把写操作类排除了，新增的写类工具会被覆盖；属于**全新类别**的工具才需要 review 一次。这个取舍值得在 code review 时留意。

**第二，它的模型是 `haiku`。** Explore 做的是搜索和阅读，不需要最强的推理能力，用更小更快的模型足够，还能省下可观的 token 成本。

### Plan —— 独立上下文的规划

分析需求、制定执行计划，但不直接执行。主 Agent 拿到计划后逐步执行。

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

这里要先澄清一个容易混淆的点。**ACode 里有两个跟「规划」相关的东西：**

- **Plan 权限模式**（`PermissionMode.PLAN`）：主 Agent **自己**切到只读规划状态，通过 `/plan` 命令触发，主 Agent 换一套工具集接着干活，**不创建任何子 Agent**
- **Plan Agent**：一个**独立的只读 SubAgent**，有自己的对话上下文

两者用途互补：**临时切到规划状态用 Plan 模式，需要长链分析且不想污染主上下文，用 Plan Agent。**

为什么值得单独做一个 Plan Agent？因为**上下文隔离**。Plan 权限模式虽然限制了工具，但规划过程产生的分析信息**还是留在主 Agent 上下文里**。Plan Agent 在独立上下文里规划，完成后只把**最终的计划文本**返回，中间的分析过程完全隔离。

### general-purpose —— 通用子 Agent

拥有全部工具，用于需要完整能力但又要独立上下文的场景。

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

### Verification —— 找 bug 的验证专家

这个比较特殊：**它需要通过配置开关启用。** 在 `.acode/config.yaml` 里加一行就能开启，不加则不会出现在内置 Agent 列表中。

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

它的系统提示引导它用**怀疑的眼光**审视改动：不仅检查是否实现了需求，还检查边界条件、错误处理这些容易被忽略的角落。提示里明写自己有两个失败模式，**这是有意的**——把已知的失败模式写进提示，比让它自己踩坑更划算。

**为什么不直接内置，而要加个开关？** 因为 Verification 的价值**高度依赖使用场景**。在快速迭代的开发阶段，每次改动都跑一遍验证会显著拖慢节奏。做成开关，就能按需开启：**CI 流程里开启做门禁检查，日常开发关闭保持速度。**

这里展示了一个值得记住的设计模式：**有些能力不应该默认暴露。** Agent 类型可以和配置开关结合，让系统的能力边界动态可调。

### 怎么用这些内置 Agent

在调用 `Agent` 工具时指定 `subagent_type`：

```
subagent_type: "Explore"    → 走定义式，用内置的 Explore 定义
subagent_type: "Plan"       → 走定义式，用内置的 Plan 定义
（留空）                     → 走 Fork，继承当前对话上下文
```

主 Agent 在推理时会自己判断：这个子任务适合交给专门的 Agent，还是该 Fork 一个助手。**整个过程对用户是透明的**——你不会在界面上看到「正在创建子 Agent」这种噪音，只会看到子任务的结果。

## 设计哲学

**能力边界优于行为约束。**

限制一个 Agent 能做什么，有两种思路：一种是在提示词里写「请不要修改文件」，另一种是**根本不给它写文件的工具**。

第二种更可靠。提示词是可以被说服、被绕过、被幻觉覆盖的；工具列表是硬的。`security-reviewer` 之所以能放心自动批准，不是因为它被叮嘱「不要乱改」，而是因为它**手里根本没有改文件的工具**。

同样的道理贯穿全章：不写死「最多嵌套 3 层」而是让子 Agent 看不到 `Agent` 工具；不靠约定「后台任务别乱来」而是硬编码后台工具白名单。**把约束放在能力层，而不是纪律层。**

## 边界

- **本章不做后台任务基础设施**。`TaskManager`、`task-notification` 异步回传、`adoptRunning` 前台转后台、ESC 手动切换、超时自动转后台，全部留给下一阶段。本章的 Fork 式**前台同步执行**——它继承上下文、命中缓存的核心价值不受影响，只是暂时不能并行派多个活。

- **Fork 不实现「无条件后台」语义**。ACode 目前没有后台任务基础设施，硬塞一个半成品会污染架构。好消息是 `Agent.run()` 已经是「独立线程 + 事件队列」结构，后续接入后台是**增量扩展而非重构**，这一章不做不会留下技术债。

- **不做文件系统隔离**。子 Agent 和主 Agent 共用同一个工作目录。`isolation: worktree` 这个参数**留字段但先不实现**，留给 Worktree 那一章。

- **本节先建底座，Skill 章后续复用**。ACode 目前还没有 Skill 系统（Skill 章内容待补）。教材里这两者的关系方向是：**SubAgent 的「Agent 构造 + RunToCompletion」是共享底座，Skill 的 fork 通过 `SkillForkHost.RunSubAgent` 委托给它**，两边走同一条创建路径。所以本章的目标是**把这套底座做扎实**——Skill 章落地时直接复用，不需要回头改这里的接口。

- **Hook 的 `agent` 动作执行器留待衔接**。Hook 章设计过一个 `agent` 类型的动作执行器（「写完文件自动启动子 Agent 做安全审查」），它依赖的正是本章的运行时。两边接口对得上就接，对不上就留明确 TODO，**不为了让某一章看起来完整而写假实现**。

- **不做 Agent 定义的热重载**。定义文件在启动时加载一次，改完需要重启。热重载涉及文件监听和注册表刷新，收益不足以进这一章。
