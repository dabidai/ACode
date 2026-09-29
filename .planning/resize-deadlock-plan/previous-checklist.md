# ch13 验收清单

## 具体约定
- [x] 工作树位于 `<项目根>/.acode/worktrees/<name 中 / 替换为 +>`，分支为 `worktree-<扁平名>`；名称长 1–64，每段仅字母、数字、点、下划线、连字符，拒绝空段、`.`、`..`、Git 非法引用及 Windows 保留设备名/尾点。
- [x] 元数据 `.acode/worktree_registry.json` 记录名称、基准 HEAD、temporary 来源；会话 `.acode/worktree_session.json` 记录名称、路径、分支、原目录、原分支及 HEAD；退出原子写 `null`。
- [x] 默认配置 `worktree.symlinkDirectories: [node_modules, .venv, vendor]`，`staleAfterDays: 7`；只覆盖出现字段，拒绝未知键、非整数/非正阈值、多段/绝对/设备目录名，空列表可用。
- [x] Git 使用参数数组、`GIT_TERMINAL_PROMPT=0`、空 `GIT_ASKPASS`，关闭 stdin，超时 120 秒、输出上限 4 MiB；超时/截断/中断不作为成功。

## 生命周期与安全
- [x] 临时仓库创建、进入、切换、退出、列出和删除成功；主分支/主文件不变；已有分支冲突不重置。
- [x] packed-refs 后仍可恢复；路径含空格、嵌套名称、detached HEAD 可创建。
- [x] 未注册目录、错误分支、外部路径/链接逃逸、损坏元数据不会被删除（即使 force）。
- [x] 未提交、未跟踪、忽略文件、新 commit 阻止普通删除；提示包含 `--force`；显式强制删除只删除验证过的非当前工作树。
- [x] 当前工作树删除提示先 `/worktree exit`；锁定工作树保留；Git 检查失败保留。
- [x] 会话恢复重验路径与分支；失效/损坏告警并回项目根；退出再恢复无活动工作树。

## 创建后设置与清理
- [x] `settings.local.json` 存在才复制；已有目标不覆盖；各项设置失败汇总警告且创建可完成。
- [x] hooks 尊重现有配置；仅 `extensions.worktreeConfig=true` 时写 `--worktree core.hooksPath`，否则警告且共享配置不变。
- [x] 配置依赖目录可建立符号链接，Windows 失败尝试 junction；不存在/失败给警告；源目标不能逃逸项目或覆盖已跟踪目录；强制移除后源依赖文件内容不变。
- [x] `.worktreeinclude` 支持注释、`*`、`**`、`?`、尾 `/`；拒绝 `!`；仅复制 Git 忽略且显式匹配文件，不复制 `.git`/`.acode` 或链接逃逸文件。
- [x] 手动创建 `agent-a1234567` 也永不自动清理；自动入口仅接受 `agent-a[0-9a-f]{7}` 或 `wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+`。
- [x] 过期清理跳过活动、未过期、脏、忽略文件、新提交、未推送、未知来源；满足条件的临时工作树删除；不 push。

## 命令与主流程
- [x] `/worktree` 无参列出当前箭头、状态、分支和正斜杠路径；空列表提示 `（没有 Worktree，输入 /worktree create <名称> 创建）`。
- [x] `create/enter/exit/remove/prune` 大小写不敏感；额外参数与缺参输出用法；`remove <name> --force` 是唯一强制形态。
- [x] 成功提示包括 `已创建并进入 Worktree`、`已进入 Worktree`、`已退出 Worktree`、`已删除 Worktree`；非仓库提示 `当前目录不是 Git 仓库，无法使用 Worktree`。
- [x] 帮助/补全包含 worktree；命令调用不产生 Provider 请求。
- [x] 先执行一轮再 create/enter，后续 Agent ReadFile/Bash 使用新目录；退出恢复项目根；命令上下文项目根不变；环境提示包含新目录；相对路径的沙箱/Plan 判断基于实际工具目录。
- [x] `--resume` 在启动清理前恢复工作树；后台清理异步一次且退出收尾；Skill/Hook 委托工具目录跟随会话。
- [x] README 含命令与配置；manual-test 含阶段十三。

## 端到端与交付
- [x] 临时真实 Git 仓库 + FakeProvider 完成命令创建 → 相对文件读写隔离 → 退出 → 保护删除 → 恢复链路。
- [x] `mvn test` 全量通过，记录用例数；`mvn package -DskipTests` 成功。
- [ ] 真实终端在 clone 副本验证五种操作、退出重启恢复、路径含空格及链接降级（未实跑不勾选）。

## 本轮验证结果（2026-09-28）

全量 1281 tests，0 failures，0 errors，1 skipped（既有 InstructionExpanderTest 的符号链接环境条件）。
由于本机内存压力，本次成功命令为 `mvn test -q "-DargLine=-Xmx384m -XX:ActiveProcessorCount=2"`，Maven 进程设置 `MAVEN_OPTS=-Xmx256m -XX:ActiveProcessorCount=2`；未修改生产默认。
`mvn package -q -DskipTests` 成功；target/acode.jar 中管理器、命令类及 worktree 配置均已核验。详情见 docs/ch13/test-review.md。
