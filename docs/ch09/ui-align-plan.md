# ch09 收尾：UI 风格对齐 PR #2 + 页脚残留修复 — 实施计划

> 起因：`docs/ch09/spec.md` 非功能要求「界面风格统一」写着「配色分两步走……实现完成后再对照
> PR #2（`dabidai/ACode#2`）的 `ui/StatusBar.java` 做一次风格对齐」，这一步在 T13 收尾时漏做了。
> 真机验收时又暴露一个既有缺陷（usage 脚注残留），两者合到一批处理。

## 背景：这个缺陷是什么

一次对话轮次结束时，`ExchangeRunner.printUsageFootnote`（`ExchangeRunner.java:213-219`）用
`appendCommitted` 往屏幕上**追加**一行 usage 脚注。它写在提示符**下方**。

- JLine 只管提示符那一行（`ERASE_LINE_ON_FINISH` 擦它），不知道脚注的存在；
- `appendCommitted` 只会追加，从不擦除。

于是 usage 行落在两者的缝隙里，**没人负责清掉它**。下一个提示符在它下面重开，`/help` 之类的
命令输出又接在它后面，就糊成一片。真机复现（2026-09-14）：

```
● 你是什么模型？
我是 ACode——本终端里的 AI 编程助手……
usage: in 92391 · cache_read 81152 · cache_write 0 · out 127
需要的话我也可以继续刚才的审查话题：ch10–ch14 那三处归属真空和 LoadSkill 顺序不变式。
可用命令：
  /help, /h, /?    显示帮助信息
```

## 决策

- **不 merge** `review/pr-2`：它基于旧主分支（删了 ch07/ch08/ch09 文档、`Agent.java` 少 126 行、
  反向新增 `CommandRouter`）。只挑着搬。
- **不做「事后擦」补丁**（在 `mainLoop` 里 `\033[J` 抹掉光标以下）：它与帧渲染抢同一块地盘，
  两处各自记账易漏。
- **采用「输入框边框」方案**：给提示符上下加常驻框，每轮由代码重建、上一轮的随之收尾。
  usage 行不再是孤儿——它上方有框接着，框的收尾逻辑负责清理。
- **不做 PR 的 Shift+Tab 切模式**（`InputPane.RowRewriter` + `printAbove` + `atWaitingFrame`
  新鲜度门槛）。没有它，模式行只在每轮/每命令后刷新，行为自洽。
- **不做 PR 的流式计时尾行**（`setTailRow` / `clearTailRow`）。我们主分支没有计时器，
  不需要抬降协议，帧渲染因此省掉一大块复杂度。

## 目标布局

```
usage: in 92391 · cache_read 81152 · out 127 · 2.1s   ← 进回滚（与现状一致）
──────────────────────────────────────                ← 分隔线（暗灰）
[default] · /permission-mode 切换                       ← 模式行（亮黄 + 暗灰）
──────────────────────────────────────                ← 分隔线（暗灰）
>* █                                                   ← 提示符（> 改成 >*）
──────────────────────────────────────                ← 页脚分隔线（暗灰）
deepseek-v4-flash · ctx ▓▓░░░░░░░░ 12% · …/ACode       ← 模型青 + 进度条青 + 暗灰
```

## 色板（取自 PR `ui/StatusBar.java`，已确认）

| 用途 | 常量 | 值 |
|---|---|---|
| 暗色辅助 / 分隔线 | `DIM` | `\033[90m` |
| 模式 | `MODE` | `\033[1;33m` |
| 模型（亮青） | `MODEL` | `\033[1;36m` |
| 进度条 | `BAR` | `\033[36m` |
| 复位 | `RESET` | `\033[0m` |

现有实现已一致、不用改值的：`ToolCallDisplay.STYLE_DIM` `\033[90m`、
`ToolCallDisplay.STYLE_NAME` `\033[1;36m`。

**查证更正（实施时发现，与初稿不符）**：初稿写的「`SelectionMenu.java:147` 内联的 `\033[33m`」
不存在——`SelectionMenu` 全文只有 `:93` 的反显 `\033[7m`。全仓库唯一的 `\033[33m` 是
`ToolCallDisplay.STYLE_RUNNING`（工具「进行中」状态黄），它是**状态语义色**、不属状态栏色板，
保持原样不动。故块 A 的改点只有 `ToolCallDisplay` 与 `MarkdownRenderer` 两处常量别名化。

## 四个块

### 块 B：渲染器单例（先决条件）— 已完成并提交（`b3f894b`）

**为什么必须**：`RenderContext.liveRenderer()` 原实现在真实终端路径下**每次调用都 new 一个**
`LiveRegionRenderer`。`rowsWritten`（活跃区已写行数，重绘时靠它上移回区顶）因此分散在
12 处调用点各自的实例里，帧渲染的 `\033[3A` 定位必然算错。

**已落地改动**：

| 文件 | 改动 |
|---|---|
| `ui/RenderContext.java` | 加 `cachedLive` 字段；`liveRenderer()` 真实终端分支改为惰性建一次、复用；无终端分支**不缓存**（避免测试间共享状态）；`attachTui` 在换终端时把 `cachedLive` 置 null |
| `ui/AcodeTerminal.java` | 构造器 `private` → 包可见（加注释说明生产路径只走 `open()`），供测试用虚拟终端构造 |
| `ui/RenderContextTest.java` | 加两条：`liveRendererIsReusedAcrossCallsWhenTuiAttached`（断言 `assertSame`）、`attachTuiInvalidatesCachedLiveRenderer`（断言 `assertNotSame`）；加 `virtualTerminal(columns, rows)` 辅助方法（`TerminalBuilder.system(false).streams(in,out).size(new Size(w,h))`） |

**验证方式**：测试断言用「复用同一实例」（`assertSame`）而非「只构造一次」，
这样块 C 之后 `liveRenderer()` 即使改成非惰性构造，测试**依然成立**，不会互相绑死。

**状态**：已完成。`mvn -Dtest=RenderContextTest test` → 7 用例全绿（原有 5 + 新增 2，2026-09-14）。

### 块 A：统一色板与 StatusBar 格式化（纯新增，无可见行为变化）— 实现已落地，测试待独立 Agent 回

| 文件 | 改动 |
|---|---|
| `ui/AnsiPalette.java` | **新建**：上表五个常量。放这里而不是放 `StatusBar`，因为 `ToolCallDisplay` / `MarkdownRenderer` 也要引用，从 `StatusBar` 取色会造成依赖倒置 |
| `ui/StatusBar.java` | **新建**（照 PR，但改从 `AnsiPalette` 取色）：`modeLine(mode, width)` / `infoLine(model, ctxFraction, projectPath, width)` / `divider(width)` / `ctxBarFilled(fraction)` / `ctxPercentText(fraction)`。PR 的 `nextModeName` **不做**（属 Shift+Tab，已砍） |
| `ui/ToolCallDisplay.java` | `STYLE_NAME`→`AnsiPalette.MODEL`、`STYLE_DIM`→`AnsiPalette.DIM`、`RESET`→`AnsiPalette.RESET`（别名化，值不变）；`STYLE_RUNNING`/`STYLE_OK`/`STYLE_ERR` 是状态语义色，**不动** |
| `ui/MarkdownRenderer.java` | `RESET`→`AnsiPalette.RESET`、`STYLE_INLINE_CODE`→`AnsiPalette.BAR`。标题蓝 `\033[1;34m` **不并入色板**（它是 Markdown 语义色，不是状态栏配色）；`STYLE_BOLD` 是 SGR 属性、`STYLE_CODE_BLOCK` 是背景色，均不动 |
| `ui/SelectionMenu.java` | **无需改动**（查证更正见上） |

`modeLine` 的第二段改为 `/permission-mode 切换`：PR 写的是 `Shift+Tab to [next]`，但我们不实现
Shift+Tab（键位在本分支不存在），照抄会印一个按了没反应的提示。换成真实可用的命令。
`modeLine(mode, width)` 因此是单参形式、无 `nextMode` 形参。

`ctxBarFilled` / `ctxPercentText` 在 PR 里是包内可见，这里放开为 `public`——块 D 的
`BuiltinCommands`（`com.acode.command` 包）要拿它画 `/status` 的 Token 行进度条。

**宽度口径**：PR 用 `String.length()` 判断放不放得下，对 CJK 是错的（一个汉字算 1 字符但占 2 列）。
本项目框线一旦折行，等待帧的上移行数就跟实际屏幕对不上，因此 `StatusBar` 内部改用 wcwidth
量显示宽度（`displayWidth` / `fit` / `tailFitting`），并保证**任何返回值显示宽度都 ≤ width**。

**遗留**：`StatusBar` 在块 A 提交时尚无生产调用点（消费方是块 C 的帧渲染与块 D 的 `/status`），
暂时只由单测覆盖。

`ctxBarFilled` / `ctxPercentText` 的口径照 PR：
- 进度条 10 格，四舍五入；占用非零但不足半格时至少亮 1 格（否则看着像空的）
- ≥10% 取整，`(0,10%)` 保留一位小数（大窗口下取整会长期显示 0%、看不出变化）

### 块 C：输入框边框与残留修复（核心，风险最高）— 实现已落地，测试待独立 Agent 回

**对照 PR 后修正了设计**（PR 的 `LiveRegionRenderer` / `CommandProcessor` / `ExchangeRunner` 已逐行读过）：
PR 的等待帧比初稿写的更简单——提示符上方只有「模式行 + 分隔线」两行，下方两行页脚；
轮次之间的分隔线由**上一帧的模式行/分隔线**充当，不再额外印一条。

| 文件 | 改动 |
|---|---|
| `ui/LiveRegionRenderer.java` | 新增 `renderWaitingFrame(out, modeHint, divider, footerDivider, footerModel)`：`appendCommitted` 写模式行 + 分隔线 → `\r\n` 预留提示符行 → 页脚两条 → `\033[3A` 回到提示符行。新增 `clearBelowCursor(out)`（写 `\033[J`，不移动光标，`rowsWritten = 0`） |
| `ui/RenderContext.java` | 新增 `terminalWidth()`（无终端按 80 估，测试路径安全） |
| `ConversationController.java` | 新增 `renderInputFrame()`：组装 `StatusBar.modeLine` / `divider` / `infoLine`；注入 `CommandProcessor.InputFrame` 匿名实现（`draw` → `renderInputFrame`，`erase` → `clearBelowCursor`） |
| `CommandProcessor.java` | 提示符 `"> "` → `">*"`；新增嵌套接口 `InputFrame` + `setInputFrame`；新增包内可见 `step(line)` 承载「擦 → 派发 → 画」；`mainLoop` 变薄 |

**与初稿的三处出入（均已按 PR 的实际做法定稿）**：

1. **不动 `ExchangeRunner`**。初稿让它「交换开始前擦、`printUsageFootnote` 之后画」，是照 PR 抄的；
   而 PR 之所以把两件事放进 `ExchangeRunner`，是因为它有 Shift+Tab 与计时尾行两套状态需要
   在各自路径上分别收尾。我们没有那两套状态，擦与画就是一对必须配对的光标操作，**放在
   `mainLoop` 一处统一做**更不容易漏（`step`: 非空行先 `erase` → 派发 → 非 EXIT 再 `draw`）。
   空行跳过整轮擦画——否则每敲一次空回车就会在屏上多叠一组模式行/分隔线。
2. **帧不进 `OutputPane`**。PR 把帧的每一行也 `output.appendLine` 了一份；本分支的 `OutputPane`
   是输出日志/测试断言的数据源，界面装饰混进去是污染，故只写终端。
3. **`step()` 抽出来是为了可测**：`mainLoop` 依赖真实终端、测不了；擦/画/派发的次序本身是
   本次最容易写错的地方，抽成包内方法后可由单测钉住次序（empty 行不擦不画、EXIT 不重画）。

**数据源已确认齐全**：
- `ExchangeRunner` 构造里已有 `projectRoot` 与 `permissionChecker`（`ConversationController.java:523`）
- `Conversation` 有 `model()` `:176`、`estimateContextTokens()` `:207`、`maxContextTokens()` `:181`
  → 上下文占比 = `estimateContextTokens() / maxContextTokens()`（在 `renderInputFrame` 里算）

**主要风险**：`\033[3A` 这套行数数学在真实终端上一旦算错就是错位；且帧是**唯一**在两次
`readLine` 之间做光标**上移**的代码（既有代码只追加、只下移），JLine `Display` 是否因此失步
必须真机验证。块 C 之后要在**改过窗口尺寸 / 超过一屏 / `/clear` / `/resume` / 空行回车 / Ctrl+C**
六种情形下各看一眼。

### 块 D：`/status` 与 `/help` 配色 — 实现已落地，测试待独立 Agent 修

**初稿的「Token 行换成进度条」被否掉**：`docs/ch09/checklist.md` 把 `/status` 的输出写成了验收规范
的逐字块（`Token：45,230 / 200,000（23%）`），并明写「**占比取整**」「千位加逗号」。文档是既定决策，
比我这份计划稿权威，所以 Token 行**只上色、不加进度条、不改百分数格式**，保持整数占比。
进度条只出现在输入框页脚（那里没有逐字规范约束）。

| 文件 | 改动 |
|---|---|
| `command/BuiltinCommands.java` | `statusLines`：标题保持原样；分隔线、六个标签走 `AnsiPalette.DIM`；模式值走 `MODE`。`helpListLines`：命令名列走 `MODEL`、描述走 `DIM`；`可用命令：` 与末尾提示行保持不加色。`Token` 行文本逐字不变 |
| `HelpStatusCommandTest` | **会红 5 处**（见下），需改成「去色后断言文本 + 另断言上色到位」 |

**会红的断言（已逐条核对）**：
- `helpListsEveryVisibleCommandIncludingQuit`：`l.startsWith("  /")` 统计名称行数 —— 名称列被上色后不再以 `"  /"` 开头
- `helpGroupsSectionsByTypeInRegistrationOrder`：同样 `startsWith("  /")`，且 `substring(2).split(" {2,}")[0]` 取名称列
- `statusShowsModeDirectoryAndVersion`：`assertEquals("─".repeat(13), lines.get(1))`（分隔线上色）、`contains("模式：default")`（标签上色后不再连续）
- `statusTokenLineGroupsThousandsAndRoundsPercent`：`contains("Token：45,230 / 200,000（23%）")`（标签上色）

修法是加一个 `stripAnsi` 辅助，内容断言走去色文本，另加一条「含 `AnsiPalette.MODE` / `DIM` 序列」的
上色断言——这样既钉住「可见文本不变」，又钉住「配色到位」。

## 顺序

1. ~~跑块 B 的测试~~ → 已完成（`b3f894b`，7 用例全绿）
2. ~~块 A → 单测绿 → 提交~~ → 已完成（`6fb21f5`，StatusBarTest 35 用例）
3. ~~块 C → 单测绿 → 提交~~ → 实现与测试均已完成（`0d9eaf1`）——**真机复验仍待做**（见下）
4. ~~块 D → 全量绿 → 提交~~ → 已完成（`5cc19cf`）。**全量 1056 用例、0 失败 / 0 错误 / 1 跳过**
5. 文档已同步（`checklist.md` T14、`manual-test.md` CMD8、`spec.md` 配色两步走）
6. **待用户真机验收**：打包后按 `manual-test.md` 的 CMD8 逐条走，重点是
   「对话一轮后敲 `/help`，页脚不再糊在一起」与两条残留风险（模糊宽度字符、多行输入压页脚）

**分工约定（用户要求）**：实现代码与测试**不由同一个 Agent 写**。本批的落地方式是——
主 Agent 写 `src/main`，另开子 Agent 只写 `src/test`，子 Agent 的 prompt 自带契约（而不是让它
读实现反推断言），发现实现与契约不符时只报告、不改实现。块 A 与块 C 各开了一个测试子 Agent。
**例外**：块 D 的测试修改（把逐字断言改成先去色再断言）因账户余额不足导致子 Agent 无法运行，
由主 Agent 代为完成；这是机械性的适配改动，不涉及新测试的独立编写。

## 收尾时要同步的文档

- `docs/ch09/checklist.md`：补一条 PR 配色对齐的验收项（含可 grep 的断言）
- `docs/manual-test.md`：CMD 小节追加「输入框上下框线在位」「`/help` 后 usage 行不残留」
- `docs/ch09/spec.md`：非功能要求里那句「配色分两步走」的第二步完成后，如实更新

## 已验证的事实（供后续查阅）

- **块 A 单测实测**（2026-09-14，`StatusBarTest` 35 用例全绿）：JLine 3.27.1 的 `WCWidth` 把
  `▓`/`░`/`─`/`…` 都判为 **1 列**（CJK 为 2），所以「进度条 10 格 = 10 列」「`divider(w)` = w 列」成立。
- **残留风险一（字符宽度）**：`▓`/`░`/`─`/`…` 都是东亚「模糊宽度」（East Asian Ambiguous）。
  若终端把模糊宽度渲染成双宽（部分 CJK 字体/终端设置会），页脚会实际占 20 列、超出按 wcwidth
  算出的宽度，进而折行、破坏帧的行数数学。单测防不了（wcwidth 没有终端上下文）。真机验收（CMD8）
  里「宽度收窄时截断而不折行」那条就是为了在真实终端上暴露它。
- **残留风险二（提示符向下占用行）**：页脚画在提示符**下方**，而 JLine 画提示符时会向下占行——
  所以**多行输入（Shift+Enter）或长输入折行时会盖住页脚**。这是 PR 那套帧设计本身的性质，不是
  本次引入的新想法；要根治得挂 JLine 的行改写钩子（PR 的 `RowRewriter`，已明确不采纳）。
  CMD8 里专门有一条让真机判：提交后不留被压掉一半的页脚残行即算通过，否则要回头改设计。
  **若真机确认重叠，兜底方案（备查）**：把页脚两行也挪到提示符**上方**（模式行 / 页脚 / 分隔线
  都在提示符之上，提示符下方什么都不画）。这样帧全部是 `appendCommitted` 的已提交内容，
  `\033[3A` 与 `\033[J` 都不再需要，多行输入不可能压到任何东西，JLine 失步的风险也一并消失。
  代价是失去「提示符下方常驻状态条」的观感（PR 的卖点），退回成「每轮一组的轮次标题」。
- PR 分支在本地：`git show review/pr-2:src/main/java/com/acode/ui/StatusBar.java`
- PR 的 usage 行**同样走 `appendCommitted` 进回滚**，不是画在输入框下方；输入框下方放的是常驻
  页脚（模型 + ctx 进度条 + 项目路径）
- PR 靠「尾行恒为屏幕最后一行、光标在其下一行第 0 列」的不变量精确收尾；我们不做计时尾行，
  所以帧渲染的收尾要按块 C 描述的方式重新设计
- `docs/ch09/spec.md:94`、`docs/ch09/tasks.md`（T1 之前那段「与 PR #2 的关系」）是这次对齐的出处
- 相关记忆：`project_pr2_not_merged.md`
