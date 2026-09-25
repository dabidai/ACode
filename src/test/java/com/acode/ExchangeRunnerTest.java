package com.acode;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.config.AppConfig;
import com.acode.conversation.Conversation;
import com.acode.permission.PermissionResponse;
import com.acode.provider.ChatMessage;
import com.acode.provider.FakeProvider;
import com.acode.tool.BaseTool;
import com.acode.tool.ParamSpec;
import com.acode.tool.Permission;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.acode.tool.ToolRegistry;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void cancellationWaitsForToolWorkerToActuallyStop() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        BaseTool slow = new BaseTool("SlowRead", "test", Permission.READ) {
            @Override
            protected List<ParamSpec> paramSpecs() { return List.of(); }

            @Override
            protected ToolResult doExecute(JsonNode input, ToolContext context) {
                started.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // 模拟忽略中断的外部工具。
                    }
                }
                stopped.countDown();
                return ToolResult.success("done");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(slow);
        Conversation conversation = new Conversation("m", false, 4096, 2000);
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, "读文件"));
        Agent agent = new Agent(FakeProvider.scripted(List.of(List.of(
                FakeProvider.toolUse("id-1", "SlowRead", new ObjectMapper().createObjectNode()),
                FakeProvider.complete()))),
                conversation, registry, new ToolContext(Path.of(".")), 10);
        agent.run();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        agent.cancel();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> ExchangeRunner.awaitLoopEnd(agent));
        try {
            Thread.sleep(100);
            assertFalse(waiting.isDone(), "旧工具仍在运行时，取消收尾不得提前返回");
        } finally {
            release.countDown();
        }
        waiting.get(5, TimeUnit.SECONDS);
        assertEquals(0, stopped.getCount(), "返回时工具工作线程必须已结束");
    }
}
