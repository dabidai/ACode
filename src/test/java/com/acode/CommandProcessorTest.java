package com.acode;

import com.acode.command.Command;
import com.acode.command.CommandContext;
import com.acode.command.CommandDispatcher;
import com.acode.command.CommandRegistry;
import com.acode.command.CommandResult;
import com.acode.command.CommandType;
import com.acode.ui.InputPane;
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

    /** 构造真实调度器注入处理器（tui/会话仅存于构造参数，handleLine 不触碰） */
    private static CommandProcessor processor(CommandRegistry registry, RecordingUi ui, List<String> chat) {
        CommandDispatcher dispatcher = new CommandDispatcher(registry,
                args -> new CommandContext(args, ui, null, null, null, null, null, null, null),
                chat::add);
        CommandProcessor processor = new CommandProcessor(null, null, registry);
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
        CommandProcessor processor = new CommandProcessor(null, null, new CommandRegistry());

        NullPointerException e = assertThrows(NullPointerException.class, () -> processor.handleLine("x"));

        assertTrue(e.getMessage().contains("命令调度器未注入"), "未装配时应明确报错而不是静默失效");
    }

    // ---- 新增：输入框边框的擦画时序（step） ----

    /** 记录动作次序的输入框替身：draw/erase 与调度器事件写进同一个列表 */
    private static final class RecordingFrame implements CommandProcessor.InputFrame {
        private final List<String> events;

        RecordingFrame(List<String> events) {
            this.events = events;
        }

        @Override
        public void draw() {
            events.add("draw");
        }

        @Override
        public void erase() {
            events.add("erase");
        }
    }

    /**
     * 真实调度器 + 事件化对话通道：CommandDispatcher 是 final 类、无法做替身，故用真实调度器；
     * 对话通道与命令处理函数把调用记进 events，与帧动作同列一处，从而能断言三者的先后次序。
     */
    private static CommandProcessor eventProcessor(CommandRegistry registry, List<String> events) {
        CommandDispatcher dispatcher = new CommandDispatcher(registry,
                args -> new CommandContext(args, new RecordingUi(), null, null, null, null, null, null, null),
                line -> events.add("dispatch"));
        CommandProcessor processor = new CommandProcessor(null, null, registry);
        processor.setCommandDispatcher(dispatcher);
        return processor;
    }

    /** 处理函数即事件化调度：记录一次 dispatch 并返回固定结果 */
    private static Command exitCommand(List<String> events) {
        return new Command("quit", List.of(), "退出", "/quit", CommandType.LOCAL, null, false, ctx -> {
            events.add("dispatch");
            return CommandResult.EXIT;
        });
    }

    @Test
    void stepRunsEraseThenDispatchThenDraw() {
        List<String> events = new ArrayList<>();
        CommandProcessor processor = eventProcessor(new CommandRegistry(), events);
        processor.setInputFrame(new RecordingFrame(events));

        assertEquals(CommandResult.CONTINUE, processor.step("hello"));

        assertEquals(List.of("erase", "dispatch", "draw"), events,
                "非空行：先擦页脚（让输出有干净地盘）→ 派发 → 回来重画帧");
    }

    @Test
    void stepOnEmptyLineDispatchesWithoutEraseOrDraw() {
        List<String> events = new ArrayList<>();
        CommandProcessor processor = eventProcessor(new CommandRegistry(), events);
        processor.setInputFrame(new RecordingFrame(events));

        assertEquals(CommandResult.CONTINUE, processor.step(""), "空白行仍走调度器，返回值如实传导");

        assertEquals(List.of(), events, "空白行不产出一字：既不 erase 也不 draw（页脚原地留存）");
    }

    @Test
    void stepOnWhitespaceLineDispatchesWithoutEraseOrDraw() {
        List<String> events = new ArrayList<>();
        CommandProcessor processor = eventProcessor(new CommandRegistry(), events);
        processor.setInputFrame(new RecordingFrame(events));

        assertEquals(CommandResult.CONTINUE, processor.step("   "));

        assertEquals(List.of(), events, "全空白行与空串同待遇：帧无操作");
    }

    @Test
    void blankLineStillGoesThroughDispatcher() {
        // 帧已注入时空白行不产生任何事件，故用「未注入调度器」反证它确实走到了调度器
        CommandProcessor processor = new CommandProcessor(null, null, new CommandRegistry());
        processor.setInputFrame(new RecordingFrame(new ArrayList<>()));

        NullPointerException e = assertThrows(NullPointerException.class, () -> processor.step("   "));

        assertTrue(e.getMessage().contains("命令调度器未注入"), "空白行不得短路跳过调度器");
    }

    @Test
    void exitResultErasesButSkipsRedraw() {
        List<String> events = new ArrayList<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register(exitCommand(events));
        CommandProcessor processor = eventProcessor(registry, events);
        processor.setInputFrame(new RecordingFrame(events));

        assertEquals(CommandResult.EXIT, processor.step("/quit"));

        assertEquals(List.of("erase", "dispatch"), events, "退出路径擦完就不再重画帧（随后即离开终端）");
    }

    @Test
    void stepWithoutInputFrameStillDispatchesAndNeverThrows() {
        List<String> events = new ArrayList<>();
        CommandProcessor processor = eventProcessor(new CommandRegistry(), events);

        assertEquals(CommandResult.CONTINUE, processor.step("hello"), "未注入帧时不得抛 NPE");

        assertEquals(List.of("dispatch"), events, "无帧可擦可画，只经调度器");
    }

    @Test
    void stepReturnsDispatcherResultVerbatim() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("status", List.of(), "状态", "/status", CommandType.LOCAL, null, false,
                ctx -> CommandResult.CONTINUE));
        registry.register(exitCommand(new ArrayList<>()));
        CommandProcessor processor = processor(registry, new RecordingUi(), new ArrayList<>());

        assertEquals(CommandResult.CONTINUE, processor.step("/status"));
        assertEquals(CommandResult.EXIT, processor.step("/quit"));
        assertEquals(CommandResult.CONTINUE, processor.step("hello"));
    }

    // ---- 新增：多行提示符（prompt）与默认提示符 ----

    /** 返回固定单行 prompt 的输入框替身。 */
    private static final class PromptFrame implements CommandProcessor.InputFrame {
        private final String prompt;

        PromptFrame(String prompt) {
            this.prompt = prompt;
        }

        @Override
        public String inputPrompt() {
            return prompt;
        }

        @Override
        public void draw() {
        }

        @Override
        public void erase() {
        }
    }

    @Test
    void promptWithoutFrameReturnsDefaultPrompt() {
        CommandProcessor processor = new CommandProcessor(null, null, new CommandRegistry());

        assertEquals(InputPane.DEFAULT_PROMPT, processor.prompt(), "无帧时提示符就是裸默认提示符");
    }

    @Test
    void promptWithDefaultFrameReturnsDefaultPrompt() {
        CommandProcessor processor = new CommandProcessor(null, null, new CommandRegistry());
        processor.setInputFrame(new RecordingFrame(new ArrayList<>()));

        assertEquals(InputPane.DEFAULT_PROMPT, processor.prompt());
    }

    @Test
    void promptWithNullHeaderReturnsDefaultPrompt() {
        CommandProcessor processor = new CommandProcessor(null, null, new CommandRegistry());
        processor.setInputFrame(new PromptFrame(null));

        assertEquals(InputPane.DEFAULT_PROMPT, processor.prompt(), "header 为 null 时同样退回默认提示符");
    }

    @Test
    void framedPromptIsUsedVerbatimWithoutMultilineComposition() {
        CommandProcessor processor = new CommandProcessor(null, null, new CommandRegistry());
        processor.setInputFrame(new PromptFrame("[default] > "));

        assertEquals("[default] > ", processor.prompt());
        assertTrue(!processor.prompt().contains("\n"), "真实输入提示符不得重新变成多行");
    }

    @Test
    void defaultPromptIsGreaterSignAndSpace() {
        assertEquals("> ", InputPane.DEFAULT_PROMPT, "默认提示符应为 > 加一个空格（去掉 * 是本次需求验收点）");
    }

    // ---- 新增：提示符显示的占行数（钉底时算输入框该沉到哪一行） ----

    @Test
    void displayRowsOfSingleLinePromptIsOne() {
        assertEquals(1, CommandProcessor.displayRows("> "), "无换行的提示符只占一行");
    }

    @Test
    void displayRowsCountsMultilinePromptDecorations() {
        assertEquals(3, CommandProcessor.displayRows("模式行\n分隔线\n> "),
                "模式行 + 分隔线 + 输入行应算 3 行（真机 pin 的 promptRows 就取这个数）");
    }

    @Test
    void displayRowsCountsEveryNewline() {
        assertEquals(4, CommandProcessor.displayRows("a\nb\nc\nd"), "三个换行 → 四行");
    }

    @Test
    void displayRowsOfEmptyPromptIsOne() {
        assertEquals(1, CommandProcessor.displayRows(""), "空串仍占一行，不得算成 0（否则提示符会沉进页脚）");
    }

    @Test
    void resizeInvalidatesPinDistance() {
        assertEquals(0, CommandProcessor.unpinDistance(17, true),
                "终端回流后不得按旧高度回退光标");
        assertEquals(17, CommandProcessor.unpinDistance(17, false),
                "未 resize 时保持原有成对 pin/unpin");
    }

    // ---- 新增：帧的默认页脚行数 ----

    @Test
    void frameDefaultsToZeroFooterRows() {
        assertEquals(0, new RecordingFrame(new ArrayList<>()).footerRows(),
                "测试路径的假帧没有底部状态区：默认 0 行，主循环据此跳过钉底");
    }
}
