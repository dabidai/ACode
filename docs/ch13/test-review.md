# ch13 验证记录

## 环境与边界
Windows、JDK 21、Maven；Git 操作只发生在 JUnit 临时仓库，未对开发仓库创建/删除工作树，未操作数据库。

## 自动化覆盖
- WorktreeManagerTest：名称边界、真实生命周期、空间路径、嵌套名称、packed refs、detached HEAD、分支/目录冲突、元数据和会话伪造、替换 gitfile、丢失目录、当前/锁定保护、普通/强制移除、手动来源永不清理、临时过期/远端/活动/忽略成果筛选、子进程输出及超时。
- WorktreeSetupTest：本地配置与忽略文件选择复制、目标不覆盖、步骤独立失败、共享 hooks 不变、工作树专属 hooks、依赖链接/junction 可达且移除后源数据完整、glob 子集。
- WorktreeConfigTest：默认值、三级字段合并、未知键、类型与路径校验。
- WorktreeEndToEndTest：真实命令注册 → create → FakeProvider WriteFile/ReadFile/Bash → 主目录文件保持 → exit → 删除保护 → force；首次 exchange 后切换；无对话历史也能恢复工作树；非仓库错误与命令不发模型请求。
- PermissionCheckerTest：工作树相对计划路径不能借用主目录的自动许可例外；既有主目录绝对路径例外保留。

## 已复现并修复
1. 沙箱拒绝 Windows 临时目录 toRealPath；正常本机权限下复测。
2. Windows Git force remove 沿 junction 删除主目录依赖；临时仓库测试真实复现，加入先断链处理，源文件保留测试通过。
3. 测试隔离 home 的日志句柄影响 JUnit 清理；home 改为 target 下的独立测试目录。
4. 两处固定命令清单需追加 worktree；保留现有命令相对顺序，worktree 放在 quit 前，quit 仍为本地分组末位。
5. 异步清理提前持锁可能阻塞环境初始化；清理在初始化完成后启动。

6. 全量回归一次遭系统原生内存不足中断（malloc 268944 bytes 失败，JVM 默认最大堆约4GiB）；复跑仅限制本次 Maven/测试 JVM 堆与处理器数，不修改生产配置。

## 结果
2026-09-28 最终全量：**1281 tests，0 failures，0 errors，1 skipped**。本章新增 25 个测试（含既有权限测试类中的 1 项），工作树测试无跳过。既有跳过项为 `InstructionExpanderTest.rejectsSymlinkEscapeFromProjectRoot` 的环境条件。

成功命令：PowerShell 为本次进程设置 `MAVEN_OPTS=-Xmx256m -XX:ActiveProcessorCount=2`，再执行 `mvn test -q "-DargLine=-Xmx384m -XX:ActiveProcessorCount=2"`。没有修改 pom 或生产运行配置。

`mvn package -q -DskipTests` 成功。`target/acode.jar` 大小 8,965,715 字节，已核验含 WorktreeManager、GitRunner、WorktreeCommand 及 worktree 默认配置。`git diff --check` 通过（只有既有文件的换行提示）。
真实交互终端和真实模型未在本轮执行；不将 FakeProvider 和 Git 子进程测试冒充人工终端验收。
