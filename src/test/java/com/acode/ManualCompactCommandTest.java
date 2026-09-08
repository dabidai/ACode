package com.acode;

import com.acode.config.AppConfig;
import com.acode.provider.ChatMessage;
import com.acode.provider.FakeProvider;
import com.acode.ui.OutputPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T8：手动 /compact（controller.handleManualCompact）三类输出与历史重建。 */
class ManualCompactCommandTest {

    @TempDir
    Path tempDir;

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("test-model");
        config.setMaxContextTokens(8000);
        return config;
    }

    @Test
    void manualCompactRebuildsHistoryAndShowsBeforeAfterEstimates() {
        ConversationController controller = new ConversationController(
                FakeProvider.streaming("<summary>手动压缩完成</summary>"), config(), false);
        controller.setProjectRoot(tempDir);
        controller.conversation().addMessage(ChatMessage.of(USER, "x".repeat(40_000))); // 10000 token
        controller.conversation().addMessage(ChatMessage.of(USER, "当前问题"));
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);

        controller.handleManualCompact();

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("正在压缩…"), "先出现'正在压缩…'");
        assertTrue(joined.contains("压缩完成：压缩前约 "), "成功行含'压缩前约'");
        assertTrue(joined.contains("压缩后约 "), "成功行含'压缩后约'");
        // 历史重建：摘要(user) + 边界(assistant) + 保留的当前问题
        assertEquals(3, controller.conversation().messageCount());
        assertTrue(controller.conversation().history().get(0).content().contains("手动压缩完成"),
                "历史首条为摘要消息");
        assertTrue(sw.toString().contains("压缩"), "活跃区同步写出");
    }

    @Test
    void manualCompactOnShortHistoryShowsNoContentMessageAndKeepsHistory() {
        ConversationController controller = new ConversationController(
                FakeProvider.streaming("摘要"), config(), false);
        controller.setProjectRoot(tempDir);
        controller.conversation().addMessage(ChatMessage.of(USER, "短对话"));
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);

        controller.handleManualCompact();

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("（没有需要压缩的内容）"), "无可压缩摘要区 → 提示无需压缩");
        assertEquals(1, controller.conversation().messageCount(), "短历史不被改动");
    }
}
