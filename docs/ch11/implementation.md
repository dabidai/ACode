# ch11 使用说明与实施定位

配置按 `.acode/hooks.local.yaml` → `.acode/hooks.yaml` → `~/.acode/hooks.yaml` 顺序叠加，同一文件按声明顺序。修改后重启生效。显式 ID 在三处配置中必须唯一；未填写时派生为 `来源层:.acode/文件名#序号`。

```yaml
hooks:
  - id: project-background
    event: session_start
    once: true
    action:
      type: prompt
      message: 请先阅读项目的 ARCHITECTURE.md。

  - id: protect-lockfile
    event: pre_tool_use
    if:
      tool: WriteFile
      args.file_path: {regex: '(^|[/\\])package-lock\.json$'}
    reject: true
    action:
      type: prompt
      message: 请保留锁文件，通过包管理器更新依赖。

  - id: format-java
    event: post_tool_use
    if:
      all:
        - any: [{tool: WriteFile}, {tool: EditFile}]
        - args.file_path: {regex: '\.java$'}
    action:
      type: command
      command: mvn spotless:apply
      timeout: 1m
```

格式化命令仅是示例，需使用项目实际安装的格式化工具。命令与模型调用 Bash 共用权限规则；HTTP 使用权限工具名 `HookHttp`，内容字段为 URL。工具前后同步动作可以请求确认；会话/轮次事件及异步动作没有交互入口，遇到 ASK 会跳过并记日志。可在现有 permissions 配置中仅允许所需的固定命令或 URL。HTTP 不跟随重定向。

五个事件为 `session_start`、`turn_start`、`pre_tool_use`、`post_tool_use`、`turn_end`。轮次是一次用户输入到 exchange 结束；内部工具迭代不重复触发。取消不触发 turn_end。session_start 在恢复时也触发，once 标记在触发前读回。

条件支持标量相等、regex 的局部 find、glob 的全串路径匹配，以及 all/any/not。glob 的 `*` 不跨 `/`、`**` 跨目录、`?` 匹配单字符，反斜杠路径会归一；`**/` 按字面分隔符规则要求存在 `/`。缺失字段不匹配；条件递归超过 64 层或循环引用在加载时拒绝。

变量包括 `$EVENT`、`$TOOL_NAME`、`$FILE_PATH`、`$MESSAGE`、`$ERROR` 和 `$TOOL_ARGS.xxx`，未知变量展开为空。命令模板是 shell 文本，引用路径时按当前 shell 加引号。`$FILE_PATH` 优先取 file_path，再取 path。

命令默认超时 30s，输出最多保留 30000 字符及截断提示；HTTP 默认 POST、10s 超时、响应最多 4000 字符。timeout 支持正整数的 ms/s/m，裸整数按秒。HTTP 支持 GET/POST/PUT/DELETE。prompt 只进入下一请求，不进历史；agent 返回未实现失败，留待 ch12 接入。

reject 仅允许用于 pre_tool_use，async 不允许用于 pre_tool_use。只有成功动作才能产生拒绝；失败只写日志。同步 once 在执行后标记，异步 once 在派发时标记，失败也算已触发。once 随会话 JSONL 保存，/clear 和压缩保留，恢复不重发，新会话重新计算。

## 代码定位

| 范围 | 实现 | 测试 |
|---|---|---|
| 加载、校验、条件、变量 | HookLoader / HookConfig / HookCondition / HookContext / HookEvents | HookLoaderTest / HookConditionTest |
| 顺序、短路、once、后台隔离 | HookEngine / HookAction | HookEngineTest |
| 外部动作与权限 | HookActions / HookEngine.authorize | HookActionsTest |
| 标记保存及压缩 | SessionRecorder / SessionStore | HookSystemEndToEndTest |
| 工具、轮次、提醒 | StreamingToolExecutor / Agent / ExchangeRunner / Conversation | HookSystemEndToEndTest |
| 启动、恢复、退出 | ConversationController | HookControllerTest |

动作集中在 HookActions；HookAction 接口可以替换为包含子 Agent 的执行器。引擎使用自己管理的虚拟线程执行器，恢复或关闭时取消后台任务，并通过会话代次丢弃迟到提醒。

## 审核调整

- 修正原验收中 regex find 与“不匹配 echo rm -rf”的矛盾：局部命中应为 true。
- 派生 ID 统一包含来源层，显式 ID 跨来源检查重复。
- agent 占位统一为失败，失败不能拒绝工具。
- 多提醒入口使用独立方法名，避免旧代码传 null 时产生重载歧义。
- 轮次事件放在 Agent 工作线程，保持 Runner 的 UI 事件消费可响应。
- 不改 docs/ch11/spec.md 已确认的功能范围；根目录简版 spec 排除实现参数，细节集中在本说明和验收。

真实终端和真实 provider 验收尚未执行；自动化结果见 checklist.md 与构建日志。
