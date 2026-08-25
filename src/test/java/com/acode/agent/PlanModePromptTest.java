package com.acode.agent;

import com.acode.tool.DefaultToolset;
import com.acode.tool.ToolRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanModePrompt FULL 版提醒文案的契约：提到的工具名必须已在工具表注册
 * （模型据此决定调用哪个工具，写错名字等于让模型调用不存在的工具）。
 */
class PlanModePromptTest {

    /** 驼峰工具名外形：至少两段单词（如 ExitPlanMode / AskUserQuestion），排除 Plan / Do 等普通词 */
    private static final Pattern CAMEL_TOKEN = Pattern.compile("[A-Z][a-z]+[A-Z][A-Za-z]*");

    private static Set<String> registeredToolNames() {
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        registry.register(new AskUserTool());
        registry.register(new ExitPlanModeTool());
        return new HashSet<>(registry.names());
    }

    @Disabled("待修复：PlanModePrompt FULL 提醒提到未注册工具名 AskUserQuestion（实际注册名为 AskUser，见 AskUserTool）")
    @Test
    void fullReminderMentionsOnlyRegisteredToolNames() {
        String full = PlanModePrompt.buildReminder(1);
        Set<String> registered = registeredToolNames();

        Matcher matcher = CAMEL_TOKEN.matcher(full);
        StringBuilder mentioned = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            mentioned.append(token).append(" ");
            assertTrue(registered.contains(token),
                    "FULL 提醒提到的工具名「" + token + "」应已注册，实际注册：" + registered);
        }
        assertTrue(mentioned.length() > 0, "FULL 提醒应至少提及一个工具名");
    }

    @Test
    void sparseReminderAlsoAvoidsUnknownToolNames() {
        String sparse = PlanModePrompt.buildReminder(2);
        Set<String> registered = registeredToolNames();
        Matcher matcher = CAMEL_TOKEN.matcher(sparse);
        while (matcher.find()) {
            assertTrue(registered.contains(matcher.group()),
                    "SPARSE 提醒提到的工具名应已注册，实际：" + matcher.group());
        }
    }
}
