# Resize 死锁修复验收清单

状态：计划待实施；以下方框均为后续验收，不代表已经通过。

## 锁顺序与可重复证据
- [x] `docs/ui-resize-deadlock/lock-review.md` 列出 reader monitor、JLine lock、应用绘制锁、ScreenRenderer/Status、供应器/回调的直接与间接锁依赖，明确每个共享状态的保护者。
- [x] `rg -n 'synchronized void (redisplay|handleSignal)' src/main/java/com/acode/ui/ResizeAwareLineReader.java` 返回 0 条；审查正文中的同步块也未以 reader monitor 包住调用上游加锁方法的路径。
- [x] 同一受控并发用例在旧实现的隔离副本连续 3 次检测到预期等待环或有诊断支持的挂起失败，在修复实现连续 3 次于 10 秒内完成；报告保留旧/新代码版本、命令、闩锁到达信息及结果。任意超时不能自动算作复现成功。
- [x] 回归测试通过闩锁/屏障建立关键交错，核心并发顺序不由 `Thread.sleep` 的时长决定。
- [x] 逐项检查 redisplay、handleSignal、mouse → paintHistory、readLineFramed finally、completionOverlay、paintFrameAndHistory；不存在“持绘制锁 → 等 JLine lock”的路径，回调与供应器审查结果已记录。

## 测试超时与诊断约定
- [x] 验证脚本使用 JDK 21，记录 `java -version`；所有 Maven 调用串行执行，无并发 Maven 实例由该脚本启动。
- [x] 单个并发场景等待上限 10 秒；每轮定向 Maven 命令总时限 180 秒；全量及打包每条命令总时限 900 秒。上述时限均为测试约定，不改变产品默认配置。
- [x] 故意阻塞的隔离探针超过 10 秒后被判失败；诊断限时 10 秒、清理限时 10 秒，总计 30 秒内结束，返回非零退出码，保留 `TIMEOUT` 和测试名/PID/日志位置。
- [x] 挂起诊断尝试 `jcmd <pid> Thread.dump_to_file -format=json <file>`；成功文件包含相关输入/信号线程，采集失败则记录原因，采集命令本身不阻止后续清理。
- [x] 清理仅涉及本次脚本记录的子进程树；验证后这些进程已退出，机器上其他 Java/Maven 进程未被脚本终止。不得按进程名批量终止。

## 行为与边缘场景
- [x] 普通提示符下先输入 `abc`，交替 `60×20`/`120×35` 触发 20 次 WINCH，再输入 `def` 并回车；得到 `abcdef`，footer 回调恰好 20 次。
- [x] resize 后读到的本轮标记为 true；第二轮无 resize 输入 `again` 后标记为 false，结果为 `again`。
- [x] 普通、固定框、全屏三分支输入两行 `abc`/`def`，编辑中缩放并提交，均得到 `abc\ndef`（其中 `\n` 表示一个换行），下一轮仍可输入。
- [x] 固定框高度从 24 降至 5 再恢复 24，触发现有小窗口回退并恢复；读取在 10 秒内完成，输入内容相等，无异常。
- [x] 全屏缩放、补全候选显示/清除、历史滚动与多行中文编辑测试通过；屏幕模拟器中最终文本、光标位置及坐标边界符合期望，JLine 不向物理输出额外画框。
- [x] 连续同尺寸 WINCH、非 WINCH 的 CONT、提交或取消与 resize 交错的用例均于 10 秒内结束；无空供应器异常、重复提交或遗留读取任务。
- [x] footer 回调抛 RuntimeException 后仍能提交下一段输入；回调再入场景按锁审查确定的支持边界有测试和说明。
- [x] 活动输入输出不包含 CPR 查询 `ESC[6n`，frame 缩放输出不包含清历史 `ESC[3J`；未新增尺寸轮询线程。
- [x] Ctrl+C、空输入 Ctrl+D、`/quit` 各条退出路径完成，终端模式与光标显示恢复，后续新会话可正常启动。

## 回归与端到端
- [x] 通过有界脚本串行执行目标组 `ResizeAwareLineReaderTest,ResizeAwareLineReaderConcurrencyTest,FullscreenLifecycleTest,FullscreenBoundaryTest,FullscreenCommandFlowTest,CommandProcessorTest` 共 10 轮；每轮 0 failures、0 errors、0 TIMEOUT，报告记录耗时。
- [x] 自动端到端经过真实 CommandProcessor.mainLoop + 管道终端 + 假业务依赖：编辑 → 并发 resize → 提交 → 业务收到完整输入且恰好一次 → 下一轮 → `/quit`；全链路于 10 秒内结束。
- [x] 经有界脚本执行 `mvn test`，0 failures、0 errors、0 TIMEOUT；记录实际 tests/skipped 数及跳过原因，不能沿用历史 1281 作为通过证据。
- [x] 经有界脚本执行 `mvn package -DskipTests`，退出码 0，生成 `target/acode.jar`。
- [ ] CMD、PowerShell、Windows Terminal 中各完成一次：输入多行中文与英文 → 连续拖拽缩放至少 20 次 → 补全/历史回看 → 提交一次 → 下一轮输入 → 退出；记录终端版本、步骤和观察结果，无卡死、丢字、重复提交或新增残影。未具备交互条件则保留未勾选。
- [x] 本专项变更仅涉及本计划列出的修复、测试和文档；其他任务未提交内容保留，数据库未被访问或修改。

## 实际方案与证据

复用 JLine 已有 protected lock，不新增应用绘制锁。旧版 3 次等待环与修复后 3 次成功均已保存；详见 docs/ui-resize-deadlock/test-review.md。新增 ResizeMainLoopEndToEndTest 单独验证编辑、并发缩放、提交、下一轮和退出的十秒时限，已通过。真实终端项目待人工观察。

后续用户授权继续章节开发，已开始独立的 ch14 团队基础模块；其状态见 docs/ch14/test-review.md，不计入上述 UI 修复全量结果。
