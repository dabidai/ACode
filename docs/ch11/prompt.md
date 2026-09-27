# Hook 系统 —— 生命周期钩子与自动化

> 把「人盯着 Agent 干活」变成「规则自动生效」

## 为什么需要它

用了 ACode 一段时间之后，你会发现自己养成了一些「习惯」：

- Agent 写完一个源代码文件，你手动跑一遍格式化
- Agent 修改了 `.proto` 文件，你手动跑代码生成器
- Agent 准备执行 `rm -rf /`，你紧张地盯着审批弹窗，生怕手滑点了 Allow
- 每次开始新对话，你先敲一句「请先读一下 ARCHITECTURE.md 了解项目结构」

共同点：**触发条件明确，执行动作固定**。「Agent 写了代码文件」就格式化，「Agent 要执行危险命令」就拦截，「新对话开始」就注入上下文。条件是确定的，动作也是确定的，每次一模一样。

条件和动作都固定，那为什么要让人来盯？这不就是事件驱动吗——某个事件发生了，满足某个条件，自动执行某个动作。

Hook 系统要做的就是这件事：**让用户在 Agent 的生命周期事件上挂载自动化动作**。你省下来的精力可以去思考更重要的问题，不用再当人肉 CI。

## 三要素：事件 + 条件 + 动作

一条 Hook 由三部分组成，用人话说就是「**什么时候**」「**什么情况下**」「**做什么**」。

```yaml
hooks:
  - event: post_tool_use            # 事件：工具执行之后
    if:                             # 条件：只在写文件时触发
      tool: WriteFile
    action:                         # 动作：执行什么
      type: command
      command: "black $FILE_PATH"
```

这个 Hook 说的是：每当 Agent 用 WriteFile 工具写了一个文件之后，自动跑一下格式化。`$FILE_PATH` 是上下文变量，会被替换成实际的文件路径。

**条件可以省略**，不写 `if` 就是「无条件执行」——比如每次会话开始都注入项目上下文，不需要任何判断。

**事件和动作是必须有的**。没有事件不知道什么时候触发，没有动作触发了也没用。

## 配置写在哪儿

ACode 在三个地方找 Hook 配置：

```
项目根目录/.acode/hooks.yaml        团队规范，跟代码走、随仓库一起被 review
~/.acode/hooks.yaml                 个人偏好，跨项目共享
项目根目录/.acode/hooks.local.yaml  本地临时改动，不被 git 追踪，优先级最高
```

三个文件按顺序加载，但和 Skill 那种「找到一个就停」的查找语义不一样：**Hook 配置是追加合并的**，用户级和项目级声明的 Hook 会同时生效、叠加起来用。

比如项目规定「所有写文件都要格式化」，你个人额外加一条「写完 Python 文件顺便跑一下类型检查」，两条同时生效。

## 事件：Agent 生命周期中的关键时刻

事件是 Hook 的触发时机。把 Agent 的整个运行过程想象成一条时间线，上面有很多关键节点，每个节点就是一个事件。按颗粒度分五层：

**会话级**（一个会话从开到关，各发生一次）

- `session_start` —— 新会话开始时
- `session_end` —— 会话结束时

**轮次级**（一个会话里反复出现）

- `turn_start` —— 用户发送新消息时，标志一轮对话开始
- `turn_end` —— Agent 完成回复时，标志一轮对话结束

**工具级**（最常用）

- `pre_tool_use` —— 工具执行**之前**
- `post_tool_use` —— 工具执行**之后**

**消息级**

- `pre_send` —— 消息发送给 LLM 之前
- `post_receive` —— 收到 LLM 响应之后

**系统级**

- `startup` / `shutdown` —— 启动、退出时
- `error` —— 发生错误时
- `compact` —— 上下文压缩时

**场景化**

- `permission_request` —— 权限审批请求时
- `file_change` —— 文件被修改时
- `command_execute` —— Slash Command 执行时

「之前」和「之后」的区别非常重要，下面单独讲。

按实际使用经验，**`pre_tool_use` 和 `post_tool_use` 占了绝大多数场景**：写文件后自动格式化用 `post_tool_use`，拦截危险命令用 `pre_tool_use`，改 proto 后生成代码用 `post_tool_use`。

## pre_tool_use：唯一能说「不」的事件

这是整个 Hook 系统里最有价值的事件。

其他事件都是**通知型**的：事情发生了，告诉你一声，你可以做点额外的操作。但 `pre_tool_use` 不一样，它发生在工具执行**之前**，你可以在这个时刻决定：**允许，还是拒绝**。

### 和权限系统的分工

用权限系统当然也能禁止 Agent 做某些事，但权限系统是**静态**的，写在配置里就不变了，只能按路径/工具名判断。如果你想根据工具参数的**内容**来决定是否放行呢？比如「允许执行 `rm` 命令，但不允许 `rm -rf /`」——权限系统做不到这么细粒度的判断，Hook 可以。

一句话说清楚分工：

- 权限系统回答「**这个工具，Agent 能不能用**」
- Hook 回答「**这次调用，具体在干嘛，该不该放行**」

### reject：拒绝标记

```yaml
hooks:
  - event: pre_tool_use
    if:
      tool: WriteFile
      args.path: package-lock.json
    action:
      type: command
      command: "echo 'REJECT: package-lock.json 应该由 npm install 生成，不要手动修改'"
    reject: true
```

`reject: true` 是 `pre_tool_use` 的特殊标记。当 Hook 设置了 `reject`，工具调用会被取消，**Agent 会收到 Hook 输出的文本作为错误信息**。

`reject` **只能用在 `pre_tool_use` 上**。这是逻辑上的必然——`post_tool_use` 的时候工具已经执行完了，再说「拒绝」有什么意义？

### 拦截之后的反馈循环

拦截**不只是「堵」，更是一次纠正和引导**。

Agent 收到拒绝原因后，会理解「这个操作被拒绝了」，然后**换一种方式完成任务**。比如上面那个例子，Agent 看到「应该由 npm install 生成」，就会改用 `npm install` 来更新依赖。

```
Hook 拦截 → Agent 收到原因 → Agent 调整策略 → 换一种做法完成
```

### 顺序约定

多个 Hook 匹配同一个事件时，**按它们在 yaml 里出现的先后顺序逐个执行**；只要前面任何一个 Hook 标记了 reject，**后面的 Hook 就完全不会跑**。

所以**写 Hook 时位置很重要**——把最该兜底的拦截规则放前面，不会被后面更宽松的 Hook 跳过。

### 正则匹配示例

```yaml
hooks:
  - event: pre_tool_use
    if:
      tool: Bash
      args.command:
        regex: "rm\\s+-rf\\s+/"
    action:
      type: command
      command: "echo '警告：检测到高危删除命令，已拦截'"
    reject: true
```

条件里用 `regex` 做正则匹配，能匹配各种空格写法，比单纯的字符串相等灵活得多。

> 说明：`rm -rf /` 这类高危命令 **ACode 现有的危险命令检测已经硬拦截了**，这里只用它演示"正则 + 拒绝"的写法。Hook 的价值不在这类既有防线已覆盖的场景，而在下面三件既有机制给不了的事：**能做动作**（不只是判定放行/拒绝）、**能挂在非工具调用的时刻**（会话开始、轮次起止）、**条件能跨字段组合**。

## 条件语法：用 YAML 结构表达

条件决定 Hook 在事件触发时是否执行。

**条件不是一段字符串表达式，而是 YAML 结构。** 本项目所有配置都是 YAML，条件也交给 YAML 自己表达，就不必为条件单独造一个表达式解析器。值里的空格、引号、反斜杠由 YAML 处理，用户也不用记操作符和优先级。既有权限规则的匹配语法只用于权限规则，Hook 条件自成一套结构，两边互不影响。

### 字段

- `tool` —— 工具名
- `args.xxx` —— 工具参数中的某个字段，比如 `args.command` 是 Bash 工具的 `command` 参数，`args.path` 是 WriteFile 的 `path` 参数
- **事件名不写在条件里**：`event` 字段已经声明了这条 Hook 挂在哪个事件上

**未知字段不匹配、不报错。**

### 匹配方式

| 写法 | 含义 |
|---|---|
| `字段: 值` | 精确相等 |
| `字段: { regex: "..." }` | 正则匹配 |
| `字段: { glob: "..." }` | glob 匹配（用于路径）|

### 组合

**多个字段默认是「且」**，不用写 `&&`：

```yaml
if:
  tool: WriteFile
  args.path: { glob: "*.java" }
```

需要更复杂的组合时用 `all` / `any` / `not` 三个关键字，**它们可以任意嵌套，层级本身就是优先级**——不存在「运算符优先级」的问题，也就不需要禁掉某种组合。

```yaml
# 任一满足
if:
  any:
    - tool: Bash
    - tool: WriteFile

# 取反：除 ReadFile 之外的所有工具
if:
  not:
    tool: ReadFile

# 写文件、但不是 vendor 目录下的
if:
  all:
    - tool: WriteFile
    - not:
        args.path: { glob: "vendor/**" }
```

### glob 和正则的区别

**正则**表达力强，适合匹配命令这类文本，比如 `rm\s+-rf` 能匹配各种空格写法。

**glob** 是文件系统里常用的通配符语法（就是 `.gitignore` 里写的那套），适合匹配路径，比正则简单得多：

- `*` 匹配任意字符，但**不跨**目录分隔符
- `**` 匹配任意层级的路径
- `?` 匹配单个字符

比如 `*.java` 只匹配当前层的 Java 文件，`src/**/*.java` 匹配 src 下任意深度的 Java 文件。

### 示例

```yaml
# 工具名精确匹配
if:
  tool: Bash

# 工具参数的正则匹配
if:
  tool: Bash
  args.command: { regex: "rm\\s+-rf" }

# 文件路径的 glob 匹配
if:
  tool: WriteFile
  args.path: { glob: "*.proto" }

# 反向匹配：除了 ReadFile 之外的所有工具
if:
  not:
    tool: ReadFile
```

## 四种动作执行器

动作是 Hook 触发后执行的操作，支持四种类型。

### command：执行 shell 命令

最常用的执行器。命令中可以使用上下文变量，执行前会被替换。

```yaml
action:
  type: command
  command: "prettier --write $FILE_PATH"
  timeout: 10s
```

如果 Agent 写了 `src/components/Header.tsx`，实际执行的就是 `prettier --write src/components/Header.tsx`。

底层就是启动一个 shell 子进程执行命令，捕获输出和退出码。`timeout` 控制命令的最长执行时间，**超时后引擎会终止子进程并返回超时错误**。格式化工具一般很快，但如果你挂的是跑测试的命令，设个合理的超时就很有必要。

### prompt：注入提示词

不跑任何外部命令，做的事就是**给 Agent 塞一段提示词**。

```yaml
action:
  type: prompt
  message: "请先阅读 ARCHITECTURE.md 了解项目结构，然后再开始工作。"
```

**这里有个容易踩的概念坑**：所谓「塞一段」**不是**往 LLM API 的 messages 数组里加一条 `role=system` 的消息，而是以**系统 reminder 的形式注入到 system prompt 区域**，Agent 在下一轮请求前会读到。这样做的好处是不污染对话历史的结构，token 计费也好追踪。

适合在 `session_start` 或 `turn_start` 时给 Agent 补充上下文。配合 `once: true` 还能做到只在第一次对话时注入。

和直接写死在 system prompt 里的区别在于：**Hook 是动态的**，可以加条件（比如只在特定项目里注入），而 system prompt 是静态的、对所有场景生效。

### http：发送 HTTP 请求

把事件通知发送到外部系统——发 Slack 通知、写日志到收集系统、触发监控告警。

```yaml
action:
  type: http
  url: "https://hooks.slack.com/services/xxx"
  method: POST
  body: '{"text": "ACode: Agent 修改了 $FILE_PATH"}'
```

实现上就是标准的 HTTP 请求。

### agent：启动子 Agent

最强大的执行器：**启动另一个 Agent 来处理事件**。前三种执行器都做确定的动作，`agent` 执行器则把事件交给一个新的 Agent 自主决策。

```yaml
action:
  type: agent
  prompt: "请检查刚才写入的文件 $FILE_PATH 是否有安全漏洞。"
```

想想这意味着什么：每次 Agent 写完文件，自动启动另一个 Agent 来做安全审查。**用 AI 来监督 AI**。

不过 `agent` 执行器要真正跑起来，**得依赖 SubAgent 运行时**。本章把执行器的接口和调用骨架搭好就行：配置能加载、能校验通过。至于子 Agent 怎么真的被启动、上下文怎么传递，留到 SubAgent 那一章再展开。

## 执行控制

除了三要素，Hook 还有几个控制机制。

### once：只执行一次

有些 Hook 执行一次就够了。比如 `session_start` 时注入项目上下文，第一次注入了，后面的会话不需要再注入，因为上下文已经在对话历史里了。

```yaml
hooks:
  - event: session_start
    action:
      type: prompt
      message: "项目技术栈：Java 21 + Maven + Claude API"
    once: true
```

`once: true` 表示这个 Hook 第一次触发后就标记为「已执行」，**同一个会话里不会触发第二次**。

这个标记**跟着会话走**。ACode 有会话持久化，能把之前的会话接着聊下去；如果标记只放在内存里，恢复会话时就会丢掉，变成「每次恢复都重新注入一遍项目背景」——而用户的预期是接着上次继续，不是开新会话。所以标记随会话一起存下来，**恢复会话不重新触发**；只有**新建**会话才重新计一次。

要跨会话的「真正只触发一次」（连新会话也不触发），应该用版本检测之类的机制，不是 `once` 该管的事。

### async：异步执行

有些 Hook 的动作耗时较长，不应该阻塞 Agent 的执行流程。比如发 Slack 通知，网络请求可能要几百毫秒甚至几秒，通知发没发成功不影响 Agent 继续工作，没必要让 Agent 等着。

```yaml
hooks:
  - event: post_tool_use
    if:
      tool: WriteFile
    action:
      type: http
      url: "https://hooks.slack.com/services/xxx"
      body: '{"text": "文件已修改: $FILE_PATH"}'
    async: true
```

**但有一个硬性限制：`pre_tool_use` 事件的 Hook 不能设为 `async`。**

原因很明显——`pre_tool_use` 需要同步返回「允许还是拒绝」的决定。如果异步执行了，工具调用都已经开始了，拒绝还有什么意义？**配置校验的时候要检查这个约束。**

### 错误兜底：只记日志，不中断

还有一个重要的设计原则：**Hook 执行出错只记日志，不中断 Agent 主流程。**

为什么？Hook 是辅助机制。格式化失败了，Agent 写的代码还是在的。Slack 通知没发出去，Agent 的工作成果不受影响。**如果一个辅助机制的故障能反过来把核心流程搞崩，那就是尾巴摇狗了。**

所以引擎在捕获到 Hook 执行错误时，只写一行日志，然后继续往下跑。

## 上下文变量

`command` 和 `body` 里那些 `$FILE_PATH`、`$TOOL_NAME` 是怎么工作的？

每当一个事件触发，引擎会创建一个 HookContext，里面包含这个事件的所有上下文信息。执行动作之前，会把命令模板里的变量替换成上下文中的实际值。

HookContext 包含：事件名、工具名、工具参数字典、文件路径、消息内容、错误信息。

**变量替换规则**：

| 变量 | 替换成 |
|---|---|
| `$EVENT` | 事件名 |
| `$TOOL_NAME` | 工具名 |
| `$FILE_PATH` | 文件路径 |
| `$MESSAGE` | 消息内容 |
| `$ERROR` | 错误信息 |
| `$TOOL_ARGS.xxx` | 工具参数字典里 `xxx` 字段的字符串表示 |

**未定义的变量会被替换成空字符串，不会报错。** 这个设计让 Hook 配置的容错性更好，不会因为某个变量在特定事件中不存在就崩掉。

条件求值也是从同一个上下文里取字段值：`tool` 取工具名，`event` 取事件名，`args.xxx` 取工具参数里的对应字段。未知字段同样返回空字符串。

## 实战配置示例

### 写文件后自动格式化

Agent 生成的代码格式不一定完美，跑一下格式化保证一致性。

```yaml
hooks:
  - id: auto-format
    event: post_tool_use
    if:
      tool: WriteFile
      args.path: { glob: "*.java" }
    action:
      type: command
      command: "google-java-format -i $FILE_PATH"
```

### 禁止修改 vendor 目录

`vendor/` 目录应该由包管理工具管理，Agent 不应该直接修改里面的文件。用 Hook 做一个硬性拦截。

```yaml
hooks:
  - id: block-vendor
    event: pre_tool_use
    if:
      tool: WriteFile
      args.path: { glob: "vendor/**" }
    action:
      type: command
      command: "echo 'vendor 目录由包管理工具管理，请勿手动修改'"
    reject: true
```

### 新会话时加载项目上下文

每次开始新对话，自动告诉 Agent 项目的基本信息，你不用每次都手动提醒它。

```yaml
hooks:
  - id: project-context
    event: session_start
    action:
      type: prompt
      message: |
        项目信息：
        - 技术栈：Java 21 + Maven
        - 代码规范：参见 checkstyle.xml
        - 架构文档：参见 ARCHITECTURE.md
    once: true
```

### 拦截高危删除命令

```yaml
hooks:
  - id: block-dangerous-rm
    event: pre_tool_use
    if:
      tool: Bash
      args.command: { regex: "rm\\s+-rf\\s+/" }
    action:
      type: command
      command: "echo '警告：检测到高危删除命令，已拦截'"
    reject: true
```

### 文件修改后发 Slack 通知

适合团队协作场景，让其他人知道 Agent 改了什么。

```yaml
hooks:
  - id: slack-notify
    event: post_tool_use
    if:
      tool: WriteFile
    action:
      type: http
      url: "https://hooks.slack.com/services/xxx"
      method: POST
      body: '{"text": "ACode Agent 修改了 $FILE_PATH"}'
    async: true
```

用了 `async: true`，因为通知不需要阻塞 Agent 的执行流程。发成功了很好，发失败了也不影响 Agent 继续工作。

## 配置加载与校验

Hook 从 yaml 文件加载，**加载时**要校验配置的合法性。规则很明确：

- 事件名必须在合法事件列表中
- `action.type` 必须是 `command` / `prompt` / `http` / `agent` 四者之一
- 条件结构必须合法：`all` / `any` / `not` 的嵌套形状要对，`regex` / `glob` 只能用在这两种匹配上，正则要能编译通过
- `reject` 只能用在 `pre_tool_use` 上
- `async` 不能用在 `pre_tool_use` 上
- 每种 action 类型的必填字段要检查：`command` 类型必须有 `command` 字段，`http` 类型必须有 `url` 字段

**非法配置应该给出明确的错误信息并定位到具体是哪个 Hook**（带上 `id` 或序号），这样用户能快速找到写错的地方。**在运行前就拦住非法配置**，而不是等真跑到那一步才炸。

错误报在哪里也有讲究：**配置写错要打到终端**（沿用 ACode 既有的「启动阶段配置出错直接打印」的做法），因为项目日志是**只写文件、不进终端**的（全屏界面会被 console 日志污染）——运行期日志在会话里根本看不见。配置写错是用户必须当场知道的事，不能只落在看不见的文件里。运行期的执行错误则只写日志，不在会话里刷屏。

## 设计哲学

**配置优于编码。**

把自动化逻辑从代码里抽出来，变成用户可声明的 yaml 规则。每次加新规则不需要改代码、不需要重新编译。规则文件还能跟代码一起提交、一起 review，新同事 clone 下来就自动有了团队的安全规则和自动化规则。

## 边界

- **本章不做 SubAgent**。`agent` 执行器只搭接口骨架，配置能加载能校验，真正调用留到 SubAgent 章节。
- **`once` 不做跨会话记忆**。标记只跟着单个会话走，新建会话重新计；跨会话的「只触发一次」不在 `once` 的职责范围内。
- **条件不做表达式解析器**。条件用 YAML 结构表达，没有字符串解析、没有运算符优先级，也就不存在「哪种组合不允许」。需要更多逻辑就拆成多条 Hook。
- **不接远程配置中心**。只读本地三个 yaml 文件。
