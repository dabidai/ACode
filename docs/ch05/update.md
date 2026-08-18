我把这 5 份材料按 **spec → tasks → checklist → eval scenarios** 对了一遍。整体上，这个计划的架构思路是成立的，而且任务拆分得比较清楚；但里面有几处我认为是**真正需要在开工前修正的设计风险**，还有一些验收标准写得过于绝对，后面很容易出现“代码其实没问题，但验收过不了”的情况。

先给结论：

> **整体方案约 7.5/10，可以做，但建议先改 6 个关键点。**
>
> 最大的问题不是七模块 Prompt 本身，而是 **cache 的假设、system-reminder 的消息语义、环境消息与 trim 的关系、Plan Mode 的轮次策略，以及“行为验收”过于绝对化**。

---

# 一、我认为最重要的 6 个问题

## 1. `cache_read > 0` 被当成必然事实，这个验收条件太硬

你现在多处写成：

* 第 1 轮 `cache_write > 0`
* 第 2 轮开始 `cache_read > 0`
* `cache_read` 大致等于 system + tools token
* T10 直接要求 Anthropic 第 2 轮起 `cache_read > 0` 且 `in` 稳定

例如 checklist：

> 第 1 轮：`cache_read` 为 0、`cache_write` > 0
> 第 2 轮及以后：`cache_read` > 0 且逐轮稳定 ≈ system + tools token 

这里有一个很大的问题：

**“设置了 cache_control” ≠ “服务端一定产生 cache_write/cache_read”。**

实际是否产生缓存命中，还可能受到：

* provider 的缓存策略
* prompt 是否达到缓存要求
* API/模型版本
* cache TTL
* system/tools 内容是否真的保持一致
* provider 对缓存断点的具体实现
* 请求前缀是否完全匹配

影响。

所以你现在的测试实际上混合了两个东西：

```text
代码是否正确产生 cache_control
        +
真实 API 是否按预期命中缓存
```

这两个应该拆开。

### 建议改成两层验收

**离线测试：确定性**

```text
Anthropic:
system 是 array
system 最后一个 block 有 cache_control
tools 最后一个 tool 有 cache_control
OpenAI 不出现 cache_control
```

这些现在已经有了。

**真实 API：概率性/环境相关**

不要写：

> 第 2 轮必须 `cache_read > 0`

改成：

> 在满足 provider 缓存条件的情况下，多轮请求应观察到 `cache_read > 0`；若 provider 未产生缓存命中，应记录原因，不作为代码失败的唯一依据。

尤其是：

> `cache_read ≈ system + tools token 数`

这个也不建议作为严格判据。

---

# 2. “环境消息放 user + system-reminder”这个设计需要非常谨慎

这是我认为架构上第二大的风险。

spec 明确决定：

> 环境上下文作为首条 user 消息，并用 `<system-reminder>` 包裹。

然后：

```text
[SYSTEM: 七模块]
+
[历史消息]
+
[system-reminder user message]
```

你的设计理由是避免污染 system cache。这个思路可以理解。

但问题在于：

**你实际上把“可信的系统上下文”降级成了 user message。**

也就是说，从消息协议层面：

```text
SYSTEM
  ↓
最高优先级

USER + <system-reminder>
  ↓
低优先级
```

你希望模型把后者当作“系统指令”，但 API 层面它本质还是 user content。

而且你的 `system-reminder` 同时承担两种完全不同的东西：

1. 环境事实

```text
Working directory
Platform
Git branch
...
```

2. 强行为指令

```text
Plan Mode:
You are in read-only mode...
```

这两类东西其实不应该完全等价。

### 我建议至少在设计文档里明确：

```text
system-reminder is a transport convention, not a higher-priority
instruction channel.
```

否则后面很容易出现一个误区：

> “因为叫 system-reminder，所以模型一定把它当 system instruction。”

这在架构上是不成立的。

---

# 3. 环境信息“首条消息”与 `trim` 的关系现在是自相矛盾的

你已经意识到了这个问题：

> 环境消息是最旧消息，长会话 trim 时可以被丢掉，而且接受这个风险。

但 spec 同时又强调：

> 环境上下文让模型知道工作目录、OS、Git 状态，是 Prompt 工程体系的重要能力。

这就形成了一个明显的设计矛盾：

```text
环境信息很重要
       ↓
但放在最老消息
       ↓
trim 后可能消失
       ↓
模型突然不知道自己在哪个目录
```

你现在的解决办法是：

> “接受，等 ch08 压缩兜底。”

我觉得**这个决定有点过早**。

因为这不是一个纯粹的 ch08 问题。

### 我更建议：

环境消息不要依赖“永远存在于历史”。

每轮组装时至少应该保证：

```text
SYSTEM
+
trim 后历史
+
必要的环境 context
+
turn reminder
```

但这样又会破坏缓存吗？

**不会影响 system cache。**

因为环境信息本来就在 messages，而不是 system。

所以你完全可以把“环境快照”视为：

> session state，而不是 history state。

例如：

```java
Conversation.environmentSnapshot
```

每次 assemble：

```text
system
history
environment reminder
turn reminder
tools
```

这样即使 history trim 掉环境消息，下一轮仍然能补回来。

---

## 这里我反而认为你现在的“已知风险、接受”不够合理

你写的是：

> 环境消息 trim 后丢失属预期。

我建议改成：

> **环境信息不应依赖历史保留；trim 后应由 session-level snapshot 重新注入。**

这会让整个系统可靠很多。

---

# 4. Plan Mode 的“每 5 轮完整版”有点机械，而且可能浪费 token

现在定义：

```text
1 FULL
2 SPARSE
3 SPARSE
4 SPARSE
5 SPARSE
6 FULL
7 SPARSE
...
```

tasks 甚至明确修正了参考实现里的 bug。

这个公式本身没问题：

```java
iteration == 1 || (iteration - 1) % 5 == 0
```

但是从 Prompt Engineering 角度，我觉得**“每 5 轮重复 FULL”本身没有足够强的依据**。

因为 Plan Mode 的核心状态不是：

> “经过了 5 轮”

而是：

> “模型是否还处于 plan mode，以及它是否需要重新看到关键约束”。

例如：

```text
1 FULL
2 SPARSE
3 SPARSE
4 SPARSE
5 SPARSE
6 FULL
```

如果第 6 轮模型刚好在：

```text
tool → tool → tool → tool → tool
```

你重新塞一整套 plan instruction 是合理的。

但如果：

```text
第 5 轮已经 ExitPlanMode
```

第 6 轮再发 FULL 就没有意义。

所以更合理的设计可能是：

```text
FULL：
- 首轮
- 长时间没有完成计划时周期性刷新

SPARSE：
- 普通轮次

STOP：
- ExitPlanMode 后不再发送
```

当然，如果你当前章节的目标就是**验证一个简单 deterministic 的 Plan Mode 策略**，可以保留现在的设计。

但我建议把它明确成：

> “这是成本/复杂度折中的 heuristic，而不是语义上的必然规则。”

---

# 5. 评估场景里有一些“模型行为绝对化”了

这个问题非常明显。

比如场景 2：

> 出现 `cat/head/tail/sed/echo>` Bash 调用即失败。

这太绝对。

例如用户说：

> “请运行 `cat /etc/hosts` 查看这个系统文件。”

如果 ACode 的 `ReadFile` 根本不能读取这种特殊路径，模型使用 Bash `cat` 可能是合理的。

你的 Prompt 原文其实也是：

> Bash 仅在无专用工具时用。

这两个规则并不完全一致。

应该测试：

> **存在专用工具且专用工具适用时，优先使用专用工具。**

而不是：

> 出现 Bash 就失败。

否则你会把合理的 fallback 判成 bug。

---

同样的问题还有：

### “探索性问题不直接动手”

这个规则：

> 探索性问题回 2~3 句建议，不直接动手。

也有点过硬。

比如：

> “你觉得这个项目用 Spring 还是别的框架重构更好？”

模型当然应该先讨论。

但如果项目上下文已经明确存在，而且回答这个问题必须查看 `pom.xml`，那么：

```text
先 Read pom.xml
再回答
```

其实可能比纯粹猜测更好。

所以建议改成：

> **没有明确执行请求时，不主动进行具有修改/副作用的操作；允许只读探索以获取回答所需上下文。**

这个规则明显更稳。

---

# 6. “默认不写注释”可能过度约束代码质量

你现在在多个地方都把：

> 默认不写注释

作为硬规则。

然后 eval 又写：

> 不主动给代码加注释。

这个方向我能理解——防止 AI 每改三行代码就塞一大堆废话注释。

但最好不要写成绝对规则。

因为存在：

```java
// workaround for ...
```

或者：

```java
// Required because ...
```

这种真正有价值的注释。

更好的 Prompt 是：

> **Do not add comments unless they explain non-obvious behavior, constraints, or workarounds.**

这样既避免 AI 注释泛滥，又不会禁止必要注释。

---

# 二、还有几个比较隐蔽的问题

## 7. `systemPrompt` 的生命周期设计有重复/不一致风险

现在 T3：

```text
Conversation.systemPrompt
```

然后 T8：

```text
start()
  conversation.setSystemPrompt(PromptBuilder.buildSystemPrompt())
```

spec 又规定：

> system prompt 会话启动构建一次，会话内保持 byte-stable。

这个方向对。

但 resume 时怎么处理，需要更加明确。

现在 checklist 写：

> resume 刷新环境，但 system prompt 不应该变化。

tasks 却又说：

> 恢复会话时 system prompt 重建（内容确定性）。

这两者目前**不是完全同一个概念**：

```text
重新 build system prompt
```

和

```text
保证 system prompt 与原会话完全一致
```

不是一回事。

如果以后 PromptSections 改了，那么：

```text
旧 session resume
```

到底应该使用：

```text
旧版本 system prompt
```

还是：

```text
当前版本 system prompt
```

现在没有定义。

### 我建议现在明确：

> session resume 使用当前 ACode 版本重新构建 system prompt；Prompt prompt 本身不持久化。system prompt 只要求“当前 session 内 byte-stable”，不保证跨版本稳定。

这样就清楚了。

---

# 8. `PromptPipeline` 现在有点像“为了架构而架构”

T3 里：

```java
PromptPipeline.assemble(...)
    -> Conversation.buildRequest(...)
```

然后 checklist 又要求：

> `PromptPipeline.assemble` 与 `conversation.buildRequest` 等价。

这其实说明：

> **Pipeline 目前没有真正承担“组装管线”的职责。**

只是 facade。

spec 又把它描述成：

> “七源到三通道的组装管线”。

所以存在一个架构名实不符的问题。

现在可以接受，因为你是在逐章演进。

但最好把职责明确成：

```text
PromptPipeline
  ├── system
  ├── history
  ├── environment
  ├── turn reminder
  └── tools
```

而不是：

```text
PromptPipeline
    ↓
Conversation.buildRequest()
```

否则第 7、9 章继续加：

```text
MEWCODE.md
Memory
```

时，最终很可能又全部堆回 `Conversation`。

---

# 三、任务依赖总体没问题，但 T8 太胖

你现在：

```text
T1,T2 → T3
T2,T3 → T7
T3,T5,T6,T7 → T8 → T10
T4,T5,T6,T9 并行
```

这个 DAG 基本合理。

但是 T8 实际塞了太多东西：

```text
start()
resume
loadSession
clear
usage
UI rendering
preview
环境消息
system prompt
```

也就是说 T8 是一个**大集成炸弹**。

我会建议拆成：

```text
T8a Session/environment integration
T8b Usage UI integration
T8c UI filtering
T8d End-to-end integration
```

这样出了问题更容易定位。

尤其：

```text
环境消息 bug
```

和

```text
usage event bug
```

本质上完全不是一类问题。

---

# 四、我特别建议增加一个“反例测试”章节

目前你的 eval 全部是在证明：

> Prompt 工作了。

但缺少：

> **Prompt 不应该误伤正常行为。**

这很重要。

比如增加一个场景 6：

### 场景：专用工具不可用时允许 fallback

测试：

```text
ReadFile 无法处理某种特殊对象
→ Bash 是否可以合理 fallback？
```

### 场景：只读探索允许读取

```text
“帮我判断这个项目是否使用 Spring”
```

模型应该可以：

```text
Read pom.xml
Grep spring
```

而不是：

```text
不执行任何工具
```

### 场景：必要注释

让模型修一个需要解释 workaround 的 bug：

```text
是否允许必要注释？
```

### 场景：危险命令的用户明确授权

第一次：

```text
帮我 git push --force
```

→ 确认。

用户明确：

```text
确认执行
```

→ 应该执行，而不是因为 Prompt 写了“危险命令”就永久拒绝。

---

# 五、另外一个很重要的问题：安全规则和工具权限不是一回事

你现在场景 4：

> 删除 / force push → 模型应该先确认。

这个设计不错，但需要明确：

```text
Prompt 层确认
≠
ACode 工具层权限确认
```

你自己其实已经意识到这一点：

> “给模型一个会触发权限确认（ACode 拦截位）的命令时，等待确认而非绕过。”

这是正确方向。

我建议把架构原则直接写进 spec：

> **Prompt provides behavioral guidance; tool permission interception remains the authoritative security boundary.**

否则以后很容易变成：

```text
Prompt：
危险操作需要确认

但是：
BashTool
   ↓
没有权限控制
```

那 Prompt 再写十遍安全规则都没用。

---

# 六、我会怎么修改你的验收标准

如果让我直接给这个阶段重新划分优先级，我会这样：

### P0：必须改

1. **cache 命中不能作为确定性单测结果**
2. **明确 system-reminder 只是 user message，不是新的权限层级**
3. **环境信息不能依赖 history 永久存在**
4. **Prompt 安全规则 ≠ tool permission boundary**
5. **“禁止 Bash / 禁止注释 / 禁止探索”等绝对规则改成条件规则**

### P1：建议改

6. 拆 T8
7. 明确 resume 时 system prompt 生命周期
8. 明确 `PromptPipeline` 后续真正负责多源组装
9. Plan Mode FULL/Sparse 改成 heuristic，而不是语义硬规则

### P2：可以以后再做

10. cache 第三个 breakpoint
11. 自动 eval
12. token 成本历史统计
13. prompt A/B 测试

这也和你的 out-of-scope 边界比较一致。

---

# 七、整体评价

我觉得这套计划**最大的优点**是已经不是“堆 Prompt 文案”，而是在做一个真正的 Prompt runtime：

```text
七模块
   ↓
PromptBuilder
   ↓
PromptPipeline
   ├── system
   ├── messages
   └── tools
        ↓
Provider
        ↓
usage
        ↓
AgentEvent
        ↓
UI
```

这个方向是对的，而且 T1～T10 的拆分也比较工程化。spec 对“稳定内容放 system、动态内容放 messages、工具描述放 tools”的核心分层也很清晰。

**真正值得在开工前修改的是：不要把“Prompt 的理论设计”误写成“模型行为的绝对保证”。**

尤其是：

> `cache_read > 0`
> `出现 Bash 即失败`
> `不读就不能回答`
> `绝不写注释`
> `探索性问题绝不调用工具`

这些都应该从**绝对规则**变成**有条件的行为约束**。

如果只让我选一个地方先改，我会先改 **环境消息 + trim + system-reminder 的设计**；第二个改 **cache 验收标准**。这两个是后面最容易出现架构返工的地方。
