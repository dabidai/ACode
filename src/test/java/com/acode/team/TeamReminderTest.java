package com.acode.team;

import com.acode.agent.Agent;
import com.acode.conversation.Conversation;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TeamReminderTest {
    @TempDir Path root;
    private Conversation conversation() {
        var conversation = new Conversation("test", false, 1024, 32000);
        conversation.setSystemPrompt("system"); conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, "prompt"));
        return conversation;
    }
    private void run(Agent agent) throws Exception {
        var events = agent.run(); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (agent.isRunning() || !events.isEmpty()) {
                assertTrue(System.nanoTime() < end); events.poll(50, TimeUnit.MILLISECONDS);
            }
        } finally { agent.cancel(); agent.awaitTermination(); }
    }
    @Test void absentAndEmptyReminderProduceIdenticalRequests() throws Exception {
        var first = FakeProvider.streaming("done"); var second = FakeProvider.streaming("done");
        var plain = new Agent(first, conversation(), new ToolRegistry(), new ToolContext(root), 2);
        var withEmpty = new Agent(second, conversation(), new ToolRegistry(), new ToolContext(root), 2);
        withEmpty.setTurnReminderSource(() -> null, () -> {});
        run(plain); run(withEmpty);
        var json = new ObjectMapper();
        assertEquals(json.valueToTree(first.receivedRequests().getFirst().messages()), json.valueToTree(second.receivedRequests().getFirst().messages()));
        assertEquals(first.receivedRequests().getFirst().tools(), second.receivedRequests().getFirst().tools());
    }
    @Test void failedRequestConstructionNeverAcknowledgesMail() throws Exception {
        var provider = FakeProvider.streaming("done"); var accepted = new AtomicInteger();
        var mailbox = new FileMailbox(root);
        mailbox.deliver("lead", "alice", "summary", "retry me", "text");
        var agent = new Agent(provider, conversation(), new ToolRegistry(), new ToolContext(root), 2);
        agent.setTurnReminderSource(() -> { throw new IllegalStateException("build failure"); }, accepted::incrementAndGet);
        run(agent);
        assertEquals(0, accepted.get()); assertTrue(provider.receivedRequests().isEmpty()); assertEquals(1, mailbox.unread("alice").size());
    }
}
