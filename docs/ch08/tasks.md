# ACode 阶段八：工具结果展示策略（只读摘要 + 写入 diff）— 任务清单

> 最后更新：2026-08-17
> 依赖关系：`T1→T2→T3`；`T1→T4`；`T2,T4→T5`；`T1,T5→T6`；`T6→T7`；`T7→T8`。
> 参考实现：ch06 的 `ToolCallDisplay.appendDone` 渲染、`ToolResultEvent` 结果管道、ch07 的 ToolResult 重建链路。

## 约定

- 包根 `com.acode`；diff 工具放 `src/main/java/com/acode/tool/impl/`，测试 `src/test/java/com/acode/`
- 现有文件行号以 2026-08-17 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；测试方法名用英文驼峰
- 需要复用的现有设施：`ToolResult`（success/failure + content，ch08 扩展 display）、`ToolCallDisplay.appendDone`（输出块渲染 + 截断 + 耗时脚注）、`AgentEvent.putSafe`（阻塞入队）、`ToolResultEvent`（结果事件）
- 本轮不引入新依赖；模型回传的 `tool_result` 内容一律不变（display 仅界面层）
- 文档按惯例三份（本文件 + spec.md + checklist.md），实现按本文件顺序

### 全局集成风险（各任务参考资料会引用，编号 R1~R4）

- **R1** display 不污染模型 payload：`ToolResult.display()` 仅界面层消费；`content()` 不变；对话持久化只存 content（`ConversationController.renderHistoryMessage` 不受影响）
- **R2** diff 计算时机：WriteFile 写盘**前**读旧内容（此时旧内容仍在磁盘）；旧内容过大（>2MB）不读、读取失败**不阻断写入**（display 降级提示）；diff 超限（变更中段行数总和 >300）降级为 header + 「…（变化过大，省略对比）」，不计算、不爆内存
- **R3** 渲染层通用着色：按行前缀着色（`"+ "`→绿、`"- "`→红），不按工具名硬编码；display 为空/null 时回退 content 渲染（现行为完全保留，存量测试不破）
- **R4** 管道完整透传：`ToolResultEvent` 增 display 组件后，`StreamingToolExecutor`（3 处构造）、`ConversationController`（L518-521 重建）必须同步带透，漏一处则展示信息丢失

---

### T1 ToolResult 增加 display + withDisplay + 测试

**目标**：结果对象具备「展示正文」能力，默认 null、与原 content 解耦。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/tool/ToolResult.java`：
  - 新增 `private final String display` 字段 + getter `String display()`（null 默认）
  - 新增实例方法 `ToolResult withDisplay(String display)`（返回带 display 的新副本，成功/失败均可带；原对象不可变不变）
  - `success()`/`failure()` 工厂、`content()`、`isSuccess()`/`isError()` 全部不变
- 测试：断言 `success("x").withDisplay("d").content()=="x"`（display 不进 content）、`display()=="d"`、默认 `display()==null`、失败结果也可 withDisplay

**依赖**：无

**参考资料**
- ToolResult.java:7-27（字段/私有构造）、19-25（success/failure 工厂）、39-48（content() 现值）

---

### T2 LineDiff 行级 diff + 单测

**目标**：行级 diff，供 WriteFile 对比。先裁共同前缀/后缀，仅对变更中段做 LCS——文件整体多大都不影响是否出 diff，只有「变更本身过大」才降级。

**影响文件（新建 + 测试）**
- `src/main/java/com/acode/tool/impl/LineDiff.java`（新建，包私有类）— `static List<String> diffLines(List<String> old, List<String> new, int maxChange)`：
  - 裁共同前缀与后缀，得中段 oldMid/newMid
  - `oldMid.size() + newMid.size() > maxChange` → 返回 `null`（调用方降级）
  - 中段 LCS DP（`int[o+1][n+1]`，o+n ≤ maxChange，矩阵 ≤151×151）回溯生成 `"- x"` / `"+ y"` 前缀行列表（保留原行内容）
  - 完全相同 / 中段双空 → 空列表；一方为空 → 全 `+` 或全 `-`
- `src/test/java/com/acode/tool/impl/LineDiffTest.java`（新建）— 增/删/改/相同为空/一方为空/超限 null/前缀正确/大文件小改动不出 null（各 5000 行仅中段 1 行不同 → 仍返回 diff）

**依赖**：无

**参考资料**
- 经典 LCS 回溯实现（无现成引用，直接实现）；前缀/后缀裁剪为新增思路，见 spec.md 关键设计决策

---

### T3 只读工具一行摘要 + 测试

**目标**：ReadFile/Glob/Grep 成功结果 `withDisplay(摘要)`，模型回传不变。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/tool/impl/ReadFileTool.java` — `doExecute` 成功分支（L61）：`ToolResult.success(sb.toString()).withDisplay(摘要)`；摘要 = `"返回 " + (showEnd - start) + " 行（L" + (start + 1) + "-" + showEnd + "）"`（1 起行号范围；offset=0 时即「L1-N」），文件超 MAX_LINES 截断时追加 `"（已截断）"`
- `src/main/java/com/acode/tool/impl/GlobTool.java` — 成功分支（L84）：`withDisplay("返回 " + results.size() + " 个匹配" + (truncated ? "（已截断）" : ""))`
- `src/main/java/com/acode/tool/impl/GrepTool.java` — 成功分支（L97）：`withDisplay("返回 " + hits.size() + " 条命中" + (truncated ? "（已截断）" : ""))`
- 测试（ReadFileToolTest/GlobToolTest/GrepToolTest 各加用例）：`display()` 断言——ReadFile 返回行数与摘要一致、截断时含「已截断」；Glob/Grep 计数正确；`output()`（回传模型）仍含原内容

**依赖**：T1

**参考资料**
- ReadFileTool.java:52-61（showEnd/start 计算、success 返回）、57-59（截断提示条件 `lines.size() > MAX_LINES`）
- GlobTool.java:61-84（results/truncated 收集、success 返回）
- GrepTool.java:68-97（hits/truncated 收集、success 返回）

---

### T4 写入工具红绿 diff + 测试

**目标**：WriteFile 全文件行级 diff；EditFile 每段 old/new 对比行。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/tool/impl/WriteFileTool.java`：
  - `doExecute` 写盘前（L44-48 前）读旧内容：`Files.isRegularFile(file)` 为假 → 旧内容 null（新文件，diff 全 `+`）；为真但 `Files.size(file) > MAX_OLD_FILE_BYTES`（2MB 大小守卫）→ 标记「过大」，不读；其余 → `try { Files.readString(file) } catch (IOException e) { 旧内容标记「读取失败」}`（**大小守卫与读取失败均不阻断写入**，L44-48 的写盘照常）
  - 成功分支（L49）：`ToolResult.success("已写入 " + file + "（" + content.length() + " 字符）")` 后接 `.withDisplay(diffText)`：
    - diffText 首行 = 同上确认文案 + `\n` + LineDiff 行
    - 旧内容 null（新文件）→ 全 `+` 行
    - 旧内容过大（>2MB）→ 确认文案 + `\n…（旧内容过大，省略对比）`
    - 旧内容读取失败 → 确认文案 + `\n…（旧内容读取失败，省略对比）`
    - LineDiff 返回 null（变更超限）→ 确认文案 + `\n…（变化过大，省略对比）`
- `src/main/java/com/acode/tool/impl/EditFileTool.java`：
  - 成功分支（L83）：`ToolResult.success("已编辑 " + file + "（" + edits.size() + " 处替换）")` 后接 `.withDisplay(diffText)`：
    - diffText = 确认文案 + 每段 edits：`old` 按行拆 → `"- " + 行`，`new` 按行拆 → `"+ " + 行`
    - 累计 diff 行数 > 300 → 确认文案 + 前 300 行 + `\n…（变化过大，省略对比）`（封顶，防刷屏）
- 测试（WriteFileToolTest/EditFileToolTest 各加用例）：新文件全 `+` 行；覆盖含 `-`/`+`；相同内容 diff 为空（display 只剩确认行）；多行段按行拆分；变更中段超 300 行显示「（变化过大，省略对比）」；大文件小改动照常出 diff；旧内容非 UTF-8（预写非法字节）→ 写入仍成功、display 含降级提示；旧文件超 2MB（预写大文件）→ 不读旧内容、写入仍成功、display 含「旧内容过大」；EditFile 多段累计 diff 行超 300 → display 含「变化过大，省略对比」；`output()`（回传模型）仍是原确认文案

**依赖**：T1、T2

**参考资料**
- WriteFileTool.java:37-53（doExecute 结构、写盘 L48、success L49）
- EditFileTool.java:41-87（doExecute 结构、edits 迭代 L60-79、success L83）

---

### T5 展示层渲染 display + 前缀着色 + 测试

**目标**：`appendDone` 优先渲染 display；`+ ` 绿 / `- ` 红通用着色；回退逻辑保留。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/ui/ToolCallDisplay.java` — `appendDone`（L70-103）：
  - 取展示正文：`success && result.display()!=null && !display().isEmpty()` → 用 display，否则用 content（现行为）
  - 行着色扩展：行以 `"+ "` 开头 → `STYLE_OK`（绿）；`"- "` 开头 → `STYLE_ERR`（红）；其余首行保持成败色、后续行原色
  - 缩进 / MAX_DISPLAY_LINES 截断 / 耗时脚注（L100）逻辑复用不变
- `src/test/java/com/acode/ui/ToolCallDisplayTest.java` 加用例：display 非空时渲染 display 而非 content；`+ ` 行绿 / `- ` 行红；display 空串回退 content；display 超长截断

**依赖**：T1

**参考资料**
- ToolCallDisplay.java:70-103（appendDone 现值）、75-98（content 渲染/截断）、100（脚注）

---

### T6 管道：ToolResultEvent 增 display + 透传 + 测试

**目标**：display 从执行器经事件到控制器完整透传。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/agent/AgentEvent.java` — `ToolResultEvent` record（L36）增加第 6 组件 `String display`
- `src/main/java/com/acode/agent/StreamingToolExecutor.java` — 3 处 `new ToolResultEvent(...)` 构造补 `result.display()`：L118-119（拒绝路径，failure 无 display，传 null 亦可）、L129-130（交互路径）、L140（普通路径）
- `src/main/java/com/acode/ConversationController.java` — L518-521 重建：`ToolResult.success(toolResult.output())` 后接 `.withDisplay(toolResult.display())`（失败分支不变）
- 测试：`AgentEventTest.java` 3 处构造（L45/L50/L106）补 display 组件；`StreamingToolExecutorTest` 加断言：普通/交互路径事件 display 与 result.display() 一致

**依赖**：T1、T5

**参考资料**
- AgentEvent.java:36（ToolResultEvent record）
- StreamingToolExecutor.java:116-119（拒绝）、122-131（交互）、133-140（普通）
- ConversationController.java:518-521（事件循环重建 ToolResult）

---

### T7 接入主流程 + 端到端测试 + mvn test 全绿

**目标**：端到端验证「读→摘要、写→diff」，存量全绿。

**影响文件（测试，视情况微调生产）**
- `src/test/java/com/acode/ConversationControllerTest.java`：
  - 现有 `singleStepToolLoopExecutesToolAndReturnsFinalText`（L48-85）仍绿（ReadFile 摘要行仍含 `  ⎿  ` 与 `STYLE_OK`，断言不受影响）——若断言失败按新渲染修正
  - 新增端到端用例（FakeProvider）：模型调 ReadFile 成功 → 界面回滚含「返回 N 行」且**不含**文件正文行；模型调 WriteFile → 回滚含 `+ ` 绿行；模型调 ReadFile 失败 → 仍显示错误红行
- 若暴露 display 透传缺漏，修生产代码（R4）

**依赖**：T3、T4、T6

**参考资料**
- ConversationControllerTest.java:47-85（端到端模式：FakeProvider.scripted + 断言回滚）
- FakeProvider（脚本化 delta/toolUse/complete）

---

### T8 端到端验证

**目标**：全量回归 + 文档手测更新 + 真终端验收。

**影响文件（修改 + 新建）**
- `docs/manual-test.md`：
  - 阶段六 M13（ReadFile 多行原样缩进）预期改为「一行摘要「返回 N 行」+ 耗时」；M15 的 ReadFile 部分改摘要行、Bash 部分不变
  - 追加「阶段八」小节——手测：ReadFile 只出摘要行不刷屏（含截断场景）；WriteFile 新建全 `+` 绿、覆盖出 `-` 红/`+` 绿；EditFile 出替换段对比；失败仍显示错误；Bash 输出不变；`--resume` 历史不受影响
- `mvn package` 打包后用户真终端验收，按 checklist.md 逐项打勾；修 bug 产生的影响文件视情况

**依赖**：T7

**参考资料**
- manual-test.md:196-216（阶段六 M13/M15 现值）、ch08/checklist.md（验收项）
