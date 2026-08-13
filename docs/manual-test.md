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
