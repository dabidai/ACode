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
