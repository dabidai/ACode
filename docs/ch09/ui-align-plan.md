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

## v7：把整个输入框沉到屏幕底部

**触发**：v6 真机验收后，用户提出 v6 的空档位置不对——「输入框的下划线贴在屏幕底部、上边的贴在
对话内容上，中间空了很大一块」。v6 及以前把这种空档写成了「预期现象」，v7 推翻它：
**整个输入框（状态行 + 上边框 + 输入行 + 下边框 + 页脚）整体沉底，空档移到框的上方。**

**为什么难**：JLine 把提示符画在**光标当前所在行**，没有「提示符钉底」这种能力
（`LineReader.Option` 全量枚举里没有任何 pin/bottom/scroll 选项）。要让输入框沉底，
只能**先把光标挪下去**，让 JLine 在那里画。

**先做探针再动主代码**。这套机制每轮要「下移 → 画提示符 → 回车后上移回退」，边界多，
而 ch09 当年正是这类光标手工操作来回修了五版残行缺陷。所以先写了个独立探针
（`probe/CursorProbe.java`）在真机上把机制验穿，再移植——探针本身也踩了两个坑：

- **terminfo 的 `\E` 必须解释**。`u7` 的值是字面量 `"\E[6n"`，直接 `writer.write` 原文会把这六个
  字符打到屏幕上（第一版真机就是这样，CPR 0/29 全是假阴性）。要走 `puts`/`Curses.tputs`。
- **回车后光标停在提示符块的「首行」，不是底行**。从 JLine 源码推的是底行（`Display.currentPos`
  取的是 `(N-1)*columns`），真机实测是首行；按底行回退会多上移两行、撞到屏幕顶被钳住，
  于是每轮新内容都覆盖第一行。**这类结论只能实测，读源码会得出错的答案。**

**机制**（`ui/BottomAnchor.java`）：

1. CPR（`\033[6n`）问终端光标在第几行，**带超时**；
2. 下移到「滚动区底行 − 提示符高度 + 1」——即 `height - footerRows - promptRows + 1`；
3. `readLine` 后按**相同的下移量**上移回退，让后续内容接在上一段之后而不是掉到空档下面。

**为什么不用 `Terminal.getCursorPosition`**：JLine 自己那份（`CursorSupport`）内部是
`terminal.reader().read()`——**无超时的阻塞读**。终端若定义了 `user6`/`user7` 却不回应
（`windows-vtp` 两者都有），界面会永久卡死。本实现改用 `NonBlockingReader.read(timeout)`。

**为什么跨轮挪光标是安全的**：`LineReaderImpl` 每轮 `readLine` 都 `new Display(...)`
（`doDisplay()`，`LineReaderImpl.java:804-812`），把「当前光标处」当作原点，`oldLines` 为空。
所以两次 `readLine` **之间**由外部挪光标不会累积错位。`readLine` **进行中**挪动才会失同步。

**降级**：终端不回应 CPR、缺 `user6`/`user7`、无页脚、测试路径——一律不动光标，
退回「输入框跟在内容后面」的老行为。

**resize 仍是坏的，但与本条无关**。用改动前的 jar（无钉底）跑，缩小窗口同样错乱，已实证。
根因在 JLine `Status`：它不主动感知尺寸（`display.rows` 只在 `resize()` 时更新），缓存的
`scrollRegion` 在尺寸突变后失准，而 `update()` 会拿它当绝对定位锚点。探针里试过
`resize() + reset()`：**几何跟得上尺寸**（`滚动区底行` 45↔28 正确切换），但 resize 后
CPR 报第 1 行、内容被从头覆盖，**未解决**。作为独立待修项记在 T16，探针留作复现工具。

## v8：活动输入期间 resize 修复

**触发**：`docs/ui-resize-diagnosis/` 与 `probe/ResizeProbe.java fix` 已把问题收敛到两层：活动
`readLine()` 会临时接管 WINCH，应用层 handler 无法重建 prompt；终端 reflow 后，JLine Display
仍持有旧光标模型，单纯 `resize()+reset()` 不能把底部输入块重新锚定。

**第一版失败结论**：探针的 ANSI CPR 会与活动 `readLine()` 竞争同一个 terminal reader。真机日志连续
出现 `现场 CPR=失败`，CPR 响应被当作用户输入显示成 `;;3R` / `R[...]`，并伴随重复框线。虚拟终端
没有 CPR capability，原自动化只走了降级分支，未暴露该问题。活动读取期间发送 CPR 的方案已废弃。

**第二版实现（真机失败）**：`ResizeAwareLineReader` 以 120ms 间隔观察尺寸变化，但活动读取期间绝不访问
terminal reader。Windows 使用已有 JNA 依赖调用 `GetConsoleScreenBufferInfo`，从输出控制台原生读取
光标与 viewport 坐标。尺寸变化时：

1. 用 Windows 原生坐标找到当前输入行，从多行 prompt 顶部清到屏尾；
2. 按新高度重新定位到 `height - footerRows - promptRows + 1`；
3. 通过 `LineReaderImpl` 的 protected/public 扩展点重置 Display、替换新宽度 prompt 并 redisplay；
4. 调用输入帧的 resize 回调，按新宽度刷新 Status 页脚。

生产实现不反射 JLine 私有字段，也不发送 ANSI CPR，但真机证明 watcher 与 JLine 自身 WINCH 会形成
两条重绘链：放大时输入区异常变高，缩小时页眉与分隔线重复堆叠，因此第二版废弃。

**第三版实现**：删除 watcher，覆写 `LineReaderImpl.handleSignal(WINCH)`。在同一个 synchronized 信号路径中：

1. 调用 `super.handleSignal` 前重算动态 prompt，并在 Windows 原生坐标可用时清理、重锚定旧输入块；
2. 由 JLine 默认实现完成唯一一次 Display/Status resize、reset 与 redisplay；
3. 默认重绘完成后调用输入帧回调，按新宽度刷新 footer。

原生坐标不可用时不做外部清屏，只更新 prompt 并保留 JLine 默认重绘。自动化用虚拟终端保持
`readLine` 阻塞，把尺寸从 80x24 改为 60x20 后显式触发一次 WINCH，断言回调恰好一次、动态
prompt/footer 刷新、`hello` 输入原样返回且输出不含 `ESC[6n`。真实终端拖拽仍按 checklist/manual-test 验收。

**第三版真机失败**：虽然只有一个 handler，但它在 JLine 默认重绘前先移动了真实光标。JLine 内部
`Display.cursorPos` 没有同步这次移动，连续 WINCH 便按错误原点重复输出页眉；缩窄时旧分隔线发生的
物理折行又让按换行数推导的清屏起点偏低，最终形成逐行左移的阶梯状页眉。

**第四版实现（稳定优先）**：活动输入的 WINCH 路径不再查询或移动真实光标，也不执行应用层清屏；
它只替换按新宽度生成的 prompt，然后让 JLine 默认 handler 独占 Display 重绘。读取前按旧高度得到的
pin 距离若遇到 resize 就作废，避免提交/退出时把光标拉回错误行；下一轮以新尺寸重新 pin。
自动化新增 20 次交替尺寸事件风暴、编辑中缩放和下一轮标记复位，第四版全量回归为
1123 tests、0 failures、0 errors、1 skipped。实时缩放时允许本轮暂时失去严格钉底，以不重复、不覆盖、
不丢输入为更高优先级；最终视觉效果仍需真实 Windows 终端验收。

## 最终方案与收尾（2026-09-26）

第四版及其后的多行 JLine prompt 在真实 CMD 缩放时仍留下重复模式行。最终实现让 JLine 继续处理按键编辑，`ResizeAwareLineReader` 将模式行、分隔线、输入内容和页脚统一绘制到固定高度的 `Status` 区域。输入多行时消耗预留空白行，避免在编辑过程中反复改变滚动区域。缩放发生时清除 Windows 回流残影，再从 `OutputPane` 重放已提交的 ACode 对话。

真实 CMD、PowerShell 和 Windows Terminal 已验证缩放及多行输入；CMD 保留原生鼠标拖选复制，原生滚轮回看时输入框跟随视口移动。`/quit` 的关闭顺序已调整为先关闭 `Status` 再关闭终端，CMD 真机退出正常。2026-09-26 的全量测试为 1138 tests、0 failures、0 errors、1 skipped。最终 Ctrl+C、Ctrl+D 以及最新 jar 的真机复核记录以 `checklist.md` 文末为准。

11:04 的 CMD 复核又发现两个视觉问题：初次建状态区时预留半屏会将 banner 挤出当前画面；缩放重放对话后，`Status` 可能保存位于新滚动区之外的光标，使 banner 与输入框分隔线交错。已将正常尺寸下的固定预留区缩为约三分之一屏，并在每次 `Status.update` 前按**即将使用的**状态区高度把光标放回对话区。退出时 `Status.close` 之后仅清除当前可见屏幕，保留终端回滚。11:12:12 打包的 jar 已通过 1138 个全量测试，三项视觉效果仍须真机复验。

11:12 版真机显示退出已正常，但启动 banner 仍被挤出画面，缩小窗口仍在 banner 上方留下输入框残影。11:22 版在首次画出状态区后主动从 `OutputPane` 重画当前对话区；WINCH 时先让 JLine 完成内部重绘，再清除终端回流，重放已提交对话并重画当前输入框，避免清屏之后又被 JLine 的旧帧覆盖。39 个定向测试通过；最终全量和 CMD 视觉结果待补。

11:22 版真机仍产生多份 banner 和大段空白：在重放对话后再次调用 `Status.update`，会把刚重放的内容向上滚动；首次画框后重画对话也会留下原 banner。11:29 版在打印 banner 前先建立固定高度的状态区；缩放时等 JLine 定好新尺寸，清屏重放后直接按绝对行绘制当前帧，并恢复同一滚动区域，不再触发第二次 `Status.update`。当前只完成 77 个终端布局定向测试，CMD 视觉与最终全量回归待验。

11:29 版 `UI.txt` 仍记录到重复 banner 和旧输入框。代码复核发现缩放路径把 `OutputPane` 历史再次追加到终端，随后又按坐标绘制相同历史，前一步会制造多余回滚和空白。11:35 版取消追加，缩放时仅清当前屏幕，直接由 `OutputPane` 画可见历史和最新输入帧；1139 个全量测试通过，但 CMD 缩小窗口仍在回滚缓冲中留下旧框。后续版在缩放时用 ED3 清除 Windows 回流产生的旧缓冲，再直接绘制当前历史和帧；退出仍只用 ED2 清当前屏幕。最终 CMD 视觉待验。

11:46 版仅加 ED3 后，用户反馈缩小、放大都仍异常。JLine 3.30 的 `LineReaderImpl.handleSignal(WINCH)` 会先调用 `Status.resize()`，该方法按旧状态区行数搬动并清理终端行，然后才重画输入。11:50 版在调用父类处理 WINCH 前先隐藏旧 `Status`，让它以空行集完成尺寸更新；父类生成新帧后再清屏并直接绘制 `OutputPane` 可见历史和新帧。77 个界面定向测试通过，CMD 视觉待验。

11:50 版 `UI.txt` 仍显示旧帧、大段空白和新帧串接。CMD 对 ANSI ED3 的行为不足以清理回流到缓冲区的旧内容。11:57 版在原生 Windows 控制台的 WINCH 路径直接调用 `FillConsoleOutputCharacterW` 与 `FillConsoleOutputAttribute` 清空旧缓冲，再按 `OutputPane` 重画视口；调用不可用时退回 ANSI 路径。79 个相关测试通过，真机待验。

**本轮最终决定（2026-09-26）**：用户复验 11:57 版后确认 CMD 缩小和放大窗口仍异常，并要求先停止处理，在文档中记录。ED3、缩放前隐藏 `Status`、原生控制台清缓冲这几种未通过真机验收的尝试均已撤回，最终代码保留 11:35 版的启动同屏修复、无重复追加的视口重绘、以及退出只清当前屏幕的行为。CMD 缩放时仍可能出现旧输入框、重复 banner 和大段空白；这是**未解决的已知问题**，不计入阶段九已通过验收。后续若重启该工作，应以 `C:\Users\liuch\Desktop\UI.txt` 中最新的 CMD 可见画面为复现基准，同时核对 JLine `Status.resize()`、Windows 控制台缓冲区和实际视口坐标的关系。优先建立能断言缩放后物理屏幕内容的测试，再修改终端控制序列。
