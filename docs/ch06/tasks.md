# ACode 阶段六：工具结果渲染（Claude Code 式命令输出展示）— 任务清单

> 最后更新：2026-08-16
> 依赖关系：`T1→T2→T3→T4→T5`；`T1,T2,T3,T4→T5`；`T5→T6`。
> 参考实现：Claude Code 终端渲染（● 命令 + 缩进 ⎿ 输出块 + ⎿ (耗时) 脚注）。

## 约定

- 包根 `com.acode`；事件类型放 `src/main/java/com/acode/agent/`，渲染放 `src/main/java/com/acode/ui/`，测试 `src/test/java/com/acode/`
- 现有文件行号以 2026-08-16 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；测试方法名用英文驼峰
- 需要复用的现有设施：`AgentEvent.putSafe`（阻塞入队）、`ToolResult`（success/failure + content）、`LiveRegionRenderer.appendCommitted`（追加式写屏）、`StreamPrinter.flushCards`（卡片逐行追加）
- 本轮不改工具实现、不引入新依赖、不做语法高亮

### 全局集成风险（各任务参考资料会引用，编号 R1~R3）

- **R1** 事件构造点同步：`ToolResultEvent` 加字段波及 5 处构造（生产 2 处 StreamingToolExecutor、测试 3 处 AgentEventTest L43/L47/L90），须一次性改齐，否则编译失败一目了然但别漏
- **R2** 平行列表对齐：`turnResults` 与 `elapsedList` 在事件循环里平行记录、同序消费、TurnComplete 后同处重置；`updateToolCalls` 签名变化波及 2 处生产调用（ConversationController L574/L587）+ 1 处测试调用（StreamPrinterTest L151），漏一处即编译错
- **R3** 追加式不重绘：`appendDone` 重置 `screenAppended=0` 使 flushCards 从新块首行写起——运行行已进回滚（静态历史），输出块整块追加其后，不重绘不覆盖；测试须验证「运行行仍在 + 输出块在其后」

---

### T1 ToolResultEvent 加 elapsedMs

**目标**：事件模型携带耗时，UI 无需另行配对。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/agent/AgentEvent.java:35` — `record ToolResultEvent(String toolId, String toolName, String output, boolean isError, long elapsedMs) implements AgentEvent`
- `src/test/java/com/acode/agent/AgentEventTest.java` — 3 处构造补参：L43 `(... "内容", false, 0)`、L47 `(... "命令失败", true, 250)`、L90 `(... "out", false, 0)`；`toolResultEventRecordCarriesOutputAndErrorFlag` 增断言 `elapsedMs` 正确取回

**依赖**：无

**参考资料**
- AgentEvent.java:34-35（现 ToolResultEvent record）；AgentEventTest.java:42-49、90

---

### T2 StreamingToolExecutor.runCall 测耗时

**目标**：nanoTime 包裹执行，正常路径事件传 elapsedMs；确认拒绝路径记 0。

**影响文件（修改）**
- `src/main/java/com/acode/agent/StreamingToolExecutor.java`：
  - 拒绝路径（114-120 行）：`new ToolResultEvent(call.id(), call.name(), results[index].content(), true, 0)`（耗时 0：不含用户确认思考时间）
  - 正常路径（121-126 行）：`long start = System.nanoTime();` 包裹 `executor.execute(call)`；`long elapsedMs = (System.nanoTime() - start) / 1_000_000;`；事件构造传 `elapsedMs`

**依赖**：T1

**参考资料**
- StreamingToolExecutor.java:107-127（runCall）、114-120（拒绝）、121-126（正常）

---

### T3 ToolCallDisplay 输出块渲染

**目标**：● 运行行 + 多行输出块 + 耗时脚注；删折叠逻辑。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/ui/ToolCallDisplay.java`：
  - 常量：删 `MAX_RESULT_LENGTH=200`；加 `MAX_DISPLAY_LINES = 300`、`STYLE_DIM = "\033[90m"`（脚注灰）
  - `appendRunning()`（59-65 行）：`"● " + STYLE_NAME + toolName + RESET + (paramsSummary.isEmpty() ? "" : "(" + paramsSummary + ")")`——去「⏳ 调用工具」
  - `appendDone(ToolResult result, long elapsedMs)`（68-78 行）：改为生成输出块——
    - 拆 `result.content()` 为多行（`\r?\n`），行数上限 `MAX_DISPLAY_LINES`；
    - 首行 `"  ⎿  " + (ok ? STYLE_OK : STYLE_ERR) + line + RESET`，后续行 `"     " + line`（原色）；
    - 超出则补行 `"  ⎿  …（输出过长，已截断）"`；
    - 空内容 → 占位行 `"  ⎿  （无返回结果）"`；
    - 末尾脚注 `"  ⎿  " + STYLE_DIM + "(" + formatDuration(elapsedMs) + ")" + RESET`
  - 新增 `static String formatDuration(long elapsedMs)`：<1000 → `"823ms"`；≥1000 → `String.format(Locale.ROOT, "%.1fs", seconds)`（2.3s）；负值按 0 处理
  - 删 `collapse()`（100-110 行）与 `MAX_RESULT_LENGTH` 引用
- `src/test/java/com/acode/ui/ToolCallDisplayTest.java` — 重写卡片断言（见 T5 汇总）

**依赖**：T1

**参考资料**
- ToolCallDisplay.java:19-26（常量）、59-78（appendRunning/appendDone）、100-110（collapse，删）
- ToolResult.java（success/failure/content）；`splitLines` 思路参考 StreamPrinter.java:178-185

---

### T4 主流程接线：updateToolCalls 签名 + elapsedList

**目标**：耗时从执行器经事件循环传到渲染。

**影响文件（修改）**
- `src/main/java/com/acode/ui/StreamPrinter.java:71-83` — `updateToolCalls(List<ToolResult> results, List<Long> elapsedMsList)`；循环内 `long elapsed = (i < elapsedMsList.size()) ? elapsedMsList.get(i) : 0;` → `appendDone(result, elapsed)`
- `src/main/java/com/acode/ConversationController.java`：
  - 事件循环（556 行起）：`turnResults` 旁加 `List<Long> elapsedList = new ArrayList<>();`
  - ToolResultEvent 分支（582-585 行）：加 `elapsedList.add(toolResult.elapsedMs());`
  - LoopComplete（574 行）与 TurnComplete（587 行）两处 `updateToolCalls(turnResults)` → `updateToolCalls(turnResults, elapsedList)`
  - TurnComplete 后（589 行）`turnResults = new ArrayList<>()` 处同步 `elapsedList = new ArrayList<>();`
- `src/test/java/com/acode/ui/StreamPrinterTest.java:151` — `updateToolCalls` 调用补平行耗时参数

**依赖**：T2、T3

**参考资料**
- StreamPrinter.java:71-83（updateToolCalls）、165-176（flushCards）
- ConversationController.java:556（turnResults 声明）、574（LoopComplete）、582-585（ToolResultEvent）、587-589（TurnComplete + 重置）

---

### T5 测试收口 + mvn test 全绿

**目标**：渲染纯函数与主流程接线断言齐备，存量全绿。

**影响文件（修改 + 新建）**
- `src/test/java/com/acode/ui/ToolCallDisplayTest.java` — 重写/新增：
  - `runningCardShowsBulletAndToolName` — 运行行含「●」「Bash」，不再含「调用工具」
  - `doneCardFirstLineColoredForSuccess` / `forFailure` — 首行含 `  ⎿  ` + STYLE_OK/STYLE_ERR，不含「成功/失败」字
  - `doneCardMultiLineOutputIndented` — 多行结果每行独立渲染，后续行缩进对齐、首行着色
  - `doneCardTruncatesLongOutput` — >300 行输出补「…（输出过长，已截断）」
  - `doneCardEmptyContentShowsPlaceholder` — 空结果出「（无返回结果）」
  - `doneCardAppendsDurationFooter` — 脚注行含 `  ⎿  ` 与 `(<耗时>)`
  - `formatDurationFormatsMsAndSeconds` — `823→"823ms"`、`2300→"2.3s"`、`0→"0ms"`、`5000→"5.0s"`
  - 删 `multilineResultCollapsedToSingleLine`（折叠行为已删）
- `src/test/java/com/acode/ui/StreamPrinterTest.java` — `updateToolCallsCommitsDoneCardsToModel`（151 行）传平行耗时列表；断言模型含输出行与耗时脚注
- `src/test/java/com/acode/agent/StreamingToolExecutorTest.java:182-204` — `toolResultEventEmittedPerCompletedCall` 增断言：e1/e2 的 `elapsedMs()` ≥ 0
- 全程 `JAVA_HOME="D:\java\jdk21" mvn test` 全绿

**依赖**：T1、T2、T3、T4

**参考资料**
- ToolCallDisplayTest.java（现有 9 例，改造 3 例 + 新增 5 例）；StreamPrinterTest.java:143-157；StreamingToolExecutorTest.java:182-204

---

### T6 端到端验证

**目标**：全量回归 + 真实终端手动验收。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md`（若不存在则创建）追加阶段六小节 — 手测步骤：真实 provider 让模型跑 Bash 命令 → 看到 ● 行 + 缩进输出块 + 「⎿ (耗时)」脚注；故意失败命令 → 首行红色 ⎿；ReadFile 多行内容 → 原样缩进展示；长输出命令（如 `ls -R` 大目录 / `mvn` 全量日志）→ 截断 marker
- 修 bug 产生的影响文件视情况

**依赖**：T5

**参考资料**
- 手测按 checklist.md 逐项打勾；联网问题用临时错误 base_url 模拟（沿用 ch02 做法）
