# Findings（2026-09-25 稳定性收尾会话）

> 归档自仓库根目录。与同目录 `findings.md`（2026-09-18 诊断会话）是两段不同的记录。

## 当前基线
- 阶段 1–9 已实现；阶段 10–14 仅有未跟踪的规划文档。
- 正常本机权限下全量测试：1118 tests，1 failure，0 errors，1 skipped。
- 唯一失败：`StdioTransportTest.subprocessReceivesOnlyWhitelistedEnvironment`，未白名单变量查询返回 `C:` 而不是 `(null)`。
- resize 缺陷已有完整诊断，材料位于 `docs/ui-resize-diagnosis/`；活动 `readLine()` 会接管 WINCH，旧 prompt 几何和 JLine Display 光标记账不会被应用层正确重建。

## 待验证假设
- 已确认唯一失败是测试筛选逻辑问题：Windows 环境变量名不区分大小写，但测试用 `List.contains` 区分大小写地排除白名单。父环境中的 `SYSTEMDRIVE` 因白名单写作 `SystemDrive` 被误选为“未声明变量”，子进程查询时正确得到 `C:`。
- JLine 3.27.1 的 `LineReaderImpl` 在活动读取时接管 WINCH；其 `handleSignal`、`display`、`size` 为 protected，`setPrompt(String)` 与 `redisplay()` 为 public，`Display.reset()`/`resize()` 也为 public。可通过小型子类实现，无需反射私有字段。
- 默认 WINCH 路径会 `resize + status.reset + redrawLine + redisplay`，但不能重算 ACode 的动态多行 prompt，也不知道 BottomAnchor 的目标行；生产修复仍需在子类/回调中补充 prompt 重建与底部重锚定。
- Context7 的主干文档与本地 3.27.1 源码在 WINCH 细节上有差异；实现以项目实际锁定的 3.27.1 源码为准。
- 已有 `ResizeProbe` 验证过可行顺序：检测尺寸变化 → CPR 定位旧提示符块 → 清到屏尾 → 新几何下重锚定 → reset reader Display → 更新 prompt/redisplay → reset/update Status。
- 生产化时可由 `LineReaderImpl` 子类直接访问 protected `display`/`size`，并调用 public `setPrompt`/`redisplay`；`Status.resize/reset/update` 均为公开 API，因此探针中的私有字段反射可全部删除。
- 应用注册的 WINCH handler 在活动 `readLine` 时会被替换；第二版曾据此改用轮询，但真机证明轮询会与 JLine 的内部 WINCH 形成双重重绘，因此该结论已废弃。
- 自动化集成测试使用虚拟 Terminal + PipedInputStream 保持 `readLine` 活动，修改 `Terminal.setSize` 后显式触发 WINCH，断言回调恰好一次、新宽度 prompt 出现且输入完整。
- 真机否定了原自动化结论：watcher 在活动 `readLine` 中发送 ANSI CPR 时，JLine 与 watcher 竞争读取同一输入流。日志连续出现 `现场 CPR=失败`，截图中的 `;;3R`/`R[...]` 是 CPR 响应被当作用户输入的直接证据。
- 自动化之所以漏报，是虚拟 Terminal 不提供 CPR capability，测试只走了“CPR 不可用”的降级分支，没有模拟真实终端回送 `ESC[row;colR`。
- 新约束：活动 `readLine` 期间绝不能通过 terminal reader 查询光标位置。Windows 可考虑用 JNA `GetConsoleScreenBufferInfo` 从输出控制台读取光标坐标；其他平台无安全定位能力时必须降级，不能回退到 ANSI CPR。
- 第二版已采用 JNA `GetConsoleScreenBufferInfo`，坐标换算为 `bufferY - windowTop + 1`；活动 resize 查询不消费输入流。原生查询失败时 `repair` 立即返回，不再执行 reset/redisplay 造成重复绘制。
- 第二版仍失败：虽然没有 CPR 文本污染，但 JLine 的内部 WINCH handler 与尺寸 watcher 都会重绘。缩小时连续出现多个页眉，放大时输入框区域异常扩张，符合“双重 Display/Status 重绘互相覆盖”的表现。
- 第三版约束：不再轮询尺寸；覆写 `LineReaderImpl.handleSignal(WINCH)`，在调用 `super.handleSignal` 前更新动态 prompt/可选重锚定，之后刷新 footer。所有动作在 JLine 自己的 synchronized 信号路径内串行发生。
- 第三版全量回归：1123 tests，0 failures，0 errors，1 skipped；真实终端视觉验收仍待完成。
- 第三版真机输出出现约 25 份逐行左移的模式页眉。阶梯形态与拖动窗口逐列触发 WINCH 一致，说明每个事件都留下了未清除的旧提示符。
- 第三版在 `super.handleSignal(WINCH)` 前手动改变真实光标，但没有同步 JLine `Display.cursorPos`；随后默认重绘以旧内部坐标输出相对移动，两个光标模型必然分叉。
- `displayRows` 只统计换行。窗口缩窄时旧宽度分隔线会被终端物理折行，实际占用行数大于逻辑行数，按逻辑行数计算的清屏起点会留下上半部分旧页眉。
- 安全修复边界：活动输入期间不做外部光标定位、移动或清屏；resize 后旧的 pin/unpin 距离作废，下一轮再以新尺寸重新钉底。
- 第四版可完全删除 `ResizeAwareLineReader` 对 Windows 原生光标查询和提示符逻辑行数的依赖；原生光标查询只保留在进入 `readLine` 之前的 `BottomAnchor.pin` 边界。
- 连续 20 次同步 WINCH 的虚拟终端测试已通过，输入 `abc` 后缩放再输入 `def` 能严格返回 `abcdef`，回调次数与信号次数相等且无 CPR。
- 第四版真机仍失败：每次 WINCH 输出一组“长分隔线 + 模式行”，但 `abcdef` 能完整提交，说明输入流与 pin 失效逻辑已稳定，剩余故障集中在 JLine 多行 prompt 的 Windows redisplay。
- 最新输出中页眉从启动 banner 中段开始覆盖，并出现大量随宽度变化的分隔线；这不是应用层重锚定造成，因为第四版已完全删除该路径。
- 经过四次真机否定，可靠边界应收缩为严格单行 JLine prompt。模式信息可并入同一行，顶部边框必须移除；底部两行继续由 JLine Status 管理。
- 第五版启动验证发现 CMD 的 `set TERM= &&` 会把空格留作 TERM 值；JLine 试图执行系统没有的 `infocmp`，在 banner 前打印异常栈。空白 TERM 现在按未指定处理，调用 Windows 内置能力表。
- 单行提示符按宽度预留编辑空间，页脚避开最右列。自动化无法替代真实终端回流，三种 Windows 终端的拖拽验收仍在进行。
- 用户明确要求底部固定五层布局：模式行、上分隔线、`> ` 输入行、下分隔线、模型状态行；对话内容位于其上。第五版把模式信息并入输入提示符，虽然测试通过，但不符合此布局，不能作为最终方案。
- 当前 JLine `Status` 只管理输入提示符下方两行；旧版上两行属于 JLine 多行 prompt，真实 Windows 回流时会堆叠。需要让上边框、输入与页脚在 WINCH 中共享可验证的定位和重绘模型。
- JLine 3.30.16 的 `LineReaderImpl.handleSignal(WINCH)` 在网格尺寸变化时调用 `doDisplay()` 重建 Display；3.27.1 仅在旧 Display 上 `resize()`。该变化直接对应旧版光标状态失同步的诊断，但是否解决 Windows 真机回流仍需观察。
- JLine 3.30.16 的 `InfoCmp` 区分默认与已加载能力表；自定义 Windows 类型需用 `setLoadedInfoCmp` 注册，才能避免先调用系统缺失的 `infocmp`。
- 第六版（JLine 3.30.16 + 多行 prompt）真实 CMD 仍失败：每次拖拽在滚动内容中残留 `[default]` 和上边线，最后输入 `abcdef` 只提交一次且当前末帧正确。说明仅重建 JLine Display 光标模型仍无法抹除 Windows 实际回流留下的旧多行提示符。
- 需要把底部五层框交给统一绘制器。现有 `OutputPane` 保存已提交内容快照，`ResizeAwareLineReader` 可继续提供 JLine 编辑缓冲与按键行为；全面固定绘制会改变原生滚动回看的语义，已向用户询问取舍。
- 后续真机证据推翻了“活动输入期间绝不清屏”的旧边界：把五层框统一交给 `Status` 仍会在 CMD 缩放后留下旧帧；先清除回流并从 `OutputPane` 重放 ACode 对话，才在真实 CMD 中消除残影。此操作会清除启动 ACode 前的 shell 回滚，用户确认只需保留 ACode 对话。
- 多行编辑时动态增减 `Status` 行数会让 CMD 的滚动区域与可见边线失同步，上一行文字被吞；固定半屏预留高度、让编辑内容消耗上方空白行后，用户在 CMD 中确认两行、上下边线和页脚均正常。
- 原生 CMD 的鼠标事件接管会关闭 QuickEdit 拖选；停用接管后用户确认可以再次选中复制。终端原生滚轮移动整个视口，程序无法在终端自管的回滚区内单独锚定输入框；滚轮固定与直接鼠标拖选的优先级待用户选择。

## 诊断过程错误
- 受限环境中的 JShell 因无法写 Windows Preferences 注册表而退出，未用于最终判断；改由测试代码和 `ProcessEnv` 的大小写语义直接定位。
- 从 Codex 启动的外部 `cmd.exe` 会继承 `TERM=dumb`；`AcodeTerminal.statusBarSafeType()` 尊重显式 TERM，因而不会注入 Windows VTP capability，最终在能力检查处拒绝启动。真机探针需先清空 TERM。
