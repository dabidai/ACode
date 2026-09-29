# 调查记录
- 当前源码没有 worktree 包；ch12 已实现，README 章节状态滞后。
- ExchangeRunner 缓存 projectRoot 并直接创建 ToolContext，需动态目录 supplier。
- CommandContext 项目根契约应保持；命令可通过注册时的 supplier 获取管理器。
- 旧方案 cwd 恢复不适用 Java 子进程；hooks 配置共享，不能直接 git config 改主仓库。
- 临时名称不能证明自动创建：需要独立元数据来源标记，未知来源不得自动清理。
- 忽略文件也是成果；设置产生的文件同样保守保留，避免清理链接目标。
- 读取测试文件曾误用包路径，实际在 src/test/java/com/acode/subagent 中。

- 临时仓库真实复现：Windows Git force remove 会递归进入 junction 删除源依赖。必须先以 Files.delete 断开链接，再交 Git 删除。
- PermissionChecker 相对路径基于固定根，需额外接收执行目录用于沙箱/plan 判断，规则匹配仍用原参数。
