package com.acode;

import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.config.AppConfig;
import com.acode.conversation.Conversation;
import com.acode.permission.PermissionResponse;
import com.acode.provider.FakeProvider;
import com.acode.tool.ToolRegistry;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExchangeRunnerTest {

    @Test
    void constructorAcceptsAllDependencies() {
        assertDoesNotThrow(() -> new ExchangeRunner(
                FakeProvider.streaming("你好"),
                new AppConfig(),
                new Conversation("m", false, 4096, 2000),
                new ToolRegistry(),
                new OutputPane(),
                new RenderContext(new AppConfig()),
                (ConfirmationRequestEvent e) -> PermissionResponse.DENY,
                (ChoiceRequestEvent e) -> null,
                Path.of("."),
                () -> null), "构造器应能接受全部依赖且不抛异常");
    }

    @Test
    void awaitLoopEndTimeoutMillisDefaultsTo5000() {
        assertEquals(5000L, ExchangeRunner.awaitLoopEndTimeoutMillis,
                "取消后等循环线程收尾的上限默认应为 5000 毫秒");
    }
}
