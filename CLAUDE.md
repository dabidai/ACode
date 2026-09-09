我正在构建一个终端 AI 编程助手（类似 Claude Code），项目名叫 ACode，使用的编程语言是
Java。

每次我会提出一个初步的想法，需要你通过向我提问，帮助我澄清需求、挖掘边缘场景。澄清清楚后共创三份文档保存到项目根目录：

# 三份文档的角色与边界

## spec.md
回答：要解决什么问题、做哪些能力、不做哪些、什么算完成。
写：背景、目标用户、能力清单（一句话一条）、非功能要求、设计骨架、Out of Scope
不写：具体函数名 / 参数名 / 默认值 / 错误文本 / 行号 / SDK 类型名
   （这些是实现细节，spec 改一次就过期，维护爆炸）

## tasks.md
回答：按什么顺序做、每步动什么文件。
- 5~15 个任务，每个能在一次专注会话内完成
- 每个任务标注：影响文件、依赖任务、参考资料定位（精确到函数/行号都可以）
- 最后一定有「接入主流程」+「端到端验证」两个任务

## checklist.md
每一项必须可勾选、可观测，不许写「实现完整」「质量良好」。
- 把 spec 里被砍掉的具体值（错误文本、默认值、阈值）放进来作为验收项
- 写法举例：「`grep -r X` 返回 ≥3 条」「输入 Y 看到输出 Z」
- 至少一条端到端验收

---

# ACode 终端 UI 实现记忆（持久，勿删）

## 构建环境（必须）
- 所有 mvn 命令前先设 JDK 21，否则 Maven 默认走 Java 8，会在文本块（如 BANNER）编译失败：
  `export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot"`
- 打包 `mvn -q -DskipTests package` → `target/acode.jar`；运行 `java -jar target/acode.jar`（需真实交互式终端）。

## 关键不变量：LiveRegionRenderer 必须单例共享
- `RenderContext.liveRenderer()` 在真实终端（tui）路径必须返回**同一个缓存实例**（字段 `cachedLive`），不能每次 `new`。
- 原因：`LiveRegionRenderer` 持有跨调用状态 `linesSinceFrame`（模式提示行到光标的行数）、`rowsWritten`（重绘上移行数）、`tailRow`（常驻末尾的计时尾行文本）、`lastW/lastH`（尺寸变化检测）。
- `ConversationController`（启动等待帧）、`ExchangeRunner`（每轮结束等待帧）、`CommandProcessor`（Shift+Tab 原地改模式行）必须共享同一实例。
- 历史 bug：每次 `new` → Shift+Tab 回调里 `linesSinceFrame=0` → `atWaitingFrame()` 为假 → 把新模式行当**新行追加**（堆叠），而非原地更新。测试注入路径（`setLive`）与无终端 fallback 保持每次可新建，勿改。

## 终端布局机制（规范：提示符下方常驻页脚）
- 渲染走「append 提交进原生回滚」：`appendCommitted` 每行写 `行\r\n` 且 `linesSinceFrame++`（光标下移与计数严格同步，故计数=模式行到光标的物理行数）。
- 等待帧 `renderWaitingFrame(out, modeHint, divider, footerDivider, footerModel)`：提交 模式提示+分隔线 → `\r\n` 预留提示符空行 → 提交 页脚分隔线+模型信息 → `\033[3A` 光标上移回预留行 → `linesSinceFrame=WAITING_FRAME_ROWS(=2)`；JLine 随后在预留行画 `>*`。相对上移对「页脚落屏幕末行触发滚动」安全（偏移恒为 3）。前提：调用时不存在尾行。
- 布局自上而下：模式提示 → 分隔线 → `>*` 提示符 → 页脚分隔线 → 页脚模型信息 `model · ctx ▓▓▓░░░░░░░ 12% · 项目全路径`。
- 模型信息**只在页脚**出现（回复前不再单独打印）；echo 无空格：`>*`+输入原文。
- **页脚在提示符下方 ⇒ 回车后必然留残留**：JLine 的 `ERASE_LINE_ON_FINISH` 只擦提示符行本身（`cleanup()` 清空 prompt/buffer 后 `redisplay(false)`、不 println），页脚分隔线与模型信息行仍在屏上；而 `appendCommitted` 写 `行\r\n` 时**不先清行**，短文本盖不住页脚模型信息行的尾巴。故每次交换/命令输出开始前必须先 `live.clearBelowCursor(writer)`（写 `\033[J`，不移动光标，并把 `rowsWritten/linesSinceFrame/tailRow` 归零）。
- **每条会输出的斜杠命令之后都要重绘等待帧**：`CommandProcessor.mainLoop` 统一走「`clearBelowCursor` → 冲刷延迟消息 → `executeCommand` → `framePrinter.run()`」；`CHAT` 交给 `ExchangeRunner`（它自己擦页脚、结束时自己渲染帧），`SKIP`（空行）直接 `continue`，`QUIT` 存档返回，`CLEAR` 自带 `clearScreen`+重绘，`RESUME` 之后补 `framePrinter.run()`（`SessionManager` 全程只 `clearScreen`、不重绘帧）。不这么做的话：提示符压在页脚分隔线上、输入盖住模型信息行，且 `linesSinceFrame` 随输出增长，此时 Shift+Tab 会上移到早已滚进回滚的旧模式行、改坏可见区顶行。

## Shift+Tab 原地改模式行（CommandProcessor.cyclePermissionMode，包级可见）
- 循环 default → acceptEdits → plan → bypassPermissions。绑定：`InputPane` 把 `\033[Z` 绑到 widget，widget 调 `setCyclePermissionCallback` 注入的回调（在 JLine `readLine` 期间触发）。
- **绝不能在 widget 回调里直接往终端 writer 写 ANSI**。JLine 的 `org.jline.utils.Display` 缓存了「自己画了哪些行、光标在第几列」，之后所有移动都由 `moveVisualCursorTo` 从这份缓存推算；我们绕过它写完，物理光标在提示符行第 0 列而 Display 以为在 `width(">*"+buffer)` 列，下一次按键就按陈旧坐标定位、字符吃掉 `>*`，一直错到回车重开 `readLine` 才恢复——这就是「切完模式必须敲一次 Enter」的根因。`Display.reset()` 虽是 public 但 `LineReaderImpl.display` 是 protected 拿不到；`callWidget(REDISPLAY)` 只对未变化的缓存做 diff，修不了光标列。
- **唯一正确原语是 `LineReader.printAbove(String)`**（已反编译 jline-3.27.1 核实）：`lock` → 若 `reading` 则 `display.update(emptyList, 0)`（擦掉输入区、光标落到输入区顶行第 0 列、缓存清空）→ 串以 `\n`/`\n\033[m`/`\n\033[0m` 结尾则 `print(s)` 否则 `println(s)` → 若 `reading` 则 `redisplay(false)`（从空缓存全量重绘 `>*`+buffer）→ `flush`。
- 接线：`InputPane.RowRewriter`（嵌套函数式接口，测试可注入假实现）；真实实现是 `InputPane.rewriteRowAboveInput(rowsAbove, text)` → `reader.printAbove(rowRewriteSequence(...))`，`mainLoop` 用方法引用 `input::rewriteRowAboveInput` 传进去。**注意 `class InputPane implements InputPane.RowRewriter` 会触发 javac「循环继承」错误**（类不能把自己的嵌套类型写进 implements），故 InputPane 不声明 implements，靠方法引用结构化对接。
- `rowRewriteSequence(rowsAbove, text)` = `\033[rowsAbove A` + `\r\033[2K` + text + `\r` + (`rowsAbove-1>0` 时 `\033[(rowsAbove-1)B`) + `\r\n`。三个易错点：`rowsAbove` 从**输入区顶行**起算（printAbove 已把光标放到那里，故 buffer 折行/多行输入也正确）；text 后的 `\r` 化解「显示宽度恰等于终端宽度」的 pending-wrap 幻影换行；结尾 `\r\n` **必须**——只有以 `\n` 结尾 printAbove 才走 `print` 而非 `println`（否则多补一行、与随后的重绘失同步），同时它把光标送回输入区顶行第 0 列，净行位移为 0。
- 门槛：只有 `live.atWaitingFrame()`（即 `linesSinceFrame == WAITING_FRAME_ROWS`，自 `renderWaitingFrame` 以来没有任何追加）才原地重写；否则把新模式行加进 `deferredMessages`（`mainLoop` 在下一次命令输出时冲刷）。等待帧之上/之下一旦有追加，模式行就不在固定偏移处，原地重写会改坏屏幕。

## 尾行协议（流式计时器）
- 不变量：`tailRow != null` 时，屏幕最后一行已提交内容就是 tailRow，光标在其下一行第 0 列。故**原地刷新它的偏移恒为 1**——不需要跨调用计数，也不可能因回答超过一屏、尾行滚进回滚而让上移序列被终端截断。
- `setTailRow(out, text)`：已有尾行则先 `\033[1A\r\033[2K` 抬起再写 `text\r\n`（净位移 0）；从无到有则直接写一行并 `linesSinceFrame++`。
- `clearTailRow(out)`：有尾行则 `\033[1A\r\033[2K`（光标回到该空白行、不留多余空行），`linesSinceFrame--`；无尾行是 no-op（不得减成负数）。
- `appendCommitted` 在存在尾行时必须**先抬起、写完新内容再贴回底部**；空文本仍提前返回（不得为 no-op 抬行，否则净位移变 -1）。`commitRegion()` 不写屏，故也不动尾行（`PromptAnswerer`/`ConfirmationPrompt` 会在交换中途调它）。
- `StreamingTimer` 因此退化成「纯文本 + 500ms 节流」：只有 `shouldUpdate(now)`（首次必命中，让尾行立刻出现）与 `text(now)`；`ExchangeRunner` 循环尾部 `if (timer.shouldUpdate(now)) live.setTailRow(writer, timer.text(now))`。收尾由 `printUsageFootnote` 做 `clearTailRow` → `appendCommitted(usage 行)`，usage 行正好落在原计时行位置。
- 历史 bug（「AI 只要回答多行屏幕就很乱」的根因，勿回退）：旧 `StreamingTimer` 把计时行写在回答**顶部**，靠 `\033[(N+1)A` 上移重写、`\033[NB` 下移回来维持，而 N（计时行与光标之间的行数）有五处漏计——`finalizeWith` 上移后完全没下移、`TurnComplete` 分支在 drain 之前重建 printer 丢掉计数、`finishTurn` 自清零让 Ctrl+C 路径少计、`（重试中：…）`/`completeLoop` 提示走 appendCommitted 不计数、权限确认弹窗绕过 printer 写屏。且 N 无上移钳制，回答超一屏后每 500ms 把可见区顶行覆盖成计时行。
- 同时删掉了 `StreamPrinter.linesWrittenSinceDrain` / `drainLinesWritten()` 这条脆弱计数管道：`StreamPrinter` 自身不发射任何光标序列，全部走 `appendCommitted`。`TurnComplete` 不再重建 printer（`finishTurn()` 已复位全部每轮状态，同一实例服务下一轮）。
- 已知代价：计时尾行活着时占一行屏幕，故与「无尾行」相比会多滚走一行内容（在回滚里，不是丢失）。`LiveRegionTerminalSimTest` 的超屏用例据此只断言「屏上活下来的行是干净渲染的尾部后缀」，不要求逐行全等。

## 页脚 ctx 计量（真实用量优先，勿回退）
- 格式：`StatusBar.infoLine(model, ctxFraction, projectPath, width)` → `model · ctx ▓▓▓▓▓▓▓░░░ 70% · 项目全路径`。进度条恒 `BAR_CELLS(=10)` 格；`ctxBarFilled` 四舍五入且**非零占用至少亮 1 格**；`ctxPercentText` 在 ≥10% 取整、(0,10%) 保留一位小数（大窗口下取整会长期显示 0%、看不出变化）。
- 路径是**完整 `projectRoot.toString()`**（不再只取最后一级目录名）；超宽时从**左侧**截断并前置 `…`，保留最深目录名。`infoLine` 硬保证返回值 ≤ width：极窄终端连 `…` 都放不下时退化为无色截断——页脚多折一行会破坏等待帧的行数数学。
- 数据源：`Conversation.contextUsageFraction()` ∈ [0,1]，优先用 provider 回传的真实 prompt token（`Usage.promptTokens()`，由 `ExchangeRunner` 的 `UsageEvent` 分支 `recordPromptTokens(...)` 记录）；拿不到时退回 `estimateContextTokens()` = 历史 + **system 提示词 + 环境快照**（后两者不进历史但每轮都发，漏算会显著低报）。`clear()` 把真实用量一起作废，否则 `/clear` 后页脚还显示清空前的占用。页脚只在 `renderWaitingFrame` 刷新（启动 / 每轮结束 / 命令输出后），故 ctx 每轮更新一次。
- **`Usage.promptTokens` 有协议口径差异**：Anthropic 的 `input_tokens` **不含** `cache_read/cache_creation`，故四参构造器把三者相加；OpenAI 的 `prompt_tokens` **已含** `prompt_tokens_details.cached_tokens`，故 `OpenAiSseParser` 必须走 `Usage.openAi(...)` 工厂直接用它，再加一次会双计。本项目内置默认 `protocol: openai`（`src/main/resources/config.yaml`），实际走后者。usage 脚注的 `in N` 仍是原始 `inputTokens`，与 ctx 口径不同，勿混。
- 历史现象「ctx 一直 0%、看不出变化」的四个叠加原因：① 项目级 `.acode/config.yaml` 把 `max_context_tokens` 覆盖成 **1000000**（内置默认 128000），字符估算下 1% 需要 4 万字符历史；② 整数百分比截断；③ system 提示词与环境快照没算进估算；④ provider 回传的真实用量解析后被丢弃。
- 已知局限：字符 ÷ 4 的估算对中文低估约 4 倍，只在拿不到真实用量时兜底（`buildRequest` 的历史裁剪仍用它，未改）；`▓ ░ · …` 属 East-Asian ambiguous 宽度，一律按 1 列计（与既有 `─` 分隔线同一假设），在把 ambiguous 渲染成双宽的终端里页脚会偏宽。

## 测试约束
- TUI 需真实交互式终端；本环境**无法可视化验证**（`printf | java -jar` 会因建不出终端而被 `start()` 的 IllegalStateException 干净捕获退出）。
- UI 测试断言**内容与 ANSI 字节序列**（如 `usage:`、`已中断`、`\033[2A`/`\033[2K`/`\033[3A`），不断言可视布局。
- **`FakeTerminal`（test/com/acode/ui）是忠实终端模拟**：解析 `\033[NA`/`\033[NB`/`\033[J`/`\033[K`/`\r`/`\n`/SGR，按 wcwidth 占列、到右缘自动折行、写到底部自动滚动、上移封顶到第 0 行。凡涉及「上移 N 行重写」的改动都必须用它验，`StringWriter` 复现不了错位乱码。
- `LiveRegionTerminalSimTest.hasCursorOps` 是**有效绊线**：断言纯追加式流式全程不发射 `\033[NA`/`\033[J`。别为了通过新用例把它「顺手简化」掉；带计时尾行的场景另写用例（尾行协议本来就会发 `\033[1A`）。
- 相关测试类：ConversationControllerTest / ExchangeRunnerTest / LiveRegionRendererTest / LiveRegionTerminalSimTest / CommandProcessorTest / RenderContextTest / StreamingTimerTest / InputPaneTest / StatusBarTest。
- 跑全套时**与 UI 无关的既有噪声**（勿追）：`BashToolTest.hugeOutputTruncated` 长期失败；`McpToolWrapperTest` 偶发 `Failed to delete temp directory`（Windows 下 MCP 子进程占着 `@TempDir`），单独重跑即绿。

## 上一轮已完成（修两个 TUI bug：Shift+Tab 后卡输入 / AI 多行回答错乱）
- Bug 1「切权限模式后必须敲一次 Enter」：`cyclePermissionMode` 的 raw ANSI 让 JLine `Display` 缓存失同步 → 改走 `InputPane.RowRewriter`（内部 `LineReader.printAbove`）+ `live.atWaitingFrame()` 新鲜帧门槛（详见「Shift+Tab 原地改模式行」）。
- Bug 2「回答多行就乱」：计时行改为**尾行协议**、删掉 `StreamPrinter` 计数管道、`TurnComplete` 不再重建 printer、交换前 `clearBelowCursor` 擦页脚残留（详见「尾行协议」）；每条会输出的斜杠命令之后都重绘等待帧。

## 本轮已完成（页脚改版 + ctx 计量修复）
- 页脚模型信息行由 `model | ctx[X%] | folder` 改为 `model · ctx ▓▓▓▓▓▓▓░░░ 70% · 项目全路径`（`·` 分隔、10 格进度条、完整 `projectRoot`）。
- ctx 从「永远 0%」改为真实占用：优先 provider 回传的 prompt token，退回含 system+环境的字符估算，10% 以下显示一位小数、非零至少亮 1 格。
- 改动文件：`StatusBar`（`infoLine` 重写 + `ctxBarFilled`/`ctxPercentText`，删掉无人调用的 `format()`）、`Conversation`（`contextUsagePercent()` → `contextUsageFraction()`、`recordPromptTokens(long)`、`estimateContextTokens()`、`clear()` 作废真实用量）、`Usage`（新增 `promptTokens` 分量 + 四参构造器 + `openAi(...)` 工厂）、`OpenAiSseParser`（改用工厂，避免双计 cached_tokens）、`ExchangeRunner` 与 `ConversationController`（记录真实用量、页脚传 fraction 与全路径）。
- 新增/改写测试：`StatusBarTest`（新，9 条：格式原文、恒 10 格、无竖线、左截断保最深目录名、极窄终端不折行、亮格与百分比换算）、`ConversationTest`（+7 条：真实用量优先、估算含 system+环境、非正值忽略、钳到 1.0、窗口非正为 0、`clear()` 复位两则）、`AnthropicSseParserTest`/`OpenAiSseParserTest`（各 +1 条 `promptTokens` 口径断言）。
- 全套 769 测试：除既有噪声（`BashToolTest.hugeOutputTruncated` 长期失败、`McpToolWrapperTest` 偶发 temp 目录删除失败，单独重跑 11/11 绿）外全绿；`target/acode.jar` 已重打包。
- **已知遗留（未修）**：① 多行输入（Shift+Enter 或折行）与「页脚在提示符下方」的设计本身冲突——JLine 会把 buffer 续行写到页脚两行上，printAbove 既不加重也不修复；彻底解决要么放弃页脚在下方，要么改用 JLine `Status`（等于重写追加式模型）。② usage 行落在工具卡片终态输出之前（`LoopComplete` 分支既有调用顺序），纯观感。③ 流式过程中终端 resize 会破坏所有相对移动数学（无 reflow 追踪），是追加式模型的固有限制。④ 项目级 `.acode/config.yaml` 把 `max_context_tokens` 设成 1000000（内置默认 128000）——若模型真实窗口没这么大，ctx% 会偏低，且 `buildRequest` 的历史裁剪要等到远超真实上限才触发（届时是 provider 报错而非本地裁剪），需按真实窗口校准。
