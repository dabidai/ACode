# ACode 阶段六：MCP 连接并发设计记录（design notes）

> 最后更新：2026-09-02
> 记录 T6 `McpServerConnection` 的并发审查结论、设计决策与已落地改动。
> 本文档是「改动 + 原因」的存档：改动对应 `checklist.md` T6 新增验收项 与 `tasks.md` T6 并发契约；原因对应下方问题解释，避免重连并发这段推理将来丢失。

## 触发问题

`McpServerConnection` 的原始设计（T6）是「单 server 活连接 + 懒重连」，本身具备正确的并发基础：`McpClient` 的 pending 表按 id 异步匹配、stdio 单写者、HTTP 独立 POST。但「懒重连」与「关旧建新」组合后，存在四处没写死的并发风险：

1. **懒重连双线程竞态**：多个线程同时看到 `isAlive()==false` → 都进重连 → 关旧建新发生多次（stdio 型拉起多个子进程）
2. **重连期间并发请求无约定**：一个线程在重连（可能数秒），其余 `callTool` 是等还是失败，设计未定义
3. **重连建新后旧 wrapper 指向旧 client**：`tools()` 缓存的适配器绑定的是发现时刻的 client 实例，重连换实例后全部失效
4. **close 与重连互斥缺失**：`close()` 与并发重连交错 → 可能在 close 后又重建出僵尸连接；`isAlive`/`closed` 跨线程可见性未声明

并发来源是真实的：`StreamingToolExecutor.runConcurrently`（StreamingToolExecutor.java:88-112）把 READ 权限组的多个工具用 `VirtualThreads.POOL` **真实并行**执行，所以同一 server 的多个 `callTool` 并发到达不是假设，而是常态。

## 核心改动（已写入哪些文件）

### `checklist.md` T6 —— 新增 3 条验收 + 1 段并发决策说明

- **并发重连竞态（等锁单飞）**：两个线程同时打到死连接 → 只重建一次连接（stdio 只拉起一个子进程 / 假 server 只收到一次 initialize），两个 callTool 均正常返回
- **重连后旧 wrapper 仍可用**：重连（建新 client）后，重连前已从 `tools()` 拿到的 wrapper 再 execute → 经 connection 路由到当前活 client、调用成功
- **close 与重连互斥**：close 置 closed 标志后并发 callTool/重连不重建连接；close 时 in-flight 请求异常完成、不悬挂

### `tasks.md` T6 —— `McpServerConnection` 补充「并发契约」4 条

① 重连单飞用等锁（备选快速失败，切换只改锁获取逻辑）；② wrapper 不绑定 client 实例，经 `volatile currentClient` 路由；③ `close()` 置 `volatile closed` 标志并与重连互斥；④ `isAlive`/`closed` 用 volatile 保证可见性。

## 关键设计决策

### 决策 1：重连单飞用「等锁」（当前选型）

| 方案 | 行为 | 代价 |
|---|---|---|
| **等锁**（当前） | callTool 遇连接死亡 → 在重连锁上阻塞，同一时刻仅一个线程重建；其余等锁后复用新连接 | 重连期间并发调用阻塞，受 McpClient 超时兜底 |
| 快速失败（备选） | 并发 caller 不等待，检测到重连进行中立即返回 `ToolResult.failure` | 瞬时失败更多，但更快、不阻塞 |

**选型理由**：重连多发生在「进程被杀 / 网络断」等低频场景，优先减少失败、保证调用成功率。

**切换方式**：只改 `callTool` 的锁获取逻辑（阻塞获取 → 尝试获取失败即返回），验收用例与契约不变。

### 决策 2：wrapper 经 connection 的 `volatile currentClient` 路由

wrapper 只持有 connection 与原始工具名，不持有 client 实例；每次 execute 现读 `currentClient`。重连只换 volatile 字段，旧 wrapper 自动落到新连接，对重连透明。

### 决策 3：close 与重连互斥

connect/close/reconnect 共用一把锁；`close()` 置 `volatile closed`，`connect()` 开头检查 closed，防 close 后重建。

### 决策 4：跨线程可见性

`isAlive()`/`closed`/`currentClient` 用 volatile；HTTP 的 isAlive「最近请求成败」用原子更新，避免内存可见性问题。

### 决策 5：McpToolWrapper 不继承 BaseTool——`final execute` 的保留理由与边界

**为什么 `BaseTool.execute`（BaseTool.java:53）是 final**：模板方法把「校验 → 提交虚拟线程 → 超时/中断/异常 → `ToolResult.failure`」焊死。`final` 把「永不抛异常 + 必有超时上限」这条安全不变式从**约定**（Tool 接口 javadoc 仅声明「实现不应抛出异常」）升级为**结构强制**——6 个内置工具（Bash 起进程、文件 IO 等复杂逻辑）没有任何一个能绕过超时或把异常漏进 Agent 循环。一旦 execute 可覆写，任意一个 override 都能打破该不变式。

**值得保留的原因**：安全外壳是普适不变式，强制优于约定；内置工具逻辑复杂、风险集中，此处最需要结构保证。

**已知边界（模板范围过度）**：final 把三件事捆绑，只有「安全外壳」是普适的——
1. `paramSpecs()` 校验：不普适（MCP 是任意 JSON Schema，交互/无参工具也不走）
2. 虚拟线程执行模型：不普适（交互工具需特定线程、MCP 需异步）
3. final 只保护 BaseTool 子树：直接实现 `Tool` 的类（AskUserTool/ExitPlanModeTool/本阶段 McpToolWrapper）仍须手动保证不抛，靠 Tool 接口 javadoc + 单点 adapter 兜底——final 给的是防御纵深，不是系统级保证

**与 T6 的关系**：McpToolWrapper 绕开 BaseTool 不是规避缺陷，而是这层模板形状不符；直接实现 Tool 复用 AskUserTool/ExitPlanModeTool 的既有先例，安全契约在 adapter 单点兑现（任何异常转 `ToolResult.failure`）。

**未来方向（本阶段不做）**：理想解是把安全外壳从继承解耦成装饰器（如 SafeTool wrapper，可包任意 Tool 获得「不抛 + 超时」），内置与 MCP 工具统一复用；属跨工具统一重构，ch07 不实施。

## 问题解释存档（为什么）

### 问题 A：同时调 callTool 的「两个线程」是哪两个？

来源是 `StreamingToolExecutor.runConcurrently`（StreamingToolExecutor.java:88-112）：一个模型回合如果产出多个 **READ 权限** 工具调用，会分成「读组」用 `VirtualThreads.POOL.submit` 真实并行执行。

```
模型一个回合要调 2 个 MCP READ 工具（同属一个 server）
  → 读组并行：每个工具提交一个虚拟线程（VirtualThreads.POOL）
  → 此时 server 连接刚好是死的（stdio 子进程被杀 / HTTP 网络断）
  → 两个虚拟线程各自进 McpToolWrapper.execute → callTool
  → 两个线程都看到 isAlive==false → 都尝试重连 → 竞态
```

「两个线程」= **同一个读组里的两个虚拟线程**，各自执行一个 `tool_use` 块，并发打到同一个 `McpServerConnection`。串行组（非 READ）在执行线程上逐个跑、互不竞争；**并发只发生在读组**。

### 问题 B：旧 McpToolWrapper 为什么指向旧 client？

`connect()` 语义是「关旧建新」——重连会新建一个 McpClient（新 transport、stdio 型就是新子进程）；而 wrapper 是连接发现时由 `tools()` 一次性缓存的。

```
时间轴：
 t0  connect() → 建 client#1 → listTools → tools() 缓存一批 McpToolWrapper
       └─ 若 wrapper 构造时直接持有 client#1 的引用 ─┐
 t1  子进程死 → callTool 触发重连 → 关 client#1 → 建 client#2 │
 t2  重连后，之前缓存/已发给 Agent 的 wrapper 再 execute     │
       └─ 它仍握着 client#1 → 打一个已关闭的 client → 永远失败 ←┘
```

根子：**连接生命周期会变（重连换实例），而 wrapper 是静态缓存的**。修法即决策 2——wrapper 不缓存 client 引用，每次现读 connection 的 `volatile currentClient`。

## 验收要点（可勾选，见 checklist T6）

- [ ] 两个线程同时打到死连接 → 只重建一次连接，两个 callTool 均正常返回
- [ ] 重连建新后，重连前拿到的旧 wrapper 再 execute 仍成功（路由到新连接）
- [ ] close 置 closed 后，并发 callTool/重连不重建连接；in-flight 请求异常完成

## 明确不做 / 后续可改

- **快速失败切换**：已预留，只改 `callTool` 锁获取逻辑，本轮不实现
- **HTTP isAlive 原子更新细节**：实现时按「最近一次请求结果原子记录」处理，不新增接口
- **安全外壳解耦成装饰器**（SafeTool wrapper，见决策 5 未来方向）：跨工具统一重构，本轮不实施
- 其他并发点（tools() 快照不可变性、重连期间的超时上限）在实现时随 T6/T7 落定，不单独立文档
