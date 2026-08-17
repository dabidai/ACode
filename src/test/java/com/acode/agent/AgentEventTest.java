package com.acode.agent;

import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void streamTextRecordCarriesText() {
        StreamText event = new StreamText("你好");
        assertEquals("你好", event.text());
    }

    @Test
    void toolUseEventRecordCarriesIdNameAndArgs() {
        var args = JSON.createObjectNode().put("file_path", "a.txt");
        ToolUseEvent event = new ToolUseEvent("toolu_1", "ReadFile", args);
        assertEquals("toolu_1", event.toolId());
        assertEquals("ReadFile", event.toolName());
        assertEquals("a.txt", event.args().path("file_path").asText());
    }

    @Test
    void toolResultEventRecordCarriesOutputErrorFlagAndDisplay() {
        ToolResultEvent ok = new ToolResultEvent("toolu_1", "ReadFile", "内容", false, 0, "返回 1 行（L1-1）");
        assertFalse(ok.isError());
        assertEquals("内容", ok.output());
        assertEquals(0, ok.elapsedMs());
        assertEquals("返回 1 行（L1-1）", ok.display());

        ToolResultEvent fail = new ToolResultEvent("toolu_2", "Bash", "命令失败", true, 250, null);
        assertTrue(fail.isError());
        assertEquals(250, fail.elapsedMs());
        assertNull(fail.display(), "失败/拒绝路径 display 为 null");
    }

    @Test
    void turnCompleteRecordCarriesTurnNumber() {
        assertEquals(3, new TurnComplete(3).turn());
    }

    @Test
    void loopCompleteRecordCarriesTotalTurns() {
        assertEquals(2, new LoopComplete(2).totalTurns());
    }

    @Test
    void errorEventRecordCarriesMessage() {
        assertEquals("连接失败", new ErrorEvent("连接失败").message());
    }

    @Test
    void retryEventRecordCarriesReasonAndWait() {
        RetryEvent event = new RetryEvent("限流", 2000);
        assertEquals("限流", event.reason());
        assertEquals(2000, event.waitMs());
    }

    @Test
    void confirmationRequestEventCarriesToolAndResponseChannel() {
        Confirmation response = new Confirmation();
        ConfirmationRequestEvent event =
                new ConfirmationRequestEvent("toolu_1", "WriteFile", "{\"file_path\":\"a.txt\"}", response);
        assertEquals("toolu_1", event.toolId());
        assertEquals("WriteFile", event.toolName());
        assertEquals("{\"file_path\":\"a.txt\"}", event.argsSummary());
        assertTrue(event.response() instanceof Confirmation);
    }

    @Test
    void choiceRequestEventCarriesQuestionOptionsAndResponse() {
        Choice response = new Choice();
        ChoiceRequestEvent event =
                new ChoiceRequestEvent("toolu_1", "AskUser", "你想先做哪个？", List.of("A", "B"), response);
        assertEquals("toolu_1", event.toolId());
        assertEquals("AskUser", event.toolName());
        assertEquals("你想先做哪个？", event.question());
        assertEquals(List.of("A", "B"), event.options());
        assertTrue(event.response() instanceof Choice);
    }

    @Test
    void allEventTypesAreSealedMembers() {
        // sealed interface 的编译期约束：这 9 种 record 都能被 instanceof 判别
        AgentEvent e = new StreamText("x");
        assertTrue(e instanceof StreamText);
        assertTrue(new ToolUseEvent("id", "n", JSON.createObjectNode()) instanceof AgentEvent);
        assertTrue(new ToolResultEvent("id", "n", "out", false, 0, null) instanceof AgentEvent);
        assertTrue(new TurnComplete(1) instanceof AgentEvent);
        assertTrue(new LoopComplete(1) instanceof AgentEvent);
        assertTrue(new ErrorEvent("e") instanceof AgentEvent);
        assertTrue(new RetryEvent("r", 0) instanceof AgentEvent);
        assertTrue(new ConfirmationRequestEvent("id", "n", "", new Confirmation()) instanceof AgentEvent);
        assertTrue(new ChoiceRequestEvent("id", "n", "q", List.of("A"), new Choice()) instanceof AgentEvent);
    }

    @Test
    void queueCapacityIs64() {
        assertEquals(64, AgentEvent.QUEUE_CAPACITY);
    }

    @Test
    void putSafeEnqueuesEvent() {
        BlockingQueue<AgentEvent> queue = new ArrayBlockingQueue<>(2);
        AgentEvent.putSafe(queue, new StreamText("你好"));
        AgentEvent.putSafe(queue, new ErrorEvent("x"));
        assertEquals(2, queue.size());
        assertEquals(new StreamText("你好"), queue.poll());
        assertEquals(new ErrorEvent("x"), queue.poll());
    }

    @Test
    void putSafeBlocksUntilCapacityAndThenCompletes() throws Exception {
        BlockingQueue<AgentEvent> queue = new ArrayBlockingQueue<>(1);
        AgentEvent.putSafe(queue, new StreamText("第一"));
        AtomicBoolean done = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            AgentEvent.putSafe(queue, new StreamText("第二"));
            done.set(true);
        });
        producer.start();
        Thread.sleep(100);
        // 队满时 offer() 会直接放弃，putSafe 必须阻塞到腾位——用 offer() 本测试即失败
        assertTrue(producer.isAlive(), "队满时 putSafe 应阻塞而非丢事件");
        AgentEvent polled = queue.poll();
        assertEquals(new StreamText("第一"), polled);
        producer.join(1000);
        assertTrue(done.get(), "腾位后 putSafe 应完成入队");
        assertEquals(new StreamText("第二"), queue.poll());
    }

    @Test
    void putSafeRestoresInterruptFlagWhenInterrupted() throws Exception {
        BlockingQueue<AgentEvent> queue = new ArrayBlockingQueue<>(1);
        AgentEvent.putSafe(queue, new StreamText("第一"));
        Thread producer = new Thread(() ->
                AgentEvent.putSafe(queue, new StreamText("第二")));
        producer.start();
        Thread.sleep(100);
        assertTrue(producer.isAlive(), "队满时 putSafe 应阻塞");
        producer.interrupt();
        producer.join(1000);
        assertFalse(producer.isAlive(), "被中断的线程应退出");
        assertTrue(producer.isInterrupted(), "putSafe 被中断后应恢复中断位，供上层取消路径感知");
        assertEquals(1, queue.size(), "被中断的 put 不应入队");
        assertEquals(new StreamText("第一"), queue.poll());
    }
}
