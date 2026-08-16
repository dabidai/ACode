# ACode 阶段七：选择交互（↑↓ 菜单替换 y/n + AI 选择工具）— 任务清单

> 最后更新：2026-08-16
> 依赖关系：`T1→T2→T3→T4`；`T5→T6→T7`；`T3,T4,T7→T8`；`T8→T9`。
> 参考实现：/resume 会话菜单（`ConversationController.selectSession`）的 ↑↓ 菜单逻辑抽取复用。

## 约定

- 包根 `com.acode`；菜单基础设施放 `src/main/java/com/acode/ui/`，选择握手与交互工具放 `src/main/java/com/acode/agent/`，测试 `src/test/java/com/acode/`
- 现有文件行号以 2026-08-16 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；测试方法名用英文驼峰
- 需要复用的现有设施：`AgentEvent.putSafe`（阻塞入队）、`ToolResult`（success/failure + content）、`LiveRegionRenderer.appendCommitted`（追加式写屏）、`Confirmation` 事件握手模式（agent 阻塞 + UI 主线程应答）
- 本轮不改 tool 包、不引入新依赖；`Confirmation`/`ConfirmationRequestEvent`/`EventConfirmationGate` 语义不变（仍是 boolean）

### 全局集成风险（各任务参考资料会引用，编号 R1~R5）

- **R1** 菜单基础设施与 /resume 同源：`MenuKeySource` 抽象按键源，`SelectionMenu` 抽通用菜单渲染/循环；/resume 重构后删除 `readMenuKey`/`drainPendingInput`/`KEY_*`/`menuLines`/`menuLine`，行为除 `▸`→`>` 与 EOF→取消外不变
- **R2** `redraw` 与 `appendCommitted` 交错：`appendCommitted` 不碰 `rowsWritten`（LiveRegionRenderer L149-157），提示行写入后 `rowsWritten==0`，菜单帧独立锚定；`clear` 收敛在 `SelectionMenu.select` 内所有退出路径，调用方不再手动清
- **R3** 同轮多个交互调用：每次调用独立 `Choice` 通道随事件携带，事件队列背压 + UI 主线程串行应答，无竞态（与 Confirmation 同模式）
- **R4** AskUserTool 不走 BaseTool：其 `execute` 是 final 带 10s 超时、`inputSchema` 把 ARRAY items 硬编码成 object——AskUserTool 直接 `implements Tool`，手写 schema 表达 string 数组
- **R5** 包依赖循环：`InteractiveTool`/`AskUserTool` 放 agent 包，AskUserTool 在 ConversationController 构造器注册，tool 包零改动

---

### T1 MenuKeySource + TerminalMenuKeySource + 单测

**目标**：按键源抽象 + 抽取 /resume 的按键解析，修复 EOF→NONE 死循环风险为 EOF→CANCEL。

**影响文件（新建 + 修改）**
- `src/main/java/com/acode/ui/MenuKeySource.java`（新建）— `@FunctionalInterface`：`int readKey()` + `default void drainPendingInput() {}`；常量 `KEY_NONE=0`/`KEY_UP=1`/`KEY_DOWN=2`/`KEY_ENTER=3`/`KEY_CANCEL=4`
- `src/main/java/com/acode/ui/TerminalMenuKeySource.java`（新建）— 构造 `(NonBlockingReader reader)`；`readKey()` 抽取现 `readMenuKey` L300-331 解析（`\r`/`\n`→ENTER、0x03→CANCEL、`\033[` 或 `\033O` + A/B→UP/DOWN、裸 Esc→CANCEL、**EOF(-1)→CANCEL**）；`drainPendingInput()` 抽取现 L337-355（50ms 窗口排空残留字节，EOF 结束）
- `src/test/java/com/acode/ui/TerminalMenuKeySourceTest.java`（新建）— 假 NonBlockingReader 脚本化字节序列（抽象方法仅 `read(long,boolean)` 与 `readBuffered(char[],int,int,long)` 两个）

**依赖**：无

**参考资料**
- ConversationController.java:300-331（readMenuKey）、337-355（drainPendingInput）、225-229（KEY_* 常量现值）
- NonBlockingReader API：`int read(long timeout, boolean isPeek)` 返回 int 或 -1；`int readBuffered(char[] b, int off, int len, long timeout)`

---

### T2 SelectionMenu + 单测

**目标**：通用单选菜单——渲染、环形移动、Enter 选中、Esc/EOF 取消、clear 收敛。

**影响文件（新建）**
- `src/main/java/com/acode/ui/SelectionMenu.java`（新建）— 构造 `(List<String> options, String header, int initialSelected)`（header 可 null）；`int select(LiveRegionRenderer live, Writer writer, MenuKeySource keys)`：
  - `keys.drainPendingInput()` → 循环 `live.redraw(writer, lines)`（首行 header 原色，选中行 `"\033[7m> " + opt + "\033[0m"`，未选中 `"  " + opt`）
  - KEY_UP/DOWN 环形移动（越界回绕）；KEY_ENTER → `live.clear(writer)` + 返回 index；KEY_CANCEL → `live.clear(writer)` + 返回 -1；KEY_NONE 忽略继续
- `src/test/java/com/acode/ui/SelectionMenuTest.java`（新建）— 脚本化 `MenuKeySource` int 队列：NONE 忽略、上下环形、Enter 返回正确 index、Esc 返回 -1、退出后 `rowsWritten()==0`、writer 含 `\033[7m> X\033[0m` 与 `"  Y"`

**依赖**：T1

**参考资料**
- LiveRegionRenderer.java:113-115（rowsWritten）、122-137（redraw）、140-143（clear）、149-157（appendCommitted 不碰 rowsWritten）
- ConversationController.java:271-284（menuLines/menuLine 现值，`▸`→`>`、header 文案保留）

---

### T3 /resume 重构

**目标**：`selectSession` 改用 SelectionMenu，删除重复菜单代码，行为不变（`▸`→`>`、EOF→取消）。

**影响文件（修改）**
- `src/main/java/com/acode/ConversationController.java`：
  - `selectSession`（L235-268）改装配：`new SelectionMenu(条目行, "（↑/↓ 选择会话，回车加载，Esc 取消）", sessions.size()-1).select(live, writer, new TerminalMenuKeySource(tui.terminal().reader()))`；返回 index → loadSession；返回 -1 → 无操作
  - 删除 `readMenuKey`/`drainPendingInput`/`KEY_*` 常量/`menuLines`/`menuLine`（L225-229、271-284、300-355）
  - `preview`（L286-294）保留，条目行组装逻辑保留（选中行由 SelectionMenu 统一渲染）

**依赖**：T1、T2

**参考资料**
- ConversationController.java:225-268、271-294、300-355

---

### T4 确认菜单改造

**目标**：y/n 行输入换成 ↑↓ 选择菜单；`Confirmation` 握手语义不变。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/ui/ConfirmationPrompt.java`：
  - 构造签名 `(Function<String,String> reader, LiveRegionRenderer live, Writer writer)` → `(MenuKeySource keys, LiveRegionRenderer live, Writer writer)`
  - `ask` 改造：`drainPendingInput()` → `appendCommitted(「要执行「X（args）」？」)`（去掉 `[y/n]` 后缀）→ `commitRegion()` → `new SelectionMenu(List.of("是","否"), null, 0).select(...)`：
    - 0 → `appendCommitted(「（已批准执行「X」）」)` return true
    - 1 → `appendCommitted(「（已拒绝执行「X」）」)` return false
    - -1 → `appendCommitted(「（已取消）」)` return false
  - 删 `isYes`/`isNo`/`readAnswer` 与 y/n 循环、`PROMPT` 常量；`promptLine` 保留但去掉 `[y/n]`
- `src/main/java/com/acode/ConversationController.java` — `answerConfirmationPrompt`（L451-457）改装配：`tui == null` → false；否则 `new ConfirmationPrompt(new TerminalMenuKeySource(tui.terminal().reader()), liveRenderer(), screenWriter())`
- `src/test/java/com/acode/ui/ConfirmationPromptTest.java` — 重写：键序列注入（是→true+「已批准」、否→false+「已拒绝」、取消→false+「已取消」、提示行无 `[y/n]`）

**依赖**：T1、T2

**参考资料**
- ConfirmationPrompt.java:30-49（ask 现值）、54-75（isYes/isNo/readAnswer，删）
- ConversationController.java:446-457（answerConfirmationPrompt + setConfirmAnswerer）

---

### T5 Choice + ChoiceRequestEvent + 单测

**目标**：选择通道 + 事件类型，照搬 Confirmation 握手模式。

**影响文件（新建 + 修改 + 测试）**
- `src/main/java/com/acode/agent/Choice.java`（新建）— `LinkedBlockingQueue<Optional<String>>`（容量 1，避开 null 禁令）；`answer(String)` 幂等（null → Optional.empty）；`await(AtomicBoolean cancelled)` 复刻 `Confirmation.await`（50ms 轮询、cancel→null、中断恢复中断位返 null）；null = 取消
- `src/main/java/com/acode/agent/AgentEvent.java` — 新增 `record ChoiceRequestEvent(String toolId, String toolName, String question, List<String> options, Choice response) implements AgentEvent`
- `src/test/java/com/acode/agent/ChoiceTest.java`（新建）— answer/await 轮询、cancel、中断
- `src/test/java/com/acode/agent/AgentEventTest.java` — ChoiceRequestEvent 构造冒烟

**依赖**：无

**参考资料**
- Confirmation.java（现握手实现，await 轮询模式）；AgentEvent.java:34-35（ToolResultEvent record 位置）
- EventConfirmationGateTest.java:35-62（应答线程测试模式）

---

### T6 InteractiveTool + StreamingToolExecutor 分派 + 单测

**目标**：交互工具接口 + 执行器识别分派，交互耗时记 0。

**影响文件（新建 + 修改 + 测试）**
- `src/main/java/com/acode/agent/InteractiveTool.java`（新建）— `ToolResult executeInteractive(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled)`
- `src/main/java/com/acode/agent/StreamingToolExecutor.java` — `runCall` 中 gate 检查（L114-121）之后、计时之前插入：
  ```
  if (tool instanceof InteractiveTool interactive) {
      ToolResult result = interactive.executeInteractive(call, events, cancelled);
      if (cancelled.get()) { return; }        // fillCancelled 兜底「已取消」
      results[index] = result;
      putSafe(events, new ToolResultEvent(call.id(), call.name(), result.content(), result.isError(), 0));
      return;                                  // elapsedMs=0：不含用户思考时间
  }
  ```
- `src/test/java/com/acode/agent/StreamingToolExecutorTest.java` — InteractiveTool 桩：走 executeInteractive 而非 execute、elapsedMs==0、READ 权限不触 gate

**依赖**：T5

**参考资料**
- StreamingToolExecutor.java:107-127（runCall 结构）、114-121（gate 检查位置）
- AgentEvent.java（ToolResultEvent 构造）

---

### T7 AskUserTool + 注册 + 单测

**目标**：模型发起的单选菜单工具，options 校验 + 取消回传失败。

**影响文件（新建 + 修改 + 测试）**
- `src/main/java/com/acode/agent/AskUserTool.java`（新建）— `implements Tool, InteractiveTool`：
  - `Permission.READ`（绕过确认门 + 自动进 plan 模式工具表）
  - `name()`/`description()`；`inputSchema()` 手写：`{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}},"required":["question","options"]}`
  - `executeInteractive`：校验 `options` 为空或含非字符串 → failure「选项不能为空」；`putSafe(events, new ChoiceRequestEvent(...))` → `await(cancelled)` → null → failure「用户取消选择」、否则 `success(选中项)`
  - `execute()` 防御性返回 failure（生产只走 executeInteractive）
- `src/main/java/com/acode/ConversationController.java` — 构造器（L126-127 `DefaultToolset.registerAll` 之后）`toolRegistry.register(new AskUserTool())`（不进 DefaultToolset，避免 tool→agent 包依赖）
- `src/test/java/com/acode/agent/AskUserToolTest.java`（新建）— 应答线程模式（仿 EventConfirmationGateTest L35-62）：选中回传 success、取消回传 failure、空 options 直接 failure 不弹事件

**依赖**：T5、T6

**参考资料**
- Tool.java（接口形态）、DefaultToolset.java:126-127（registerAll 位置）
- BaseTool.java:55（final execute 10s 超时）、99-101（ARRAY items 硬编码，AskUserTool 规避）

---

### T8 接入主流程 + 端到端测试 + mvn test 全绿

**目标**：choiceAnswerer + 事件分支 + 端到端测试，存量全绿。

**影响文件（修改 + 测试）**
- `src/main/java/com/acode/ConversationController.java`：
  - 字段 `private Function<ChoiceRequestEvent, String> choiceAnswerer = this::answerChoicePrompt;` + 测试注入 `void setChoiceAnswerer(...)`（与 confirmAnswerer L102/L446 同模式）
  - `answerChoicePrompt(event)`：`tui == null` → null（取消）；否则 `TerminalMenuKeySource` + `appendCommitted(event.question())` → `commitRegion()` → `new SelectionMenu(event.options(), "（↑/↓ 选择，回车确认，Esc 取消）", 0).select(...)` → 选中 `appendCommitted(「（已选择「X」）」)` 返回 X；取消 `appendCommitted(「（已取消）」)` 返回 null
  - 事件循环（L599-601 旁）加分支：`else if (event instanceof ChoiceRequestEvent choice) { choice.response().answer(choiceAnswerer.apply(choice)); }`
- `src/test/java/com/acode/ConversationControllerTest.java` — 端到端：FakeProvider 脚本模型调 AskUser → 注入 `setChoiceAnswerer` 桩选「B」→ 第二轮请求 `tool_result.content()=="B"` 且 `isError=false`；桩返 null → `isError=true` 含「取消」；首轮 `tools()` 含 AskUser

**依赖**：T3、T4、T7

**参考资料**
- ConversationController.java:102（confirmAnswerer 字段）、446-448（setConfirmAnswerer）、451-457（answerConfirmationPrompt）、599-601（事件分派）
- ConversationControllerTest.java:311-363（denied/approved 测试模式）

---

### T9 端到端验证

**目标**：全量回归 + 真实终端手动验收。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md` 追加阶段七小节 — 手测步骤：Bash/WriteFile 确认弹 ↑↓ 菜单（`> 是` 反显）、↑↓ 移动、Enter 批准、Esc 取消显示「（已取消）」且模型收到拒绝；让模型调 AskUser 出多选项菜单、选中回传正确、Esc 取消失败回传；/resume 菜单行为不变（`>` 箭头 + 反显）
- 修 bug 产生的影响文件视情况

**依赖**：T8

**参考资料**
- 手测按 checklist.md 逐项打勾；联网问题用临时错误 base_url 模拟（沿用 ch02 做法）
