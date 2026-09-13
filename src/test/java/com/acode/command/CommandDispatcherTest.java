package com.acode.command;

import com.acode.ui.MenuEntry;
import com.acode.ui.UIController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandDispatcherTest {

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

    private static CommandContext context(RecordingUi ui, String args) {
        return new CommandContext(args, ui, null, null, null, null, null, null, null);
    }

    private static CommandDispatcher dispatcher(CommandRegistry registry, RecordingUi ui, List<String> chat) {
        return new CommandDispatcher(registry, args -> context(ui, args), chat::add);
    }

    @Test
    void emptyInputExecutesNothing() {
        CommandRegistry registry = new CommandRegistry();
        AtomicReference<String> received = new AtomicReference<>("sentinel");
        registry.register(new Command("fake", List.of(), "d", "/fake", CommandType.LOCAL, null, false,
                ctx -> {
                    received.set(ctx.args());
                    return CommandResult.CONTINUE;
                }));
        RecordingUi ui = new RecordingUi();
        List<String> chat = new ArrayList<>();
        CommandDispatcher dispatcher = dispatcher(registry, ui, chat);

        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch(""));
        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch("   "));

        assertEquals("sentinel", received.get(), "空输入不应执行任何命令");
        assertTrue(chat.isEmpty(), "空输入不应走对话通道");
        assertTrue(ui.messages.isEmpty(), "空输入不应产生输出");
    }

    @Test
    void nonCommandGoesToChatChannel() {
        RecordingUi ui = new RecordingUi();
        List<String> chat = new ArrayList<>();
        CommandDispatcher dispatcher = dispatcher(new CommandRegistry(), ui, chat);

        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch("你好，帮我写一个排序算法"));

        assertEquals(List.of("你好，帮我写一个排序算法"), chat);
        assertTrue(ui.messages.isEmpty(), "非命令输入不应产生系统消息");
    }

    @Test
    void bareSlashIsEquivalentToHelp() {
        RecordingUi ui = new RecordingUi();
        List<String> chat = new ArrayList<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("help", List.of(), "d", "/help", CommandType.LOCAL, null, false,
                ctx -> {
                    ui.appendSystemMessage("help-line");
                    return CommandResult.CONTINUE;
                }));
        CommandDispatcher dispatcher = dispatcher(registry, ui, chat);

        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch("/"));
        List<String> afterBareSlash = List.copyOf(ui.messages);
        ui.messages.clear();
        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch("/help"));

        assertEquals(List.of("help-line"), afterBareSlash);
        assertEquals(afterBareSlash, ui.messages, "裸斜杠输出应与 /help 完全一致");
        assertTrue(chat.isEmpty(), "裸斜杠不应走对话通道");
    }

    @Test
    void unknownCommandEchoesTypedNameAndPointsToHelp() {
        RecordingUi ui = new RecordingUi();
        List<String> chat = new ArrayList<>();
        CommandDispatcher dispatcher = dispatcher(new CommandRegistry(), ui, chat);

        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch("/nosuch"));

        assertEquals(List.of("未知命令：/nosuch（输入 /help 查看可用命令）"), ui.messages);
        assertTrue(chat.isEmpty(), "未知命令不应走对话通道");
    }

    @Test
    void unknownCommandEchoPreservesCase() {
        RecordingUi ui = new RecordingUi();
        CommandDispatcher dispatcher = dispatcher(new CommandRegistry(), ui, new ArrayList<>());

        dispatcher.dispatch("/NoSuchCmd");

        assertEquals(List.of("未知命令：/NoSuchCmd（输入 /help 查看可用命令）"), ui.messages,
                "错误提示应回显用户实际输入的命令名");
    }

    @Test
    void argsPassedToHandlerVerbatim() {
        CommandRegistry registry = new CommandRegistry();
        AtomicReference<String> captured = new AtomicReference<>();
        registry.register(new Command("fake", List.of(), "d", "/fake", CommandType.LOCAL, null, false,
                ctx -> {
                    captured.set(ctx.args());
                    return CommandResult.CONTINUE;
                }));
        RecordingUi ui = new RecordingUi();
        CommandDispatcher dispatcher = dispatcher(registry, ui, new ArrayList<>());

        dispatcher.dispatch("/fake  spaced  out ");

        assertEquals(" spaced  out ", captured.get(), "参数原文应原样交给处理函数");
    }

    @Test
    void handlerExceptionYieldsSingleErrorLineAndContinues() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("boom", List.of(), "d", "/boom", CommandType.LOCAL, null, false,
                ctx -> {
                    throw new RuntimeException("故障原因");
                }));
        RecordingUi ui = new RecordingUi();
        CommandDispatcher dispatcher = dispatcher(registry, ui, new ArrayList<>());

        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch("/boom"));

        assertEquals(List.of("命令执行失败：故障原因"), ui.messages, "异常应兜底为一行错误");
    }

    @Test
    void handlerExitResultPropagates() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("quit", List.of(), "d", "/quit", CommandType.LOCAL, null, false,
                ctx -> CommandResult.EXIT));
        RecordingUi ui = new RecordingUi();
        CommandDispatcher dispatcher = dispatcher(registry, ui, new ArrayList<>());

        assertEquals(CommandResult.EXIT, dispatcher.dispatch("/quit"));
    }
}
