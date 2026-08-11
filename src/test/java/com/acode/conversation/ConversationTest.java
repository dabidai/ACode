package com.acode.conversation;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import org.junit.jupiter.api.Test;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTest {

    private static final int WINDOW = 2000;

    private Conversation conversation() {
        return new Conversation("m", false, 4096, WINDOW);
    }

    private static ChatMessage user(String content) {
        return ChatMessage.of(USER, content);
    }

    @Test
    void estimateAsciiCharsToTokens() {
        assertEquals(250, Conversation.estimateTokens("a".repeat(1000)));
    }

    @Test
    void messageCountIncrements() {
        Conversation c = conversation();
        assertEquals(0, c.messageCount());
        c.addMessage(user("hi"));
        assertEquals(1, c.messageCount());
        c.addMessage(user("yo"));
        assertEquals(2, c.messageCount());
    }

    @Test
    void keepsAllMessagesWithinWindow() {
        Conversation c = conversation();
        for (int i = 0; i < 3; i++) {
            c.addMessage(user("h".repeat(400))); // 每条 100 token
        }
        c.addMessage(user("q")); // 1 token，合计 301 ≤ 2000
        ChatRequest request = c.buildRequest();
        assertEquals(4, request.messages().size());
    }

    @Test
    void trimsOldestUntilFits() {
        Conversation c = conversation();
        for (int i = 0; i < 10; i++) {
            c.addMessage(user("h".repeat(1200))); // 每条 300 token
        }
        c.addMessage(user("q".repeat(4))); // 1 token，合计 3001 > 2000

        ChatRequest request = c.buildRequest();
        int total = request.messages().stream()
                .mapToInt(m -> Conversation.estimateTokens(m.content())).sum();
        assertTrue(total <= WINDOW, "组装后总量必须 ≤ 窗口，实际 " + total);
        // 3001 → 丢 4 条（每条 300）后剩 6×300 + 1 = 1801 ≤ 2000
        assertEquals(7, request.messages().size(), "应从最早丢弃恰好 4 条，剩 7 条");
        assertEquals("q".repeat(4), request.messages().get(6).content(), "最新问题必须保留");
        assertEquals(11, c.messageCount(), "完整历史不应被截断，仍为 11 条");
    }

    @Test
    void keepsOnlyCurrentQuestionWhenItExceedsWindow() {
        Conversation c = conversation();
        c.addMessage(user("h".repeat(4000))); // 1000 token
        c.addMessage(user("big".repeat(3000))); // 9000 字符 → 2250 token，单条即超窗口
        ChatRequest request = c.buildRequest();
        assertEquals(1, request.messages().size(), "历史全部丢弃，只保留当前问题");
        assertEquals("big".repeat(3000), request.messages().get(0).content());
    }

    @Test
    void clearEmptiesHistory() {
        Conversation c = conversation();
        c.addMessage(user("hi"));
        c.addMessage(user("yo"));
        c.clear();
        assertEquals(0, c.messageCount());
        assertTrue(c.history().isEmpty());
    }

    @Test
    void clearThenAddWorksNormally() {
        Conversation c = conversation();
        c.addMessage(user("old"));
        c.clear();
        c.addMessage(user("new"));
        assertEquals(1, c.messageCount());
        ChatRequest request = c.buildRequest();
        assertEquals(1, request.messages().size());
        assertEquals("new", request.messages().get(0).content());
    }

    @Test
    void clearOnEmptyConversationIsSafe() {
        Conversation c = conversation();
        c.clear();
        assertEquals(0, c.messageCount());
    }
}
