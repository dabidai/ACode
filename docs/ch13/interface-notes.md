# ch13 接口与边界

## 管理器
`WorktreeManager(Path, AppConfig)` 绑定项目根；`create(name)` 为手动来源，`createTemporary(name)` 为临时来源。
`enter`/`exit` 操作项目级当前会话；`workingDirectory` 返回当前工具目录；`restore` 只在 `--resume` 启动执行。
`list` 返回带当前标记、状态的视图，`remove(name, force)` 始终核验 Git 注册及实际分支，当前会话和锁定工作树不可强删。
`cleanup(name)` 只清理确认干净且没有本地独有提交的临时工作树；`prune()` 额外检查过期时间。
`drainWarnings()` 供命令前台汇总输出，后台不直接写终端。

## 消费者
- BuiltinCommands 的新增注册重载接收管理器 supplier；CommandContext 继续保持原项目根契约。
- ExchangeRunner 的目录 supplier 每次创建 ToolContext 时求值，避免第一次 exchange 缓存目录。
- PermissionChecker 的三参重载以实际工具目录解析 file_path，再交原沙箱判断；规则匹配保留原参数。
- ConversationController 更新工作目录变化后的模型环境提示，Skill fork 与 Hook agent 使用同一目录来源。
- MCP 服务和 Hook command 的进程目录保持启动项目根。本章不提供它们的动态迁移。

## 持久化与删除
元数据只记录来源和基准，Git 注册与公共 Git 目录仍为身份依据。原子替换 registry/session 文件；不使用数据库。
Windows Git 会沿 junction 递归删除内容，移除前必须先列出工作树内所有链接/重定向节点，非强制操作遇链接保留，强制操作先断开链接再交 Git 删除。
若断链后 Git 删除失败，目录保留但链接可能已断开；警告须由命令输出，用户可重新配置依赖。不会递归清理创建失败留下的身份不明目录。

## 后续章节
ch14 可调用 createTemporary，但不能仅凭临时命名判断所有权；手动同名形态不参与自动清理。
当前活动保护为单个项目会话记录与管理器串行锁，不支持多 ACode 进程同时管理或多个活跃队员租约。后续 Teams 必须先引入每个队员的活动租约，不能直接复用当前会话字段作为并行活动集合。
