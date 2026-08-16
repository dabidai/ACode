# ACode 阶段七：选择交互（↑↓ 菜单替换 y/n + AI 选择工具）— spec

> 最后更新：2026-08-16

## 背景

`docs/ch05/ui.md` 提出四个 UI 需求，用户确认逐个立阶段：ch06（结果渲染）已完成，本阶段 ch07 做第二项——**选择交互**。

现状两处不直观：
1. 阶段五（ch05）的工具权限确认用 y/n 行输入（`ConfirmationPrompt`），不直观、易输错、无方向键操作；
2. AI 无法主动向用户发起选择——模型只能「在回复里让用户输入」，无法把用户选择结构化回传给模型。

目标：确认执行改用 ↑↓ 选择菜单（`> 是` / `  否`，Enter 选中、Esc/EOF 取消）；新增「选择工具」，模型可发起多选项单选菜单，用户选完结果回传模型。两处共用同一套菜单基础设施。

## 目标用户

- 本人：希望确认/选择交互直观、键盘可操作的终端 AI 助手用户
- 后续阶段潜在使用者：终端开发者、习惯命令行工作流的用户

## 能力清单

1. 确认菜单：非 READ 工具执行前弹 ↑↓ 选择菜单 `> 是` / `  否`，默认选中「是」
2. 键盘语义：↑/↓ 环形移动，Enter 选中当前项，Esc / Ctrl+C / EOF 等价取消
3. 取消等价拒绝：确认菜单取消 → 返回「用户拒绝执行」失败结果，模型可调整重试（与 ch05 一致）
4. 选中样式：`>` 箭头 + 整行反显 `\033[7m`（与 /resume 会话菜单统一）
5. AI 选择工具：新增 `AskUser` 工具，模型发起 question + N 个 options 的单选菜单
6. 选择回传：用户选中项作为工具成功结果回传模型（`tool_result` 链路复用）
7. 选择取消：Esc/Ctrl+C/EOF 取消 → 工具返回「用户取消选择」失败结果
8. 参数校验：`options` 为空或非字符串数组 → 返回带原因失败结果，不弹菜单
9. 菜单复用：/resume 会话选择菜单改用同一套基础设施，行为不变（箭头统一为 `>`）
10. 可测试：按键源抽象为可注入接口，测试脚本化键序列，不触真实终端

## 非功能要求

- 追加式兼容：确认/选择菜单作为活跃区 overlay（`redraw`/`clear`），进出菜单不污染回滚；提示行与状态行以提交行写入
- 无死锁：选择工具与确认门同模式——agent 线程发事件阻塞等待，UI 主线程消费事件并应答
- 无竞态：每次调用独立 Choice 通道随事件携带，同轮多个交互调用由事件队列背压 + 主线程串行应答
- 可测试：按键解析、菜单移动、握手应答、工具校验、端到端编排均有单测，无网络依赖
- 层不循环：交互接口与选择工具放 agent 包，tool 包零改动

## 设计骨架

### 分层结构

```
┌──────────────────────────────────────────────────────┐
│ UI 层：MenuKeySource（按键源）+ SelectionMenu（菜单）    │
│        ConfirmationPrompt（确认菜单化）                 │
├──────────────────────────────────────────────────────┤
│ 事件订阅层：ConversationController                     │
│   确认应答（沿用）+ ChoiceRequestEvent 分派             │
├──────────────────────────────────────────────────────┤
│ Agent 层：Choice（选择通道）+ ChoiceRequestEvent       │
│        InteractiveTool（交互工具接口）                  │
│        AskUserTool（模型发起选择）                      │
├──────────────────────────────────────────────────────┤
│ 执行器层：StreamingToolExecutor                       │
│   tool instanceof InteractiveTool → executeInteractive│
├──────────────────────────────────────────────────────┤
│ 工具层：Tool（不变）                                    │
└──────────────────────────────────────────────────────┘
```

### 关键设计决策（已确认）

| 决策点 | 结论 |
|---|---|
| 确认交互 | ↑↓ 菜单选 `是`/`否`，Enter 选中，Esc/EOF 取消（=拒绝）；默认选中「是」 |
| AI 选择 | AskUser 工具，模型发起**单选**菜单；取消回传失败 |
| 选中样式 | `>` 箭头 + 反显 `\033[7m`；/resume 的 `▸` 统一为 `>` |
| 状态文案 | 确认沿用「（已批准执行「X」）/（已拒绝执行「X」）/（已取消）」；选择用「（已选择「X」）/（已取消）」 |
| 确认握手 | Confirmation/ConfirmationRequestEvent 语义不变（仍是 boolean），仅 UI 换菜单 |
| 选择握手 | 照搬 Confirmation：Choice 通道 + ChoiceRequestEvent + await 轮询 |
| 交互分发 | StreamingToolExecutor 识别 `InteractiveTool` 改走 executeInteractive，耗时记 0（不含用户思考时间） |
| AskUser 权限 | Permission.READ：绕过确认门、自动进 plan 模式工具表 |
| 包边界 | InteractiveTool/AskUserTool 放 agent 包，AskUserTool 在 ConversationController 构造器注册，tool 包零改动 |

### 选择数据流

```
模型发起 AskUser(question, options)
  → StreamingToolExecutor.runCall 识别 InteractiveTool
  → AskUserTool.executeInteractive
      校验 options 非空 → putSafe(ChoiceRequestEvent(question, options, Choice))
      阻塞 Choice.await(cancelled)
  → UI 主线程事件循环收到 ChoiceRequestEvent → choiceAnswerer
      appendCommitted(question) → SelectionMenu(options).select()
      选中 → appendCommitted(「（已选择「X」）」) 返回 X
      取消 → appendCommitted(「（已取消）」) 返回 null
  → choice.response().answer(X / null)
  → agent 线程醒：X → ToolResult.success(X)；null → failure「用户取消选择」
  → ToolResultEvent → turnResults → 下一轮 tool_result 回传模型
```

## Out of Scope（本章明确不做）

- 多选/复选菜单（用户已确认单选）
- 模糊搜索、超屏翻页、鼠标选择、菜单超时自动取消
- InteractiveTool 暂仅 AskUser 一个交互工具
- options 数量上限 / 图标 / 描述 / 默认值
- 结构化回传（只回传选中项文本）
- 确认菜单「始终允许」/ 批量审批
- plan 模式提醒文案、/resume 菜单功能变化（仅箭头样式统一）
