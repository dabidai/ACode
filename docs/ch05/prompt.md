本章需要做什么？
上一章把 Agent Loop 跑起来了。

这一章要形成一套完整的 Prompt 工程体系。做完之后，ACode 的 System Prompt 会有清晰的七模块结构，组装管线会按七源到三通道的规则正确分发信息，Prompt Cache 会真正命中并把每轮的 input token 成本打下来。Plan Mode 的指令也从硬拼接改成走 system-reminder 通道，不再每次都让缓存失效。

具体要新增和重构这些东西：

- 七模块 System Prompt 组装器 ：Section 结构体 + Priority 排序，IdentitySection / BehaviorSection / ToolUsageSection / CodeQualitySection / SecuritySection / TaskPatternSection / OutputStyleSection

- Prompt 组装管线 ：assembleAPIPayload 函数把七类信息分发到 system / messages / tools 三通道

- 环境上下文重构 ：从 ch04 的 system 通道挪到 messages 通道首条 user 消息，避免污染 cache

- 工具描述强化 ：ReadFile / EditFile / WriteFile / Bash / Glob / Grep 的 description 字段补齐用法、优先级、配合关系

- Prompt Cache 控制 ：system 通道整体设 `cache_control: ephemeral`，tools 通道同样设，并从 API 返回 usage 验证命中

- system-reminder 注入机制 ：role=user + `<system-reminder>` XML 标签包裹的消息，注入位置区分会话级与轮次级

- Plan Mode 改造 ：Plan Mode 文本不再拼进 System Prompt，改成按轮次注入 system-reminder（第 1 轮完整版，每 5 轮重复一次）

- 典型场景评估脚本 ：5 个定性评估场景，方便每次改 prompt 后做人工对照

这章 不做 ：MEWCODE.md 项目指令文件加载（章节 7）、自动记忆系统（章节 9）、真实 MCP Server 接入（章节 6）、LLM-as-judge 自动评估管线（依然是人工跑场景对比）。

我的初步想法：
- 把全局指令按职责拆成多个模块（身份、行为、工具使用、代码规范、安全边界、任务模式、输出风格），用优先级排序的方式拼装，便于后续章节插入新模块。
- 区分稳定内容和变化内容：稳定的全局指令和工具描述走可缓存通道，变化的环境信息、对话历史、动态补充走对话通道。
- 把环境信息（工作目录、操作系统、时间、Git 状态等）从全局指令里搬出来，作为对话首条系统级补充消息，避免环境每次变化都让缓存失效。
- 在工具自身描述和全局指令里双重强化关键规则,覆盖模型的默认偏好(例如优先调用专用工具而不是通用 shell 命令、编辑前必须先读)。
- 引入一种带特殊标签的对话消息形式,在运行中向模型注入补充指令(外部工具上线、当前模式提醒、温和提示),既不污染缓存也不会被模型当作用户输入回复。
- 把会话级开关功能(如规划模式)的指令从全局指令里拆出来按轮次动态注入,用首轮完整、间隔轮次重复完整、其余轮次精简的节奏控制注入频率。
- 通过解析 API 返回的缓存命中字段验证缓存策略是否真的生效;准备一组典型行为场景做人工对比,作为本章的定性评估手段。

如果存在不确定的信息，可以参考http://localhost/#/chapter-5-1