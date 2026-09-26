# ACode 阶段十：Skill 系统 — 验收清单

> 2026-09-26 实施验收记录。已验证项目勾选；未执行项目保留。⚑ 表示必须在真实终端或真实 provider 验证。测试使用临时项目与临时 Git 仓库，不在 ACode 仓库内运行示例提交。

## 具体约定

| 项 | 验收值 |
|---|---|
| 查找位置 | 项目 `.acode/skills/` > 用户 `~/.acode/skills/` > jar 内 `skills/` |
| 文件形态 | 单文件 `<name>.md`；目录 `<name>/SKILL.md`，可带 `scripts/`、`references/`、`assets/` |
| 名称 | `^[a-z0-9-]+$`；目录/文件名与 frontmatter `name` 一致 |
| 必填字段 | `name`、`description`；描述经单行化后入清单 |
| 可选字段 | `allowedTools` 为工具名列表，缺省空；`model` 为非空字符串；`mode` 缺省 `inline`；`context` 缺省 `full` |
| 枚举 | `mode`: `inline`/`fork`；`context`: `full`/`recent`/`none`；`recent` 预留为最近 5 条 |
| 参数 | `$ARGUMENTS` 最多出现一次；无参数时删掉占位行 |
| 加载入口 | 工具名 `LoadSkill`，必填 Skill 名；`/skill` 无参数等价于 list |
| 多 Skill 工具范围 | 非空白名单取交集；空名单不增加限制；`LoadSkill` 作为控制工具仍可见，Plan 模式与既有权限检查继续生效 |
| 多 Skill 模型选择 | 最近一次成功激活且声明 `model` 的 Skill 决定请求模型；清空/压缩后回到会话默认值 |
| 内置 Skill | `commit` 与 `test`，均为 `inline`；jar 清单列出两者 |
| 提交指引 | conventional commit；标题英文且不超过 72 字符；超过 10 个变更文件建议拆分；禁 `git add -A` |
| 占位结果 | 成功：`Skill "commit" loaded.`；重复：`Skill "commit" is already in context.`；不存在：`Skill "X" not found.` |
| 版本去重 | 同一来源且正文未变为重复；正文变化后再次调用追加新版本并标明取代旧版本；解析失败回退仍按旧版本去重 |
| 失效提醒 | 列出原激活名称，含「已失效，如需继续请重新加载」；只提示一次 |

## T1 定义与解析

- [x] 给定合法 frontmatter 和正文，解析结果包含六个字段及原始正文；纯正文文件、缺失 `name` 或 `description` 均报含字段名的错误。
- [x] `Commit`、`my_skill`、文件名与 `name` 不一致均解析失败；合法的 `my-skill` 通过。
- [x] `mode`、`context` 的每个合法值都通过；`detached` 或未知 context 被拒绝；`inline` 携带 context 可解析但不影响执行。
- [x] `allowedTools` 非列表、`model` 空字符串、重复 `$ARGUMENTS` 均被拒绝；缺省值与上表一致。
- [x] 单测直接构造解析器，不需终端、用户目录或网络。

## T2 三层发现与资源

- [x] 三层均有同名有效定义时选项目级；移走项目级后选用户级；再次移走后选内置级。
- [x] 高优先级定义的 YAML 错误时记录一条可定位日志，低优先级有效同名定义仍可发现；其他 Skill 正常加载。
- [x] 同层的单文件与目录型重名时使用明确的固定顺序，重复扫描结果相同；目录缺 `SKILL.md` 被跳过。
- [x] 从构建后的 `target/acode.jar` 读取资源索引并找到 `commit`、`test`；项目缺用户目录时正常启动。
- [x] 已发现文件改动后下一次读取用新正文；改成坏 YAML 时只回退同一来源的最近有效版本；删除高优先级文件并 reload 后使用下一层，旧缓存不串层。

## T3 运行态

- [x] 点名不存在或已禁用的工具时激活失败，错误包含 Skill 名和工具名；激活名单、模型和工具范围均不变。
- [x] 有参数时 `$ARGUMENTS` 被替换；无参数时整行删除；同版本且参数相同的重复加载使历史正文增加数为 0，参数或正文改变则追加新调用内容。
- [x] 激活白名单 `A,B` 后再激活 `B,C`，下一轮业务工具只剩 `B`；空白名单不扩大该范围，控制工具 `LoadSkill` 仍可用。
- [x] 两个 Skill 分别声明不同模型时，后成功激活的模型用于下一请求；无 model 的新 Skill 不改变当前覆盖值。
- [x] Skill 执行中修改原文件，不改变该次执行的定义与工具快照；下次调用同名 Skill 时追加新版本并明确取代旧正文，下一请求使用新版本的范围与模型。

## T4 系统提示索引

- [x] 提示索引按名称稳定排序，每项只有 `name: description`，不含正文和本地路径。
- [x] 描述中注入换行与伪造指令，渲染后仍只占清单中的一行，系统提示中不出现额外段落。
- [x] 在排除全部三层 Skill 的测试夹具中，系统提示与改动前基线逐字节相同；生产默认存在内置 Skill 时显示两项。
- [x] reload 后新增、删除、改描述的结果在下一轮系统提示中同步体现。

## T5 动态命令

- [x] `/help` 与 Tab 补全出现 `/commit`、`/test`，描述末尾为 ` [skill]`；`/skill` 可见且属于本地命令。
- [x] `help`、`quit`、`h` 等与内置名或别名冲突的 Skill 不注册斜杠命令，启动告警含冲突名；它仍可通过 `LoadSkill` 按名查找。
- [x] `/skill` 与 `/skill list` 输出相同名称、描述、来源；`/skill info commit` 显示完整元信息及来源，内置资源不伪装成本地文件路径。
- [x] `/skill reload` 后新增、删除、改名及覆盖变化的命令与帮助、补全同步；只移除 Skill 自己注册的命令，`/review` 仍可用。
- [x] `/skill list`、`info`、`reload` 不增加 provider 请求数；未知子命令返回用法提示。

## T6 工具边界

- [x] 无激活 Skill 时，正常模式业务工具列表与基线一致；Plan 模式只列只读工具和既有计划退出入口。
- [x] 白名单激活后，假 provider 收到的下一请求只列交集中的业务工具与 `LoadSkill`；`ToolRegistry.availableList()` 前后相同。
- [x] 直接构造白名单外的工具调用，即使该工具存在于注册表，执行结果也是拒绝且实际执行次数为 0。
- [x] Plan 模式激活含 `Bash` 的 Skill 后，`Bash` 不出现在请求中，直接调用也不执行；普通模式允许工具仍经过现有权限判断。
- [x] MCP 工具在连接注册完成后才参与依赖检查；已禁用工具不可通过声明名字重新启用。

## T7 加载工具与消息顺序

- [x] 一轮调用 `LoadSkill` 后历史依次出现 assistant 的 tool_use、同批全部 tool_result 所在的 user 消息、独立的 Skill 正文 user 消息；`sanitize()` 后顺序及配对不变。
- [x] 同批含 `LoadSkill` 和其他只读工具时，全部结果先写入一条结果消息；成功正文按调用顺序随后写入，不与结果交错。
- [x] 不存在或 fork 未实现的 Skill 返回错误结果，正文增加数为 0，激活状态不变。
- [x] 取消工具批次或切换历史代次后，迟到的加载结果不追加正文、不改变工具范围或模型。
- [x] 成功加载后的下一次请求包含正文，并使用新的工具范围与模型。

## T8 内置 Skill

- [x] jar 中存在 `skills/index.txt`、`skills/commit/SKILL.md`、`skills/test/SKILL.md`，两者均不依赖 jar 内可执行脚本。
- [x] commit 正文要求先看 status、未暂存和已暂存 diff，再逐个 add 指定文件；明确排除敏感文件、禁止 `git add -A`，超过 10 个文件建议拆分。
- [x] test 正文先检测构建方式；Maven 项目运行 `mvn test`，对失败比较断言、期望行为与相关代码；没有覆盖率报告时输出「未测量」，不捏造百分比。
- [x] 项目级或用户级同名定义覆盖内置定义，reload 后索引、命令和正文来源一致。

## T9 命令与 fork

- [x] `/commit 额外说明` 经现有对话入口启动 Agent，独立 user 正文含参数，原始命令字符串不作为另一条普通用户消息发送。
- [x] `/commit` 热读取新正文并追加标明取代旧版的消息；文件变坏时使用最近有效版本并给出可定位日志；没有有效版本时不启动 Agent。
- [x] 命令和模型两条入口调用 `mode: fork` 都返回含 `mode: fork` 与「未实现，阶段十二接入」语义的结果，不注入正文或激活状态；模型入口的错误 tool_result 正常留在历史中。
- [x] `SkillForkHost` 只定义委托契约，无当前阶段的伪实现。

## T10 生命周期

- [x] `/clear` 后激活名单为空，业务工具与模型回默认；再次 `LoadSkill` 会重新注入正文。
- [x] 压缩成功并重建历史后名单与覆盖状态清空；下一轮只有一次失效提醒，列出压缩前的 Skill 名；压缩无变更时状态不丢。
- [x] `--resume`/`/resume` 恢复旧历史时从未激活状态开始；再次加载可重复注入且不崩溃。
- [x] reload 与执行并发时，当前轮使用旧快照，下一次查找用新快照；命令与提示索引不存在半更新状态。

## T11 接入主流程

- [x] 启动、MCP 注册、仓库扫描、命令注册与系统提示构建按依赖顺序完成；启动单个坏 Skill 不妨碍进入输入循环。
- [x] README 阶段十状态与命令表在功能交付后更新；`docs/manual-test.md` 有阶段十手测步骤，未实跑的 ⚑ 项保持未勾选。
- [x] 现有 `/review`、`/clear`、`/compact`、Plan 模式和无 Skill 测试夹具的行为无回归。

## T12 端到端验证

- [x] 假 provider + 临时项目：自然语言请求触发 `LoadSkill`，下一请求能看到正确的正文、结果配对、工具交集和模型覆盖。
- [x] 临时项目：`/skill reload` 新增/删除文件后命令、帮助和索引同步；`/clear` 后重新加载；fork 错误后仍可继续对话。
- [x] 运行 JDK 21 下定向测试与 `mvn test` 全量测试，记录总数、失败数、跳过数与运行环境；全量失败时保持该项未勾选。
- [ ] ⚑ 在临时 Git 仓库中调用 `/commit`，核对提交文件集合、message 格式及权限确认记录。
- [ ] ⚑ 在真实终端调用 `/test`，分别验证全绿、代码错误和测试断言错误；覆盖率有报告时才出现数值。
- [ ] ⚑ 自然语言触发 Skill、修改文件后再次调用、Plan 模式下的工具边界均在真实 provider 上观察通过。

## 2026-09-26 实施记录

实现文件定位见 `implementation.md`。自动化覆盖分组：
- `SkillParserTest`：元信息、枚举、参数、重复键及安全 YAML。
- `SkillRepositoryTest`：三层覆盖、坏文件降级、热读取回退、同层顺序、内置工具名与索引隔离。
- `SkillRuntimeTest`：工具交集、模型、版本、失效、迟到提交与后注册工具依赖。
- `SkillEndToEndTest`：假 provider 批次消息、执行拒绝、Plan、取消/重建、权限 deny。
- `SkillCommandTest`：真实 Controller/Dispatcher/Runner 路径的命令、参数、重载、清空、冲突与 review 回归。
- `SkillReloadTest`：并发查找等待命令及提示索引发布。

⚑ 项未调用真实 provider、未在真实终端实跑，保持未勾选。恢复专项使用打包后的真实 Controller 的 --resume 共用加载路径验证；/resume 菜单沿用既有命令回归。



最终验证环境：Windows 11 amd64，Eclipse Adoptium JDK 21.0.11，Maven 3.9.9。

- Skill 定向测试：16 tests，0 failures，0 errors，0 skipped。
- 最终 `mvn package`（包含全量测试）：1155 tests，0 failures，0 errors，1 skipped；BUILD SUCCESS。
- `target/acode.jar` 已更新；`target/ch10-final-validation.log` 保存完整构建与测试记录。
- `SkillJarSmoke` 仅使用打包后的 jar，在临时项目/临时用户目录读取内置索引和两份正文，并走真实 Controller 恢复路径，验证模型覆盖撤销及正文可重新注入；输出 PASS。
- 冒烟脚本：`.planning/ch10-implementation/SkillJarSmoke.java`；编译输出位于 `target/skill-jar-smoke`。
- `git diff --check` 无空白错误。
