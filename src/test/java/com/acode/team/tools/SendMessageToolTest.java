package com.acode.team.tools;

import com.acode.team.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SendMessageToolTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private TeamManager manager;
    private Team setup() {
        manager = new TeamManager(root);
        var team = manager.create("team", "lead-id", null);
        manager.register("team", new TeammateInfo("alice", "a", null, null, null, null, false, false));
        manager.register("team", new TeammateInfo("bob", "b", null, null, null, null, false, false));
        return team;
    }
    private ToolResult send(String actor, String to, String summary, String type) {
        var input = JSON.createObjectNode().put("to", to).put("message", "body");
        if (summary != null) input.put("summary", summary);
        if (type != null) input.put("messageType", type);
        return new SendMessageTool(new TeamMessaging(() -> manager.find("team").orElseThrow(), actor))
                .execute(input, new ToolContext(root));
    }
    @Test void aliasesAndBroadcastUseCanonicalMailbox() {
        var team = setup();
        var mailbox = new FileMailbox(team.configPath().getParent());
        assertTrue(send("a", "bob", "one two three four five", null).isSuccess());
        assertTrue(send("a", "b", "one two three four five", null).isSuccess());
        assertTrue(send("lead-id", "*", "one two three four five", null).isSuccess());
        assertEquals(3, mailbox.unread("bob").size());
        assertEquals(1, mailbox.unread("alice").size());
        assertTrue(mailbox.unread("bob").stream().allMatch(m -> m.to().equals("bob")));
        assertEquals("alice", mailbox.unread("bob").getFirst().from());
        assertFalse(manager.member("team", "bob").orElseThrow().isActive());
    }
    @Test void summaryAndStructuredPermissionsAreEnforcedBeforeDelivery() {
        var team = setup();
        for (String summary : new String[]{null, "one two three four", "1 2 3 4 5 6 7 8 9 10 11"})
            assertEquals("summary 必须为 5～10 个词", send("a", "bob", summary, null).content());
        assertTrue(send("a", "bob", "1 2 3 4 5 6 7 8 9 10", null).isSuccess());
        assertEquals("不支持的消息类型：invalid", send("a", "bob", null, "invalid").content());
        assertEquals("只有 Lead 能发送 plan_approval_response", send("a", "bob", null, "plan_approval_response").content());
        assertEquals("shutdown_response 只能发给 Lead", send("a", "bob", null, "shutdown_response").content());
        assertEquals("收件人不存在：missing", send("a", "missing", null, "shutdown_request").content());
        assertTrue(send("a", "lead-id", null, "shutdown_response").isSuccess());
        assertTrue(send("lead-id", "bob", null, "plan_approval_response").isSuccess());
        assertEquals(2, new FileMailbox(team.configPath().getParent()).readAll("bob").size());
        assertThrows(IllegalArgumentException.class, () -> manager.register("team",
                new TeammateInfo("lead", "x", null, null, null, null, false, false)));
    }
}
