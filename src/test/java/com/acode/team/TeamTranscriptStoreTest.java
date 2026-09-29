package com.acode.team;

import com.acode.provider.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamTranscriptStoreTest {
    @TempDir Path root;
    @Test void appendRewriteAndCorruptLineRecovery() throws Exception {
        var store = new TeamTranscriptStore(root, "alice", message -> fail(message));
        store.save(List.of(ChatMessage.of(ChatMessage.Role.USER, "first")));
        store.append(ChatMessage.of(ChatMessage.Role.ASSISTANT, "second"));
        Files.writeString(root.resolve("transcripts/alice.jsonl"), "{bad\n", StandardOpenOption.APPEND);
        assertEquals(List.of("first", "second"), store.read().stream().map(ChatMessage::content).toList());
        store.save(List.of(ChatMessage.of(ChatMessage.Role.USER, "compacted")));
        assertEquals(List.of("compacted"), store.read().stream().map(ChatMessage::content).toList());
    }
    @Test void writeFailureWarnsWithoutThrowing() throws Exception {
        Files.writeString(root.resolve("transcripts"), "sentinel");
        var warnings = new ArrayList<String>();
        var store = new TeamTranscriptStore(root, "alice", warnings::add);
        assertDoesNotThrow(() -> store.save(List.of(ChatMessage.of(ChatMessage.Role.USER, "test"))));
        assertFalse(warnings.isEmpty()); assertEquals("sentinel", Files.readString(root.resolve("transcripts")));
    }
}
