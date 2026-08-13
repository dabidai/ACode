# ACode 阶段二：工具调用 — 验收清单

> 最后更新：2026-08-13
> 每一项均可勾选、可观测。执行顺序与 tasks.md 一致；带 ⚑ 的为端到端验收。
> 默认值说明：Bash 默认超时 60s、文件/搜索工具默认超时 10s、命令输出截断 30000 字符、ReadFile 上限 2000 行、Grep 命中上限 500 条、Glob 结果上限 200 条、工具结果进入历史前截断 30000 字符、UI 结果摘要前 5 行。

## T1 工具框架核心

- [ ] `mvn compile` 通过，`com.acode.tool` 包编译无警告
- [ ] 参数缺失时执行返回失败结果，错误文本包含缺失参数名
- [ ] 参数类型不匹配（如应传数字传了字符串）返回失败结果，错误文本包含参数名
- [ ] 工具内部抛运行时异常 → 返回失败结果（is_error=true），不向上抛

## T2 消息模型结构化

- [ ] 旧 `ChatMessage.of(Role, String)` 构造的消息，Jackson 序列化→反序列化往返后文本一致（向后兼容）
- [ ] 含 tool_use 块的消息：序列化后 JSON 含 `type:"tool_use"`、id、name、input；往返后字段不丢
- [ ] 含 tool_result 块的消息：序列化后 JSON 含 `type:"tool_result"`、tool_use_id、is_error；往返后字段不丢
- [ ] 阶段一相关测试（ConversationTest / SessionStoreTest / AnthropicProviderTest）全部保持绿色，未因消息模型改造破坏

## T3 工具注册中心

- [ ] 注册 6 个内置工具后 `list()` 返回 6 条，名称各不相同
- [ ] 转换为 Anthropic tools 格式：数组长度为 6，每条含 name / description / input_schema 三字段
- [ ] `disable(name)` 后该工具不可用（执行返回失败结果），`enable(name)` 后恢复
- [ ] 查询未注册的工具名 → 明确返回不存在，不抛未捕获异常

## T4 文件读写工具

- [ ] 读已存在文本文件 → 返回内容与磁盘一致
- [ ] 读不存在文件 → 返回失败结果，错误文本包含文件路径
- [ ] 读超过 2000 行的文件 → 返回前 2000 行并附「已截断」提示
- [ ] 写文件（目标文件已存在）→ 磁盘内容被完整覆盖，与入参一致
- [ ] 写文件到不存在父目录的路径 → 自动创建父目录后写入成功
- [ ] 相对路径基于工作目录解析，绝对路径直接用

## T5 多段编辑工具

- [ ] 一次调用 2 个替换段全部匹配 → 文件中两处都被替换，结果正确
- [ ] 其中一段 old 内容在文件中不存在 → 整体失败，返回失败结果
- [ ] 任一段 old 内容在文件中出现 2 次以上 → 失败，错误文本含「不唯一」字样
- [ ] 上述失败场景下原文件字节数与内容完全不变（原子性）

## T6 搜索工具

- [ ] 模式 `**/*.java` 在项目目录命中 ≥3 个文件
- [ ] Grep 正则命中内容 → 返回路径 + 行号 + 行内容
- [ ] Grep 无命中 → 返回空结果，不报错
- [ ] Grep 命中超过 500 条 → 截断并附提示
- [ ] Glob 结果超过 200 条 → 截断并附提示

## T7 命令执行工具

- [ ] `echo hello` → 结果含 `hello`，进程正常退出
- [ ] `sleep 5`（配 1s 超时）→ 返回超时错误，错误文本含「超时」，进程已被终止
- [ ] 输出超过 30000 字符 → 截断并附「输出过长」提示
- [ ] 命令退出码非 0（如 `exit 3`）→ 结果带 is_error 标记，返回码可见
- [ ] 本机安装 Git Bash 时 shell 检测优先命中 git-bash 路径；无 Git Bash 环境回退系统默认 shell 且不报错

## T8 Anthropic 请求侧

- [ ] `AnthropicProviderTest` 断言：请求 JSON 含 `tools` 数组，每条含 name/description/input_schema
- [ ] 请求 JSON 中 assistant 消息的 content 为数组，tool_use 块含 id/name/input
- [ ] 请求 JSON 中 user 消息的 tool_result 块含 `tool_use_id` 与 `content`
- [ ] SYSTEM role 仍收进根 `system` 字段，不受 content 改造影响

## T9 Anthropic 响应侧

- [ ] 录制片段：单个 tool_use 参数跨 3 次 `input_json_delta` 碎片 → 拼接解析出的参数 JSON 与完整值一致
- [ ] 录制片段：thinking 块与 tool_use 块混排 → tool_use 被正确捕获，thinking 不串入
- [ ] 纯文本回复（无 tool_use）仍走 onDelta 流程，行为与阶段一一致

## T10 工具执行与回传

- [ ] tool_use 命中已注册工具 → 工具真实执行，结果回传
- [ ] tool_use 命中未注册/已禁用工具 → 返回带 is_error 的错误结果，不抛异常
- [ ] 工具失败 → 回传的 tool_result 块 `is_error=true`
- [ ] 工具结果超过 30000 字符 → 回传与进入历史前都被截断

## T11 UI 工具调用展示

- [ ] 工具调用时输出区出现一行「▸ 工具名(参数摘要)」
- [ ] 执行期间状态为「进行中」，完成后变为「完成」/「失败」（可观测字样）
- [ ] 结果摘要超过 5 行 → 只显示前 5 行并带折叠标记
- [ ] 文本流式回复仍正常显示，与工具卡片不互相覆盖

## T12 接入主流程（单步闭环）

- [ ] FakeProvider 两轮模拟：第一轮返回 tool_use（真实执行 ReadFileTool）→ 第二轮请求携带含 tool_result 的历史 → 最终文本展示
- [ ] 上述闭环后会话历史同时含 tool_use 块与 tool_result 块
- [ ] 无 tool_use 的普通提问 → 行为与阶段一完全一致（单次请求、直接展示文本）
- [ ] 第二轮仍返回 tool_use → 工具不执行，仅展示文本并提示「连环调用未支持」
- [ ] 工具执行期间按 Ctrl+C → 中断工具执行，可继续输入新问题

## T13 会话持久化与上下文适配

- [ ] 含工具调用的对话退出 → 会话文件包含 tool_use 与 tool_result 块
- [ ] `--resume` 恢复该会话 → 文本块照常显示，工具块显示为一行摘要（如「〔工具调用 ReadFile〕」）
- [ ] 恢复后继续提问 → 上下文包含恢复前的工具结果（模型能引用）
- [ ] 上下文超限丢弃时，tool_use/tool_result 内容参与 token 估算（不因结构变化丢块或漏算）

## T14 端到端验收 ⚑

- [ ] 真实 API：问「读 `pom.xml` 并总结用到了哪些依赖」→ 屏幕出现工具卡片（ReadFile + 参数摘要）→ 最终回复引用 `pom.xml` 真实内容
- [ ] 真实 API：分别让模型调用 WriteFile / EditFile / Bash / Glob / Grep 各至少一次，卡片状态为「完成」，结果与真实执行一致
- [ ] 真实 API：让模型调用不存在的工具名 → 卡片状态为「失败」，模型最终回复能说明失败原因（is_error 已回传）
- [ ] 含工具调用的会话退出后 `--resume` 恢复 → 工具块显示摘要，继续对话正常
- [ ] 全程 `mvn test` 全绿（新增工具相关单测均无网络依赖）
