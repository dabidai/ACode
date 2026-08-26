package com.acode.ui;

import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.agent.Choice;
import com.acode.agent.Confirmation;
import com.acode.config.AppConfig;
import com.acode.permission.PermissionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PromptAnswererTest {

    /** tui 传 null 模拟纯测试环境（无终端守卫路径）。 */
    private static PromptAnswerer withoutTui() {
        return new PromptAnswerer(null, new RenderContext(new AppConfig()));
    }

    @Test
    void confirmationPromptWithoutTuiReturnsDeny() {
        ConfirmationRequestEvent event =
                new ConfirmationRequestEvent("tool-1", "Bash", "ls -la", new Confirmation());
        assertEquals(PermissionResponse.DENY, withoutTui().answerConfirmationPrompt(event),
                "无终端时应默认拒绝确认请求");
    }

    @Test
    void choicePromptWithoutTuiReturnsNull() {
        ChoiceRequestEvent event =
                new ChoiceRequestEvent("tool-1", "Tool", "请选择", List.of("a", "b"), new Choice());
        assertNull(withoutTui().answerChoicePrompt(event),
                "无终端时选择请求应返回 null（取消）");
    }
}
