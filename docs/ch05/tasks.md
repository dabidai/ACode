# ACode 阶段五：权限系统 — 任务清单

> 最后更新：2026-08-19
> 依赖关系：`T1~T4→T5`；`T5,T7→T8`；`T6、T7 可并行`；`T5,T6,T7→T9→T11`；`T10 与开发并行`。
> 参考实现：MewCode Java（`F:/code/agent-doc-tech/agent-doc-tech/downloads/source/2_mewcode-java.zip` 内 `src/main/java/com/mewcode/permission/{PermissionChecker,PermissionMode,PermissionResponse}.java`），任务中标注的参考文件用 `unzip -p` 直接读取，不必解压。

## 约定

- 包根 `com.acode`，新增权限层 `src/main/java/com/acode/permission/`（模式、危险命令检测、沙箱、规则、决策检查器），测试 `src/test/java/com/acode/permission/`
- 现有文件行号以 2026-08-18 的 HEAD 为准，改动时先 Read 确认
- 每个任务完成后跑 `mvn test` 确认不破坏已有代码（构建环境：`JAVA_HOME=D:\java\jdk21`）；测试方法名用英文驼峰
- 需要复用的现有设施：`ToolContext.resolve`（相对路径基于工作目录）、`ToolRegistry.available`、`SelectionMenu`（确认菜单）、`FakeProvider`（集成测试桩）、`@TempDir`（沙箱/规则文件测试）、`ToolResult.failure/success`（拒绝/放行结果形态）
- 规则文件命名空间 `.acode/`：用户级 `~/.acode/permissions.yaml`、项目级 `{工作目录}/.acode/permissions.yaml`、本地级 `{工作目录}/.acode/permissions.local.yaml`；plan 计划目录 `{工作目录}/.acode/plans/`（与 `PlanWriter` 一致）

### 全局集成风险（各任务参考资料会引用，编号 R1~R8）

- **R1** 确认机制从布尔升级为三选一是**破坏性改动**：`Confirmation`、`ConfirmationGate`、`EventConfirmationGate`、`ConfirmationPrompt`、`StreamingToolExecutor`、`ConversationController` 六个类 + `ConfirmationTest/ConfirmationPromptTest/EventConfirmationGateTest/StreamingToolExecutorTest/ConversationControllerTest` 五个测试，编译错误即消费点索引，逐个补。
- **R2** 并发安全：READ 工具在虚拟线程并发执行，`PermissionChecker.check` 被并发调用。规则列表在 `appendLocalRule` 时整体替换 → 用 `volatile List<PermissionRule>` 持有不可变快照（append 时构造新列表再赋值），或对 check/append 加同步；`mode` 字段用 `volatile`（`/permission-mode` 变更需对并发读可见）。`appendLocalRule` 的 YAML 读-改-写回盘仅从 HITL 确认路径触发（UI 一次确认一个工具，串行），不与并发读路径竞争，暂不加文件锁；未来若支持并行确认再加固。
- **R3** 沙箱 fail closed 但新建文件不误伤：WriteFile 目标尚不存在时 `resolve(strict=true)` 抛错 → 改解析**父目录**，父目录在允许范围内即放行（对齐理论篇 validatePath 伪代码第 2 步）。
- **R4** deny 跨层不可翻转：规则求值用**逐层后定义优先**——每层（local/project/user 各自文件）内从后往前求值、后定义覆盖先定义，得该层最终结果（ALLOW/DENY/null）；任一层最终为 DENY 即返回 DENY；否则按 local→project→user 取第一个非 null；全为 null 返回 null。这同时满足「同层先 deny、后 allow → ALLOW」与「用户级 deny > 本地级 allow」。**偏离 Java 参考的扁平列表实现**（其本地 allow 可盖用户 deny），按理论篇 + Python 源码。测试必须断言「用户级 deny > 本地级 allow」与「同层后定义覆盖」（含同层先 deny 后 allow 的反例）。
- **R5** 规则优先级：本地 > 项目 > 用户。理论篇 ASCII 图写反，以本清单为准。
- **R6** 内容提取遗漏即静默绕过：新工具未注册到 CONTENT_FIELDS / isPathTool 时 content=null，跳过沙箱与规则层（参考实现同样如此）。本阶段只覆盖六个内置工具；后续新增**文件类工具必须同步更新 isPathTool**（checklist 有登记项）。
- **R7** READ 工具现在也过权限检查：存量测试若假设「READ 永不拦截」需更新。default 模式 READ→ALLOW 不弹窗、读并发路径不阻塞（检查纯内存）；但 ReadFile 越界路径现在会被沙箱 DENY。
- **R8** `.acode/` 全目录 gitignore：项目级/本地级规则文件在 gitignore 内（与 config.yaml 一致，单人工具、团队共享 out of scope）。「始终允许」写本地文件不产生 VCS 噪音，无副作用。
- **R9** 「始终允许」持久化失败不阻断执行：`appendLocalRule` 写回失败（目录不可写/磁盘满）时，会话级授权已即时生效、本次执行仍放行，但必须产生可观测警告（日志/提示）；写回用临时文件 + 原子 rename，避免半写损坏规则文件。T4 单测与 T9 确认路径都覆盖该降级路径（R2 的串行前提沿用：仅 HITL 路径触发写盘）。

---

### T1 PermissionMode 枚举 + 模式矩阵

**目标**：四档权限模式 + 按工具分类查表的决策方法，嵌套 switch 利用编译期穷举。

**影响文件（新建）**
- `src/main/java/com/acode/permission/PermissionMode.java` —
  - `enum PermissionMode { DEFAULT, ACCEPT_EDITS, PLAN, BYPASS }`
  - 内嵌 `enum Decision { ALLOW, DENY, ASK }`
  - `Decision decide(com.acode.tool.Permission category)` 嵌套 switch：`DEFAULT → READ:ALLOW, WRITE/EXEC:ASK`；`ACCEPT_EDITS → READ/WRITE:ALLOW, EXEC:ASK`；`PLAN → DEFAULT.decide(category)`（委托，不单独实现矩阵）；`BYPASS → ALLOW`
- `src/test/java/com/acode/permission/PermissionModeTest.java` — 4 模式 × 3 分类 = 12 组合决策断言（对照 spec 模式矩阵表）；PLAN 与 DEFAULT 对同一分类结果一致的断言

**依赖**：无

**参考资料**
- MewCode `PermissionMode.java`（zip，全文可直接照搬语义）；ACode 复用现有 `com.acode.tool.Permission` 枚举（READ/WRITE/EXEC：BashTool=EXEC、ReadFile/Glob/Grep=READ、WriteFile/EditFile=WRITE）作为 `decide` 的分类输入

---

### T2 危险命令检测 + 安全命令白名单

**目标**：8 条预编译正则的黑名单 + 只读安全命令白名单，二者独立可测。

**影响文件（新建）**
- `src/main/java/com/acode/permission/DangerousCommandDetector.java` —
  - `detect(String command)`：遍历 8 条预编译 `Pattern`，`matcher.find()` 子串匹配，命中返回 `(true, 中文原因)`，全部不命中返回 `(false, "")`
  - 内置 8 条正则（Java 转义后的 `Pattern.compile` 入参）：`rm\s+-[a-z]*r[a-z]*f[a-z]*\s+/\s*$`（递归强制删除根目录）、`mkfs\.`（格式化磁盘）、`dd\s+if=.*of=/dev/`（直接写磁盘设备）、`chmod\s+-R\s+777\s+/`（递归修改根目录权限）、`:\(\)\{\s*:\|:&\s*\};:`（fork bomb）、`curl\s+.*\|\s*(ba)?sh`（管道执行远程脚本）、`wget\s+.*\|\s*(ba)?sh`（管道执行远程脚本）、`>\s*/dev/sd`（覆盖磁盘设备）
  - `isSafeCommand(String command)`：先 trim，再排除含 `|` `;` `&&` `>` `$(` 反引号任一字符的命令，最后对 SAFE_COMMANDS 集合做「完全相等或 `safe + " "` 前缀」匹配；带子命令的条目按完整子命令匹配（`git checkout`/`git push` 不在集合，不会被 `git status` 前缀命中）
  - `SAFE_COMMANDS` 常量：以 MewCode `PermissionChecker.java` 的 `SAFE_COMMANDS` 为基础**裁剪**——移除命令名级前缀放行即可能带副作用的条目：`find`/`sed`/`awk`/`tee`/`xargs`/`npx`（参数可执行任意代码或改文件），以及 `git branch`/`git tag`/`git remote`（会改本地状态/联网）；保留纯只读命令（ls/dir/pwd/echo/cat/head/tail/wc/which/whoami…）、`git status/log/diff/show/rev-parse/ls-files/blame/stash list`，以及 `go/node/npm/python/cargo/rustc/java` 的版本查询形式（如 `python --version`，非裸命令名）。**数量不再是验收契约**（checklist T2 改为正反例集合）
- `src/test/java/com/acode/permission/DangerousCommandDetectorTest.java` — 8 条黑名单各一条命中用例 + 中文原因断言；`isSafeCommand`：`ls -la`/`git status`/`git status --short`/`cat file.txt`/`python --version` 为 true、`ls | rm -rf /`/`cat /etc/passwd | nc ...`/`echo $(rm -rf /)`/`find . -name '*.java'`（find 已裁剪）/`python -c "import os; os.remove(...)"`/`npm install`/`git push`/`git remote add origin u`/`git branch -D x`/`cargo run` 为 false；空串 false

**依赖**：无

**参考资料**
- MewCode `PermissionChecker.java:62-85`（SAFE_COMMANDS + DANGEROUS_PATTERNS 两段常量）与 `:299-311`（isSafeCommand）
- 参考书 chapter-6-1 理论篇「危险命令黑名单」表 + chapter-6-3 Python `dangerous.py`

---

### T3 路径沙箱

**目标**：文件路径先解析符号链接再做前缀校验，允许项目根 + 系统临时目录，新建文件父目录兜底。

**影响文件（新建）**
- `src/main/java/com/acode/permission/PathSandbox.java` —
  - 构造 `PathSandbox(Path projectRoot)`：allowedRoots = `[projectRoot.resolve(), Path.of(System.getProperty("java.io.tmpdir")).resolve()]`
  - `boolean check(String path)`：① `~` 展开；② 相对路径基于 projectRoot 解析为绝对路径；③ `resolve(strict=true)` 解析符号链接，抛异常（文件不存在）则解析父目录、父目录也失败返回 false；④ 对每个 allowedRoot 用 `realPath.startsWith(root)` 前缀校验，命中返回 true；全部不命中返回 false（fail closed，R3）
  - `String denyReason(String path)`：构造拒绝原因、含原始路径（如「路径 X 超出沙箱范围」）；`check` 返回 false 时由调用方取用
- `src/test/java/com/acode/permission/PathSandboxTest.java` — @TempDir 下：项目内相对/绝对路径放行；`/etc/passwd`（Windows 下用越界的绝对路径）拒绝；`../../` 逃逸拒绝；**符号链接逃逸**（项目内 `link→项目外文件`，`Files.createSymbolicLink` 失败则跳过该用例并在注释说明）；项目内不存在的新文件放行（父目录兜底）；`java.io.tmpdir` 下路径放行

**依赖**：无

**参考资料**
- MewCode `PermissionChecker.java:313-326`（isPathTool + isPathAllowed，但参考用 normalize-only + fail open——本任务按理论篇改造成 resolve + fail closed）
- 参考书 chapter-6-1 理论篇「路径沙箱」validatePath 伪代码 + chapter-6-3 Python `sandbox.py`（resolve(strict) + 父目录兜底）
- `ToolContext.resolve`（相对路径解析的现有实现，供参考）

---

### T4 规则引擎（三层 YAML + 逐层求值）

**目标**：`工具名(模式)` 语法解析 + glob 匹配 + 三层优先级 + deny 跨层不可翻转 + 「始终允许」持久化。

**影响文件（新建）**
- `src/main/java/com/acode/permission/PermissionRule.java` — `record PermissionRule(String toolName, String pattern, RuleEffect effect)`；内嵌 `enum RuleEffect { ALLOW, DENY }`；`matches(String toolName, String content)`：工具名不相等返回 false；**先精确匹配** `pattern.equals(content)`（防 glob 元字符误匹配「始终允许」持久化的精确内容）；不中再扁平 glob：`*`→`.*`、`?`→`.`、其余字符正则转义，`Pattern.matches(regex, content)` 全串匹配（Python fnmatch 语义，`*` 可跨 `/`——`ReadFile(*.env*)` 能匹配绝对路径；偏离 Java 参考的 PathMatcher，其 `*` 不跨 `/`）
- `src/main/java/com/acode/permission/RuleEngine.java` —
  - 构造 `RuleEngine(Path userFile, Path projectFile, Path localFile)`
  - `RuleEffect evaluate(String toolName, String content)` 返回三态（或 `null` 表示无匹配）：**逐层求值（R4）**——每层（local/project/user 各自文件）内从后往前遍历、后匹配者覆盖先匹配者，得该层最终结果（ALLOW/DENY/null）；任一层最终为 DENY 即返回 DENY；否则按 local→project→user 取第一个非 null；全为 null 返回 null
  - 规则语法解析：`Pattern.compile("^(\\w+)\\((.+)\\)$")`，group1=工具名、group2=模式
  - `loadRulesFile(Path)` 极致容错：文件不存在 / 读失败 / YAML 解析失败返回空列表；条目非 map / 缺 `rule`/`effect` / effect 非 allow|deny / rule 串解析失败全部跳过（参考源码 `loadRulesFile`）
  - `appendLocalRule(String toolName, String pattern)`：读本地文件现有规则 + 追加一条 ALLOW + SnakeYAML dump 回写 `{localFile}`（`Files.createDirectories` 建目录），随后重载内存规则；**写回失败（目录不可写/磁盘满）不抛异常、返回失败标志**（R9），调用方（T9 确认路径）降级为「仅会话授权 + 警告」；写回用**临时文件 + 原子 rename**，避免半写损坏规则文件
- `src/test/java/com/acode/permission/PermissionRuleTest.java` — matches：工具名不匹配 false；`Bash(git *)` 匹配 `git commit -m x`、不匹配 `gitstatus`；`*.env*` 匹配 `/abs/path/.env`（扁平 glob、`*` 跨 `/`）、不匹配 `env.md`；精确匹配优先（`Bash(git commit -m "a*b")` 匹配同串、不误匹配 `git commit -m "axb"`）
- `src/test/java/com/acode/permission/RuleEngineTest.java` — @TempDir 建三层规则文件：同层后定义胜出（**同层先 deny、后 allow → ALLOW**，锁死 R4 语义）；local allow > project allow > user allow；**用户级 deny + 本地级 allow → DENY（R4）**、**项目级 deny + 本地级 allow → DENY**（后加 deny 覆盖先前「始终允许」落盘）；坏 YAML / 坏条目静默跳过、不抛异常；appendLocalRule 后 evaluate 立即反映新规则；appendLocalRule 写回失败返回失败标志、不抛异常（R9）；文件缺失不抛异常

**依赖**：无

**参考资料**
- MewCode `PermissionChecker.java:38-52`（PermissionRule.matches，Java 用 PathMatcher、`*` 不跨 `/`——本任务偏离为扁平 glob，见 spec 决策表）、`:184-297`（RULE_PATTERN / loadRules / loadRulesFile / appendLocalRule）
- chapter-6-3 Python `rules.py`（evaluate 逐层求值 + fnmatch 扁平 glob 的语义来源）

---

### T5 PermissionChecker 决策链

**目标**：内容提取 + 决策链（plan 例外 → 安全命令 → 危险命令 → 沙箱 → 规则 → 会话「始终允许」→ 模式矩阵）的编排器，输出 ALLOW/DENY/ASK + 原因；会话级「始终允许」集合。

**影响文件（新建）**
- `src/main/java/com/acode/permission/PermissionChecker.java` —
  - 内嵌 `record CheckResult(Decision decision, String reason)` + 静态工厂 `allow()/deny(String)/ask()`（Decision 来自 PermissionMode.Decision）
  - 内容提取 `CONTENT_FIELDS`：Bash→`command`、ReadFile/WriteFile/EditFile→`file_path`、Glob/Grep→`pattern`（与六个工具的 ParamSpec 名一致）；`extractContent(Tool, JsonNode args)` 无对应字段返回 null（R6）
  - `CheckResult check(Tool tool, JsonNode args)` 决策链（① 内容提取为预处理，②~⑧ 任一判定 ALLOW/DENY 即返回）：
    - ① 内容提取：`extractContent(tool, args)` → `(toolName, content)`；content=null 时跳过 ②~⑦（R6），仅剩 ⑧ 模式矩阵
    - ② 危险命令：`toolName=="Bash" && content!=null && detector.detect(content)` → deny(原因)
    - ③ 安全命令：`toolName=="Bash" && content!=null && isSafeCommand(content)` → allow
    - ④ 沙箱：`isPathTool(toolName)`（ReadFile/WriteFile/EditFile）`&& content!=null && !sandbox.check(content)` → deny(sandbox.denyReason(content))
    - ⑤ plan 例外：`mode == PLAN` 且 (WriteFile|EditFile)：`file_path` 经 ④ 沙箱通过后，canonical 解析并以 canonical 的 `{项目}/.acode/plans` 根 `startsWith` 命中 → allow（防 `..`/符号链接逃逸绕过沙箱；参考 Java 用 `.mewcode/plans/` 字符串包含，ACode 改为 canonical + startsWith，对齐 PlanWriter）
    - ⑥ 规则：`ruleEngine.evaluate(toolName, content)` ALLOW/DENY 直接返回；null 继续
    - ⑦ 会话级「始终允许」：`allowAlwaysRules.contains(toolName + ":" + content)` → allow（R2：集合用并发安全集合，如 `ConcurrentHashMap.newKeySet()`）
    - ⑧ 模式矩阵：`mode.decide(tool.permission())` → ALLOW/DENY 返回，ASK 返回 `ask()`
  - `void addAllowAlwaysRule(String toolName, String content)`：加入会话集合
  - 构造 `PermissionChecker(PermissionMode mode, Path projectRoot, RuleEngine ruleEngine)`（mode 用 volatile；projectRoot 供沙箱构造，也可外部传入 PathSandbox）
- `src/test/java/com/acode/permission/PermissionCheckerTest.java` — 装配真实 detector/sandbox/ruleEngine：黑名单 DENY、沙箱 DENY、规则 allow/deny 优先级、安全命令 allow、plan 计划文件 allow、**plan 模式 `.acode/plans/../secret.txt` 不命中计划例外（`..` 逃逸、canonical 后越界）**、会话「始终允许」二次命中 allow、**plan 模式下会话「始终允许」放行计划目录外写（显式授权 > plan 限制）**、**切到 default 后同会话规则仍生效（跨模式）**、default 模式 READ allow + WRITE/Bash ask、bypass 全 allow 但黑名单仍 DENY、缺参/空参 content=null 不抛异常落模式矩阵

**依赖**：T1、T2、T3、T4

**参考资料**
- MewCode `PermissionChecker.java:106-176`（check 主流程 + CheckResult）、`:87-94`（CONTENT_FIELDS）
- 参考书 chapter-6-1 理论篇「把五层防线串成决策链」伪代码

---

### T6 HITL 三选一升级（确认机制布尔 → 三态）

**目标**：确认答复从「是/否」升级为「放行 / 始终允许 / 拒绝」，贯穿事件通道、UI 菜单与答复原语。

**影响文件（新建 + 修改，R1 破坏性改动）**
- `src/main/java/com/acode/permission/PermissionResponse.java`（新）— `enum PermissionResponse { ALLOW, ALLOW_ALWAYS, DENY }`
- `src/main/java/com/acode/agent/Confirmation.java`（改）— `BlockingQueue<Boolean>` → `BlockingQueue<PermissionResponse>`；`answer(boolean)` → `answer(PermissionResponse)`；`await` 返回 `PermissionResponse`（取消/中断返回 DENY，等价拒绝语义不变）
- `src/main/java/com/acode/agent/ConfirmationGate.java`（改）— `boolean confirm(...)` → `PermissionResponse confirm(...)`；`ALWAYS_ALLOW` 返回 `PermissionResponse.ALLOW`
- `src/main/java/com/acode/agent/EventConfirmationGate.java`（改）— `confirm` 返回类型改三态（事件握手逻辑不变，透传 `response.await`）
- `src/main/java/com/acode/ui/ConfirmationPrompt.java`（改）— `ask(...)` 返回 `PermissionResponse`；`SelectionMenu` 选项由 `["是","否"]` 改为 `["放行","始终允许","拒绝"]`，选中 0→ALLOW、1→ALLOW_ALWAYS、2→DENY、Esc/Ctrl+C/EOF→DENY；提示行文案不变
- 测试（改）：`ConfirmationTest`（answer/await 三态 + 幂等 + 取消返回 DENY）、`ConfirmationPromptTest`（三选项映射 + 取消→DENY）、`EventConfirmationGateTest`（透传三态）

**依赖**：无（与 T1~T5 可并行；与 T9 集成）

**参考资料**
- MewCode `PermissionResponse.java`（zip，枚举三态）
- 参考书 chapter-6-2 实战篇验证小节「按 1 放行 / 2 始终允许 / 3 拒绝」

---

### T7 Config 增加 permission_mode

**目标**：`permission_mode` 配置键（4 合法值校验），作为启动默认档位。

**影响文件（修改）**
- `src/main/java/com/acode/config/AppConfig.java`（改）— 加 `String permissionMode` 字段 + getter/setter
- `src/main/java/com/acode/config/ConfigLoader.java`（改）— `KNOWN_KEYS`（27-29 行）加 `"permission_mode"`；`apply`（99-138 行）解析字符串字段
- `src/main/java/com/acode/config/ConfigValidator.java`（改）— `permission_mode` 非空时必须在 `default/acceptEdits/plan/bypassPermissions` 之一，否则 `ConfigException`（带文件路径定位）
- 测试（改）：`ConfigLoaderTest`（读取 permission_mode 字段）、`ConfigValidatorTest`（非法值报错、缺省不报错）

**依赖**：无

**参考资料**
- ConfigLoader.java:27-29（KNOWN_KEYS）、99-138（apply）、ConfigValidator（validate 现有模式）

---

### T8 /permission-mode 运行时切档

**目标**：slash 命令即时切换权限模式，调用 checker 的 volatile mode。

**影响文件（修改）**
- `src/main/java/com/acode/ui/CommandRouter.java`（改）— `Action` 枚举（10 行）加 `PERMISSION_MODE`；`route`（38-43 行后）加 `case "/permission-mode" -> Action.PERMISSION_MODE`
- `src/main/java/com/acode/ConversationController.java`（改）— 增加 `PermissionChecker` 字段 + setter（T9 的 `start()` 装配时注入）；命令分发 switch（210 行附近）加 `case PERMISSION_MODE`：参数先 trim → 大小写敏感地校验 4 合法值（`ACCEPT_EDITS` 非法；多余参数非法）→ 非法输出错误提示、模式不变 → 合法 `permissionChecker.setMode(...)` → 输出当前模式；无参数时输出当前模式；**运行时切档只改内存 volatile mode、不写回 config.yaml，重启后按 config 值恢复**
- 测试（改）：`CommandRouterTest`（路由映射）、`ConversationControllerTest`（setter 注入 checker 后：`/permission-mode acceptEdits` 更新 checker 模式、`/permission-mode acceptEdits ` 尾随空白 trim 后合法切换、`/permission-mode ACCEPT_EDITS` 大小写敏感非法、`/permission-mode acceptEdits extra` 多余参数非法、非法值模式不变、切换后 config 文件未被改写；「WriteFile 不再弹确认」的行为断言在 T9 接入 executor 后验证）

**依赖**：T5、T7（checker 与 config 已存在）

**参考资料**
- CommandRouter.java:10（Action 枚举）、:38-43（route switch）；ConversationController.java:210-231（命令分发）
- MewCode `PermissionChecker.java:103`（`setMode`）

---

### T9 接入主流程（executor + Agent + controller 装配）

**目标**：权限检查插入工具执行前——DENY 返回错误结果、ASK 走三选一确认、ALLOW 直接执行；「始终允许」持久化；覆盖全部工具含 READ。

**影响文件（修改）**
- `src/main/java/com/acode/agent/StreamingToolExecutor.java`（改）—
  - 构造器加 `PermissionChecker permissionChecker`（`this(registry, context, permissionChecker, gate)` 重载；存量无 checker 构造器仅保留给测试并标 `@Deprecated`，生产装配路径强制非 null checker——装配测试断言 `ConversationController.start()` 构造的 executor 均携带非 null checker）
  - `runCall`（107-142 行）前置权限检查：
    - `permissionChecker.check(tool, call.input())` → DENY → `results[index] = ToolResult.failure("权限拒绝：" + reason)` + `ToolResultEvent(isError=true, elapsedMs=0)` + return（复用现有拒绝路径的入历史方式；**reason 为权限层纯原因**（如「危险命令：…」「路径 … 超出沙箱范围」「规则拒绝」），Agent 层统一只加一次前缀，杜绝「权限拒绝：权限拒绝：…」）
    - ASK → `confirmationGate.confirm(call, events, cancelled)`：`PermissionResponse.DENY` → 现有 `failure("用户拒绝执行…")` 路径；`ALLOW_ALWAYS` → `checker.addAllowAlwaysRule(toolName, content)`（会话级即时生效）+ `checker.appendLocalRule(toolName, content)`（R8：本地文件；**R9：写回失败不阻断本次执行，仅警告**）+ 放行；`ALLOW` → 放行
    - ALLOW（含 READ 工具，R7）→ 现有交互/串行执行路径
  - 原 114-121 行「`permission != READ && !confirm`」逻辑删除，改由 checker 统一决策
- `src/main/java/com/acode/agent/Agent.java`（改）— 加 `PermissionChecker permissionChecker` 字段 + setter；`:352-354` 构造 executor 传入
- `src/main/java/com/acode/ConversationController.java`（改）—
  - `start()`（150-155 行附近）装配 `PermissionChecker`：mode 取自 `config.getPermissionMode()`（null → DEFAULT）、`projectRoot = 工作目录`、RuleEngine 三路径（`~/.acode/permissions.yaml`、`{项目}/.acode/permissions.yaml`、`{项目}/.acode/permissions.local.yaml`）；`agent.setPermissionChecker(...)` + `this.permissionChecker = checker`（供 T8 命令用）
  - `confirmAnswerer`（107 / 371 / 381 行）返回类型 `Function<ConfirmationRequestEvent, PermissionResponse>`；`answerConfirmationPrompt` 返回三态（T6 的 ConfirmationPrompt.ask）
  - 事件循环（561 行）`ConfirmationRequestEvent` 分支适配三态
  - 存量交互工具（AskUserTool / ExitPlanModeTool 已返回 `Permission.READ`）走 READ→ALLOW 直接放行、不弹确认（spec 决策表「交互工具」行；无需改工具本身）
- 测试（改 + 新）：`StreamingToolExecutorTest`（黑名单 DENY 进结果、ASK 三态分支、READ 沙箱 DENY、ALLOW_ALWAYS 后二次调用直接放行）、`AgentIntegrationTest`（拒绝后 Loop 继续：FakeProvider 第二轮给替代工具）、`ConversationControllerTest`（装配后的确认应答 + `/permission-mode acceptEdits` 切档后 WriteFile 不再 ASK 的行为断言）

**依赖**：T5、T6、T7

**参考资料**
- StreamingToolExecutor.java:107-142（runCall 全貌）、Agent.java:74-76/352-354、ConversationController.java:107/371/381/502-503/561
- MewCode 执行器集成参考：`internal/permissions/permissions.go`（Go 版 `Checker.Check()` 与 executor 的调用点，zip 同目录），或看参考书 chapter-6-1「嵌入 Agent Loop」伪代码
- R2/R4/R6/R7/R8 风险清单

---

### T10 手测文档

**目标**：阶段五人工验收步骤成文，含四档模式对照 + 黑名单/沙箱/规则/HITL 验证。

**影响文件（修改）**
- `docs/manual-test.md`（改）— 追加「阶段五」小节：default 读不弹/写弹三选一；acceptEdits 写不弹/命令弹；plan 只读 + 计划文件放行；bypass 全放行但 `rm -rf /` 仍拦截；`ReadFile .env` 规则拦截；「始终允许」写 `.acode/permissions.local.yaml` 后重启仍生效；`/permission-mode` 切档即时生效

**依赖**：无（可与 T8/T9 并行，验收在 T11）

**参考资料**
- 参考书 chapter-6-2 实战篇「功能验证过程」小节（验收步骤措辞来源）
- checklist.md ⚑ 项逐条对应

---

### T11 端到端验证

**目标**：全量回归 + 真实 provider 手动验收，按 checklist.md ⚑ 项逐条打勾。

**影响文件（新建 + 视情况）**
- `docs/manual-test.md` 阶段五勾选（见 T10）
- 修 bug 产生的影响文件视情况

**依赖**：T9、T10

**参考资料**
- 手测按 checklist.md ⚑ 项逐条打勾；真实 API 联网问题用临时错误 base_url 模拟（沿用 ch01 做法）
