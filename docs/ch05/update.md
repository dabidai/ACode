我会按“安全边界、决策语义、并发与文件一致性、主流程接入、测试验收”几条线审阅这三份文档，重点找规范之间互相冲突、实现后容易出现假安全，以及清单无法真正证明正确性的地方。由于目前只有 spec/checklist/tasks，没有源码，我会把结论限定为设计和验收层面的风险。整体看，这套设计的结构是完整的：有模式矩阵、黑名单、沙箱、规则、HITL、错误回填和端到端验收。但目前存在几处较严重的语义冲突和安全边界问题，尤其是 **规则优先级、plan 目录例外、路径检查 TOCTOU、命令白名单**。这些问题如果按现有 tasks 直接实现，测试可能全绿，但行为仍然不符合“安全优先、规则只收缩不放宽”的目标。

**高优先级问题**

1. **规则的“同层后定义优先”和“deny 永远赢”互相矛盾**

文档同时规定：

- 同一层内，后定义规则覆盖先定义规则；
- deny 跨层不可翻转；
- `RuleEngine` 第一遍扫描所有层，只要发现任意 deny 就直接返回 DENY；
- 同层规则需要 reversed 遍历。

按照当前“两遍法”，下面的规则会返回 DENY：

```yaml
- rule: Bash(rm *)
  effect: deny
- rule: Bash(rm -rf ./build)
  effect: allow
```

但“同层后定义优先”通常应该返回 ALLOW。

建议明确成以下算法：

1. 每一层内部从后往前求值，得到该层最终结果；
2. 如果任意层的最终结果是 DENY，则返回 DENY；
3. 否则按 local > project > user 选择第一个 ALLOW；
4. 如果没有结果，返回 null。

这样可以同时满足：

- 同层后定义覆盖先定义；
- 不同层的 deny 不可被 allow 翻转；
- local allow 不能覆盖 project/user deny。

当前 checklist 没有覆盖“同层先 deny、后 allow”的反例，建议补上。

2. **plan 例外可能绕过沙箱**

当前决策顺序是：

```text
内容提取
→ plan 例外
→ 安全命令
→ 危险命令
→ 沙箱
```

而 plan 例外的描述是判断 `file_path` 是否“含有 `.acode/plans/`”。这会产生明显问题：

```text
.acode/plans/../secret.txt
```

或者：

```text
.acode/plans/link-to-outside/file.txt
```

如果只做字符串包含判断，就可能被当成计划文件放行。更重要的是，spec 明确规定：

> 权限规则只能在沙箱内收缩权限，无法放行沙箱外路径。

plan 例外也应该遵守这个原则。

建议：

- 先执行路径沙箱检查；
- 对路径做 canonical/real path 解析；
- 将 canonical path 与 canonical 的 `.acode/plans` 根目录进行 `startsWith` 判断；
- 只允许 `WriteFile/EditFile`；
- 不能用字符串 `contains(".acode/plans/")`；
- 计划目录本身及其父级符号链接也要验证。

建议将顺序改为：

```text
内容提取
→ 危险命令
→ 沙箱
→ plan 目录例外
→ 规则
→ 会话授权
→ 模式矩阵
→ HITL
```

对于文件工具，沙箱必须是不可覆盖的硬边界。

3. **路径检查存在 TOCTOU 问题**

即使 `PathSandbox.check()` 正确解析了符号链接，也可能出现：

```text
权限检查：link -> project/file.txt
权限通过
攻击者或其他线程：link 改成 -> /etc/passwd
工具执行：实际访问了项目外文件
```

这属于典型的 check-then-use 问题。当前设计只说明“检查前解析符号链接”，没有规定执行阶段如何保证检查的路径和实际打开的路径一致。

建议至少补充一种实现约束：

- ReadFile 使用 `NOFOLLOW_LINKS` 或安全文件句柄打开；
- WriteFile/EditFile 在打开前再次校验；
- 对父目录和目标文件都避免符号链接替换；
- 尽量使用已打开的 `Path`/`FileChannel`，不要检查一个字符串、执行另一个重新解析的字符串；
- 测试中增加并发替换符号链接的用例，或者明确说明当前阶段不覆盖该威胁。

如果工具层无法做到原子安全，文档不应使用“危险操作绝对拦死”这种表述。

4. **安全命令白名单的前缀匹配过于宽泛**

当前规则是：

```text
safe command 完全相等
或 command.startsWith(safe + " ")
```

这对简单命令可用，但如果白名单包含以下类别，就可能放行实际具有副作用的命令：

```text
find ...
python ...
node ...
npm ...
cargo ...
git ...
```

例如：

```bash
find . -exec sh -c '...' \;
python -c '...'
node -e '...'
npm install
git remote ...
```

其中部分命令可能修改文件、联网或执行任意代码。

文档提到 SAFE_COMMANDS 共有 66 条，但没有定义每一条的完整语法边界，只要求与参考实现逐一核对。建议：

- 不要只按命令名做白名单；
- 对具有子命令的工具按子命令匹配；
- 对 `python/node/npm/cargo/go` 等解释器或构建工具，除非明确限定参数，否则不应作为自动放行命令；
- 增加反例测试，例如：
  - `find . -exec rm -rf {} \;`
  - `python -c "import os; os.remove(...)"`;
  - `npm install`;
  - `git push`;
  - `git remote add`;
  - `cargo run`;
- 重新评估“66 条”这个数量指标。数量本身不是安全性质，具体语义才是。

5. **黑名单的能力与目标描述不一致**

spec 中使用了：

> 危险操作绝对拦死  
> 黑名单最高优先  
> 确保 Agent 不做危险的事

但 checklist 又明确验收以下命令“不拦截”：

```bash
rm -rf --no-preserve-root /
rm -rf /*
echo ... | base64 -d | sh
```

这本身可以作为“启发式黑名单”的设计，但不能同时宣传为绝对安全。尤其在：

```text
bypassPermissions → Bash = ALLOW
```

的模式下，以下命令可能仍然可以绕过黑名单：

```bash
python -c ...
perl -e ...
ruby -e ...
node -e ...
sh -c ...
base64 -d | sh
```

建议二选一：

- 如果目标是强安全：改为命令解析/策略引擎，至少覆盖解释器、重定向、管道、子 shell 和编码执行；
- 如果保留启发式黑名单：把产品目标改为“阻断已知高风险模式”，并明确 bypass 模式只适用于完全受控环境。

目前文档的安全承诺明显高于实现方案能提供的保证。

**中优先级问题**

6. **Glob 规则转换需要更严格的测试**

规则定义为：

```text
* → .*
? → .
其他字符正则转义
```

这可以实现扁平 glob，但需要明确：

- 反斜杠如何处理；
- 空 pattern 是否允许；
- 换行符是否允许；
- `[`、`]`、`(`、`)`、`+`、`{}` 是否全部按普通字符处理；
- pattern 中出现 Unicode 或 Windows 路径时的行为；
- `Pattern.matches()` 是否对换行、大小写做预期处理。

目前只有少数简单测试，容易出现“精确匹配安全，但 glob 转换仍可误匹配”的问题。建议增加特殊字符矩阵测试。

7. **权限拒绝结果可能出现重复或不稳定前缀**

T5 中 `CheckResult.deny(reason)` 返回原因，T9 又固定包装：

```text
权限拒绝：" + reason
```

需要明确 `reason` 是否保证不包含“权限拒绝”。否则可能出现：

```text
权限拒绝：权限拒绝：危险命令
```

建议统一约定：

- 权限层只返回纯原因，例如“危险命令：递归强制删除根目录”；
- Agent 层统一负责生成用户/模型可见的 `权限拒绝：...`；
- 所有拒绝路径都通过一个统一工厂方法生成结果。

同时，用户主动选择“拒绝”时的结果是：

```text
用户拒绝执行……
```

而黑名单、沙箱、规则拒绝的原因格式不同，应在 checklist 中明确每类错误的稳定契约。

8. **未知工具可能被错误放行**

文档规定未知工具的内容提取返回 null，并跳过内容相关检查。这样会导致：

- 未知工具如果被标成 READ，default 模式下直接 ALLOW；
- bypass 模式下未知 WRITE/EXEC 工具直接 ALLOW；
- 未知工具的参数可能包含实际路径或命令，但不会经过沙箱/黑名单。

R6 把这描述为“参考实现同样如此”，但这是明确的扩展安全风险。建议：

- 未注册工具默认 DENY，或者至少默认 ASK；
- 只有明确注册、明确分类的工具才能进入自动放行；
- 对没有 content extractor 的 EXEC/WRITE 工具默认禁止 bypass 自动放行；
- 新增工具时由编译期或注册 API 强制提供权限分类和参数提取器。

9. **Glob/Grep 的路径安全边界被刻意排除，但风险没有充分说明**

spec 明确不覆盖 Glob/Grep 的可选 `path` 参数。问题是：

- Glob/Grep 可能搜索项目外路径；
- 搜索结果可能暴露敏感文件名、内容或目录结构；
- default 模式下它们是 READ 自动放行。

如果这是有意的范围限制，需要在威胁模型中明确写出“Glob/Grep 仍可能访问沙箱外路径”。更稳妥的实现是：

- 对工具的 `path` 参数也做沙箱校验；
- pattern 只用于规则匹配，不应被误当成路径；
- 增加 `Glob/Grep(path=项目外目录)` 的测试。

10. **“始终允许”持久化的失败语义没有定义**

T9 流程是：

```text
addAllowAlwaysRule()
appendLocalRule()
执行工具
```

如果本地文件写入失败：

- 是继续执行但只保留会话授权？
- 还是本次也拒绝？
- 是否向用户提示持久化失败？
- 如果写入成功但重载失败，内存和磁盘是否一致？
- 文件被并发修改时是否覆盖其他规则？

建议定义为：

- 会话授权先立即生效；
- 持久化失败时本次执行仍可继续，但必须产生可观测警告；
- 使用临时文件 + 原子 rename；
- 保留未知 YAML 字段或明确声明会重写整个文件；
- 对 `appendLocalRule` 增加写入失败、目录权限、文件损坏、并发调用测试。

11. **规则文件的安全属性没有定义**

本地规则文件相当于权限授权数据库。当前只定义了 YAML 解析容错，没有定义：

- 文件是否需要限制权限；
- 符号链接规则文件是否允许；
- 用户级规则文件被项目进程替换时如何处理；
- YAML 别名、递归结构、超大文件如何限制；
- 项目目录下 `.acode/permissions.local.yaml` 被其他进程修改后的行为。

单用户本地工具的威胁模型可以简化，但至少要说明信任边界。

12. **并发设计只覆盖了内存列表，没有完整覆盖状态一致性**

文档提到：

```java
volatile List<PermissionRule>
ConcurrentHashMap.newKeySet()
volatile mode
```

这是必要的，但还不够：

- `appendLocalRule` 的读-改-写不是原子操作；
- 多个工具调用可能同时返回 `ALLOW_ALWAYS`；
- `setMode` 与正在等待确认的调用之间，采用旧模式还是新模式没有定义；
- executor 的结果数组、事件列表、confirmation gate 是否支持并发；
- 多个 ASK 同时出现时，确认 UI 是否串行；
- 取消一个确认是否会影响其他确认。

建议补充并发验收，尤其是多个并行工具调用同时选择“始终允许”的情况。

**验收清单本身的问题**

13. **“mvn compile 无警告”不适合作为稳定验收项**

不同 JDK、Maven 插件和编译参数可能产生不同警告。建议改成：

```text
mvn -DskipTests compile 通过
```

如果确实要求无警告，应在 `pom.xml` 中固定编译器参数，并明确 warning policy。

14. **“SAFE_COMMANDS = 66 条”是脆弱的验收标准**

数量不能证明集合正确。增加或删除一个合理命令就会导致无意义失败。建议验收：

- 必须自动放行的命令集合；
- 必须 ASK 的命令集合；
- 必须 DENY 的命令集合；
- 对危险参数和子命令的反例。

可以保留数量作为参考检查，但不应作为核心行为契约。

15. **几个关键边界没有测试**

建议补充以下测试：

- 同层先 deny、后 allow；
- 同层先 allow、后 deny；
- local/project/user 三层分别有 allow 和 deny 的完整组合；
- plan 路径 `../` 逃逸；
- plan 路径符号链接逃逸；
- Windows 路径分隔符；
- 路径大小写差异；
- 项目根目录本身是符号链接；
- 空参数、缺失参数、参数类型错误；
- 未知工具；
- `Glob/Grep` 的外部 `path`；
- `appendLocalRule` 写入失败；
- 多线程同时追加规则；
- 确认过程中切换模式；
- executor 并发发起多个确认；
- 真实工具执行前后路径发生变化。

16. **T8 的命令解析行为不完整**

目前只说：

```text
/permission-mode
/permission-mode default
/permission-mode yolo
```

但没有定义：

```text
/permission-mode default extra
/permission-mode    acceptEdits
/permission-mode ACCEPT_EDITS
/permission-mode bypassPermissions trailing-space
```

建议明确：

- 是否大小写敏感；
- 多余参数是否报错；
- 空白如何处理；
- 是否显示配置模式还是当前运行时模式；
- 运行时切换是否持久化到 config；
- 重启后是否恢复配置值。

17. **构造器兼容性可能造成未启用权限检查**

T9 要求保留旧的 `StreamingToolExecutor` 构造器给存量测试。如果旧构造器允许 `permissionChecker == null`，生产代码或未来调用方可能无意中绕过权限系统。

建议：

- 旧构造器只保留给测试，并标记 deprecated；
- 或提供一个明确的测试 fake；
- 生产装配路径中强制要求非 null checker；
- 增加“所有生产 executor 都携带 checker”的装配测试。

**建议优先修改的文档结论**

在开始实现前，至少应修改以下四点：

1. 明确规则求值算法，解决同层优先级和跨层 deny 的冲突。
2. 将 plan 目录判断改为 canonical path 判断，并确保不能绕过沙箱。
3. 重新定义危险命令黑名单的安全承诺，或者扩大命令解析能力。
4. 对未知工具和 Glob/Grep 外部路径采用 fail-closed 或至少 ASK 策略。

目前最值得警惕的是：这份清单非常详细，容易给人“覆盖充分”的感觉，但它对几个真正影响安全性的反例没有验收。尤其是“测试全绿”并不能证明权限系统安全，因为规范本身已经允许部分危险命令绕过，且规则优先级还有未定义行为。