# Findings

用于记录窗口缩放问题的代码证据与推断。

## 初步证据

- `InputPane.readLine(String)` 把多行提示符一次性交给 JLine，读取期间没有更新入口。
- `ConversationController.promptHeader()` 每轮按当时终端宽度生成模式行与分隔线。
- `ConversationController.installResizeRefresh()` 的 WINCH 处理器只调用 `drawFooter()`，只重排提示符下方的 JLine `Status` 页脚。
- 源码注释明确写明：缩放发生在 `readLine` 期间时，提示符块保持旧宽度，直到提交后才修正。这与用户报告的“启动后缩放窗口输入框显示错误”高度一致。
- 下一步需检查 `CommandProcessor` 的底部锚定和 JLine redisplay 是否会造成额外错位。

## 输入循环与锚定

- `CommandProcessor.mainLoop()` 在进入 `readLine(prompt)` 前只计算一次 prompt 和 `BottomAnchor` 下移量；读取期间终端高度变化不会重新 pin。
- `BottomAnchor` 的设计注释明确禁止在 `readLine` 期间由外部移动光标，因此不能在 WINCH handler 中简单重新执行 pin/unpin。
- 当前测试只覆盖 prompt 行数的纯计算和固定高度几何；没有“readLine 阻塞时改变 terminal Size 并触发 WINCH”的集成测试。
- Context7 已定位官方 JLine 文档库 `/jline/jline3`，下一步查询 resize/redisplay 与动态提示符 API。

## JLine 文档核对

- 项目使用 JLine `3.27.1`。
- 官方文档确认 WINCH handler 是重排应用 UI 的入口，但没有给出在活动 `readLine(prompt)` 中替换 prompt 文本的公开示例。
- 当前代码使用 `terminal.handle(WINCH, ...)` 覆盖处理器；需继续核查 JLine 自己的 LineReader resize handler 是否被覆盖，以及是否因此丢失默认 redisplay。

## JLine 3.27.1 源码证据

- `LineReaderImpl.readLine()` 在活动读取期间执行 `terminal.handle(WINCH, this::handleSignal)`，保存旧 handler，读取结束才恢复。
- `LineReaderImpl.handleSignal(WINCH)` 不调用旧 handler；只更新 Display/Status 几何、清屏尾并按当前缓存的 prompt 与 Status 内容 redisplay。
- 所以 ACode 在 `ConversationController.installResizeRefresh()` 注册的 handler 在活动输入期间不会执行。它恰好是最需要重算 `StatusBar.divider(width)` 和 footer 文本的时段。
- JLine 的 `setPrompt(String)` 与 `redisplay()` 在实现类上公开，但现有 `InputPane` 把 reader 封装为接口且没有暴露 resize-safe 的更新入口。
- `docs/ch09/ui-align-plan.md` 已存在 resize 相关历史记录和真机验收说明，需读取对应定稿段确认这是否是已知遗留。

## 已知遗留确认

- `docs/ch09/ui-align-plan.md` v6 明确限定：resize 只尝试重建页脚，提示符块在活动读取期间保持旧宽度。
- 同文档 v7 进一步写明“resize 仍是坏的”，真机已复现；记录的根因是 JLine `Status` 缓存的 `scrollRegion` 在尺寸突变后失准，简单 `resize()+reset()` 仍会出现 CPR 回第 1 行、内容从头覆盖。
- `docs/ch09/checklist.md` T16 仍有未勾选的“待修”项，因此这不是新回归，而是仓库中已记录、尚未完成的 UI 缺陷。
- 仓库已有专用 `probe/ResizeProbe.java`，下一步读取它确认已验证方案和剩余边界。

## ResizeProbe 提供的更深层根因

- 探针记录了两层问题：旧宽度 prompt 折行只是表层；更深层是终端已 reflow，但 JLine `Display.cursorPos` 仍是旧模型的相对偏移，`resize()` 不重算它。
- 探针的 `fix` 模式采用 120ms 尺寸轮询，因为活动 `readLine()` 会吃掉应用 WINCH handler。
- 修复实验需要在尺寸变化时：CPR 获取实际光标行 → 清理屏幕底部旧块 → 归零 reader/status Display 记账 → 按新几何定位 → 重建 prompt/footer 并 redisplay。
- 该实验依赖反射访问 JLine 私有字段，说明它适合作为根因验证，不宜未经封装直接搬进生产代码。

## 自动化验证与工作区状态

- UI 相关 5 个测试类共 133 个测试全部通过：`CommandProcessorTest`、`BottomAnchorTest`、`LiveRegionRendererTest`、`RenderContextTest`、`StatusBarTest`。
- 这不反驳真机缺陷：现有测试只验证静态几何、格式化与固定尺寸的 Display 行为，没有活动 `readLine()` + 实际终端 reflow + WINCH 的组合测试。
- `probe/ResizeProbe.java` 当前为未跟踪文件，属于用户已有工作，未修改。
- 当前产品代码无本轮改动；本轮只新增 planning-with-files 的三个诊断记录文件。

## 第一版修复失败（2026-09-25 真机）

- 活动 `readLine` 与 watcher 同时读取 terminal reader；watcher 发出的 CPR 响应被 JLine 当作用户输入。
- 证据：12 次接管均记录 `现场 CPR=失败`，屏幕出现大量 `;;3R` / `R[...]`，模式行与分隔线重复堆叠。
- 原虚拟终端测试不提供 CPR capability，只验证了 fallback，属于覆盖缺口。

## 第二版修复与失败结论

- `ResizeAwareLineReader` 继承 JLine 3.27.1 的 `LineReaderImpl`，直接使用 protected `display`/`size`
  与 public `setPrompt`/`redisplay`；`Status.resize/reset/update` 也均为公开 API，生产代码不再需要探针反射。
- 活动读取期间由 120ms 尺寸 watcher 触发修复，避开 `readLine()` 覆盖应用 WINCH handler 的限制。
- Windows 光标位置改由 JNA `GetConsoleScreenBufferInfo` 从输出控制台读取，不消费 terminal reader；
  原生坐标不可用或运行期异常时跳过接管，退化到 JLine 自身 WINCH 行为。
- 自动化覆盖了活动 `readLine` 的尺寸变化、动态 prompt/footer、普通输入完整性与“无 ANSI CPR 请求”，
  但没有暴露 watcher 与 JLine 内部 WINCH 的双重重绘。
- 真机确认第二版失败：放大时输入区异常变高，缩小时页眉与分隔线重复堆叠。

## 第三版修复落地

- 删除尺寸 watcher，覆写 `LineReaderImpl.handleSignal(WINCH)`，让应用准备动作与 JLine 默认重绘处于同一个
  synchronized 信号调用中。
- 调用 `super.handleSignal` 前更新动态 prompt，并在 Windows 原生坐标可用时清理、重锚定旧输入块；
  之后仅刷新 footer。一次 WINCH 只有一条 Display/Status 重绘链。
- 自动化显式触发一次 WINCH，断言 resize 回调恰好一次、新宽度 prompt 出现、普通输入完整且无 ANSI CPR。
- 全量回归：1123 tests，0 failures，0 errors，1 skipped；真实终端拖拽视觉验收待完成。

## 第三版真机失败与第四版修复

- 第三版仍在 `super.handleSignal(WINCH)` 前移动真实光标；JLine `Display.cursorPos` 不知道这次移动，
  默认重绘继续按旧内部坐标输出相对移动，连续 WINCH 因而形成阶梯状重复页眉。
- 提示符旧高度只按换行计算，窗口缩窄后的终端物理折行会让清屏起点进一步偏低。
- 第四版完全取消活动输入期间的应用层光标查询、移动、清屏和重锚定，只更新 JLine prompt 模型后
  委托其默认 WINCH 重绘。
- 进入读取前计算的 pin 距离在本轮发生 resize 后作废，避免回车或 Ctrl+C 时按旧高度回退；下一轮
  resize 标记复位，并按最新终端尺寸重新 pin。
- 自动化覆盖 20 次交替尺寸 WINCH、编辑中缩放与下一轮复位；全量回归 1123 tests，0 failures，
  0 errors，1 skipped。真实 Windows 终端仍是最终视觉验收门槛。

## 测试命令错误

- 首次运行多个 Maven `-Dtest` 类时，PowerShell 把未加引号的逗号参数解析失败；下一次将整个 `-Dtest=...` 参数作为单个字符串传递。

## 搜索错误

- 一次 `rg` 命令包含 Windows 下无效的 `test*` 路径参数，产生路径语法错误；有效目录的结果仍返回。后续改用明确路径，不重复该模式。

## 工具错误

- 尝试通过 `$JAVA_HOME/bin/javap.exe` 检查 JLine 类时 `$JAVA_HOME` 未设置；改用本地 Maven 仓库中的 `jline-3.27.1-sources.jar` 直接读取源码，已获得所需证据。
