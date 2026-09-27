# ACode 阶段十一验收

章节的完整默认值和验收证据见 docs/ch11/checklist.md。构建记录见 target/ch11-final-validation.log。

- [x] 三处 hooks 配置各两条时读出六条，顺序 local → project → user，ID 跨来源唯一。
- [x] 坏条目报告文件、序号和 ID，其他条目继续加载；循环条件被拒绝。
- [x] regex 为 find；glob 中 * 不跨目录、** 跨目录、? 匹配单字符；缺失字段不匹配。
- [x] 未定义变量展开为空，路径、消息、错误与工具参数正确展开。
- [x] pre 拒绝后目标文件不存在，下一请求包含 `Hook 拒绝：blocked by policy`。
- [x] 权限拒绝、用户拒绝、已取消的工具不触发 pre/post；交互工具按确认 → pre → 执行 → post 排序。
- [x] command 默认 30s，HTTP 默认 10s；命令输出截断至 30000 字符并附提示，HTTP 响应限制为 4000 字符。
- [x] 命令非零退出和超时返回失败；agent 占位失败不能拒绝原工具；无交互入口的 ASK 动作执行次数为 0。
- [x] HTTP 默认 POST、正文变量展开，本地响应和非 2xx 正确处理；重定向不自动跟随。
- [x] post 的提示词进入同一次 exchange 下一请求，历史不含该提醒；turn_start/turn_end 不随内部迭代重复。
- [x] once 元数据无 role，压缩后排在消息之前；恢复后不重发，新会话重新触发，/clear 后标记仍存在。
- [x] 后台任务在恢复到新会话后不能注入旧提示；取消 exchange 不触发 turn_end。
- [x] 配置错误打印到 stderr，运行失败日志含 `Hook 命令非零退出 [fail]：exit=1`，stderr 无此日志。
- [x] JDK 21 下最终 `mvn package` 为 BUILD SUCCESS：1207 tests，0 failures，0 errors，1 skipped；日志 target/ch11-final-validation.log。
- [ ] HK1–HK6 真实终端／真实模型验收通过（步骤见 docs/manual-test.md；本轮尚未实跑）。
