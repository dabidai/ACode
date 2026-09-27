# ch12 测试复审与补充

## 复审发现

上一轮已覆盖核心成功链路，但存在这些欠缺：

1. Fork 前缀仅比较文本，无法发现 tool_use 参数被浅复制、历史块丢失或错误配对。
2. 取消测试只确认 provider 收到中断，没有验证工具尚在清理时 runner 是否提前返回，以及主会话能否恢复。
3. 授权测试仅比较批准/拒绝同一次调用，没有混入不同目标文件或不同工具的越界请求。
4. 父级 Skill 范围此前只在 runner 层验证，缺少真实 Controller 装配路径的模型继承与能力上限断言。
5. 子 Hook 只覆盖提示注入，没有证明外部动作遵循子权限而非父 bypass。
6. 额外记忆根用例处在系统临时目录下；该目录本来就被沙箱允许，可能假通过。
7. 同批兄弟任务隔离、重试中间文本、空最终回复、触顶不执行、配置覆盖等边缘行为缺少独立断言。

## 本轮新增 20 个测试

| 范围 | 新增数量 | 可观测断言 | 文件 |
|---|---:|---|---|
| 运行时边界 | 7 | 完整消息 JSON 前缀一致、嵌套参数修改不影响父历史；失败尝试的文本和工具不复用；空最终回复保持空；触顶工具执行次数；工具异常回填恢复；无权限检查器不发请求；取消等待实际清理且后续工具不执行 | SubAgentBoundaryTest |
| 权限负例 | 3 | 同批不同路径/工具拒绝；deny 规则覆盖 bypass 与显式授权；父/子四种模式与三种权限共 48 种组合 | SubAgentPermissionTest |
| Controller 主流程 | 4 | 活动 Skill 模型和工具上限传递；plan 中 slash fork 不暴露写工具；子 provider 失败回填且可继续对话；取消派发后可开启下一 exchange | AgentToolIntegrationTest |
| 注册表 | 1 | 坏的项目覆盖定义告警后保留有效用户定义 | AgentRegistryTest |
| 跨章适配 | 3 | recent 截断后去孤立结果并保留完整工具对；Hook 变量展开及空内容失败状态；子 Hook 外部动作使用子权限 | SubAgentAdaptersTest |
| 同批子任务 | 1 | 兄弟历史不串入、结果按 tool_use id 和声明顺序回填、父历史无私有中间文本 | SubAgentEndToEndTest |
| 配置 | 1 | 项目 false 覆盖用户 true，项目缺省继承用户值 | ConfigLoaderTest |

另外强化两项原测试：

- 额外记忆根用例临时缩小默认临时目录，并加入“不配置额外根必须拒绝”的负对照；finally 恢复系统属性。
- 原端到端派发用例注入父子不同 token 用量，断言父事件队列只收到父用量。

Java 测试均在 `src/test/java/com/acode/subagent/` 下，配置用例位于 `src/test/java/com/acode/config/ConfigLoaderTest.java`。Controller 测试包为 com.acode，以直接验证实际装配。涉及取消时用 latch 控制进入、清理和释放，finally 释放等待并回收线程；不执行真实危险命令。

## 验证结果

首批新增用例及关联测试通过；最终 `mvn test` 为 1256 tests、0 failures、0 errors、1 skipped，`git diff --check` 通过。本轮未修改生产代码；补强测试未复现新的运行时缺陷。

## 仍需真实环境验证

自动化使用 FakeProvider、受控工具和无终端 Controller，不能证明真实模型遵循 Scope/字数要求，也不能替代终端卡片视觉、raw-mode Ctrl+C 键盘输入、网络断开时实际 provider 的资源回收测试。这些仍在 manual-test.md 中保持未勾选。

本轮没有引入覆盖率插件或把覆盖率百分比作为完成标准；优先断言实际调用次数、请求内容、错误标志和副作用边界。
