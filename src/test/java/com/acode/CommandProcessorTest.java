package com.acode;

import com.acode.command.Command;
import com.acode.command.CommandContext;
import com.acode.command.CommandDispatcher;
import com.acode.command.CommandRegistry;
import com.acode.command.CommandResult;
import com.acode.command.CommandType;
import com.acode.conversation.Conversation;
import com.acode.ui.MenuEntry;
import com.acode.ui.UIController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主循环已无命令分支：断言每行输入都经注入的调度器执行、返回值如实传导。 */
class CommandProcessorTest {

    /** 收集系统消息的假界面；未用到的操作为空实现 */
    private static final class RecordingUi implements UIController {
        final List<String> messages = new ArrayList<>();

        @Override
        public void appendSystemMessage(String text) {
            messages.add(text);
        }

        @Override
        public void submitUserInput(String text) {
        }

        @Override
        public void setPlanMode(boolean enabled) {
        }

        @Override
        public ContextUsage contextUsage() {
            return new ContextUsage(0, 0);
        }

        @Override
        public int selectMenu(List<MenuEntry> entries, String title) {
            return -1;
        }
    }

    /** 构造真实调度器注入处理器（tui/output/会话等仅存于构造参数，handleLine 不触碰） */
    private static CommandProcessor processor(CommandRegistry registry, RecordingUi ui, List<String> chat) {
        CommandDispatcher dispatcher = new CommandDispatcher(registry,
                args -> new CommandContext(args, ui, null, null, null, null, null, null, null),
                chat::add);
        CommandProcessor processor = new CommandProcessor(null, null, null,
                new Conversation("m", false, 4096, 2000), null,
                () -> null, chat::add, b -> { });
        processor.setCommandDispatcher(dispatcher);
        return processor;
    }

    @Test
    void chatInputFlowsThroughDispatcherChatChannel() {
        RecordingUi ui = new RecordingUi();
        List<String> chat = new ArrayList<>();
        CommandProcessor processor = processor(new CommandRegistry(), ui, chat);

        assertEquals(CommandResult.CONTINUE, processor.handleLine("hello world"));

        assertEquals(List.of("hello world"), chat, "非命令输入应原样进入对话通道");
    }

    @Test
    void unknownCommandProducedByDispatcherWithoutProcessorBranch() {
        RecordingUi ui = new RecordingUi();
        List<String> chat = new ArrayList<>();
        CommandProcessor processor = processor(new CommandRegistry(), ui, chat);

        assertEquals(CommandResult.CONTINUE, processor.handleLine("/nosuch"));

        assertEquals(List.of("未知命令：/nosuch（输入 /help 查看可用命令）"), ui.messages,
                "未知命令的提示应由调度器输出");
        assertTrue(chat.isEmpty(), "未知命令不应走对话通道");
    }

    @Test
    void exitResultPropagatesFromDispatcher() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("quit", List.of(), "退出", "/quit", CommandType.LOCAL, null, false,
                ctx -> CommandResult.EXIT));
        RecordingUi ui = new RecordingUi();
        CommandProcessor processor = processor(registry, ui, new ArrayList<>());

        assertEquals(CommandResult.EXIT, processor.handleLine("/quit"),
                "调度器返回退出时处理器应如实传导");
    }

    @Test
    void continueResultPropagatesFromDispatcher() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("status", List.of(), "状态", "/status", CommandType.LOCAL, null, false,
                ctx -> CommandResult.CONTINUE));
        RecordingUi ui = new RecordingUi();
        CommandProcessor processor = processor(registry, ui, new ArrayList<>());

        assertEquals(CommandResult.CONTINUE, processor.handleLine("/status"));
    }

    @Test
    void missingDispatcherFailsFast() {
        CommandProcessor processor = new CommandProcessor(null, null, null,
                new Conversation("m", false, 4096, 2000), null,
                () -> null, s -> { }, b -> { });

        NullPointerException e = assertThrows(NullPointerException.class, () -> processor.handleLine("x"));

        assertTrue(e.getMessage().contains("命令调度器未注入"), "未装配时应明确报错而不是静默失效");
    }
}
