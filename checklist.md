# 终端缩放稳定性验收清单

## 静态约束

- [x] 活动 WINCH 处理路径中不存在对 `BottomAnchor.moveCursor` 的调用。
- [x] 活动 WINCH 处理路径中不存在 ANSI CPR 请求或终端输入流读取。
- [x] 缩放后清除旧画面并从 `OutputPane` 重放已提交的 ACode 对话行。
- [x] 代码中不存在活动输入 resize 轮询线程或固定间隔 watcher。
- [x] resize 实现不反射访问 JLine 私有字段。
- [x] 一次活动 WINCH 只触发一次对话重放，不创建额外轮询线程。
- [x] 数据库及其存储文件没有改动。

## 自动化测试

- [x] 活动输入从 `80x24` 调整为 `60x20` 后，输入 `hello` 回车，返回值严格等于 `hello`。
- [x] 一次活动输入中连续触发至少 20 次交替放大/缩小，输入保持完整，不出现额外轮询回调。
- [x] 输入 `abc` 后缩放，再输入 `def` 并回车，返回值严格等于 `abcdef`。
- [x] 输出字节中不包含 CPR 请求 `ESC[6n`。
- [x] resize 后使用旧钉底距离的回退次数为 0，下一轮恢复未 resize 状态并使用最新终端尺寸重新计算。
- [ ] 页脚隐藏时触发 resize，状态区仍保持隐藏。
- [ ] 正常提交、Ctrl+C、Ctrl+D 三条退出路径均不会遗留旧的钉底偏移。
- [x] 状态区同时绘制 `[default]`、上边线、`> `、下边线和模型行。
- [x] 24 行终端中 Shift+Enter 后，底部预留区高度保持不变，输入内容在预留区内向上展开。
- [x] 非原生 Windows 测试终端中，滚轮事件不改变编辑缓冲区 `abcdef`；原生 Windows 依用户选择保留鼠标拖选和终端滚轮。
- [x] 页脚绘制宽度比终端宽度至少少 1 列，避免 Windows 最右列自动折行。
- [x] 空白 `TERM` 在 Windows 被当作未指定；`TERM=dumb` 仍视为用户显式设置。
- [x] 当前版本全量 `mvn test` 为 1134 tests、0 failures、0 errors、1 skipped。
- [x] 升级 JLine 后 `mvn package -DskipTests` 成功生成 `target/acode.jar`。

## 真实终端端到端验收

- [x] CMD 启动 ACode 后无 `infocmp` 异常栈；底部只出现一份 `[default]`、上边线、`> ` 输入行、下边线、模型状态行。
- [x] CMD 等待输入时，从宽窗口连续拖到窄窗口，再拖回宽窗口，屏幕始终至多保留一份活动页眉和一组页脚。
- [ ] 连续拖动过程中不出现 `;;3R`、`R[...]`、`ESC[6n` 或其他控制序列文本。
- [x] CMD 拖窄后不出现阶梯状重复页眉，拖宽后输入区不会扩张成多行空白块。
- [x] CMD 输入 `abc`，缩放窗口，继续输入 `def` 并回车，程序只收到一次 `abcdef`。
- [ ] resize 后再进行下一轮输入，提示符位置稳定，不覆盖历史内容。
- [x] CMD 输入 `abc`、Shift+Enter、`def` 时两行同时可见，输入区向上增高，上下边线及模型状态行仍显示。
- [x] 自动化输入 150 个字符和 12 个换行，跨缩放后提交值严格等于原文；CMD 两行输入的实际边线与页脚已通过真机检查。
- [ ] `/help` 输出后缩放并继续输入，历史输出不被活动区重绘覆盖。
- [x] CMD 输入 `/quit` 后没有 `Terminal has been closed` 异常，shell 提示符可正常使用。
- [x] CMD Ctrl+C 后 shell 提示符可正常使用且没有残留活动页眉或页脚。
- [ ] CMD Ctrl+D 后 shell 提示符可正常使用且没有残留活动页眉或页脚。
- [x] CMD、PowerShell、Windows Terminal 的启动、拖拽、`abc` + resize + `def` 和 Shift+Enter 检查通过。
- [x] PowerShell、Windows Terminal 的 `/quit` 退出与历史检查通过。
- [x] CMD、PowerShell、Windows Terminal 退出后，滚轮仍可查看 ACode 对话。
- [x] 缩放后的终端滚轮历史来自 `OutputPane` 缓存；当前缓存上限为最近 2000 行。
- [x] 原生 CMD 可以用鼠标拖选复制；滚轮浏览旧视口时输入框随终端视口移动，滚回最新画面后重新贴底。

## 降级验收

- [ ] 不具备活跃区能力的终端仍显示既有“不支持活跃区”提示，不打印控制序列或异常栈。
