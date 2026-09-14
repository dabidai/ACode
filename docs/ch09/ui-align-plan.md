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
[default] Shift+Tab to [acceptEdits]                   ← 模式行（亮黄 + 暗灰）
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
需要对齐的：`SelectionMenu.java:147` 内联的 `\033[33m` → 应为 `\033[1;33m`。

## 四个块

### 块 B：渲染器单例（先决条件）— 代码已完成，未验证

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

**状态**：`JAVA_HOME=D:\java\jdk21 mvn -Dtest=RenderContextTest test` 已被用户打断，**未跑**。

### 块 A：统一色板与 StatusBar 格式化（纯新增，无可见行为变化）

| 文件 | 改动 |
|---|---|
| `ui/AnsiPalette.java` | **新建**：上表五个常量。放这里而不是放 `StatusBar`，因为 `ToolCallDisplay` / `MarkdownRenderer` 也要引用，从 `StatusBar` 取色会造成依赖倒置 |
| `ui/StatusBar.java` | **新建**（照 PR，但改从 `AnsiPalette` 取色）：`modeLine(mode, nextMode, width)` / `infoLine(model, ctxFraction, projectPath, width)` / `divider(width)` / `ctxBarFilled(fraction)` / `ctxPercentText(fraction)`。PR 的 `nextModeName` **不做**（属 Shift+Tab，已砍）；`modeLine` 因此要改写，或整段删掉 next 部分 |
| `ui/ToolCallDisplay.java` | 5 个常量改为引用 `AnsiPalette`（值不变） |
| `ui/MarkdownRenderer.java` | 同上。注意标题蓝 `\033[1;34m` 是 Markdown 语义色，**是否并入色板待定** |
| `ui/SelectionMenu.java` | `:147` 的 `\033[7m` 反显高亮不动；模式黄相关处改用 `AnsiPalette.MODE` |

`ctxBarFilled` / `ctxPercentText` 的口径照 PR：
- 进度条 10 格，四舍五入；占用非零但不足半格时至少亮 1 格（否则看着像空的）
- ≥10% 取整，`(0,10%)` 保留一位小数（大窗口下取整会长期显示 0%、看不出变化）

### 块 C：输入框边框与残留修复（核心，风险最高）

| 文件 | 改动 |
|---|---|
| `ui/LiveRegionRenderer.java` | 新增 `renderWaitingFrame(out, modeHint, divider, footerDivider, footerModel)`：`appendCommitted` 写模式行 + 分隔线 → `\r\n` 预留提示符行 → 页脚两条 → `\033[3A` 回到提示符行；记行数状态。新增 `clearBelowCursor(out)`（写 `\033[J`，不移动光标，`rowsWritten = 0`）。新增 `clearScreen` 时重置行数状态 |
| `ui/RenderContext.java` | 可能加 `clearBelowCursor()` 门面（无终端/无 live 时 no-op，保测试安全） |
| `ConversationController.java` | 加 `renderWaitingFrame(...)`：组装 `StatusBar.modeLine` / `infoLine`；把 `framePrinter` 注入 `CommandProcessor` |
| `CommandProcessor.java` | 提示符 `"> "` → `">*"`；接入 `framePrinter`；启动时和各命令后各渲染一次 |
| `ExchangeRunner.java` | 每轮在 `printUsageFootnote` **之后**渲染帧；交换开始前擦掉上一帧页脚 |

**数据源已确认齐全**：
- `ExchangeRunner` 构造里已有 `projectRoot` 与 `permissionChecker`（`ConversationController.java:523`）
- `Conversation` 有 `model()` `:176`、`estimateContextTokens()` `:207`、`maxContextTokens()` `:181`
  → 上下文占比 = `estimateContextTokens() / maxContextTokens()`
- `ConversationController:382` 已有同样的算例（`UIController.ContextUsage`）

**主要风险**：`\033[3A` 这套行数数学在真实终端上一旦算错就是错位。块 B 必须先落地。
块 C 之后要在**改过窗口尺寸 / 超过一屏 / `/clear` / `/resume`** 四种情形下各看一眼。

### 块 D：`/status` 与 `/help` 配色

| 文件 | 改动 |
|---|---|
| `command/BuiltinCommands.java` | `statusLines`（`:314-328`）加色：标签走 `DIM`、模式值走 `MODE`；Token 行换成 `ctxBarFilled`/`ctxPercentText` 的进度条。`helpListLines` 命令名亮青、描述暗灰 |
| `HelpStatusCommandTest` | **会红**——现有用例大概率逐字断言输出，需按新格式改 |

## 顺序

1. ~~跑块 B 的测试~~（被打断，从这继续）
2. 块 A（纯新增）→ 单测绿 → 提交
3. 块 C（帧）→ 单测绿 → **真机复验**：聊天一轮 → 敲 `/help`，确认 `usage:` 不再残留 → 提交
4. 块 D（命令配色）→ 修 `HelpStatusCommandTest` → 全量绿 → 提交
5. 更新 `docs/manual-test.md` ch09 小节的观察项；勾 `docs/ch09/checklist.md` 里那条 PR 对齐项

## 收尾时要同步的文档

- `docs/ch09/checklist.md`：补一条 PR 配色对齐的验收项（含可 grep 的断言）
- `docs/manual-test.md`：CMD 小节追加「输入框上下框线在位」「`/help` 后 usage 行不残留」
- `docs/ch09/spec.md`：非功能要求里那句「配色分两步走」的第二步完成后，如实更新

## 已验证的事实（供后续查阅）

- PR 分支在本地：`git show review/pr-2:src/main/java/com/acode/ui/StatusBar.java`
- PR 的 usage 行**同样走 `appendCommitted` 进回滚**，不是画在输入框下方；输入框下方放的是常驻
  页脚（模型 + ctx 进度条 + 项目路径）
- PR 靠「尾行恒为屏幕最后一行、光标在其下一行第 0 列」的不变量精确收尾；我们不做计时尾行，
  所以帧渲染的收尾要按块 C 描述的方式重新设计
- `docs/ch09/spec.md:94`、`docs/ch09/tasks.md`（T1 之前那段「与 PR #2 的关系」）是这次对齐的出处
- 相关记忆：`project_pr2_not_merged.md`
