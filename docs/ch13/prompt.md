# Worktree 并行隔离 —— 每个 Agent 一张自己的书桌

> 让多个 Agent 在同一时刻、各自独立的目录里干活，谁也别踩谁的文件

## 为什么需要它

前面几章我们把 Agent 的**上下文**隔离做得很干净了：子 Agent 有自己的消息历史、自己的权限、自己的缓存。它不知道主 Agent 聊过什么，主 Agent 也不操心它内部怎么想。

但有一个维度始终没隔离：**文件系统**。

所有 Agent 共用同一块硬盘、同一个目录。于是：

> 主 Agent 正在改 `server.py`，同一时刻一个后台子 Agent 也在改 `server.py`。
> A 写了前半段，B 写了后半段，最后文件变成一个拼接出来的怪物。

要注意，**前台子 Agent 没这个问题**。因为主 Agent 会等它干完才继续，同一时刻只有一个 Agent 在动文件，天然串行。出事的是**并行**的那一类：后台子 Agent，以及 Agent Team 里的队员。

而且这压根不是「Agent 特有的问题」。它本质上就是**并行开发的文件冲突**，和两个程序员同时改同一个文件一模一样。既然人类程序员早就遇到过、也早就有了对策，那我们先看看人类是怎么解决的——以及为什么现成的对策不够用。

## 分支解决不了这个问题

程序员对付文件冲突的经典武器是 **Git 分支**：每人开一个分支，改完了再合并。

那给每个子 Agent 开一个分支不就行了？

听起来合理，但有个致命问题：**分支管的是版本，不是目录。**

你的工作目录始终只有一份。切到 `feature-a`，目录里的文件就变成 `feature-a` 的内容；再切到 `feature-b`，目录里的文件又整个换掉。整个过程中，**桌子只有一张**。

```
时间点1：Agent 在 main 分支，桌面上的 server.py 是 A 版本
时间点2：切到 feature 分支，桌面上的 server.py 变成 B 版本
时间点3：此时 main 分支的工作目录已经变了

主 Agent 和子 Agent 不能同时待在不同分支上——因为只有一个工作目录。
```

换成一句话：

- **分支**提供的是**时间维度**的隔离 —— 同一份代码在不同时间点的快照
- 我们真正要的是**空间维度**的隔离 —— 同一时刻存在多个互不干扰的工作区

还有个很现实的坑。切分支的时候，Git 会刷新那些分支间有差异的文件的**修改时间戳（mtime）**。依赖时间戳做增量判断的构建工具一看，以为这些文件全变了，顺手把它们的下游也标记成「需要重新构建」——本来只该编一个文件，结果编了大半个项目。

所以我们需要一个东西，能在**同一时刻拥有多个独立的工作目录**，每个目录对应不同的分支，互不干扰。

## Git Worktree：共享仓库，隔离文件

这个东西 Git 早就有，叫 **Worktree**（Git 2.5 引入）。一句话概括它做的事：

> 允许同一个仓库，拥有多个独立的工作目录。

```bash
# 在当前仓库旁边开一个新的工作目录
git worktree add ../my-project-feature-a feature-a

# 现在有两个工作目录：
# ./my-project/            -> main 分支
# ./my-project-feature-a/  -> feature-a 分支
```

执行完，硬盘上就多了一个目录，里面是 `feature-a` 分支的完整文件。原来的目录**纹丝不动**，还是 `main` 的内容。

打个比方：

- 一个 Git 仓库 = 一栋房子，原本**只有一张书桌**
- 切分支 = 把书桌上的东西整个换掉，你只能同时在桌上摊开一份文件
- Worktree = 在隔壁**多开几间房**，每间房配**同一把钥匙**（共享同一个 `.git`）

于是就有了这句总结：

> **共享仓库，隔离文件。**

两间房完全独立：你可以在 A 房改代码、在 B 房编译跑测试，互不打扰。但因为共用同一本账（`.git`），在任一房间提交的 commit，去另一间房 `git log --all` 都能看见。

对比一下：

```
分支方案：
  只有一个工作目录，同一时刻只能有一个分支的内容。

Worktree 方案：
  ./project/          -> main 分支，    server.py 内容为 A
  ./project-feature/  -> feature 分支， server.py 内容为 B

  两个 Agent 同时工作，互不影响。
```

主 Agent 在主目录干活，子 Agent 在 Worktree 目录里干活。子 Agent 怎么折腾都碰不到主 Agent 手上的文件。

原理就这么多。剩下的全是工程细节——下面这些才是这一章真正要写的东西。

## 在 ACode 里落地：WorktreeManager

Worktree 的生命周期有四件事：**创建、进入、退出、删除**。再往前一步还有两件兜底的事：**自动清理**和**过期清理**。ACode 要做的就是把这一圈封装成一个管理器。

### 维护哪些状态

管理器自己要知道的：

```
WorktreeManager:
    repoRoot        主仓库路径
    worktreeDir     Worktree 存放目录
    active          name -> Worktree 的映射
    currentSession  当前活跃的 Worktree 会话（可为空）
```

每个 Worktree 记的信息：

```
Worktree:
    name        名称
    path        目录绝对路径
    branch      分支名
    basedOn     基于哪个分支创建
    headCommit  创建时的 HEAD commit
    created     创建时间
```

另外，「进入」Worktree 时需要一组**进入前的现场快照**，退出时才能原样恢复回去：

```
WorktreeSession:
    originalCwd         进入前的工作目录
    worktreePath        Worktree 路径
    worktreeName        名称
    originalBranch      进入前所在的分支
    originalHeadCommit  进入时的 HEAD commit
```

`currentSession` 要**持久化**到 `.acode/worktree_session.json`。这样如果 ACode 进程意外退出，下次 `--resume` 启动时能从这里恢复——直接回到上次用的 Worktree，跳过重新创建的过程。

> ACode 已经有 `--resume`（`App.java` 解析 `--resume`，`ConversationController.run(resume)` 承接）。Worktree 会话跟着这条路走就行，不用另造一套恢复机制。

### Slug 安全验证：进门先过安检

创建 Worktree 之前，有一件事**必须先做**：验证名称。

为什么？因为名称会被同时用作**目录名**和 **Git 分支名**。而名称是 LLM 生成的，**不可信**——万一它传一个 `../../etc/passwd`，你把它往路径上一拼，Worktree 就可能被创建到系统目录下面去。这就是经典的**路径遍历攻击**。

规则定成这样：

- 允许大小写字母、数字、点号、连字符、下划线
- 总长度不超过 64
- 可以包含 `/` 作为**嵌套名称**的分隔符，比如 `team-refactor/alice`

按 `/` 切成若干段，**每一段**都要匹配 `^[a-zA-Z0-9._-]+$`：

```
function validateSlug(name) -> error:
    if name is empty: return error("name cannot be empty")
    if length(name) > 64: return error("name too long")

    segments = split(name, "/")
    for seg in segments:
        if seg == "." or seg == "..":
            return error("must not contain . or ..")
        if not matches(seg, "^[a-zA-Z0-9._-]+$"):
            return error("invalid segment: " + seg)

    return null
```

**这里有个很容易漏的陷阱。** 点号本身是合法字符——`v1.0` 完全没问题。但**独立成段的 `.` 和 `..` 必须显式拒绝**，它们是操作系统层面的特殊路径。上面那个正则**会放行它们**，不专门拦一条就等于放行路径遍历。这也是为什么校验逻辑里要单独有那个 `if`，而不是只靠正则。

**为什么要允许 `/`？** 因为名称可能是复合的。比如 Agent Team 创建 Worktree 时会命名为 `team-refactor/alice`，斜杠分隔团队名和队员名。但转成 Git 分支名时，`/` 要替换成 `+`——否则 Git 会犯「目录和文件同名」的毛病（D/F conflict）。

### 创建：六步

名称验过之后，创建流程是这样：

```
function createWorktree(manager, ctx, name, baseBranch):
    // 1. 验证名称
    err = validateSlug(name)
    if err: return err

    // 2. 检查是否已存在
    if name in manager.active:
        return error("worktree already exists: " + name)

    // 3. 构建路径和分支名
    flatSlug = replace(name, "/", "+")
    wtPath = joinPath(manager.worktreeDir, flatSlug)
    branchName = "worktree-" + flatSlug
```

分支名统一加 `worktree-` 前缀，好处是 `git branch` 一敲出来，哪些分支是 ACode 建的**一眼就能认出来**。嵌套名称 `team-refactor/alice` 对应的分支名就是 `worktree-team-refactor+alice`。

目录统一放在仓库内部的 `.acode/worktrees/` 下，和 ACode 已有的 `.acode/config.yaml`、`.acode/plans/` 保持一致，不需要新增约定。

#### 第 3.5 步：快速恢复（一个很值钱的优化）

如果 Worktree 目录**已经存在**，能不能跳过 `git worktree add` 直接复用？

```
    // 3.5 快速恢复：纯文件系统读取，不调 git 子进程
    headSha = readWorktreeHeadSha(wtPath)
    if headSha is not null:
        return existingWorktree(wtPath, branchName, headSha)
```

所谓「快速恢复」，就是**不启动 git 子进程，只读文件系统**：先读 Worktree 目录下的 `.git` 指针文件拿到 gitdir 路径，再读 `HEAD` 文件；如果 `HEAD` 是符号引用（`ref: refs/heads/...`），就顺着 `refs/` 目录还原出 commit SHA。

整个过程只有几次文件读取，**大约 3 毫秒**。

对比一下：在大仓库上，光 Git 本地的 commit-graph 扫描就要 **6~8 秒**（还没开始走网络）。Agent 反复进出同一个 Worktree 的场景下，这个优化省下的时间非常可观——三个数量级的差距。

#### 第 4~5 步：真正创建

```
    // 4. 执行 git worktree add
    env = {"GIT_TERMINAL_PROMPT": "0", "GIT_ASKPASS": ""}
    run("git", "worktree", "add",
        "-B", branchName, wtPath, baseBranch,
        workDir=manager.repoRoot,
        env=env, stdin="ignore")

    // 5. 创建后设置（只对新建执行，快速恢复跳过）
    performPostCreationSetup(manager.repoRoot, wtPath)
```

两个细节值得单独说：

**`GIT_TERMINAL_PROMPT=0` 必须设，而且所有 Git 命令都要设。** 如果创建过程中 Git 需要输入凭证（比如远端要求认证），没有这个环境变量，进程会**挂在那里傻等用户输入**——而 ACode 是全屏 TUI，用户根本看不到那个提示。再配上 `GIT_ASKPASS=''` 和 `stdin: 'ignore'` 作双重保险。

**用 `-B` 而不是小写 `-b`。** `-B` 会**重置**已存在的分支。万一上次创建后没清理干净、留下一个孤儿分支，`-b` 会直接报错，而 `-B` 直接覆盖过去。

#### 第 6 步：记录状态

```
    wt = new Worktree(
        name, wtPath, branchName, baseBranch,
        resolveHead(wtPath), now())

    manager.active[name] = wt
    saveWorktreeSession(manager.currentSession)
    return wt
```

### 创建后设置：四件「装修」活儿

`git worktree add` 给出的是一个**干净**的工作目录。但干净过头了——主仓库里那些「不在版本库里、但运行时需要」的东西，它一样都没有。直接让 Agent 进去干活，很多东西会报错，或者行为不一致。

四件事要补：

**A. 本地配置文件。** 比如 `settings.local.json` 这类存放密钥和环境变量、**不入库**的配置。Worktree 是新目录，不会自动拥有，得从主仓库复制过去。

**B. Git Hooks 配置。** Worktree 共享主仓库的 `.git` 目录，但 `core.hooksPath` 这个配置**不会自动继承**过来。如果主仓库用了 husky 或自定义 hooks 目录，那么 Worktree 里的 `git commit` **不会触发** pre-commit 检查、lint、格式化这些流程——等于质量门禁静默失效了。
做法：检测主仓库的 hooks 路径（优先看 `.husky/`，回退到 `.git/hooks/`），然后显式设置到 Worktree 的 git config 里。

**C. 软链接大目录。** `node_modules`、`.venv`、`vendor` 这些依赖目录动辄几百 MB。每个 Worktree 都拷一份，磁盘很快就满了。做法是**软链接**到主仓库的对应目录，所有 Worktree 共享同一份依赖。
**要链接哪些目录不能写死在代码里**——不同项目的依赖目录结构完全不同。所以从配置读，比如 `worktree.symlinkDirectories`。

> **Node.js 项目要特别注意一个坑。** Node 默认会把软链接**解析到真实路径**，包内部用 `__dirname` 拿到的是**主仓库**路径，而不是 Worktree 路径。webpack、TypeScript 的模块 resolve 默认也是同样行为。多数项目无碍，但依赖 `__dirname` 做路径计算、或者用 npm link 风格的 monorepo，可能会拿到错的路径——表现为**构建产物指向了主仓库的源文件**。真遇到了，要么开 `--preserve-symlinks` / `resolve.symlinks: false`，要么干脆在 Worktree 里独立装一份依赖。
> **软链接是 best-effort 策略，不是万能的。**

**D. 复制「被忽略但需要」的文件。** 有些文件被 `.gitignore` 忽略了，`git worktree add` 自然不会复制它们，但 Worktree 又确实需要——最典型的就是 `.env`。
做法：用一个 `.worktreeinclude` 文件，用和 `.gitignore` 一样的语法，声明哪些被忽略的文件需要复制过来。底层用 `git ls-files --others --ignored --exclude-standard --directory` 列出所有被忽略的文件，再拿 `.worktreeinclude` 的模式过滤。

这四件事**都是 best-effort**——匹配失败或文件不存在，**只记警告，不中断创建流程**。因为它们的目的是「让 Worktree 更好用」，而不是「没有它就不能用」。

### 进入：不改全局 cwd，改 ToolContext

Worktree 建好了，怎么让 Agent 切进去干活？

最直觉的做法是 `chdir`——把进程的工作目录切到 Worktree 路径，后面所有文件操作自然就落在 Worktree 里了。

**ACode 不这么做。**

理由是：**进程级 cwd 是全局可变状态。** Bash 工具偶尔 `cd` 一下、子线程看到的是另一个 cwd、并发的后台 Agent 之间还会撞上时序问题——谁先谁后，结果可能完全不同。一旦把 Worktree 切换绑死在进程 cwd 上，cwd 就成了所有并发组件的**同步点**，复杂度直线上升。

正确的做法是反过来：**进程 cwd 不动，把 Worktree 路径记进会话状态，让每次工具执行自己显式地取。**

好消息是，**ACode 的工具层天生就是这个形状**。看 `ToolContext`：

```java
public class ToolContext {
    private final Path workingDirectory;
    ...
    /** 解析路径：绝对路径直接用，相对路径基于工作目录解析 */
    public Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : workingDirectory.resolve(p).normalize();
    }
}
```

每次工具执行都带着自己的 `workingDirectory`，相对路径基于它解析。而它是**每次 exchange 构造一次**的（`ExchangeRunner.java:129`）：

```java
Agent agent = new Agent(provider, conversation, toolRegistry,
        new ToolContext(projectRoot), maxIterations(), contextManager);
```

所以「进入 Worktree」在 ACode 里的落点极其干净：**把这里的 `projectRoot` 换成当前会话的工作目录即可**。工具实现一行都不用改。

「进入」的动作本身很简单——只记录现场，不碰任何全局状态：

```
function enterWorktree(ctx, name) -> error:
    wt = active[name]
    if wt is null: return error("not found: " + name)

    // 记录现场：原 cwd、Worktree 路径、原分支、原 HEAD
    currentSession = new WorktreeSession(
        originalCwd:         getCurrentDirectory(),
        worktreePath:        wt.path,
        worktreeBranch:      wt.branch,
        originalBranch:      ...,
        originalHeadCommit:  ...)

    // 持久化到 .acode/worktree_session.json
    saveWorktreeSession(currentSession)
    return nil
```

注意里面**既没有 chdir，也没有清缓存**。之后 Agent 调用 Bash、Read、Write 时，工具自己从这个会话状态里取路径，作为本次调用的工作目录。进程 cwd 一直留在原地，**每次工具调用都是显式声明「这次在 Worktree 里跑」**。

这个模式的好处：

- 主 Agent 同时在调多个工具？每次调用各取各的
- 又开了新 Worktree？各自有自己的会话状态，互不干扰
- 工具调用栈很深？每一层自己取，不依赖「中间某次 chdir 没被 reset」

**至于文件缓存——ACode 没有文件读取缓存**（第一章到第九章都没引入过），所以教材里那套「进出时清理文件缓存」的动作在 ACode 里**根本不需要**。这也算是显式 cwd 模式自带的好处：就算将来加了缓存，只要 cache key 用**绝对路径**，`/repo/server.py` 和 `/repo/.acode/worktrees/feature/server.py` 天然就是两个 key，撞不到一起。

### 退出：变更保护

活儿干完了要切回主目录。退出时有一个选择：**保留**这个 Worktree 还是**删掉**？

如果选择删掉，就得先回答一个问题：**Worktree 里有没有没保存的工作？**

这就是退出时的**变更保护**。如果里面有未提交的文件修改、或者新增的 commit，直接删就等于丢工作成果。LLM 未必理解这些改动的价值，一个错误的删除调用就能把子 Agent 好几个小时的活儿彻底抹掉。

所以删除必须**显式确认**：

```
function exitWorktree(manager, ctx, name, action, discard):
    wt = manager.active[name]

    // 1. 变更保护
    if action == "remove" and not discard:
        changes = countWorktreeChanges(wt.path, wt.headCommit)
        if changes.uncommitted > 0 or changes.newCommits > 0:
            return error("worktree has changes, "
                + "set discardChanges=true to force")
```

检查通过后，切回原地并清掉现场：

```
    // 2. 切回原 cwd 兜底
    //    Worktree 会话期间工具调用走显式工作目录，进程 cwd 理论上
    //    一直在 originalCwd。这里再切一次是兜底——防止会话期间某个
    //    Bash 调用偶尔改过进程 cwd 留下残留。
    changeDirectory(session.originalCwd)

    // 3. 清会话 + 持久化清除
    currentSession = null
    saveWorktreeSession(repoRoot, null)
```

**为什么会话必须清掉、而且要把持久化文件写成 `null`？**

因为下次 `--resume` 启动时会读 `.acode/worktree_session.json`。如果还留着这条记录，恢复逻辑会以为**还有一个没结束的 Worktree 会话**，于是去尝试切回一个已经退出——甚至已经被删掉——的目录。退出时及时把这条记录清掉，是 `--resume` 能正确工作的前提。

如果用户选择删除：

```
    // 5. 可选：删除 Worktree
    if action == "remove":
        run("git", "worktree", "remove", "--force", wt.path,
            workDir=manager.repoRoot)

        sleep(100)     // 等待 git lockfile 释放
        run("git", "branch", "-D", branchName,
            workDir=manager.repoRoot)

        delete manager.active[name]
```

`git worktree remove` 和 `git branch -D` 之间那个 `sleep(100)` 看着不优雅，但有实际作用：两条 git 命令挨得太紧，可能因为 git 的 lockfile 还没释放而失败。100ms 是经验值。

### 自动清理：干净的删，有货的留

每次都手动决定保留还是删除，对子 Agent 场景来说太繁琐。能不能自动判断？

思路很朴素：

> **里面没有未提交的修改、也没有新增的 commit → 说明只是读了读文件做了分析，没留下有价值的东西，直接清掉。**
> **写了代码或做了 commit → 留着让主 Agent review。**

```
function autoCleanup(manager, name, headCommit):
    wt = manager.active[name]
    if hasWorktreeChanges(wt.path, headCommit):
        return {kept: true, path: wt.path, branch: wt.branch}
    else:
        removeWorktree(manager, name)
        return {kept: false}
```

`hasWorktreeChanges` 检查两件事：`git status --porcelain` 看有没有未提交的修改，`git rev-list --count <headCommit>..HEAD` 看有没有新增的 commit。两个都没有才算干净。

**还有一个 fail-closed 的细节**：如果 Git 命令**本身执行失败**（比如目录被外部删了、git 报错），默认**返回 true**——宁可多保留一个目录，也不误删。这类判断出错的方向，永远选保守的那一边。

注意：**用户通过 `/worktree create` 手动创建的 Worktree 不走自动清理**，保留手动控制权。

### 过期 Worktree 的后台清理

自动清理解决的是「正常退出」的清理。但如果子 Agent **异常退出**呢？进程崩溃、被用户强杀，Worktree 就留在磁盘上成了**孤儿**。时间一长，`.acode/worktrees/` 下面会堆一堆没用的目录。

问题来了：怎么区分「该清理的临时 Worktree」和「用户手动创建的、不该动的」？

**靠命名模式。**

- 子 Agent 自动创建的：`agent-a` + 7 位随机 hex，比如 `agent-a3f2b1c`
- 工作流创建的：`wf_` 前缀 + 固定格式 hex
- 用户 `/worktree create my-feature` 创建的：不匹配任何模式，**永远不会被自动清理**

后台清理是个**三层漏斗**，一层比一层保守：

```
EPHEMERAL_PATTERNS = [
    "agent-a[0-9a-f]{7}",
    "wf_[0-9a-f]{8}-[0-9a-f]{3}-d+",
]

function cleanupStaleWorktrees(cutoffDate):
    for wt in listWorktreeDirectories(manager.worktreeDir):
        // 第一层：只清理临时 Worktree
        if not matchesAny(wt.name, EPHEMERAL_PATTERNS):
            continue
        // 第二层：跳过正在使用中和没过期的
        if isCurrentSession(wt) or modTime(wt) > cutoffDate:
            continue

        // 第三层：fail-closed 变更检查
        if hasWorktreeChanges(wt.path, wt.headCommit):
            continue

        // 有没推到远端的 commit 也不删
        unpushed = run("git", "rev-list", "--max-count=1",
            "HEAD", "--not", "--remotes", workDir=wt.path)
        if unpushed.trim() is not empty:
            continue

        removeWorktree(manager, wt.name)
```

第三层是最后的安全网。**即使 Worktree 已经过期**，只要里面还有未提交的修改、或新增的 commit，就不删。

还有一类很容易被漏掉的情况：**子 Agent 已经 commit 了，但还没 push。** 这种中间状态下直接删，等于丢掉工作。所以还要用 `git rev-list --not --remotes` 检查有没有「本地有、远端没有」的 commit。

**宁可多占一些磁盘，也不丢可能有价值的工作成果。**

## `/worktree` 命令

手动操作入口是一条斜杠命令，挂在 ACode 已有的命令框架上（`BuiltinCommands` 里注册，走 `CommandRegistry`）：

```
/worktree list              列出所有 Worktree（含分支、路径、是否有未提交变更）
/worktree create <name>     创建并进入
/worktree enter <name>      进入已有 Worktree
/worktree exit               退出，回到主目录
/worktree remove <name>      删除（有变更时要求确认）
/worktree prune              手动触发过期清理
```

几个约定：

- 名称走 `validateSlug` 校验，**报错要让用户看得懂**——说的是「名称不能包含 `..`」，不是「invalid slug」
- 通过命令手动创建/进入的 Worktree **不参与自动清理**
- `list` 要能一眼看出**当前在哪个 Worktree 里**，以及哪些有未提交的变更

## 与 SubAgent 的配合（跨章衔接点）

Worktree 和 SubAgent 是天生一对：

- **SubAgent** 管的是**逻辑层面**的隔离——消息、权限、缓存
- **Worktree** 管的是**物理层面**的隔离——每个子 Agent 一个目录

> **⚠️ 前提说明：ACode 目前还没有 SubAgent。**
> `src/main/java` 里既没有 `SubAgent` 也没有 `isolation` 相关代码（`grep -ri subagent src/main/java` 为空）。
> 教材里这一节讲的 `isolation: worktree` 字段、`executeWithWorktree` 流程，在 ACode 里**暂时没有落点**。下面这部分是设计意图的记录，等 SubAgent 章节（教材第 13 章）落地后再接。

教材里的接线方式，是 Agent 定义文件中的一个字段：

```yaml
# .acode/agents/refactor-worker.md
---
name: refactor-worker
description: 在独立工作树中执行重构
maxTurns: 40
isolation: worktree
---

你是一个重构 Agent。在独立的工作树中执行重构任务。
完成后提交你的更改。
```

`isolation` 设为 `worktree` 时，子 Agent 的启动流程自动变成：

1. 创建 Worktree（用唯一 ID 命名，避免同类型 Agent 并发冲突）
2. 创建子 Agent，工作目录设为 Worktree 路径
3. 运行子 Agent
4. 子 Agent 完成后，在 Worktree 里提交更改
5. 退出并清理 Worktree
6. 把结果返回给主 Agent
7. 主 Agent 决定是否合并

**这里有一个很容易被忽略、但很关键的动作：注入 Worktree 上下文通知。**

在任务文本**前面**加一段话，告诉子 Agent 三件事：

1. 你继承了父 Agent 的对话上下文
2. 你当前在一个**独立的 Git Worktree** 里工作
3. 父 Agent 传来的路径指向的是**主目录**，你需要翻译成本地路径，并且在编辑前**重新读取文件**

**不注入会怎样？** 子 Agent 根本不知道自己在隔离副本里。它会直接拿父 Agent 传来的绝对路径去读写——而那些路径指向的是主目录。更隐蔽的问题是：子 Agent 读到的明明是 Worktree 里的文件，却用父 Agent 对话里提到的主目录版本来理解内容，产生**认知偏差**。这种 bug 很难查，因为它不报错，只是结果莫名其妙地不对。

## 配置

Worktree 相关的设置在 ACode 的三级配置里（内置默认 → `~/.acode/config.yaml` → 项目 `.acode/config.yaml`）：

```yaml
worktree:
  # 要软链接到主仓库的依赖目录（不写死代码里，因为各项目结构不同）
  symlinkDirectories:
    - node_modules
    - .venv
    - vendor
  # 过期清理的判定阈值
  staleAfterDays: 7
```

另外，项目根目录的 `.worktreeinclude` 文件声明「哪些被 `.gitignore` 忽略的文件需要复制进 Worktree」，用 gitignore 语法：

```
.env
.env.local
config/secrets.yaml
```

## 设计哲学

**不要改全局状态，把选择权交给每一次调用。**

这是这一章最值得记住的一条。`chdir` 很省事，但它把「当前在哪个目录」变成了一个全局变量，然后所有并发组件都来抢它。显式工作目录啰嗦一点，但每一层调用都自给自足。

ACode 的 `ToolContext` 恰好天生就是这个形状——所以这一章在 ACode 里落地时，**改动量远比教材里小**，这是前面几章打下的底子。

**另一条：判断出错时，往保守的方向错。**

变更保护、fail-closed 的 `hasWorktreeChanges`、清理前的未推送 commit 检查、清理只认特定命名模式——四处都在做同一件事：**宁可多占磁盘、多留目录，也不丢工作成果。** 磁盘是便宜的，Agent 几小时的工作不是。

## 边界

- **本章不做 SubAgent**。`isolation: worktree` 字段与 `executeWithWorktree` 流程只记录设计意图，等 SubAgent 章节落地后再接。这一章交付的是**可独立使用的 Worktree 管理器 + `/worktree` 命令**。
- **不做合并工具**。Worktree 之间怎么合并，交给主 Agent 用 Bash 跑 `git merge` / `git cherry-pick`。不做内置 merge 的原因见下面那条思考题。
- **不做 Worktree 嵌套 Worktree**。Worktree 目录固定在 `.acode/worktrees/` 下，不允许在 Worktree 里再开 Worktree。
- **不自动 push**。清理时只检查「有没有未推送的 commit」来决定删不删，绝不代替用户 push。
- **不清理用户手动创建的 Worktree**。手动创建的永远不匹配临时命名模式，自动清理绝不碰。
- **软链接是 best-effort**。Node.js 的 `__dirname` 解析问题、monorepo 的 npm link 场景，软链接可能失效——不追求 100% 覆盖，遇到问题回退到「在 Worktree 里独立装依赖」。

## 留给你想的一个问题

> 子 Agent 在 Worktree 里改了 `server.py`，还提交了 commit。主 Agent 想把这些变更合并回主分支——**翻遍 ACode 的工具列表，没有内置的 merge 工具**。为什么不做？

三个角度：

1. **主 Agent 应该亲眼看到合并冲突。** 冲突是代码语义层面的问题，需要人来判断谁对谁错。封装成一个「自动合并」的工具，就把一个需要判断的决策压成了一个布尔返回值。
2. **合并策略因项目而异。** merge / rebase / cherry-pick，主干保护与否，squash 与否——每个团队答案不同，工具没法预设。
3. **合并失败的恢复路径。** 合并到一半冲突了，工作区处于中间状态，怎么退出、怎么回滚，比合并本身更难做对。

所以正确的姿势是：Worktree 只负责**隔离**和**保留现场**，把路径和分支名交回给主 Agent，合并这一步交给人来决策。
