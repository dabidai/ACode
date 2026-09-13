package com.acode.command;

import com.acode.util.VirtualThreads;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {

    private static Command command(String name, String... aliases) {
        return new Command(name, List.of(aliases), "desc of " + name, "/" + name,
                CommandType.LOCAL, null, false, ctx -> CommandResult.CONTINUE);
    }

    private static Command hiddenCommand(String name) {
        return new Command(name, List.of(), "hidden " + name, "/" + name,
                CommandType.LOCAL, null, true, ctx -> CommandResult.CONTINUE);
    }

    private static CommandContext ctx(String args) {
        return new CommandContext(args, null, null, null, null, null, null, null, null);
    }

    @Test
    void findsCommandByFormalNameAndAlias() {
        CommandRegistry registry = new CommandRegistry();
        Command help = command("help", "h", "?");
        registry.register(help);
        assertSame(help, registry.find("help"));
        assertSame(help, registry.find("h"));
        assertSame(help, registry.find("?"));
    }

    @Test
    void lookupIsCaseInsensitiveForNameAndAlias() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help", "h"));
        assertEquals("help", registry.find("HELP").name());
        assertEquals("help", registry.find("H").name());
        assertSame(registry.find("help"), registry.find("HELP"));
    }

    @Test
    void duplicateFormalNameThrows() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("compact", "c"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.register(command("COMPACT")));
        assertTrue(e.getMessage().contains("compact"));
        assertEquals("compact", registry.find("c").name(), "冲突后原命令不受影响");
    }

    @Test
    void aliasCollidingWithAliasThrows() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("compact", "c"));
        registry.register(command("plan", "p"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.register(command("help", "h", "c")));
        assertTrue(e.getMessage().contains("c"), "错误信息应含冲突的名字");
        assertTrue(e.getMessage().contains("compact"), "错误信息应含已占用它的命令名");
    }

    @Test
    void aliasCollidingWithFormalNameThrows() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("compact"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.register(command("status", "compact")));
        assertTrue(e.getMessage().contains("compact"));
    }

    @Test
    void formalNameCollidingWithExistingAliasThrows() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help", "h"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.register(command("h")));
        assertTrue(e.getMessage().contains("h"), "错误信息应含冲突的名字");
        assertTrue(e.getMessage().contains("help"), "错误信息应含已占用它的命令名");
    }

    @Test
    void failedRegisterLeavesRegistryUnchanged() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("compact", "c"));
        registry.register(command("help", "h"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(command("status", "s", "h")));
        assertEquals(2, registry.all().size());
        assertNull(registry.find("status"), "失败注册的新名字不应残留");
        assertNull(registry.find("s"), "失败注册的新别名不应残留");
        assertEquals("compact", registry.find("c").name(), "原有别名仍指向原命令");
        assertEquals(List.of("compact", "help"),
                registry.visible().stream().map(Command::name).toList());
    }

    @Test
    void visibleListKeepsRegistrationOrder() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("quit"));
        registry.register(command("help", "h"));
        registry.register(command("status", "s"));
        assertEquals(List.of("quit", "help", "status"),
                registry.visible().stream().map(Command::name).toList());
    }

    @Test
    void hiddenCommandExcludedFromVisibleButStillFindable() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));
        registry.register(hiddenCommand("internal"));
        registry.register(command("quit"));
        assertEquals(List.of("help", "quit"),
                registry.visible().stream().map(Command::name).toList());
        assertEquals(List.of("help", "internal", "quit"),
                registry.all().stream().map(Command::name).toList());
        assertNotNull(registry.find("internal"), "隐藏命令仍可调用");
    }

    @Test
    void sameHandlerInstanceCanBackMultipleCommands() {
        Command.Handler shared = ctx -> CommandResult.CONTINUE;
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command("resume", List.of(), "d1", "/resume",
                CommandType.LOCAL, null, false, shared));
        registry.register(new Command("resume-session", List.of(), "d2", "/resume-session",
                CommandType.LOCAL, null, false, shared));
        assertSame(registry.find("resume").handler(), registry.find("resume-session").handler());
        assertEquals(CommandResult.CONTINUE, registry.find("resume").handler().execute(ctx(null)));
        assertEquals(CommandResult.CONTINUE, registry.find("resume-session").handler().execute(ctx("x")));
    }

    @Test
    void concurrentLookupsDuringRegisterNeverFailOrMiss() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String name = "cmd" + i;
            registry.register(command(name));
            names.add(name);
        }
        int readers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> results = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            results.add(VirtualThreads.POOL.submit(() -> {
                start.await();
                List<String> missed = new ArrayList<>();
                for (int round = 0; round < 200; round++) {
                    for (String name : names) {
                        if (registry.find(name) == null) {
                            missed.add(name);
                        }
                    }
                }
                return missed;
            }));
        }
        Future<?> writer = VirtualThreads.POOL.submit(() -> {
            start.await();
            for (int i = 0; i < 50; i++) {
                registry.register(command("extra" + i));
            }
            return null;
        });
        start.countDown();
        for (Future<List<String>> result : results) {
            assertTrue(result.get(30, TimeUnit.SECONDS).isEmpty(), "并发读期间不应漏查已注册命令");
        }
        writer.get(30, TimeUnit.SECONDS);
        assertEquals(150, registry.all().size());
        for (String name : names) {
            assertNotNull(registry.find(name));
        }
        for (int i = 0; i < 50; i++) {
            assertNotNull(registry.find("extra" + i));
        }
    }
}
