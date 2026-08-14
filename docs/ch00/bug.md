# ACode Bug 记录：流式输出乱码根因 = 有界事件队列静默丢 delta

> 记录日期：2026-08-15
> 状态：根因已确认（源码 + 双日志证据链闭环），修复方案已确认（putSafe），实施中
> 涉及文件：`TurnCollector.java`、`Agent.java`（`AgentEvent.QUEUE_CAPACITY`）、`ConversationController.java`（消费端）

## 一句话结论

**流式输出乱码不是渲染层问题，也不是 API 问题，而是 ACode 内部的 `ArrayBlockingQueue(64)` 事件队列在满载时用 `offer()` 静默丢弃 delta**：显示端缺字、历史（session）因先 append 后 offer 而保持完整。既有的纯追加式写屏方案（阶段四）解决不了这个 bug。

## 症状

- 流式回复中后段乱码、丢字，且**总是从前半段干净处、从长行/代码块/表格处开始变坏**（如 `### 3. 父 POM` → `### . POM`、`| **持久化** | 支持 RDB 快照和 AOF 日志，重启后数据可恢复 |` → `| **持久化** | RDB快照OF ，重启后数据可恢复可用**主、哨Sentinel（ |`）。
- 同一轮的历史/会话文件（`.acode/sessions/*.json`）内容完整干净。

## 证据链（2026-08-15 用 ACODE_TEE 双日志实测，运行 `deepseek-v4-flash`）

1. **API 原始输出干净**：`acode-sse.log`（`sseDiag` 在 `OpenAiProvider` 里逐条写原始 SSE `data`）抽取 run `6344b5f1` 全文，表格完整：
   ```
   | **持久化** | 支持 RDB 快照和 AOF 日志，重启后数据可恢复 |
   | **高可用** | 支持主从复制、哨兵（Sentinel）、集群（Cluster） |
   | **原子操作** | 命令天然原子，支持事务与 Lua 脚本 |
   ```
2. **StreamPrinter 收到的 delta 缺字**：`acode-streamprinter.log` 同一时刻收到的 delta 拼起来是：
   ```
   | **持久化** | RDB快照OF ，重启后数据可恢复可用**主、哨Sentinel（ |
   原子操作** | 天然，支持与 脚本 |
   ```
3. **逐字对比确认是「整条 delta 被丢」而非「字符被破坏」**：收到内容是被丢 delta 的相邻片段拼接，每一段都是干净文本的**连续子序列**（如 `RDB快照OF ，` 是 `支持 RDB 快照和 AOF 日志，` 丢 `支持 `/` 和 A`/` 日志` 后的剩余；`可用**主、哨Sentinel（` 是下行 `| **高可用** | 支持主从复制、哨兵（Sentinel）` 丢 `| `、`高`、`支持从复制、兵` 后的剩余）。无字符被改写，只有整条 delta 凭空消失。
4. **历史干净**：`collector.text()` 在 `offer` 之前 `append`，session 拿到全量。

## 根因机制

```java
// TurnCollector.onDelta（TurnCollector.java:33-39）
if (cancelled.get()) return;
text.append(delta);                                  // ① 先累积 → 历史永全
events.offer(new StreamText(delta));                 // ② 再入队 → 满时静默丢
```

- 事件队列：`Agent.java:64-65` `events = new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY)`，容量 `QUEUE_CAPACITY = 64`（`AgentEvent.java:12`）。
- `offer()` 不阻塞、队满返回 false 且无异常 → 该条 delta 只从显示端消失，无人感知。
- 消费者（主线程）每条 delta 做一次**全量 markdown 重渲染 + 终端写屏**，渲染成本随文档变长而升高；生产者（`acode-provider` 线程）按 API 推送速度灌。文档一长、渲染赶不上 → 队列快速填满 → 中后段 delta 成批丢弃 → **前半段干净、乱码从长行/代码块处开始**。

## 为什么纯追加式写屏方案修不了这个 bug

未提交的纯追加式流式（`fizzy-shimmying-finch.md`，阶段四）只改写屏层：去掉光标上移/清屏、完整行出现即 `appendCommitted`。但 **delta 若已在队列里丢失，写屏写得再干净也是缺内容**。两个问题叠加：
1. 渲染层错位（旧 footer 重绘 + AnsiWriter 翻译 + JLine 宽度含滚动条）——纯追加方案在修；
2. **队列丢 delta（本 bug）**——纯追加方案完全不覆盖。

## 修复方案（已确认，待实施）

采用 **A 方案 + `putSafe` 封装**，参考来源：

- **MewCode 教程（`F:\code\agent-doc-tech`，ACode 的架构参照）**：Java 版明确用 `LinkedBlockingQueue(64)` + **`putSafe`**（阻塞 `put()` + `InterruptedException` 时恢复中断位），并注明「保障 TUI 关停时能干净退出」。
- **ACode 旧版（提交 330f055~1，pre-queue 时代）**：`streamRound()` 是**无丢**设计——provider daemon 线程把 delta 直接写进 `reply` StringBuilder + `printer.onDelta`（显示模型），主线程 repaint，线程安全靠 OutputPane 快照拷贝（提交 469d54b）。改成有界队列后引入 `offer()` 静默丢是唯一偏离参照的改动。

**核心方法**（加到 `AgentEvent.java`）：

```java
static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
    try {
        queue.put(event);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

`put()` 满时阻塞 → 生产者（`acode-provider` 线程）被 TCP 背压追平，**不再丢 delta**；`InterruptedException` 时恢复中断位而非吞掉，取消/退出路径不受影响。ACode 消费端是 `ConversationController` 常驻 `poll(20ms)` 循环，**无死锁风险**；不需要教程里的 30s poll 兜底。

**4 处 `offer()` → `putSafe()` 调用点**：

| 文件 | 行号 | 事件 |
|------|------|------|
| `TurnCollector.java` | 38 | `StreamText` |
| `TurnCollector.java` | 47 | `ToolUseEvent` |
| `Agent.java`（`emit`） | 407 | `TurnComplete`/`LoopComplete`/`ErrorEvent`/`RetryEvent` |
| `StreamingToolExecutor.java` | 112 | `ToolResultEvent` |

B 方案（文本独立无丢通道）暂不做，视后续需要。

## 复现/回归方法

- 启动：`$env:ACODE_TEE=1; java -jar target\acode.jar`
- 用会触发长表格/长行的提问（如「介绍 redis」），观察流式中后段表格是否完整。
- 对照检查：`acode-sse.log`（API 原文，应为干净全文）vs `acode-streamprinter.log`（收到的 delta，修复后应与 SSE 逐条一致、无缺字）。
