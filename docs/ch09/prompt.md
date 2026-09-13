# Slash Command 命令框架

一套可以直接在本地处理、不走LLM的系统，设定以`/`开头的输入都会被命令解析器拦截，绕过LLM，直接在本地处理。

## 构成

### 注册

```
Command:
	name	字符串		// 命令名，比如："compact"
	aliases	字符串列表	// 别名，如: ["c"]
	description	字符串	// 简短描述
	usage	字符串		// 用法示例
	type	CommandType	// 命令类型
	argPrompt	字符串	// 参数提示语
	hidden	布尔值		// 是否在帮助列表中隐藏
	handler	函数		// 执行函数
```

hidden 用来标记不想暴漏给用户的内部命令

声明式注册命令，把所有命令定义集中在一个地方。以`/help`举例：

```
registry.register(Command{
	name: "help",
	aliases: ["h", "?"],
	description: "显示帮助信息",
	type: LOCAL,
	handler: handleHelp
})
```

类似以上方式，不过在本项目中要结合实际情况进行实现

**注册中心需要用读写锁保护并发安全**，当前所有命令都在启动时注册，但是在后边补充的Skill系统会在运行时动态注册新命令，那时候注册和查找可能同时发生。

### 解析

以`/compact 保留数据库相关内容`为例，解析器要能把它拆成两部分：命令名`compact`和参数`保留数据库相关内容`，以空格分割。

解析时，把命令名转为小写，增加命令的灵活性

### 执行

命令的执行函数签名是统一的：

```
CommandContext：
	args	字符串	// 原始参数字符串
	agent	Agent实例	// Agent 实例
	conversation	Conversation实例	// 当前会话
	session		Session实例	// 当前会话
	ui		UIController	// UI控制接口
	config	Config	// 全局配置
```

以上只是一个例子，需要结合项目实例来安排参数类型，CommandContext 把命令执行所需的一切资源打包在一起，通过它，命令可以操作Agent、修改对话、更新UI、读取配置。每个Handler函数不需要关心怎么拿到这些依赖，框架已准备好

UIController接口，UI层暴漏给命令的操作接口：

```
interface UIController:
	addSystemMessage(text)	// 显示一条消息系统
	sendUserMessage(text)	// 将文本作为用户消息发送给Agent
	setPlanMode(enable)	// 切换为Plan Mode
	getTokenCount()		// 获取当前Token数
	refreshStatus()		// 刷新状态栏
```

## 命令类型

不同命令执行方式不同

`local`：本地命令，结果以系统消息的形式显示在 UI 中，eg：`/help`、`/status`、`/session`、`/list`都是这类。

`local-ui`：状态栏需要更新，工具可用性需要变化，整个UI的行为模式发生了变化，本地执行但会触发UI状态变化，eg：`/plan`、`/clear`、`/do`

`prompt`：不在本地执行，命令只负责构造消息，实际干活的还是Agent，eg：`/review`，实际就是把一段预设的prompt发送给Agent

## 别名

方便用户使用，`/help` 使用 `/h` 也可以调用，`/compact` 使用 `/c`也可以启动

别名在命令定义时就已声明完成，解析在命令查找阶段完成。先按照命令名进行精准匹配，找不到再遍历所有命名的别名。

对于别名冲突的情况，在注册的时候就应该检测出来

## 参数提示

如果有命令存在参数，用户在只输入命令的时候，系统应该提示他补上会话ID，不要只显示出来一个`缺少参数`的错误。

## Tab补全

除了别名，Tab 补全也是提升效率的利器。在输入框中输入 `/` 后按 Tab，应该显示所有可用命令。输入 `/com` 后按 Tab，应该自动补全为 `/compact` 。

补全结果在UI中以下拉列表的形式展示，如果只有一个匹配，直接补全到输入框；如果有多个，那就列出来让用户选择。

## 命令系统与UI事件循环集成

如果输入框有内容，用户按下回车，先判断是不是命令，是的话走命令系统，不是的话走Agent。

输入为空时直接返回。

如果只输入了`/`没有命令名时，直接列出可用命令。

命令找不到时，错误信息里带上`/help`引导。错误提示永远带引导。

有些命令被定义为必须带参数，但用户没提供，这时候显示 `argPrompt` 的提示信息。

## UI风格

参考我这个项目github上提交的PR的UI风格

## 核心命令

local：`help、compact、session、memory、permission、status`

local-ui：`clear、plan、do`

prompt：`review`

### local

```
可用命令：
  /help, /h, /?        显示帮助信息
  /compact, /c         压缩上下文
  /clear               清除对话历史
  /plan, /p            切换到 Plan 模式
  /do                  切换到执行模式
  /session             会话管理
  /memory              记忆管理
  /permission          权限管理
  /status, /s          显示状态信息
  /review              审查代码变更

输入 /help <命令名> 查看详细用法。
```

`/compact`不带参数就执行标准压缩，带参数可以指定保留重点，比如：`/compact 保留数据库相关内容`。如果当前上下文还不到 5000 token，直接提示 `无需压缩`

`/resume` 会话管理，不带任何参数时显示当前会话的概要，包括会话ID和消息数，`/session`看历史清单，找到后回车选择

`/memeory` 记忆管理命令，不带参数显示记忆概要，`/memory `显示记忆路径弹窗，修改ACODE.md，memory目录下的文档考Agent自动更新

`/permission`权限管理命令，不带参数时显示当前权限模式和规则数量，`/permission <模式>` 切换模式

`/status`显示当前综合状态信息，简写`/s`：

```
ACode 状态
─────────────
模式：default
Token：45,230 / 200,000（23%）
工具：6 个已启用
记忆：user 3 条，project 5 条
工作目录：/home/user/project
版本：
```

### local-ui

`/clear` 当前对话被保存到会话历史中，然后创建一个新会话

`/plan`切换到Plan Mode，不带参数就单纯切换模式，带参数则切换模式得同时把参数作为任务描述发送给 Agent。比如：`/plan 设计用户认证`，会切换到Plan Mode模式，并让 Agent 开始规划实现方案。

`/do` 切换回执行模式，如果上一轮`/plan`产出了一个计划，切换回执行模式后Agent开始按计划执行

### Agent

`/review`，输入后命令系统会把预设得代码审查prompt发送给Agent，分析当前`git diff`得变更。带参数时可以指定额外关注点，`/review 特别注意并发安全`

## 边界

只能执行预定义的、程序化的操作，所有的Handler都是硬编码在源码里的，改一个prompt就需要重新编译。用户无法自己添加新命令。

命令可以利用AI的能力和由外部贡献命令的能力放到后边的Skill系统中。

