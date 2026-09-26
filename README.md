# ACode

> 终端 AI 编程助手（类似 Claude Code），纯 Java 实现。

ACode 运行在终端里，与你交互、调用大模型 API、驱动 Agent 循环执行工具，帮助你完成编码任务。支持 Anthropic 与 OpenAI 两种协议，内置流式输出渲染、Prompt 工程与多级权限防护。

## 🧭 开发路线图

ACode 按阶段迭代构建，每阶段有独立设计文档（`docs/chXX/`）。README 只描述**已完成**能力（即下表中「已完成」阶段对应的章节），后续阶段完成后再同步补充到本文。

| 阶段 | 主题 | 设计文档 | 状态 |
|---|---|---|---|
| 阶段一 | 交互式对话（让 AI 开口说话） | `docs/ch01/` | ✅ 已完成 |
| 阶段二 | 工具调用（工具系统） | `docs/ch02/` | ✅ 已完成 |
| 阶段三 | Agent Loop（让 Agent 自己干活） | `docs/ch03/` | ✅ 已完成 |
| 阶段四 | Prompt 工程体系（System Prompt 设计） | `docs/ch04/` | ✅ 已完成 |
| 阶段五 | 权限系统 | `docs/ch05/` | ✅ 已完成 |
| 阶段六 | MCP 工具生态（MCP 协议） | `docs/ch06/` | ✅ 已完成 |
| 阶段七 | 上下文管理 | `docs/ch07/` | ✅ 已完成 |
| 阶段八 | 记忆系统 | `docs/ch08/` | ✅ 已完成 |
| 阶段九 | Slash Command | `docs/ch09/` | ✅ 已完成 |
| 阶段十 | Skill 系统 | `docs/ch10/`（待建） | 🚧 规划中 |
| 阶段十一 | Hook 系统 | `docs/ch11/`（待建） | 🚧 规划中 |
| 阶段十二 | SubAgent | `docs/ch12/`（待建） | 🚧 规划中 |
| 阶段十三 | Worktree | `docs/ch13/`（待建） | 🚧 规划中 |
| 阶段十四 | Agent Teams | `docs/ch14/`（待建） | 🚧 规划中 |

> 各章 spec / tasks / checklist 的共创流程见 `CLAUDE.md` 与 `AGENTS.md`。

## ✨ 功能特性

- 🖥️ **终端 TUI**：基于 JLine 的流式输出渲染（活跃区重绘、原生回滚可复制），支持行编辑与上下键选择菜单
- 🤖 **Agent 循环**：模型可多轮调用工具（ReAct），自动规划与执行任务
- 🔧 **内置工具集**：`ReadFile` / `EditFile` / `WriteFile` / `Bash` / `Glob` / `Grep` / `AskUser`，plan 模式额外提供 `ExitPlanMode`
- 🛡️ **权限系统**：五层防线决策链（危险命令黑名单 / 安全命令白名单 / 路径沙箱 / 规则引擎 / 会话「始终允许」→ 四档模式矩阵），敏感操作三选一确认（放行 / 始终允许 / 拒绝）
- 🎯 **Plan 模式**：`/plan` 只读探索并落盘计划，`/do` 按计划执行
- ⌨️ **命令框架**：内置命令声明式注册（名称 + 别名 + 描述），帮助由元数据生成、按类型分三段展示，命令名支持 Tab 补全（候选带描述，别名与正式名等价）
- 🔌 **多供应商协议**：支持 OpenAI 与 Anthropic 协议（默认 OpenAI 对接 DeepSeek）
- 🔗 **MCP 工具生态**：接入社区 MCP Server（GitHub / 数据库 / Slack 等），配置声明即自动连接并注册其工具（stdio / Streamable HTTP 双传输、子进程环境白名单隔离）
- 🧠 **Prompt 工程**：System Prompt 分段拼装（既有七段 + 项目指令段 + 记忆索引段）、环境快照注入、Prompt Cache 断点，每轮 usage 脚注（含 cache_read）
- 💬 **项目级会话池**：会话以 JSONL 逐条追加写入 `<项目根>/.acode/sessions/`，与项目绑定；压缩后整段原子重写，`--resume` 或 `/resume` 恢复并续写同一文件（建议把 `.acode/sessions/` 加入项目 `.gitignore`）
- 📄 **项目指令文件 ACODE.md**：三层加载（项目根 → 项目 `.acode/` → `~/.acode/`）按内容拼接（项目级在前），支持 `@include` 拆文件（深度上限 + 已展开集合防环 + 项目外逃逸拦截）
- 🧠 **长期记忆**：四类记忆（用户偏好 / 纠正反馈 → `~/.acode/memory/`；项目知识 / 参考信息 → `<项目根>/.acode/memory/`）一记忆一文件 + `MEMORY.md` 索引；索引随 system 提示注入（会话启动一次、会话内不变），每轮自然结束后异步提取更新（`memory_auto: false` 可关自动提取，`/memory run` 手动提取）；记忆的增 / 改 / 删由 **Agent 在对话中维护**（说「记住某事」即写入对应记忆文件并同步索引），`/memory` 命令只管三层指令文件的查看 / 创建
- 🧠 **上下文管理**：超长工具结果全文落盘 + 定长预览（信息不丢、可读回）；对话逼近窗口上限时自动结构化摘要压缩（`/compact` 可手动触发），压缩后带边界提醒、失败熔断不误伤主流程
- ⚙️ **三级配置加载**：内置默认 → 全局配置 → 项目级配置，逐级覆盖
- 📝 **纯文件日志**：日志只写文件，不污染终端输出

## 🚀 快速开始

### 环境要求

- JDK 21+（本机默认 JDK 17 需 `JAVA_HOME=D:\java\jdk21` 指向 JDK 21）
- Maven 3.9+

### 构建

```bash
mvn clean package
```

构建产物为可执行 fat jar：`target/acode.jar`

### 运行

```bash
java -jar target/acode.jar          # 启动新会话
java -jar target/acode.jar --resume # 恢复上次会话
```

## ⌨️ 命令

在会话输入框直接输入（命令名大小写不敏感）：

| 命令 | 别名 | 说明 |
|---|---|---|
| `/help` | `h` / `?` | 显示帮助；`/help <命令名>` 查看详细用法 |
| `/compact` | `c` | 压缩上下文；占用低于 5000 token 时提示无需压缩；带参数作为保留重点 |
| `/resume` | — | 弹出会话选择菜单恢复历史会话（↑/↓ 选择、回车加载、Esc 取消） |
| `/memory` | — | 查看 / 创建三层指令文件；`/memory run` 手动提取长期记忆 |
| `/permission` | — | 无参数列举三层权限规则与当前模式；带参数切换到四档模式之一 |
| `/status` | `s` | 显示综合状态（模式 / Token 占用 / 工具数 / 记忆条数 / 工作目录 / 版本） |
| `/quit` | — | 退出程序 |
| `/clear` | — | 清空界面与对话上下文 |
| `/plan` | `p` | 切换到规划模式（只读探索，计划落盘 `.acode/plans/`）；带参数把任务交给 Agent |
| `/do` | — | 切换到执行模式；有待执行计划时按计划开始执行 |
| `/review` | — | 构造审查提示词，让 Agent 分析未提交变更（只审查不修改） |

- 只有 `help` / `compact` / `plan` / `status` 四条命令有别名，别名与正式名完全等价。
- 滚动查看完整聊天：`PageUp` / `PageDown`。

## 🛡️ 权限模式

| 模式 | 读 (READ) | 写 (WRITE) | 命令 (EXEC) |
|---|---|---|---|
| `default` | 直接放行 | 弹确认 | 弹确认 |
| `acceptEdits` | 直接放行 | 直接放行 | 弹确认 |
| `plan` | 直接放行 | 弹确认 | 弹确认 |
| `bypassPermissions` | 全部放行（危险命令黑名单 + 路径沙箱仍生效） | 全放 | 全放 |

- 默认模式来自 `.acode/config.yaml` 的 `permission_mode`，未配置时按 `default`。
- `/permission <模式>` 即时切档，只改内存、不写回 config；`/permission` 无参数时列出当前模式与三层规则文件（规则只读，增 / 改 / 删请直接编辑列出的规则文件）。
- 危险命令（如 `rm -rf /`）黑名单最高优先**硬拦截不弹窗**；写项目外路径被路径沙箱直接拒绝。

## ⚙️ 配置

配置采用**三级加载链**，后加载的覆盖先加载的字段：

| 优先级 | 配置来源 | 路径 |
|---|---|---|
| 1（最低） | 内置默认（jar 内） | `config.yaml` |
| 2 | 全局配置 | `~/.acode/config.yaml` |
| 3（最高） | 项目级配置 | `.acode/config.yaml`（当前工作目录） |

常用配置项（来自内置默认 `src/main/resources/config.yaml`）：

| 键 | 说明 |
|---|---|
| `protocol` | 供应商协议：`anthropic` / `openai` |
| `model` | 模型名称 |
| `base_url` | API 请求地址 |
| `api_key` | API 密钥 |
| `max_context_tokens` | 上下文窗口上限，接近上限时自动把较早的对话压缩成摘要，不丢弃消息 |
| `max_iterations` | Agent 循环最大轮数 |
| `memory_auto` | 自动记忆提取开关（默认 `true`）。关掉后无每轮后台模型调用，`/memory run` 手动入口仍可用 |
| `permission_mode` | 默认权限模式（可选：default/acceptEdits/plan/bypassPermissions） |
| `mcp_servers` | MCP server 列表（可选）：命令启动型（stdio）或 URL 型（HTTP），见下方「MCP 工具生态」 |
| `tee` | 诊断日志开关（或通过环境变量 `ACODE_TEE` 开启） |

> 💡 首次使用请在外部配置（全局或项目级）中填写真实的 `api_key`，不要直接改 jar 内的内置默认。

## 🔗 MCP 工具生态

ACode 作为 MCP（Model Context Protocol）客户端，把社区 MCP Server 暴露的工具注册进工具中心，Agent 调用时无感。在外部配置声明 `mcp_servers` 段，启动自动连接：

```yaml
# 命令启动型（stdio 子进程）
mcp_servers:
  my_server:
    type: stdio
    command: npx
    args: [-y, "@modelcontextprotocol/server-everything"]
    # env: { KEY: value }      # 显式环境变量（其余本机变量一律不传）
    # timeout: 60              # 超时秒数，默认 60
    # permission: exec         # read/write/exec，默认 exec
  remote_server:
    type: http
    url: https://example.com/mcp
    # headers: { Authorization: "Bearer xxx" }
```

- 工具注册名 = `server名_工具名`（如 `my_server_list_issues`），规避与内置工具、多 server 重名冲突
- 默认权限档 `exec`：调用前确认、plan 模式不可见；声明 `permission: read` 的 server 工具在 plan 模式可见
- 子进程环境白名单化：只透传平台必需变量 + 配置显式 `env`，不泄露 API 密钥等敏感信息
- 单 server 连接失败打警告跳过、其余照常；退出时自动清理子进程

## 🧪 测试

```bash
mvn test   # 1139 个用例（1 个平台受限跳过）；本机内存偏紧时建议 MAVEN_OPTS="-Xmx768m" mvn test -DargLine="-Xmx512m"
```

## 📁 项目结构

```
src/main/java/com/acode/
├── App.java                    # 入口：--resume 参数解析、委托主流程
├── ConversationController.java # 主循环：输入分流、Agent 编排、事件渲染、会话持久化
├── context/                    # 上下文管理（大结果落盘 + 结构化摘要压缩 + 熔断/守卫）
├── agent/                      # ReAct 循环、事件模型、工具执行器、交互确认门
├── config/                     # 配置加载与校验（三级加载链）
├── conversation/               # 会话历史与请求组装（不静默裁剪、代次并发防护）
├── mcp/                        # MCP 客户端（JSON-RPC 编解码、stdio/HTTP 传输、工具适配、生命周期）
├── memory/                     # 长期记忆（四类记忆文件 + MEMORY.md 索引 + 每轮结束异步提取写回）
├── permission/                 # 权限系统（五层防线决策链、四档模式、黑名单、沙箱、规则）
├── prompt/                     # Prompt 工程（System Prompt 分段、ACODE.md 三层加载与 @include 展开）
├── provider/                   # 供应商实现（anthropic / openai，SSE 解析、usage）
├── session/                    # 会话持久化（<项目根>/.acode/sessions/，JSONL 追加 + 原子重写 + 恢复四步）
├── sse/                        # SSE 流解析
├── tool/                       # 工具接口、注册中心与基类（impl 下为内置工具）
└── ui/                         # 终端渲染（流式输出、工具卡片、确认/选择菜单）
src/main/resources/
├── config.yaml                 # 内置默认配置
└── logback.xml                 # 日志配置
```

## 🛠️ 技术栈

- **Java 21** + Maven
- **JLine** 3.27 —— 终端行编辑与 TUI
- **JNA** 5.14 —— Windows 原生控制台支持（JDK21 下 raw mode 的唯一可用 provider）
- **SnakeYAML** —— YAML 配置解析
- **Jackson** —— JSON 序列化
- **Logback** —— 文件日志
- **JUnit 5** —— 单元测试

## 📄 License

项目暂未指定开源协议。
