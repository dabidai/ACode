# ACode 阶段二：工具调用 — 手动验收步骤

> 对应 checklist.md 中未用单测覆盖的项：T12 的 Ctrl+C 中断，以及 T14 全部端到端项。
> 需要真实 Anthropic API 密钥；未运行手动验收前，checklist 中相应项保持未勾选。

## 前置

1. 配置密钥：把 `examples/config.yaml` 复制到 `~/.acode/config.yaml`，填入真实 `api_key` 与 `base_url`。
2. 打包：`mvn package`（产物为 `target/acode.jar`）。
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

1. 配置密钥：把 `examples/config.yaml` 复制到 `~/.acode/config.yaml`，填入真实 `api_key` 与 `base_url`（protocol 分别设为 anthropic / openai 各跑一遍）。
2. 打包：`mvn package`（产物为 `target/acode.jar`）。
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
