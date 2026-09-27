package com.acode;

import com.acode.config.AppConfig;
import com.acode.provider.*;
import com.acode.session.SessionStore;
import com.acode.ui.OutputPane;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real controller + runner + session restore, with isolated project and user configuration. */
class HookControllerTest {
    @TempDir Path root;
    @TempDir Path home;
    private String originalHome;
    private final List<ConversationController> controllers = new ArrayList<>();
    @BeforeEach void isolate() { originalHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); }
    @AfterEach void close() { controllers.forEach(ConversationController::closeSession); System.setProperty("user.home", originalHome); }
    void write(Path file, String content) throws Exception { Files.createDirectories(file.getParent()); Files.writeString(file, content); }
    ConversationController controller(FakeProvider provider, boolean resume) {
        var config = new AppConfig(); config.setProtocol("anthropic"); config.setModel("test");
        config.setMemoryAuto(false); config.setMaxContextTokens(200000); config.setMaxIterations(5); config.setPermissionMode("bypassPermissions");
        var c = new ConversationController(provider, config, resume); controllers.add(c);
        c.setProjectRoot(root); c.setOutput(new OutputPane()); c.setScreenWriter(new StringWriter());
        return c;
    }
    @Test void threeLayersExecuteInOrderThroughRealController() throws Exception {
        int index = 0;
        for (Path file : List.of(root.resolve(".acode/hooks.local.yaml"), root.resolve(".acode/hooks.yaml"), home.resolve(".acode/hooks.yaml"))) {
            write(file, "hooks:\n  - event: turn_start\n    action: {type: command, command: 'echo layer" + (++index) + " >> order.txt'}\n");
        }
        var provider = FakeProvider.streaming("done");
        controller(provider, false).handleExchange("hello", () -> false, () -> {});
        assertEquals(List.of("layer1", "layer2", "layer3"), Files.readAllLines(root.resolve("order.txt")).stream().map(String::trim).toList());
        assertEquals(1, provider.receivedRequests().size());
    }
    @Test void sessionStartOnceRestoreClearAndNewController() throws Exception {
        write(root.resolve(".acode/hooks.yaml"), """
                hooks:
                  - id: once
                    event: session_start
                    once: true
                    action: {type: prompt, message: ONCE-MARKER}
                  - id: every
                    event: session_start
                    action: {type: prompt, message: EVERY-MARKER}
                """);
        var first = FakeProvider.streaming("done"); var c = controller(first, false);
        c.handleExchange("hello", () -> false, () -> {});
        assertTrue(text(first, 0).contains("ONCE-MARKER")); assertTrue(text(first, 0).contains("<system-reminder>"));
        c.commandProcessor().handleLine("/clear");
        c.handleExchange("again", () -> false, () -> {});
        assertFalse(text(first, 1).contains("ONCE-MARKER"));
        c.closeSession();
        var store = new SessionStore(root); var session = store.list().getFirst();
        assertEquals(Set.of("once"), SessionStore.readHookOnceIds(store.resolve(session.id())));
        var resumed = FakeProvider.streaming("done"); var r = controller(resumed, true);
        r.restoreIfResume(); r.handleExchange("resume", () -> false, () -> {});
        assertFalse(text(resumed, 0).contains("ONCE-MARKER")); assertTrue(text(resumed, 0).contains("EVERY-MARKER"));
        r.handleExchange("next", () -> false, () -> {}); assertFalse(text(resumed, 1).contains("EVERY-MARKER")); r.closeSession();
        var fresh = FakeProvider.streaming("done"); controller(fresh, false).handleExchange("new", () -> false, () -> {});
        assertTrue(text(fresh, 0).contains("ONCE-MARKER")); assertEquals(2, store.list().size());
    }
    @Test void configurationErrorVisibleAndRuntimeFailureOnlyLogged() throws Exception {
        write(root.resolve(".acode/hooks.yaml"), """
                hooks:
                  - id: bad
                    event: pre_toool_use
                    action: {type: prompt, message: ignored}
                  - id: fail
                    event: turn_start
                    action: {type: command, command: 'exit 1'}
                  - id: good
                    event: turn_start
                    action: {type: prompt, message: GOOD}
                """);
        var errors = new ByteArrayOutputStream(); PrintStream old = System.err;
        var provider = FakeProvider.streaming("done"); var c = controller(provider, false);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.acode.hook.HookActions.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            System.setErr(new PrintStream(errors, true, java.nio.charset.StandardCharsets.UTF_8));
            c.handleExchange("hello", () -> false, () -> {});
        } finally { System.setErr(old); logger.detachAppender(appender); }
        String text = errors.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("hooks.yaml") && text.contains("第 1 条") && text.contains("id=bad") && text.contains("事件名不合法"));
        assertFalse(text.contains("非零退出")); assertTrue(text(provider, 0).contains("GOOD")); assertEquals(1, provider.receivedRequests().size());
        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().equals("Hook 命令非零退出 [fail]：exit=1")));
    }
    @Test void noConfigurationAddsNoRemindersAndProducesSameMessages() {
        var provider = FakeProvider.streaming("done"); var c = controller(provider, false);
        c.handleExchange("hello", () -> false, () -> {});
        assertEquals("hello", provider.receivedRequests().getFirst().messages().getLast().content());
        assertEquals(1, provider.receivedRequests().size());
    }
    static String text(FakeProvider provider, int index) {
        return provider.receivedRequests().get(index).messages().stream().map(ChatMessage::content).reduce("", (a, b) -> a + b);
    }
}
