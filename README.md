# ACode

> 终端 AI 编程助手（类似 Claude Code），纯 Java 实现。

ACode 运行在终端里，与你交互、调用大模型 API、驱动 Agent 循环执行工具，帮助你完成编码任务。支持 Anthropic 与 OpenAI 两种协议，内置流式输出渲染、Prompt 工程与多级权限防护。

## ✨ 功能特性

- 🖥️ **终端 TUI**：基于 JLine 的流式输出渲染（活跃区重绘、原生回滚可复制），支持行编辑与上下键选择菜单
- 🤖 **Agent 循环**：模型可多轮调用工具（ReAct），自动规划与执行任务
- 🔧 **内置工具集**：`ReadFile` / `EditFile` / `WriteFile` / `Bash` / `Glob` / `Grep` / `AskUser`，plan 模式额外提供 `ExitPlanMode`
- 🛡️ **权限系统**：五层防线决策链（危险命令黑名单 / 安全命令白名单 / 路径沙箱 / 规则引擎 / 会话「始终允许」→ 四档模式矩阵），敏感操作三选一确认（放行 / 始终允许 / 拒绝）
- 🎯 **Plan 模式**：`/plan` 只读探索并落盘计划，`/do` 按计划执行
- 🔌 **多供应商协议**：支持 OpenAI 与 Anthropic 协议（默认 OpenAI 对接 DeepSeek）
- 🔗 **MCP 工具生态**：接入社区 MCP Server（GitHub / 数据库 / Slack 等），配置声明即自动连接并注册其工具（stdio / Streamable HTTP 双传输、子进程环境白名单隔离）
- 🧠 **Prompt 工程**：七模块 System Prompt、环境快照注入、Prompt Cache 断点，每轮 usage 脚注（含 cache_read）
- 💬 **对话保存与恢复**：退出自动保存到 `~/.acode/sessions/`，`--resume` 或 `/resume` 恢复
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

在会话输入框直接输入：

| 命令 | 说明 |
|---|---|
| `/quit` | 退出程序 |
| `/clear` | 清空界面与对话上下文 |
| `/plan` | 进入规划模式（只读探索，计划落盘到 `.acode/plans/`） |
| `/do` | 退出规划模式，按已交付计划开始执行 |
| `/permission-mode` | 查看/切换权限模式（default/acceptEdits/plan/bypassPermissions） |
| `/resume` | 加载历史会话（↑/↓ 选择） |
| `/help` | 显示帮助 |
| `PageUp / PageDown` | 滚动查看完整聊天 |

## 🛡️ 权限模式

| 模式 | 读 (READ) | 写 (WRITE) | 命令 (EXEC) |
|---|---|---|---|
| `default` | 直接放行 | 弹确认 | 弹确认 |
| `acceptEdits` | 直接放行 | 直接放行 | 弹确认 |
| `plan` | 直接放行 | 弹确认 | 弹确认 |
| `bypassPermissions` | 全部放行（危险命令黑名单 + 路径沙箱仍生效） | 全放 | 全放 |

- 默认模式来自 `.acode/config.yaml` 的 `permission_mode`，未配置时按 `default`。
- `/permission-mode <模式>` 即时切档，只改内存、不写回 config。
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
| `max_context_tokens` | 上下文窗口上限，超出后按"轮"丢弃最早消息（保证工具调用配对完整） |
| `max_iterations` | Agent 循环最大轮数 |
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
mvn test   # 565 个用例；本机内存偏紧时建议 MAVEN_OPTS="-Xmx768m" mvn test -DargLine="-Xmx512m"
```

## 📁 项目结构

```
src/main/java/com/acode/
├── App.java                    # 入口：--resume 参数解析、委托主流程
├── ConversationController.java # 主循环：输入分流、Agent 编排、事件渲染、会话持久化
├── agent/                      # ReAct 循环、事件模型、工具执行器、交互确认门
├── config/                     # 配置加载与校验（三级加载链）
├── conversation/               # 会话历史与请求组装（上下文裁剪、代次并发防护）
├── mcp/                        # MCP 客户端（JSON-RPC 编解码、stdio/HTTP 传输、工具适配、生命周期）
├── permission/                 # 权限系统（五层防线决策链、四档模式、黑名单、沙箱、规则）
├── prompt/                     # Prompt 工程（七模块 System Prompt、环境快照、提醒注入）
├── provider/                   # 供应商实现（anthropic / openai，SSE 解析、usage）
├── session/                    # 会话持久化（~/.acode/sessions/）
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
