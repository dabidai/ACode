package com.acode.team;

import java.util.Locale;
import java.util.Set;

public final class CoordinatorMode {
    private CoordinatorMode() {}
    public static final Set<String> ALLOWED = Set.of("Agent", "SendMessage", "TaskCreate", "TaskGet", "TaskList",
            "TaskUpdate", "TeamCreate", "TeamDelete", "ReadFile", "Glob", "Grep");
    public static boolean enabled(boolean configured, String environment) {
        return configured && environment != null && Set.of("1", "true", "yes", "on").contains(environment.strip().toLowerCase(Locale.ROOT));
    }
    public static final String PROMPT = "Research：派队员调查事实。Synthesis：Lead 综合证据，理解不能外包。"
            + "Implementation：通过共享任务和隔离队员实施。Verification：验证产物与测试证据，再向用户报告。";
}
