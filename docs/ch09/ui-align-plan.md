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
[default] · /permission <模式>                           ← 模式行（亮黄 + 暗灰）
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

`modeLine` 的第二段改为 `/permission <模式>`：PR 写的是 `Shift+Tab to [next]`，但我们不实现
Shift+Tab（键位在本分支不存在），照抄会印一个按了没反应的提示。换成真实可用的命令——
注意命令名是 **ch09 改名后的 `/permission`**（无参只查看规则、带参数才切档），不是旧名
`/permission-mode`；初稿这里写错过一次，由用户问出来才发现。
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
| `ui/LiveRegionRenderer.java` | 新增 `renderWaitingFrame(out, footer, divider, modeHint)`（**v2，见文末「真机复现后的设计修正」**）：四行全部 `appendCommitted`，光标停在提示符行由 JLine 接着画。新增 `clearBelowCursor(out)`（写 `\033[J`，不移动光标，`rowsWritten = 0`） |
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

**块 C 初稿的「主要风险」——已在真机上兑现，见文末「设计修正」**：`\033[3A` 那套行数数学
在真机上确实算错了，而且错法比预想的多一种（多行输入）。原判断「JLine `Display` 是否因此
失步必须真机验证」是对的，验证结果：**是**，失步不可避免。

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
- **残留风险二（提示符向下占用行）—— 真机已复现，已按兜底方案改掉，见文末「设计修正」**：
  页脚画在提示符**下方**，而 JLine 画提示符时会向下占行——所以**多行输入（Shift+Enter）或长输入
  折行时会盖住页脚**。这是 PR 那套帧设计本身的性质，不是本次引入的新想法；要根治得挂 JLine 的
  行改写钩子（PR 的 `RowRewriter`，已明确不采纳）。
  当时的兜底方案（备查、现已采用）：把页脚两行也挪到提示符**上方**（模式行 / 页脚 / 分隔线
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

---

## 真机复现后的设计修正（v2，2026-09-14）

真机跑块 C 的产物，**两条缺陷同时现形**（用户复现，非推测）：

1. **Ctrl+C 后页脚残留**：等输入时按 Ctrl+C，屏上留下
   `[default] · /permission <模式>` + 分隔线（还带 `PS D:\code\claude\ACode>` 夹在中间），
   再下面才是另一条分隔线与页脚——shell 提示符被顶到帧中间。
2. **Shift+Enter 多行输入糊屏**：出现 `> ────────`、`> epseek-v4-flash · ctx …` 这类
   **被吃掉首字符、且带续行提示符 `> ` 前缀**的残行。

**根因（一条，不是两条）**：提示符行**及其下方**是 JLine `Display` 的地盘，它按自己记录的行数
擦除/重绘。帧往那片区域写字，JLine 并不知道。
- Ctrl+C 时 `readLine()` 抛异常直接 return，`step()`（唯一负责擦帧的地方）没被调用；
  JLine 只擦提示符行，下方页脚两行原样留在屏上。
- 多行输入时 JLine 在提示符行下面续画，正好压进预留的页脚行；回车时它按自己记的行数擦，
  跨度对不上，于是留下带 `> ` 前缀的半截行。

**改法：采用「残留风险二」早先记下的兜底方案——帧整体移到提示符上方。**

- `renderWaitingFrame` 新签名为 `(out, footer, divider, modeHint)`，方法体只剩四次
  `appendCommitted`，**没有 `\r\n` 预留行、没有 `\033[3A`**。输出顺序即参数顺序：
  页脚 → 分隔线 → 模式行 → 分隔线，光标停在下一行行首，JLine 在那里画 `>*`。
- `clearBelowCursor` **保留**（此前以为可以一并删掉，实际不行）：`erase()` 仍需要它擦掉
  JLine 在多行输入后留在提示符行以下的残迹。语义从「擦页脚」变成「擦 JLine 的地盘」。
- `mainLoop` 的 Ctrl+C / Ctrl+D 分支**补上 `inputFrame.erase()`**——原先这条路径直接
  `closeSession()` 返回，一帧都不擦，是真机残留的直接原因。
- 帧每轮照旧重画（模式与 ctx 进度是变化的），代价是每轮往回滚里多留 4 行，接受。

**收益**：多行输入不可能压到任何东西；JLine 失步风险消除；Ctrl+C/退出路径不再有孤儿行。
**代价**：页脚从「提示符下方常驻状态条」变成「每轮一组的轮次标题」——PR 的观感卖点没了，
这是明确放弃，不是遗漏。

**测试同步**：`LiveRegionRendererTest` 的 `renderWaitingFrameWritesExactByteSequence` 改为断言
新的四行字节序列并**断言不含任何光标上移序列**；另加 `renderWaitingFrameLeavesNoPromptRowInsideTheFrame`
钉死「帧内不得有空行占位」。`CommandProcessorTest` 的 `step` 次序用例（擦→派发→画）语义未变，未改。

---

## v2 被否 + v3 定稿：改用 JLine 原生 `Status`（2026-09-14 当日第二次真机反馈）

**v2 被用户否掉的原因不是崩，是观感**：帧整体上移后，每轮的 4 行都留在回滚里。用户实际看到的是——

```
● 你是什么模型？
<回答>
usage: in 4641 · ...
deepseek-v4-flash · ctx ▓░░░░░░░░░ 1.0% · D:\code\claude\ACode   ← 本轮的页脚
──────────────
[default] · /permission <模式>                                    ← 本轮的轮次标题
──────────────
>*
```

于是「当前输入框」被埋在一堆历史帧下面，而且**上一轮的模式行/分隔线还悬在用户消息上方**，
看起来就是「同样的东西出现了两次」。用户的要求很明确：**保持原来的观感**（模式行+分隔线在上、
页脚在下），**但把多行输入压掉下划线的问题真正解决**。

**v1/v2 错在哪（结论）**：都在用 `\033[3A` / `\033[J` 手工维护提示符下方那几行，而 JLine 的
`Display` 并不知道那片区域被占了。手工算行数必然与 JLine 自己记的行数对不上，多行输入和
Ctrl+C 各暴露一种错法。**这不是行数算得准不准的问题，是那片区域的归属问题。**

**v3：把提示符下方交给 JLine 自己的 `Status`。** JLine 3.27.1 的
`org.jline.utils.Status` 就是干这个的（`org/jline/utils/Status.java`）：
- 靠终端滚动区（`change_scroll_region`）把屏幕底部若干行划走，`Display` 管自己的行数；
- `LineReaderImpl.displayRows()` 会**减掉 `status.size()`**（`:4720`），所以提示符可用行数天然
  不含状态区，多行输入只会在状态区之上滚动（`Display` 溢出时只滚出可见部分，不会推进状态区）；
- resize 时 LineReader 调 `status.reset()`（`:1207`），每次 redisplay 后调 `status.redraw()`
  （`:3882`），都是 JLine 自己维护的，我们不用管；
- 不支持的终端上 `Status.getStatus(t, false)` 返回 `null`，天然降级。

落地：

| 文件 | 改动 |
|---|---|
| `ui/LiveRegionRenderer.java` | 删 `renderWaitingFrame` 与 `clearBelowCursor`（手工光标那套全部作废）；新增静态 `statusOf(AcodeTerminal)` 与 `updateStatus(Status, List<String>)` |
| `ui/RenderContext.java` | 新增 `status()`（惰性建立、换终端失效）、`updateStatusLines` / `hideStatusLines` / `closeStatus` |
| `ConversationController.java` | `InputFrame.draw` = 模式行去重追加（变了才画，画在提示符上方）+ `renderFooter()`（`Status` 画分隔线 + 页脚）；`erase` = `hideStatusLines()`；`/clear` 与 `finally` 各补一次状态区收起 |
| `CommandProcessor.java` | `mainLoop` 的 Ctrl+C / Ctrl+D 分支保留 `inputFrame.erase()`（收起状态区） |

**模式行去重是必需的**：页脚归 `Status` 后每轮原地重绘、不堆历史；模式行仍走回滚，不比对就会
每轮多留一份「模式行 + 分隔线」——v1 真机上正是这个把界面糊得看不出层次。

**验证过的事实**（跑真实 JLine 类得到的，非推断）：`AttributedString.fromAnsi()` 对带 SGR 的页脚
串算列宽正确——同一串 `charLen=79`、`columnLength=54`，即 `\033[1;36m` 这类序列不进宽度，
`Status` 的「补空格/超宽加省略号」排版不会误判。

**真机待验（CMD8 已按此重写）**：`Status` 依赖 `change_scroll_region` + `save/restore_cursor` +
`cursor_address` 四个能力。**结果见下一节——当时「Windows Terminal 理论上齐备」这个事实判断对了，
但据此推出的结论错了，根因根本不在能力上。**

---

## 两次误诊 + v5 定稿：去掉 terminfo 的 `am`（2026-09-15）

v3 上真机后页脚**一个都没出现**。这里记一笔两次误诊，它们有个共同毛病：**搜错关键词就下结论**。

**误诊一（能力缺失）**：判定「JLine 自带的 15 个 caps 文件没有一个含 `csl=`，所以
`change_scroll_region` 恒缺、`Status` 恒 null」。**错在搜的键名。** `change_scroll_region` 在
terminfo 里的短名是 **`csr`**（`capabilities.txt:88` 登记了 `csr`/`cs` 两个别名）；`csl` 是另一个
能力（`change_scroll_region_left`，左边距滚动区），跟本问题无关。拿一个不存在的键名去 grep，
搜不到是必然的。基于此误诊写出的「注入 csl」方案（v4）整个作废。

**误诊二（真因是接线）**：写了个探针在你的真实终端里跑（`TerminalBuilder` 开系统终端，
打印 type / 四个能力 / `Status.getStatus(t, true)`），输出是——

```
type = windows-vtp
change_scroll_region = \E[%i%p1%d;%p2%dr   ← 在
save_cursor / restore_cursor = \E7 / \E8     ← 在
cursor_address = \E[%i%p1%d;%p2%dH           ← 在
status(create=true) = created
```

能力齐备、`Status` 建得出来。于是查 JLine 源码：`LineReaderImpl` 里五处取 `Status` 的地方
**全部传 `create=false`**（只读不建），建的责任在应用层——而 `LiveRegionRenderer.statusOf`
也传了 `false`，全仓库没有一处建过它。**页脚消失的真因是这一个布尔值**，与 terminfo 无关。

改 `create=true` 后页脚出来了，但真机暴露新缺陷，v5 修的就是它。

**新缺陷的表现**：分隔线贴屏幕底边、页脚模型名缺首字母（`eepseek-v4-flash`）、两行糊成一行。

**根因（字节级实证）**：`Status.update` 把每一行**补齐到终端全宽**，于是分隔线正好写满最后一列。
JLine `Display` 从右边界移到下一行用的是一个技巧——在最后一列写个空格触发终端自动折行，再退格
回列 0（`Display.java` 的 `rawPrint(' ')` + `key_backspace` 分支）。用探针（`ExternalTerminal`
承载输出、type 设 `windows-vtp`、尺寸 128×30）抓到的真实字节流是：

```
ESC 7  ESC[29;1H  ESC(0  ─×128  ESC(B  ' '  \b  d e e p s e e k …
                                     └──────┘
                              就是这两步在 Windows 上失效
```

Windows Terminal 的 pending-wrap 会被退格**取消**而不是折行，于是页脚首字母落回分隔线行末、
两行糊在一起，分隔线也因此顶到屏幕最底行。

**v5：去掉 `am`。** 那个技巧的开关是 `Display.wrapAtEol`，取自 terminfo 布尔能力
`auto_right_margin`（`am`）（`Display.java:51`，构造时读一次）。去掉它之后走另一个分支：
行尾发 `CR`（取消 pending-wrap）+ 显式下移——**这正是本项目的 `appendCommitted`（行尾 `\r\n`）
已在真机验证可靠的写法**（提示符上方那条满宽的模式行分隔线一直渲染正常，用的就是它）。
全 JLine 只有两处消费该能力（`Display` 与 `LineReaderImpl.freshLine`），都有显式分支，无暗依赖。

落地：新增 `ui/TerminalCaps.java`（纯函数：去 `am`、判 Windows、自定义类型名），
`AcodeTerminal.open()` 在「Windows 且用户未显式指定 `TERM` / `org.jline.terminal.type`」时
把 JLine 自带的 `windows-vtp.caps` 读进来去掉 `am`、以自定义名 `acode-vtp` 注册并
`.type("acode-vtp")`。**不能覆盖内置名**——`InfoCmp` 静态块已把 `windows-vtp` 等 15 个名字
`putIfAbsent` 预注册，`setDefaultInfoCmp` 对已存在的键不生效。Linux 路径一行不改。

**这是对 JLine 谎报终端能力**：JLine 会认为该终端不自动折行，全部行尾转移改走 CR + 显式下移。
该路径与 ACode 已验证的模式一致，但主输入区（LineReader 的 Display）同样受影响——真机若发现
输入区行转移偏移，回退方案是页脚改**单行**（分隔线与页脚合并成一行，无行间转移，视觉略变）。

### 布局语义（与用户确认，验收基准）

输入框的整体语义是「**上方紧贴对话内容、下方紧贴屏幕底部**」：

- **提示符上方**：模式行 + 分隔线，作为**多行提示符**的一部分常驻在输入行上方
  （v6 改；此前是追加进回滚、随输出滚动）。
- **提示符下方**：分隔线 + 页脚，钉在屏幕底部（`Status` 滚动区，常驻、不随输出滚动）。
- 内容量少时（如刚启动）提示符停在上方、页脚钉在底部，**中间的空档是预期现象，不是缺陷**。

> 「随输出滚动」是 v5 及以前的表述，v6 已作废，见下节。

## v6：照桌面 UI.txt 补缺口（状态行固定 + 截断省略号 + resize）

**触发**：用户桌面的 `UI.txt` 是一份「Java 终端输入界面模仿 Claude Code」的需求稿。逐条对照后
确认它就是本章 UI 对齐工作的原始需求文本，且大部分已在 v1~v5 落地；剩 6 处差距，逐项定夺如下。

| 项 | UI.txt 要求 | 处置 |
|---|---|---|
| ① | 输入以 `/` 开头时状态行实时切命令提示模式 | **作废**（用户：这条是错的，不要） |
| ② | 状态行固定底部（不许随对话滚走） | **改**：模式行 + 上边框改为多行提示符 |
| ③ | 目录中间省略 `D:\code\...\ACode` | 保持尾部 `…ACode`（信息密度更高） |
| ④ | 状态行变窄时截断加省略号 | **改**：`modeLine` 截断补 `…` |
| ⑤ | resize 时重算重绘、不闪烁 | **改（有限范围）**：注册 SIGWINCH 重建页脚 |
| ⑥ | 提示符 `>` | **改**：由 `>*` 回到 `> `（推翻 T14 的对齐结论） |

**② 的做法与代价**。模式行 + 上边框从「`appendCommitted` 进回滚」改成 JLine **多行提示符**
（`InputFrame.promptHeader()` → `CommandProcessor.prompt()`）。附带好处：模式行不再每轮往回滚里
堆一份，原先那套「按内容去重」的 `lastModeLine` 比对可以整个删掉。

**已知限制（非缺陷）**：JLine 的提示符文本在 `readLine` 进行期间**无法改写**——这是选它当载体的
直接代价。因此切档要等这一轮提交、下一轮重建提示符时才反映到屏上（输入 `/permission acceptEdits`
后回车、下一次等输入时能看到新档位）。也正因为此，①「打字过程中状态行实时变化」在提示符载体上
做不到，需要另找位置——而用户判定该需求本身是错的，遂整体作废。

**⑤ 的做法与边界**。JLine 自己收得到 SIGWINCH、也会让 `Status` 重绘，但用的是**旧宽度**算出的
那行文本，收窄后会折行或截错，所以要按新宽度重排一遍。信号处理器在信号线程上跑，因此：

- 只读 `footerModel` / `footerCtxFraction` / `footerProjectPath` 三个 volatile 缓存值重排版，
  **不碰 `conversation`**（跨线程读会话历史会与主线程的追加竞争）；
- 写入走 `RenderContext` 的 `statusLock` 与主线程每轮的帧刷新串行化；
- `footerVisible` 守住「已收起的页脚不被 resize 又显示出来」。

**⑤ 未覆盖的部分**：提示符块（模式行 + 上边框 + 输入行）在 `readLine` 期间保持旧宽度，等这一轮
提交后自动修正；已提交内容的 reflow 交给终端原生行为（ACode 是 append-only 回滚，不做全文重绘）。
真机需重点看：resize 时是否触发处理器、有无闪烁，以及**多行提示符在回车时是否被 `ERASE_LINE_ON_FINISH`
整块擦净、不留孤儿行**——后者是本次改动风险最高的点，若留残行则退回「模式行不进提示符」的旧方案。

