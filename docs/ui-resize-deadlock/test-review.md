# Resize 死锁修复测试记录

## 版本与方案
JDK 21 / JLine 3.30.16。直接复用上游 protected final lock；没有新增 paintLock，也没有生产反射。原源码副本：`.planning/resize-deadlock-plan/ResizeAwareLineReader.before.java`。
本次工作树含用户其他改动，全量结果针对当前整棵工作树；未操作数据库。

## 已完成证据
- 旧实现受控探针 3/3 次失败（退出 2，约 8.5 秒）。同一探针停在真实 readLine widget，确认缩放线程等待内部锁后再释放 widget；输入虚拟线程 BLOCKED 于 synchronized redisplay，缩放线程 WAITING 于 ReentrantLock。
- 旧实现原始日志：`target/resize-validation/20260928-203115-159/Probe-1.*.log`、`20260928-203124-542`、`20260928-203133-811`。
- 修复后相同探针 3/3 次成功（0.38 / 0.36 / 0.42 秒，退出 0），输出 `PROBE_OK abcdef`。目录：`target/resize-validation/20260928-203535-776/`。
- 首轮已有测试与隔离探针通过；补充后的目标组通过，包括新增 5 个并发/边缘测试。目录：`target/resize-validation/20260928-203640-344/`。
- 新测试覆盖三种模式中文多行编辑、同尺寸信号、CONT、窗口 5 行降级及恢复、下一轮标记复位、footer 等待另一线程重绘且抛异常、frame 清理与被闩锁暂停的 resize 交错。
- 真实 CommandProcessor.mainLoop 的原端到端测试增加了输入期间并发 20 次缩放，并断言业务只收到 `hello`、`again` 各一次，随后 `/quit` 退出。
- 故意挂起探针在正常本机权限下触发 TIMEOUT，取得 JDK JSON 线程转储并结束自身 JVM：`target/resize-validation/20260928-203807-236/`。沙箱最初拒绝进程枚举，已按要求提权重验；该失败单独留档。

## 执行入口
`pwsh -NoProfile -File scripts/verify-resize-deadlock.ps1 -Mode Target -Rounds 10`
`pwsh -NoProfile -File scripts/verify-resize-deadlock.ps1 -Mode Full`
`pwsh -NoProfile -File scripts/verify-resize-deadlock.ps1 -Mode Package`
`pwsh -NoProfile -File scripts/verify-resize-deadlock.ps1 -Mode Hang`（预期退出 124）

脚本串行运行、保存每轮 stdout/stderr、新生成的 Surefire XML 与统计 JSON。Target 180 秒，Full/Package 900 秒，Probe/Hang 10 秒；诊断失败不会跳过自身进程清理。Maven 内存 256 MiB，测试 JVM 384 MiB，仅为验证参数。

## 当前待完成
10 轮压力测试、全量回归与打包均已完成。真实 CMD / PowerShell / Windows Terminal 拖拽视觉验收尚未执行，不以模拟器通过替代。

最终超时自检：target/resize-validation/20260928-204351-759，10 秒超时，15.5 秒完成线程转储与清理，外部观察退出码 124；JSON 包含 intentional-hang-worker 虚拟线程。故意挂起为测试兜底的预期结果，不计入生产回归失败。
生产修复源码 SHA256：ADDE34A2D59D89B0A57A7EB29298A8D834A9AE5F877004B669861E925ACCDBBA；原源码 SHA256：F6328D092EAE85C455C5B27F369144D622BB237C3C3274C7224B0EE50A317C07。旧版等待环日志另存 docs/ui-resize-deadlock/old-probe.log。


## 最终自动化结果（2026-09-29 收尾核验）
- 10 轮定向测试：每轮 59 tests、0 failures、0 errors、0 skipped；合计 590 次执行，均无 TIMEOUT。目录 `target/resize-validation/20260928-204033-776/`。
- 新增精简主流程端到端 `ResizeMainLoopEndToEndTest`：1 test 通过，显式共享十秒截止时间；目录 `target/resize-validation/20260928-204811-230/`。
- 全量：1287 tests、0 failures、0 errors、1 skipped，186.07 秒；目录 `target/resize-validation/20260928-204839-244/`。此结果在后续新增 ch14 团队代码之前取得。
- 打包：`Package` 模式退出 0，9.56 秒；目录 `target/resize-validation/20260929-105051-231/`。jar 大小 8,966,426 字节。
- 真实 CMD / PowerShell / Windows Terminal 拖拽视觉观察未执行，保留未勾选。无需真实模型验证本次锁修复。
