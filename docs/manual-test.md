# ACode 阶段二：工具调用 — 手动验收步骤

> 对应 checklist.md 中未用单测覆盖的项：T12 的 Ctrl+C 中断，以及 T14 全部端到端项。
> 需要真实 Anthropic API 密钥；未运行手动验收前，checklist 中相应项保持未勾选。

## 前置

1. 配置密钥：内置默认配置已随 jar 打包（`src/main/resources/config.yaml`），可直接启动；在 `~/.acode/config.yaml`（全局）或 `.acode/config.yaml`（项目级）写入真实 `api_key` 与 `base_url` 覆盖默认值。
2. 打包：`mvn package`（产物为 `target/acode.jar`）；源码改动后必须重新打包再启动，否则跑的是旧 jar、界面仍是旧版。
3. 启动：`java -jar target/acode.jar`；恢复上次会话：`java -jar target/acode.jar --resume`。

## M1 Ctrl+C 中断工具执行（T12）

1. 提问：「用 Bash 执行 `sleep 30`，然后告诉我结果」。
2. 屏幕出现工具卡片，状态为「进行中」，模型在等待命令执行。
3. 在工具执行期间按 Ctrl+C。
4. 预期：工具被中断，出现「已中断工具执行」提示；不出现第二轮回复；可直接输入下一条问题，程序未卡死。

## M2 读文件并总结（T14-1）

1. 提问：「读 `pom.xml` 并总结用到了哪些依赖」。
2. 预期：屏幕出现工具卡片（ReadFile + 参数摘要 file_path="pom.xml"）→ 卡片状态从「进行中」变为「完成」→ 最终回复引用 `pom.xml` 真实依赖。

## M3 各工具逐一调用（T14-2）

分别让模型调用以下工具各至少一次，每次预期卡片状态为「完成」，结果与真实执行一致：

- WriteFile：请写一个新文件 `tmp-write-test.txt` 内容为「hello」
- EditFile：请把 `tmp-write-test.txt` 里的 hello 改成 world
- Bash：请执行 `echo acode-bash-test`
- Glob：请找出 `**/*.java` 的所有文件
- Grep：请搜索代码里所有 `onToolUse` 出现的位置

检查：写文件后磁盘内容与要求一致；Bash 输出含 `acode-bash-test`；Glob/Grep 结果与真实文件系统一致。

## M4 调用不存在的工具（T14-3）

1. 提问：「用 `SendEmail` 工具给 x@y.com 发封邮件」。
2. 预期：卡片状态为「失败」，模型最终回复能说明失败原因（is_error 已回传）。

## M5 会话持久化与恢复（T14-4）

1. 新建会话，问「读 `pom.xml`」并等工具执行完成，正常退出程序。
2. `java -jar target/acode.jar --resume` 恢复该会话。
3. 预期：历史中文本块照常显示，工具块显示为一行摘要（如「〔工具调用 ReadFile〕」）。
4. 继续问「刚才读的文件里第一个依赖是什么」→ 预期模型能引用恢复前的工具结果。

---

# ACode 阶段三：Agent 循环 — 手动验收步骤

> 对应 docs/ch04/checklist.md 的 T12 端到端验收项。需要真实 API 密钥，
> anthropic 与 openai 双后端各过一遍；未运行手动验收前，checklist 中相应项保持未勾选。

## 前置

1. 配置密钥：内置默认配置已随 jar 打包（`src/main/resources/config.yaml`），可直接启动；在 `~/.acode/config.yaml`（全局）或 `.acode/config.yaml`（项目级）写入真实 `api_key` 与 `base_url` 覆盖默认值（protocol 分别设为 anthropic / openai 各跑一遍）。
2. 打包：`mvn package`（产物为 `target/acode.jar`）；源码改动后必须重新打包再启动，否则跑的是旧 jar、界面仍是旧版。
3. 启动：`java -jar target/acode.jar`；恢复上次会话：`java -jar target/acode.jar --resume`。

## A1 多轮工具链自动闭环（双后端）

1. 提问：「读 `pom.xml` 总结依赖，然后用 Bash 跑 `mvn -q compile`，再告诉我结果」。
2. 预期：屏幕出现 ≥2 轮工具卡片（ReadFile → Bash），卡片逐轮从「进行中」变为「完成」，
   模型自主连续执行到自然收尾，最终回复引用真实执行结果，全程无需再次输入。

## A2 流式输出中 Ctrl+C

1. 提一个需要长回答的问题，在模型流式输出途中按 Ctrl+C。
2. 预期：循环立即结束、输出「已中断」、屏幕无残影，可继续输入新问题。

## A3 工具执行中 Ctrl+C

1. 提问：「用 Bash 执行 `sleep 30`」。
2. 工具卡片「进行中」期间按 Ctrl+C。
3. 预期：输出「已中断」；随后正常退出，`--resume` 恢复该会话继续对话不报错（历史无悬空工具调用）。

## A4 /plan 规划模式 → 交付 → /do 执行

1. 输入 `/plan` → 预期提示已进入规划模式。
2. 提一个多步需求（如「重构某个类并保持对外接口不变」）。
3. 预期：模型只用读工具（ReadFile/Grep/Glob）探索，最终调用 ExitPlanMode 交付计划；
   计划文件出现在 `.acode/plans/` 且内容为完整计划；界面提示「输入 /do 退出 plan 模式开始执行」。
4. 输入 `/do` → 预期提示已退出规划模式；再次提问 → 写工具（WriteFile/EditFile）恢复下发，可正常修改文件。

## A5 max_iterations 触顶

1. 把配置 `max_iterations` 改为 `2`，重启后提一个多步任务。
2. 预期：达到第 2 轮后停止，界面提示「达到最大轮数」，已完成步骤的工具结果保留在会话中。

## A6 resume 含工具轮次的会话

1. 走一遍 A1 的多轮工具链后正常退出。
2. `java -jar target/acode.jar --resume` 恢复该会话。
3. 预期：工具块显示为一行摘要；继续对话时模型能引用恢复前的工具结果。

## A7 /help 文案

1. 输入 `/help`。
2. 预期：帮助文本含 `/plan` 与 `/do` 两行说明。

---

# ACode 阶段四：终端渲染改造（主屏可复制）— 手动验收步骤

> 对应 docs/ch04/followups.md §1 的端到端验收项（原 ch00/checklist.md T10）。需要真实 API 密钥。
> 核心变化：输出走原生 scrollback（可划选复制、可滚轮回看），不再用备用屏幕/自绘滚动条/鼠标捕获。
> 流式输出为纯追加式：完整行出现即提交进回滚、永不再改（无任何光标重绘操作），未完成行等换行到达后显示；
> 工具调用显示「⏳ 调用工具…」与终态行两条静态记录。因此任何终端宽度/字符宽度差异都不会造成错位。

## 前置

1. 配置密钥：内置默认配置已随 jar 打包（`src/main/resources/config.yaml`），可直接启动；在 `~/.acode/config.yaml`（全局）或 `.acode/config.yaml`（项目级）写入真实 `api_key` 与 `base_url` 覆盖默认值。
2. 打包：`mvn package`（产物为 `target/acode.jar`）；源码改动后必须重新打包再启动，否则跑的是旧 jar、界面仍是旧版。
3. 启动：`java -jar target/acode.jar`；恢复上次会话：`java -jar target/acode.jar --resume`。

## R1 划选复制历史输出

1. 正常提问并等一轮完整回复（含代码块更佳）。
2. 在 Windows Terminal 中用鼠标从历史输出上直接划选一段文本，Ctrl+C 复制。
3. 预期：选择高亮正常、不被后续重绘打断；粘贴出的内容与屏幕显示一致（含代码块与普通文本）。

## R2 滚轮滚动回看

1. 累计若干轮对话产生超过一屏的内容。
2. 鼠标滚轮向上滚动回看本会话全部历史。
3. 预期：历史可回滚（原生 scrollback），滚回底部后输入提示正常、可继续输入。

## R3 超屏长流式

1. 提一个需要长回答的问题，让流式输出超过一屏。
2. 预期：完整行逐行出现、内容连贯无重叠无字符丢失；长段落（未含换行的当前行）等它写完才显示；
   顶部已滚入回滚、可滚轮回看，全程无幻影空行与残留。

## R4 流式中途划选

1. 流式输出过程中，用鼠标划选屏幕上一段已输出的文本。
2. 预期：选择保持、不被后续输出打断（后续行只追加在下方，不触碰已选区域）。

## R5 resize 回流

1. 正常对话中拖动窗口改变尺寸。
2. 预期：内容原生回流（自动重新折行）、无清屏、无错位，输入仍可用；
   流式输出中途拖拽时已输出行不重绘、不错位，后续行按新宽度继续追加。

## R6 /resume 菜单

1. 输入 `/resume`。
2. ↑/↓ 选择会话、回车加载、Esc 取消各试一遍。
3. 预期：菜单在屏幕底部覆盖显示、不进入回滚（滚动回看时搜不到菜单行）；
   选定后菜单消失、会话历史以追加方式显示；Esc 取消后菜单消失、原内容保留。

## R7 流式中 Ctrl+C

1. 提一个需要长回答的问题，流式输出途中按 Ctrl+C。
2. 预期：输出「已中断」、可继续输入新问题，屏幕无残影。

## R8 输入无回显双写

1. 输入一句话并回车提交。
2. 预期：提交后输入行被擦除、屏幕只出现一行「● 输入」，无「输入原文 + ● 输入」双行。

## R9 多行输入与粘贴

1. Shift+Enter 输入多行内容后回车提交。
2. 粘贴一段 20 行代码后回车提交。
3. 预期：多行输入与粘贴内容保留原样（含缩进/换行），提交后正常进入对话。

## R10 退出后终端状态

1. 对话后输入 `/quit`（或 Ctrl+C）退出。
2. 预期：回到 shell 后提示符正常、无残留转义序列或 raw 模式残留。

---

# ACode 阶段六：工具结果渲染（ch06）— 手动验收步骤

> 对应 docs/ch04/followups.md §3 的端到端项（原 ch06/checklist.md T6）。需要真实 provider 密钥；未运行手动验收前，checklist 相应项保持未勾选。

## M11 Bash 命令输出块

1. 提问：「用 Bash 执行 `echo hello`」。
2. 预期：屏上出现「● Bash(command="echo hello")」运行行（工具名青色）；执行完出现缩进输出块：
   ```
   ● Bash(command="echo hello")
     ⎿  hello
     ⎿  (XXms)
   ```
   输出块首行绿色 ⎿、耗时脚注灰色。

## M12 失败命令红色 ⎿

1. 提问：「用 Bash 执行 `ls /nonexistent`」。
2. 预期：命令退出非零，输出块首行呈红色 ⎿；命令与错误输出可读、不折叠成一行。

## M13 ReadFile 一行摘要

1. 让模型读取一个 5 行以上的文件（如 `ReadFile docs/manual-test.md`）。
2. 预期：ReadFile 卡片下方不再列出文件内容，只出一行摘要「  ⎿  返回 N 行（Lx-y）」+ 耗时脚注（如 `返回 246 行（L1-246）`），不刷屏。
3. 让模型读一个超 2000 行的文件 → 摘要末尾追加「（已截断）」。

## M14 长输出截断

1. 提问：「用 Bash 执行 `seq 1 1000`」。
2. 预期：输出块到 300 行截断，末尾出现「  ⎿  …（输出过长，已截断）」，不刷屏。

## M15 多工具一轮各自成块

1. 提问：「先读 hello.cpp，再执行 `mvn -q -DskipTests compile`」。
2. 预期：ReadFile 出「● ReadFile(...)」运行行 + 一行摘要「返回 N 行」+ 耗时；Bash 出「● Bash(...)」运行行 + 缩进输出块（命令输出原样）+ 耗时；前后可分辨。

## M16 拒绝/批准后 resume

1. 让模型执行一个 WriteFile；确认菜单选中「否」拒绝 → 模型收到失败结果并调整；随后再让模型执行另一个工具并选「是」批准。
2. 退出后 `java -jar target/acode.jar --resume`。
3. 预期：恢复的会话历史中工具结果为输出块形态（含 ⎿ 内容行与耗时脚注），可继续对话。

---

# ACode 阶段七：选择交互（↑↓ 菜单替换 y/n + AI 选择工具）— 手动验收步骤

> 对应 docs/ch04/followups.md §4 的端到端项（原 ch07/checklist.md T9）。需要真实 provider 密钥。
> 核心变化：确认执行不再用 `[y/n]` 行输入，改用 ↑↓ 选择菜单（`> 是` 反显默认选中）；新增 AskUser 工具让模型发起多选项单选菜单，选中结果回传模型。

## 前置

1. 配置密钥：在 `~/.acode/config.yaml`（全局）或 `.acode/config.yaml`（项目级）写入真实 `api_key` 与 `base_url` 覆盖默认值。
2. 打包：`mvn package`（产物为 `target/acode.jar`）；源码改动后必须重新打包再启动。
3. 启动：`java -jar target/acode.jar`；恢复上次会话：`java -jar target/acode.jar --resume`。

## S1 确认菜单（↑↓ 替换 y/n）

1. 提问：「写一个新文件 `tmp-choice.txt`，内容 hello」。
2. 预期：不再出现 `[y/n]` 行输入，弹出 ↑↓ 菜单：首行「要执行「WriteFile（...）」？」下方 `> 是`（反显、默认选中）与 `  否`。
3. ↑/↓ 移动高亮、Enter 批准 → 工具执行 + 出现「（已批准执行「WriteFile」）」；磁盘出现 `tmp-choice.txt`。
4. 再让模型写文件，菜单出现后按 Esc → 显示「（已取消）」、文件不创建、模型收到拒绝结果并调整。

## S2 AskUser 多选项菜单

1. 提问：「接下来用 AskUser 工具问我想先做哪个，给我 A/B/C 三个选项」。
2. 预期：出现多选项菜单（question 行 + `> A` 反显默认 + B/C），↑/↓ 选择、Enter 确认后出现「（已选择「B」）」；模型收到所选文本并继续。
3. 再触发一次 AskUser，菜单中按 Esc → 模型收到失败结果（含「取消」）并调整。

## S3 /resume 菜单回归

1. 输入 `/resume` → 菜单仍为 ↑/↓ 选择、回车加载、Esc 取消；选中箭头由 `▸` 改为 `>`（反显）。
2. 确认/选择菜单进出后回滚不污染：滚动回看搜不到菜单行；退出后终端状态正常（无残留转义序列）。

---

# ACode 阶段八：工具结果展示策略（只读摘要 + 写入 diff）— 手动验收步骤

> 对应 docs/ch04/followups.md §5 的端到端项（原 ch08/checklist.md T8）。需要真实 provider 密钥。
> 核心变化：ReadFile/Glob/Grep 成功不再列出内容，只出一行摘要 + 耗时；WriteFile/EditFile 出红绿 diff（`-` 红 / `+` 绿）；Bash 与 AskUser/ExitPlanMode 展示不变；失败一律照常显示错误。

## 前置

1. 配置密钥：在 `~/.acode/config.yaml`（全局）或 `.acode/config.yaml`（项目级）写入真实 `api_key` 与 `base_url` 覆盖默认值。
2. 打包：`mvn package`（产物为 `target/acode.jar`）；源码改动后必须重新打包再启动。
3. 启动：`java -jar target/acode.jar`；恢复上次会话：`java -jar target/acode.jar --resume`。

## C1 ReadFile 只出摘要行

1. 提问：「读 `pom.xml`」。
2. 预期：「● ReadFile(...)」卡片下方只有一行摘要「  ⎿  返回 N 行（Lx-y）」+ 耗时脚注，不再列出文件内容、不刷屏。
3. 读一个超 2000 行的文件 → 摘要末尾追加「（已截断）」。

## C2 WriteFile 红绿 diff

1. 让模型新建一个文件（内容多行）→ 摘要行下方全是绿色 `+ ` 行。
2. 让模型覆盖已有文件（改动其中一行）→ 出现红色 `- ` 旧行与绿色 `+ ` 新行对比。
3. 写入与旧内容完全相同 → 只有确认行，无 diff 行。

## C3 EditFile 替换段对比

1. 让模型用 EditFile 改一个已有文件（如把某标识符改名）→ 出现「- 旧文 / + 新文」红绿对比行；多行段按行拆分。

## C4 失败仍显示错误

1. 让模型读一个不存在的文件 → 卡片下方仍是红色错误行（不是摘要）。

## C5 Bash 展示不变

1. 提问：「用 Bash 执行 `seq 1 5`」→ 输出块原样显示命令输出（未被摘要化），Bash 行为与阶段六一致。

## C6 --resume 历史不受影响

1. 本会话发生一次 ReadFile（摘要行）与一次 WriteFile（diff）后退出，`--resume` 恢复 → 历史中工具结果仍是原渲染形态，摘要/diff 不丢失、不污染。

---

# ACode 阶段四：Prompt 工程体系 — 手动验收步骤

> 对应 docs/ch05/checklist.md 的端到端验收项与 docs/ch05/eval-scenarios.md 的 5 个评估场景。
> 需要真实 provider 密钥；场景对照以定性为准，缓存命中观察以脚注为准。
> 核心变化：会话级 system prompt（七模块英文）+ 环境 system-reminder 每轮注入（不进历史）；
> 每轮结束输出 usage 脚注行；plan 模式提醒改为英文 system-reminder、第 1/6/11 轮完整版。

## 前置

1. 配置密钥：在 `~/.acode/config.yaml`（全局）或 `.acode/config.yaml`（项目级）写入真实 `api_key` 与 `base_url` 覆盖默认值（anthropic / openai 各跑一遍）。
2. 打包：`mvn package`（产物为 `target/acode.jar`）；源码改动后必须重新打包再启动。
3. 启动：`java -jar target/acode.jar`；恢复上次会话：`java -jar target/acode.jar --resume`。

## P1 环境注入可见

1. 启动后提问「当前工作目录是什么」→ 预期模型答出真实工作目录（环境 system-reminder 已注入）；再问 Git 仓库状态（如「当前在哪个分支」）→ 答出真实分支（非 Git 仓库目录则答不出分支也不报错）。
2. 请求侧结构（可开 tee 看日志）：SYSTEM 为英文七模块提示词，其后一条环境消息带 `<system-reminder>` 标签与 `# Environment` 段落，均不进历史。
3. `/clear` 清空后再次提问 → 环境仍注入（不因清空历史丢失）。

## P2 usage 脚注

1. 执行多轮工具任务（如「读 `pom.xml` 并总结用到了哪些依赖」）。
2. 预期：每轮结束出现脚注行 `usage: in ... · cache_read ... · cache_write ... · out ...`。
3. 第 2 轮起（满足 provider 缓存条件时）`cache_read` > 0；若为 0，记录原因（模型/端点不支持、超 5 分钟 TTL 等），不作为代码失败唯一依据。

## P3 plan 模式提醒为 system-reminder

1. 输入 `/plan`，提问「规划一下给 ACode 新增一个 `/hello` 命令」。
2. 预期：模型只做只读探索（读类 + ExitPlanMode 工具），多轮规划时提醒为英文、尾插、带 `<system-reminder>` 标签，第 1、6、11 轮为完整版、其余为稀疏版。
3. 最终调用 ExitPlanMode 交付，计划落盘 `.acode/plans/`；`/do` 退出规划模式开始执行。

## P4 评估场景人工对照

1. 按 docs/ch05/eval-scenarios.md 的 5 个场景逐一执行，逐条对照「输入示例 / 期望行为 / 对照判据」。
2. 每次场景后看脚注 `cache_read`，记录缓存命中情况（eval-scenarios.md 附录）。

# ACode 阶段五：权限系统 — 手动验收步骤

## 前置

- 构建：`mvn -DskipTests package`（构建环境 `JAVA_HOME=D:\java\jdk21`），运行 `java -jar target/acode.jar`。
- 默认权限模式来自 `.acode/config.yaml` 的 `permission_mode`；未配置时按 default。
- 每次切档用 `/permission-mode <模式>`（即时生效、不写回 config）。

## PERM1 default 模式：读不弹、写/命令弹三选一

1. 进入会话后先 `ReadFile` 一个项目内文件（或让它读 `.acode/config.yaml`）。
2. 预期：`ReadFile`/`Glob`/`Grep` 直接执行、**无确认弹窗**。
3. 再触发一次 `WriteFile` 或 `Bash`（例如问「把 hello.txt 内容追加一行」）。
4. 预期：弹出 **「放行 / 始终允许 / 拒绝」三选一**菜单；选「放行」执行、选「拒绝」模型收到失败结果并换策略继续。

## PERM2 危险命令硬拦截（黑名单最高优先）

1. 让模型执行 `rm -rf /`（可直接在消息里写「执行 rm -rf /」）。
2. 预期：即使 default 下 Bash 会弹窗，`rm -rf /` 也**不弹窗、直接拒绝**；工具结果显示「权限拒绝：危险命令：…」，Agent 换策略继续，会话正常结束、不崩溃。

## PERM3 acceptEdits：写不弹、命令弹

1. `/permission-mode acceptEdits`。
2. 触发 `WriteFile`/`EditFile`：预期**无弹窗**直接执行。
3. 触发 `Bash`：预期**仍弹三选一**。

## PERM4 plan 模式：只读 + 计划文件放行

1. `/permission-mode plan`。
2. `ReadFile` 正常；写非计划文件（如改 `src/` 下文件）被确认/拒绝。
3. `/plan` 进入规划并交付：写入 `{工作目录}/.acode/plans/` 的计划文件**自动放行、无弹窗**。

## PERM5 bypassPermissions：全放行但黑名单仍生效

1. `/permission-mode bypassPermissions`。
2. 触发 `WriteFile`/`Bash`：预期**全程无弹窗**。
3. 仍执行 `rm -rf /`：预期**仍被拦截**、结果显示「权限拒绝」。

## PERM6 规则拦截敏感文件

1. 在 `{工作目录}/.acode/permissions.yaml` 写：
   ```yaml
   rules:
     - rule: ReadFile(*.env*)
       effect: deny
   ```
   重启 ACode（规则文件改动需重启生效）。
2. 让模型读项目根下的 `.env`：预期被拒，deny 原因返回模型（「权限拒绝：规则拒绝」）。

## PERM7 「始终允许」持久化

1. default 模式下确认一个 `WriteFile` 时选 **「始终允许」**。
2. 预期：本次执行成功；`{工作目录}/.acode/permissions.local.yaml` 出现对应 `rule: WriteFile(...)` + `effect: allow`。
3. 同一操作第二次调用：**不再弹窗**、直接执行。
4. 退出重启 ACode：规则仍在，同类操作仍自动放行（持久化生效）。

## PERM8 /permission-mode 切档即时生效

1. `/permission-mode`（无参数）→ 输出当前模式。
2. `/permission-mode acceptEdits` → 输出已切换；`WriteFile` 不再弹窗。
3. `/permission-mode ACCEPT_EDITS` / `yolo` / `acceptEdits extra` → 输出非法提示、模式不变。
4. 检查 `.acode/config.yaml` 内容不变；重启后按 config 值恢复。

---

# ACode 阶段六（ch07）：MCP 工具生态 — 手动验收步骤

> 对应 docs/ch07/checklist.md 的 ⚑ 端到端验收项。前置：构建环境 `JAVA_HOME=D:\java\jdk21`，
> 已接入一个真实社区 MCP Server（推荐 `npx @modelcontextprotocol/server-everything`）。
> 未运行手动验收前，checklist 中相应项保持未勾选。

## 前置

1. 打包：`JAVA_HOME=D:\java\jdk21 mvn -DskipTests package`（产物 `target/acode.jar`）。
2. 在 `~/.acode/config.yaml`（全局）或 `.acode/config.yaml`（项目级）声明 MCP server（见 `src/main/resources/config.yaml` 注释示例）：
   ```yaml
   mcp_servers:
     everything:
       type: stdio
       command: npx
       args: [-y, "@modelcontextprotocol/server-everything"]
   ```
3. 启动：`java -jar target/acode.jar`。

## E1 启动连接可见

1. 启动 ACode。
2. 预期：未配置 mcp_servers 时启动行为与之前完全一致（无 MCP 工具、无报错）；
   配置后终端可见 MCP server 连接结果——连接失败打「警告：MCP server X 连接失败…」，成功无提示但工具可用。

## E2 工具列表含 MCP 工具

1. 提问「列出你现在有哪些工具」或让模型调用 `everything_*` 格式的工具。
2. 预期：Agent 请求的工具列表含 `everything_工具名`（server 名前缀）；直接让模型调用如
   `everything_echo` 能成功返回。

## E3 权限档与 plan 模式

1. 默认（未声明 permission）server 的工具：普通模式调用前弹确认菜单（同内置 EXEC 工具）；
   `/plan` 模式下工具表不可见。
2. 声明 `permission: read` 的 server：其工具在 `/plan` 模式工具表可见、普通模式不弹确认（READ）。

## E4 isError 不中断 Loop

1. 让模型调用一个远端不存在的 MCP 工具（如 `everything_nonexistent_tool`）。
2. 预期：工具卡片显示失败、模型收到失败结果（含远端错误文本）、Loop 继续不中断，可换策略继续对话。

## E5 退出清理子进程

1. 对话后 `/quit` 退出。
2. 打开任务管理器检查：无残留 `npx` / node 子进程（stdio 子进程被 closeAll 清理）。

## E6 懒重连（可选）

1. 用任务管理器杀掉 MCP server 的子进程（npx/node 进程）。
2. 再次让模型调用该 server 的工具。
3. 预期：自动重连一次并成功返回结果；若重连失败（如 server 已不可用）返回失败结果、不无限重试、不崩溃。

