# Findings

当前 synchronized redisplay 与 JLine 输入循环形成锁顺序反转；已有 main 栈等待 JLine 锁且持有 reader monitor，虚拟线程栈未保留完整证据。JLine readLine 持内部锁调用虚分派 redisplay，故新 paintLock 仍会出现内部锁 → paintLock 路径。
草案未覆盖 mouse → paintHistory、readLineFramed finally；footer 回调放入 paintLock 需要核查间接 JLine 调用。screen 分支与 frame/普通分支分开评估。EditorTerminal 将 JLine 输出送入 sink，实际全屏绘制由 ScreenRenderer 负责。
历史根文档已记录 ch13 全量 1281/0/0/1；仅为历史基线，不能证明偶发死锁消失。测试同步 raise 阻塞时，后续 Future.get 超时无法保护。

用户选择完整推荐范围。根三文档已完成：spec 定义范围与设计约束；tasks 共 8 项，最后为接入主流程和端到端验证；checklist 定义具体等待时限、旧新版本同用例证据、10 轮压力测试、主流程和真实终端验收。T1–T8 未执行。
进一步确认：InputFrame 是 CommandProcessor 内部接口；mainLoop 有全屏/非交互、固定框、普通动态提示符分支；全屏既有测试仅在输入完成后发送缩放，需新增真实编辑交错覆盖。
