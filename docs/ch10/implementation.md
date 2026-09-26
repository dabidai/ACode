# Skill 使用与实现说明

项目 `.acode/skills/` 覆盖用户 `~/.acode/skills/`，再覆盖 jar 内置定义。同一层目录型优先于单文件；坏定义会给出来源告警，不遮住较低层的有效定义。

保存为 `.acode/skills/explain/SKILL.md`，也可以保存为 `.acode/skills/explain.md`：

```markdown
---
name: explain
description: Explain project code without modifying files
allowedTools: [ReadFile, Glob, Grep]
mode: inline
context: full
---
先阅读项目说明，再定位相关实现，解释执行路径与边界情况。
用户关注点：$ARGUMENTS
```

执行 `/skill reload` 后，`/help` 和 Tab 补全会出现 `/explain`。输入 `/explain 模型请求如何构建`，或用自然语言提出匹配任务，让模型调用 `LoadSkill`。`/skill info explain` 显示元信息、来源和正文。命令名与内置命令或别名冲突时保留原命令，该 Skill 仍可由 `LoadSkill` 调用。

正文在独立 user 消息中进入历史，系统提示仅列名称和单行描述。目录内的 `scripts/`、`references/`、`assets/` 不会自动读取；正文消息提供资源基目录，模型仍需通过普通工具和权限检查访问资源。

`allowedTools` 缺省为空，不额外限制工具。多个已激活 Skill 的非空名单取交集，`LoadSkill` 保持为控制入口；Plan 模式再与只读集合取交集。这里使用项目真实工具名，例如 `ReadFile`、`WriteFile`、`EditFile`、`Bash`、`Glob`、`Grep`。声明不存在或已禁用工具会拒绝激活，MCP 工具连接注册后才能作为依赖使用。

可选 `model` 只覆盖请求，最近成功激活且声明模型的 Skill 生效；会话默认模型与页脚默认模型不变。模型是否被远端服务支持，仍由 provider 响应确定。

已发现正文会在调用时重新读取；同来源、定义及参数不变时不重复注入。改变正文或参数会追加标明取代旧版的新消息。读取失败使用同一来源最近有效快照并告警。新增、删除、重命名及描述索引更新需要 `/skill reload`；reload 不撤销已经激活的快照，下一次调用才切换版本。

`$ARGUMENTS` 最多出现一次；没有参数时整行删除。`/clear`、历史压缩重建、恢复会话会撤销激活范围及模型覆盖；压缩后显示一次失效提醒。恢复的旧正文不会用于重建激活身份，再次调用允许重新注入。

`mode: fork` 与 `context: full|recent|none` 已能解析，`recent` 预留最近 5 条语义。当前调用 fork 明确返回“未实现，阶段十二接入”；`SkillForkHost` 仅保留独立执行委托接口。

## 实现定位

- `skill/SkillParser`、`SkillRepository`：安全 YAML 解析、稳定发现顺序、资源清单和按来源缓存。
- `skill/SkillRuntime`：候选、版本去重、工具范围、模型及生命周期；`SkillManager`：动态命令和索引发布。
- `Agent.executeTools`：候选暂存，完整一批结果写完后按调用顺序提交正文；取消、历史代次与重建代数保护。
- `StreamingToolExecutor.runCall`：先检查本轮请求工具集合，再执行既有权限检查。
- `ExchangeRunner.runPrepared`：命令候选提交与正文写入使用同一历史锁。
- `ConversationController.start`：MCP 连接后装配 Skills，随后恢复会话和构建提示。

自动化证据与未实跑的真机项目见同目录 `checklist.md`。
