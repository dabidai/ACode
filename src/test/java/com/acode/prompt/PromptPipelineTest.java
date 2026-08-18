package com.acode.prompt;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPipelineTest {

    private static Conversation conversation() {
        return new Conversation("m", false, 4096, 20000);
    }

    @Test
    void assembleProducesFourSegmentsInOrder() {
        Conversation c = conversation();
        c.setSystemPrompt("SYSTEM_PROMPT");
        c.setEnvironment(SystemReminder.wrap("# Environment\n..."));
        c.addMessage(ChatMessage.of(USER, "history-1"));
        c.addMessage(ChatMessage.of(USER, "history-2"));

        ChatRequest request = PromptPipeline.assemble(c, List.of(), SystemReminder.wrap("turn reminder"));

        assertEquals(5, request.messages().size());
        assertEquals(ChatMessage.Role.SYSTEM, request.messages().get(0).role());
        assertEquals("SYSTEM_PROMPT", request.messages().get(0).content());
        assertTrue(request.messages().get(1).content().startsWith("<system-reminder>"));
        assertEquals("history-1", request.messages().get(2).content());
        assertEquals("history-2", request.messages().get(3).content());
        assertTrue(request.messages().get(4).content().startsWith("<system-reminder>"));
    }

    @Test
    void assembleEqualsConversationBuildRequest() {
        Conversation c = conversation();
        c.setSystemPrompt("S");
        c.setEnvironment(SystemReminder.wrap("env"));
        c.addMessage(ChatMessage.of(USER, "hi"));

        ChatRequest viaPipeline = PromptPipeline.assemble(c, List.of(), SystemReminder.wrap("turn"));
        ChatRequest viaConversation = c.buildRequest(List.of(), SystemReminder.wrap("turn"));

        // ChatMessage 无 equals 覆写（内容相同实例不同），按 content 序列比较
        List<String> pipelineContents = viaPipeline.messages().stream().map(ChatMessage::content).toList();
        List<String> conversationContents = viaConversation.messages().stream().map(ChatMessage::content).toList();
        assertEquals(conversationContents, pipelineContents);
    }
}
