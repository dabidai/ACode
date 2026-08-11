# 调试记录

以下问题在解决了之后，在每个问题下写上解决方法

## 增加 /resume 指令，显示历史记录，可以使用 ↑ 或 ↓ 来进行选择对话展开

**解决方法：**
- `CommandRouter` 新增 `RESUME` 动作与 `/resume` 路由（`Action.RESUME`），`/help` 文案同步列出 `/resume`。
- `ConversationController` 新增会话选择菜单：
  - `selectSession()` 调 `sessionStore.list()` 列出全部历史会话；菜单以输出区尾部块渲染，每次按键先 `output.removeLast(...)` 移除旧菜单块再重画，不污染历史消息。
  - `readMenuKey()` 直接读终端键（不走 JLine）：`↑`(`\033[A`)/`↓`(`\033[B`) 移动选中行（选中行反显 `\033[7m` + `▸`），`Enter` 加载，`Esc`/`Ctrl+C` 取消。
  - `loadSession()` 清空当前对话上下文与界面，回显所选会话全部消息（用户消息带 `●` 前缀），后续提问基于该历史继续；退出时仍按「追加不覆盖」存为新会话文件。
- 与启动参数 `--resume` 的区别：启动参数自动恢复最近一次会话；`/resume` 在运行中交互式挑选历史会话。

## 在流式输出对话的过程中，屏幕出现频繁闪动

**解决方法：**
- 根因：旧 `AcodeTerminal.repaint()` 每次收到 delta 都执行 `\033[H\033[2J\033[3J` 清屏（含清 scrollback）再全量重绘，20ms 一次的高频清屏导致闪动。
- 修复：`AcodeTerminal` 引入 shadow buffer（记录上次绘制到屏幕的可见行），`repaint()` 改为逐行 diff——只对内容变化的行执行 `moveTo` + 重写 + `\033[K` 擦除行尾，不再清屏；流式输出时通常只有尾部回复块几行变化，其余历史行稳定不动。
- 窗口尺寸变化时才清屏重锚定（保留 resize 修复所需的 `\033[3J`），并重置 shadow 强制全量重绘，避免缩放错位。
