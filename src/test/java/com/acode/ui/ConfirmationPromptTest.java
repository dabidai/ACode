package com.acode.ui;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationPromptTest {

    /** 脚本化读行器：按序吐出 answers，耗尽后一律答 n。 */
    private static ConfirmationPrompt scripted(LiveRegionRenderer live, StringWriter writer, String... answers) {
        Deque<String> queue = new ArrayDeque<>(List.of(answers));
        return new ConfirmationPrompt(prompt -> queue.isEmpty() ? "n" : queue.poll(), live, writer);
    }

    @Test
    void askReturnsTrueWhenAnswerIsYes() {
        StringWriter writer = new StringWriter();
        ConfirmationPrompt prompt = scripted(new LiveRegionRenderer(80, 24), writer, "y");
        assertTrue(prompt.ask("WriteFile", "{\"file_path\":\"a.txt\"}"));
        assertTrue(writer.toString().contains("要执行「WriteFile（{\"file_path\":\"a.txt\"}）」？[y/n]"));
        assertTrue(writer.toString().contains("（已批准执行「WriteFile」）"));
    }

    @Test
    void askReturnsFalseWhenAnswerIsNo() {
        StringWriter writer = new StringWriter();
        ConfirmationPrompt prompt = scripted(new LiveRegionRenderer(80, 24), writer, "n");
        assertFalse(prompt.ask("Bash", ""));
        assertTrue(writer.toString().contains("（已拒绝执行「Bash」）"));
    }

    @Test
    void askReasksOnInvalidAnswerUntilAccepted() {
        StringWriter writer = new StringWriter();
        ConfirmationPrompt prompt = scripted(new LiveRegionRenderer(80, 24), writer, "啥", "y");
        assertTrue(prompt.ask("WriteFile", ""));
        assertTrue(writer.toString().contains("（请输入 y 或 n）"));
    }

    @Test
    void askRejectsOnUserInterrupt() {
        StringWriter writer = new StringWriter();
        ConfirmationPrompt prompt = new ConfirmationPrompt(
                p -> { throw new UserInterruptException(""); },
                new LiveRegionRenderer(80, 24), writer);
        assertFalse(prompt.ask("WriteFile", ""));
        assertTrue(writer.toString().contains("（已取消）"));
    }

    @Test
    void askRejectsOnEndOfFile() {
        ConfirmationPrompt prompt = new ConfirmationPrompt(
                p -> { throw new EndOfFileException(""); },
                new LiveRegionRenderer(80, 24), new StringWriter());
        assertFalse(prompt.ask("WriteFile", ""));
    }

    @Test
    void yesAnswersAreCaseInsensitiveAndTrimmed() {
        for (String answer : new String[]{"Y", "yes", " Yes ", "YES"}) {
            assertTrue(ConfirmationPrompt.isYes(answer), "isYes(" + answer + ") 应为 true");
        }
        assertFalse(ConfirmationPrompt.isYes("n"));
        assertFalse(ConfirmationPrompt.isYes(null));
    }

    @Test
    void noAnswersAreCaseInsensitiveAndTrimmed() {
        for (String answer : new String[]{"N", "no", " No ", "NO"}) {
            assertTrue(ConfirmationPrompt.isNo(answer), "isNo(" + answer + ") 应为 true");
        }
        assertFalse(ConfirmationPrompt.isNo("y"));
        assertFalse(ConfirmationPrompt.isNo(null));
    }

    @Test
    void promptLineOmitArgsWhenEmpty() {
        assertEquals("要执行「WriteFile」？[y/n]", ConfirmationPrompt.promptLine("WriteFile", ""));
        assertEquals("要执行「WriteFile」？[y/n]", ConfirmationPrompt.promptLine("WriteFile", null));
        assertEquals("要执行「WriteFile」？[y/n]", ConfirmationPrompt.promptLine("WriteFile", "  "));
    }

    @Test
    void promptLineKeepsArgsSummary() {
        assertEquals("要执行「Bash（ls -la）」？[y/n]", ConfirmationPrompt.promptLine("Bash", "ls -la"));
    }
}
