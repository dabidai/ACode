# ACode 阶段四：Prompt 工程体系 — spec

> 最后更新：2026-08-18

## 背景

ACode 已完成对话层（阶段一）、工具调用（阶段二）、Agent Loop（阶段三）。循环跑起来了，但「驾驶手册」几乎是空白的：发给模型的 system 提示词目前**只有** plan 模式的中文提醒（SYSTEM 消息硬拼在请求首位，FULL/SPARSE 轮间变化），没有身份设定、没有行为准则、没有环境上下文——工作目录、操作系统、Git 状态一概没有告诉模型。

后果是模型行为完全依赖默认倾向：输出冗长、顺手重构、用 `bash cat` 而不是 ReadFile、不知道自己在 Windows 上该跑什么命令。同一个模型、同一套工具、同一个循环，Prompt 不同，跑出来是两辆车。

本章建立完整的 Prompt 工程体系：七模块 System Prompt 组装器、七源到三通道的组装管线、环境上下文收集与注入、工具描述强化、Prompt Cache（双断点 + usage 验证）、system-reminder 注入机制、Plan Mode 改造、5 个定性评估场景。做完之后，System Prompt 字节稳定、缓存真实命中、每轮 input token 成本下降，模型行为从「能干活」进化到「干得好」。

## 目标用户

- 本人：让 ACode 在多步任务中行为可控、可预测、成本可测
- 后续阶段的潜在使用者：终端开发者、习惯命令行工作流的用户

## 能力清单

1. 七模块 System Prompt 组装器：Section 结构体 + 优先级排序（间隔 10 留位，具体数值见 checklist），Identity 身份 / Behavior 行为准则 / ToolUsage 工具使用 / CodeQuality 代码质量 / Security 安全边界 / TaskPattern 任务模式 / OutputStyle 输出风格 七个英文模块，便于后续章节插入新模块
2. Prompt 组装管线：assembleAPIPayload 把七类信息来源分发到 system / messages / tools 三通道，后续来源（项目指令、记忆）可插拔接入
3. 环境上下文收集器：会话启动时收集工作目录、OS/arch、shell、Git 仓库与分支、模型、日期，生成环境快照
4. 环境上下文注入：作为会话级 system-reminder 消息（XML 标签包裹）注入 messages 首条 user 消息位置；环境快照是 session state，每轮随请求重新组装、不持久化进历史；不进入 system 通道，环境变化不污染缓存
5. 工具描述强化：ReadFile / EditFile / WriteFile / Bash / Glob / Grep 六个工具的 description 字段补齐用法、优先级、配合关系，与 ToolUsage 模块双重强化关键规则
6. Prompt Cache 控制：Anthropic 请求的 system 通道整体与 tools 通道末工具设 `cache_control: ephemeral`；OpenAI 缓存自动生效、不输出该字段
7. usage 解析与展示：两端解析 API 返回的 usage（含缓存命中字段），每轮结束在终端以脚注行展示，验证缓存策略真实生效
8. system-reminder 注入机制：role=user + `<system-reminder>` XML 标签包裹的消息；环境快照（会话级）每轮注入 messages 首条、轮次级提醒每轮尾插——均只进请求不进历史；它是传输约定而非更高优先级指令通道
9. Plan Mode 改造：plan 模式指令从 SYSTEM 硬拼接改为轮次级 system-reminder 尾插（近因效应）；第 1 轮完整版、每 5 轮重复完整版、其余轮次精简版
10. 典型场景评估：5 个定性评估场景文档，每次改 prompt 后做人工对照

## 非功能要求

- 字节稳定：system 提示词会话启动时构建一次，会话内每轮请求完全一致，保证缓存命中
- 零破坏迁移：存量测试全绿；现有监听实现类零改动（用 default 方法扩展接口）
- 成本可见：每轮 usage 脚注行展示 input / cache_read / cache_write / output token 数
- 可测试：请求体结构、usage 解析均可离线测试（请求 JSON 断言、录制 SSE 片段，不依赖真实 API）
- 无持久化负担：环境与轮次级提醒只进请求不进历史，历史仅含真实对话消息；恢复会话时环境快照重新探测
- 无网络依赖：新增逻辑的单元测试不依赖真实 API

## 设计骨架

### 分层结构

```
┌───────────────────────────────────────────────┐
│  UI 层：文本流式渲染、工具卡片、usage 脚注行   │
├───────────────────────────────────────────────┤
│  Agent 层：循环编排 + 事件模型（新增 usage 事件）│
├───────────────────────────────────────────────┤
│  Prompt 层（本章新增核心）：七模块 + 组装管线    │
│  + 环境收集器 + system-reminder 机制           │
├───────────────────────────────────────────────┤
│  工具层：Tool 接口 / 注册中心（description 强化）│
├───────────────────────────────────────────────┤
│  Provider 层：双后端流式协议                    │
│  + cache_control 输出 + usage 解析透传          │
├───────────────────────────────────────────────┤
│  消息模型层：content block 对话历史与持久化      │
└───────────────────────────────────────────────┘
```

- **Prompt 层**是本章新增核心：组装管线是每轮请求的唯一入口，七类来源在这里分发到三通道。
- **Provider 层**只加两件事：cache_control 输出（Anthropic）与 usage 解析透传（双端），协议处理逻辑不动。
- **工具层**只改 description 字段，接口与执行逻辑不动。
- **Agent 层**只改 plan 提醒的组装方式（SYSTEM 硬拼 → 轮次级 system-reminder），循环逻辑不动。

### 七源 → 三通道

| 信息来源 | 通道 | 本章状态 | 说明 |
|---|---|---|---|
| 静态 System Prompt（七模块） | system | 本章实现 | 会话内字节稳定，可缓存 |
| 环境上下文 | messages（首条 user 消息） | 本章实现 | 会话级 system-reminder，session state 每轮注入、不进历史 |
| 工具描述 | tools | 本章强化 | API 规范要求 |
| 对话历史 | messages | 已有 | API 规范要求 |
| System Reminder（轮次级） | messages（尾插） | 本章实现 | 按轮注入、只进请求不进历史 |
| 项目指令文件 | messages | ch07 | 扩展位（MEWCODE.md） |
| 自动记忆 | messages | ch09 | 扩展位 |

分发规则：**稳定的放 system（可缓存）、变化的放 messages（保护缓存）、工具描述放 tools（API 规范）**。轮次级 reminder 插在历史**之后**（尾插），利用近因效应让模型更容易注意到；环境快照（会话级）插在历史**之前**（首条），作为背景上下文随每轮请求注入，两者均不进历史。

### 关键设计决策（已确认）

| 决策点 | 结论 |
|---|---|
| 模块语言 | 英文（参考 Claude Code / MewCode 已验证措辞，模型遵循度最高）；项目文档仍中文 |
| 模块优先级 | 七模块按优先级递增排序，间隔 10 为后续插入留位（具体数值见 checklist） |
| 环境上下文 | 会话启动收集一次成快照，作为 session state 存入会话；每轮请求注入 messages 首条（不进历史）；恢复会话重新探测新快照 |
| system-reminder 语义 | 传输约定而非更高优先级指令通道：模型按 XML 标签识别为系统补充指令，但 API 层面仍是 user 消息 |
| 缓存断点 | 两个：system 文本块 + tools 最后一个工具；第三断点（末条 user 消息末块）留作后续优化 |
| cache_control 开关 | Anthropic 恒开、不可配置；OpenAI 缓存自动生效、不输出该字段 |
| usage 展示 | 每轮结束终端脚注行 + INFO 日志文件 |
| reminder 注入位置 | 环境快照（会话级）：每轮注入 messages 首条、不进历史；轮次级：trim 后尾插、不进历史 |
| Plan Mode 节奏 | 第 1 轮完整；每 5 轮（6/11/16…）重复完整；其余精简——成本/复杂度折中的 heuristic，非语义必然 |
| system 提示词生命周期 | 会话启动构建一次、会话内字节稳定多轮复用；resume 用当前版本重建、prompt 不持久化，不保证跨版本稳定 |
| Prompt 与权限 | Prompt 提供行为引导，工具层权限拦截位（确认门槛）是权威安全边界，二者分工 |

### 组装管线数据流

```
会话启动（一次性）：
  七模块 → 按优先级排序拼接 → system 提示词（存入会话对象）
  环境快照 → 环境收集器探测 → system-reminder 包裹 → 存入会话状态

每轮请求（assembleAPIPayload）：
  [SYSTEM: 七模块提示词]
  + [环境 system-reminder（首条，会话快照，不进历史）]
  + [历史消息（trim 超限丢弃最早）]
  + [轮次级 system-reminder（可选，尾插，不进历史）]
  + tools（Anthropic 末工具带 cache_control）
  → 组装请求 → Provider（Anthropic：system 数组形式 + cache_control；
     OpenAI：SYSTEM 转 messages 内 system 消息）
```

## Out of Scope（本章明确不做）

- MEWCODE.md 项目指令文件加载（章节 7）
- 自动记忆系统（章节 9）
- 真实 MCP Server 接入（章节 6）
- LLM-as-judge 自动评估管线（依然是人工跑场景对比）
- 第三个缓存断点（末条 user 消息末块，留作后续成本优化）
- token 成本统计图表与历史聚合
- 上下文压缩与摘要（章节 8）
