命令的执行结果渲染的要易于用户进行分辨，比如像Claude Code那样：

```
● Bash(JAVA_HOME="D:\java\jdk21" mvn test -Dtest="ExitPlanModeToolTest,AgentPlanModeTest" 2>&1 | grep -E "Tests run:|BUILD|ERROR.*Test" | tail -6)
  ⎿  [INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.459 s -- in com.acode.agent.AgentPlanModeTest
     [INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.022 s -- in com.acode.agent.ExitPlanModeToolTest
     [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
     [INFO] BUILD SUCCESS
  ⎿  (timeout 5m)
```



确认是否执行命令的时候，不要采用那种y/n的方式，不直观，采用选择的方式：

```bash
> 是
  否
```

可以使用↑或↓来进行选择执行哪一个，如果AI那边有需要用户进行选择的话，也采用类似的方式进行展示



在修改代码的时候，最好可以像Claude Code那样先显示出来红绿色对比，大概是这样：可以渲染**整行绿色背景（新增 +）、红色背景（删除 -）、代码语法高亮、左侧行号**；



希望可以实现同时开多个终端来运行，可以同时写多个项目