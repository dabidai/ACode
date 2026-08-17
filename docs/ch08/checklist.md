# ACode 阶段八：工具结果展示策略（只读摘要 + 写入 diff）— 验收清单

> 最后更新：2026-08-17
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 关键值：ReadFile 摘要「返回 N 行（Lx-y）」带行号范围（截断追加「（已截断）」）；Glob「返回 N 个匹配」；Grep「返回 N 条命中」；WriteFile/EditFile display 首行为原确认文案、后接 diff 行（`- ` 红 / `+ ` 绿）；LineDiff 先裁共同前缀/后缀、变更中段（oldMid+newMid）总和超 300 行返回 null → WriteFile display 末尾「…（变化过大，省略对比）」；WriteFile 旧内容 >2MB 不读 → display 末尾「…（旧内容过大，省略对比）」；EditFile 累计 diff 行 >300 → display 末尾「…（变化过大，省略对比）」；展示层 MAX_DISPLAY_LINES=300 截断沿用。

## T1 ToolResult display

- [ ] `ToolResult.success("x").withDisplay("d")` 后 `content()=="x"`（display 不进 content）、`display()=="d"`、`isSuccess()==true`
- [ ] 默认 `success("x").display()==null`、`failure("e").display()==null`
- [ ] `withDisplay` 不改变原对象（不可变）：两次调用返回独立实例

## T2 LineDiff

- [ ] 增行：`diffLines([a,c], [a,b,c])` 含 `"+ b"` 且无其他差异
- [ ] 删行：`diffLines([a,b,c], [a,c])` 含 `"- b"` 且无其他差异
- [ ] 改行：`diffLines([a,b,c], [a,x,c])` 含 `"- b"` 与 `"+ x"`
- [ ] 相同内容 → 返回空列表
- [ ] 一方为空：`diffLines([], [x,y])` → `"+ x"`/`"+ y"`；`diffLines([x,y], [])` → `"- x"`/`"- y"`
- [ ] 大文件小改动：old/new 各 5000 行、仅中段 1 行不同 → 返回非 null，且仅含对应 `-`/`+` 行（不因文件大而省略）
- [ ] 超限：变更中段（oldMid+newMid）总和 >300 行 → 返回 `null`
- [ ] 前缀格式统一：所有差异行以 `"+ "` 或 `"- "` 开头，后接原行内容

## T3 只读工具摘要

- [ ] ReadFile 读 87 行文件（无 offset）→ `display()=="返回 87 行（L1-87）"`；`output()` 仍为完整文件内容
- [ ] ReadFile 文件超 2000 行 → `display()` 含「已截断」
- [ ] ReadFile 带 offset/limit（如 offset=95 limit=87）→ `display()=="返回 87 行（L96-182）"`，N = 实际返回行数
- [ ] ReadFile 失败（文件不存在）→ 无 display 覆盖，`isError()`、`errorMessage()` 含路径
- [ ] Glob 匹配 5 个文件 → `display()=="返回 5 个匹配"`；结果超 200 条 → 含「已截断」
- [ ] Grep 命中 3 条 → `display()=="返回 3 条命中"`；超 500 条 → 含「已截断」
- [ ] 三工具 `output()` 均不变（模型回传内容与改动前一致）

## T4 写入工具 diff

- [ ] WriteFile 新建文件（内容 2 行）→ `display()` 首行含「已写入」、第 2 行起全部 `"+ "` 前缀
- [ ] WriteFile 覆盖已有文件（改动一行）→ `display()` 含 `"- "` 旧行与 `"+ "` 新行
- [ ] WriteFile 写入与旧内容完全相同 → `display()` 只有确认行、无 diff 行
- [ ] WriteFile 变更中段总和 >300 行 → `display()` 末尾含「变化过大，省略对比」
- [ ] WriteFile 旧内容无法读取（预写非法 UTF-8 字节）→ 写入仍成功、文件内容为新内容、`display()` 含降级提示（不报错）
- [ ] WriteFile 旧文件 >2MB（预写大文件）→ 不读旧内容、写入仍成功、`display()` 含「旧内容过大，省略对比」
- [ ] EditFile 单行替换 → `display()` 含 `"- 旧文"` 与 `"+ 新文"` 各一行
- [ ] EditFile 多行段替换 → old/new 各自按行拆成多条 `-`/`+` 行
- [ ] EditFile 多段累计 diff 行 >300 → `display()` 末尾含「变化过大，省略对比」
- [ ] EditFile 失败（匹配不唯一）→ 无 display 覆盖，`errorMessage()` 含「不唯一」
- [ ] WriteFile/EditFile `output()` 均为原确认文案（「已写入 …（N 字符）」/「已编辑 …（N 处替换）」），不含 diff

## T5 展示层渲染

- [ ] `appendDone(success("x").withDisplay("d"))` 渲染 display「d」，**不含**「x」
- [ ] display 含 `"+ hi"` → 该行含 `STYLE_OK`（绿）；含 `"- bye"` → 该行含 `STYLE_ERR`（红）
- [ ] display 为空串/null → 回退渲染 content（现行为）
- [ ] display 超 300 行 → 截断 + 「（输出过长，已截断）」marker + 耗时脚注，结构与现行为一致
- [ ] 失败结果即使带 display → 仍渲染错误正文（错误优先）

## T6 管道透传

- [ ] `ToolResultEvent` record 含第 6 组件 `display`；`AgentEventTest` 构造冒烟通过
- [ ] `StreamingToolExecutor` 普通/交互路径发出的事件 `display` 与 `result.display()` 一致；拒绝路径 display 为 null
- [ ] `ConversationController` 事件循环重建的 `ToolResult.display()` 与事件一致（端到端见 T7）

## T7 接入主流程（端到端，FakeProvider）

- [ ] 脚本模型调 ReadFile 成功 → 第二轮回传 `tool_result.content()` 含文件内容（模型侧不变）
- [ ] 界面回滚：ReadFile 卡片下方只有「返回 N 行」摘要行 + 耗时脚注，**不含**文件正文行
- [ ] 脚本模型调 WriteFile → 回滚含 `"+ "` 前缀且呈绿色（`STYLE_OK`）
- [ ] ReadFile 失败 → 回滚显示红色错误行（`STYLE_ERR`）
- [ ] 全程 `JAVA_HOME="D:\java\jdk21" mvn test` 全绿（存量 + 新增，无网络依赖）

## T8 端到端验收 ⚑

- [ ] 真实 API：提问「读 `pom.xml`」→ 只出「● ReadFile(...)」卡片 + 「返回 N 行（Lx-y）」摘要 + 耗时，不刷屏、内容不进回滚
- [ ] 真实 API：让模型 WriteFile 新建文件 → 卡片下全是绿色 `+ ` 行；覆盖已有文件 → 出红 `- ` / 绿 `+ ` 对比
- [ ] 真实 API：让模型 EditFile 改文件 → 出「- 旧 / + 新」对比行
- [ ] 真实 API：读不存在的文件 → 仍显示红色错误
- [ ] 真实 API：Bash 执行命令 → 输出块与阶段六一致（未被摘要化）
- [ ] 真实 API：`--resume` 恢复含工具轮次的会话 → 历史一行摘要渲染不受影响
- [ ] manual-test.md 阶段六 M13/M15 预期已按新行为更新
