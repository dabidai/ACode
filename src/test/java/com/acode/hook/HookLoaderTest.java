package com.acode.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HookLoaderTest {
    @TempDir Path dir;
    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }
    static String hook(String id, String event, String action) {
        return "  - " + (id == null ? "" : "id: " + id + "\n    ") + "event: " + event + "\n    action: " + action + "\n";
    }
    @Test void layersAppendAndIdsAreStableAcrossSources() throws Exception {
        Path project = dir.resolve("project"), home = dir.resolve("home");
        for (Path path : List.of(project.resolve(".acode/hooks.local.yaml"), project.resolve(".acode/hooks.yaml"), home.resolve(".acode/hooks.yaml")))
            write(path, "hooks:\n" + hook(null, HookEvents.TURN_START, "{type: prompt, message: one}") + hook(null, HookEvents.TURN_END, "{type: prompt, message: two}"));
        var loaded = HookLoader.load(project, home);
        assertEquals(6, loaded.hooks().size()); assertTrue(loaded.errors().isEmpty());
        assertEquals(List.of("local:.acode/hooks.local.yaml#1", "local:.acode/hooks.local.yaml#2", "project:.acode/hooks.yaml#1", "project:.acode/hooks.yaml#2", "user:.acode/hooks.yaml#1", "user:.acode/hooks.yaml#2"), loaded.hooks().stream().map(HookConfig::id).toList());
        assertEquals(loaded.hooks().stream().map(HookConfig::id).toList(), HookLoader.load(project, home).hooks().stream().map(HookConfig::id).toList());
    }
    @Test void invalidRowsAreLocatedAndDoNotPoisonFollowingRows() throws Exception {
        var file = dir.resolve(".acode/hooks.yaml");
        var cases = new LinkedHashMap<String, String>();
        cases.put("event: typo\n    action: {type: prompt, message: x}", "事件名不合法：typo");
        cases.put("event: turn_start\n    action: {type: shell}", "动作类型不合法：shell");
        cases.put("event: turn_end\n    reject: true\n    action: {type: prompt, message: x}", "reject 只能用在");
        cases.put("event: pre_tool_use\n    async: true\n    action: {type: prompt, message: x}", "async 不能用在");
        cases.put("event: turn_start\n    if: {all: x}\n    action: {type: prompt, message: x}", "条件结构不合法");
        cases.put("event: turn_start\n    if: {event: x}\n    action: {type: prompt, message: x}", "条件含未知字段：event");
        cases.put("event: turn_start\n    if: {args.command: {regex: '['}}\n    action: {type: prompt, message: x}", "正则无法编译：[");
        cases.put("event: turn_start\n    unknown_key: 1\n    action: {type: prompt, message: x}", "顶层含未知字段：unknown_key");
        cases.put("event: turn_start\n    action: {type: prompt, message: x, unknown: y}", "action 含未知字段：unknown");
        for (String timeout : List.of("abc", "10x", "0s", "-1s", "999999999999999999999m"))
            cases.put("event: turn_start\n    action: {type: command, command: x, timeout: '" + timeout + "'}", "timeout 格式不合法");
        for (String type : List.of("command", "prompt", "http", "agent"))
            cases.put("event: turn_start\n    action: {type: " + type + "}", "动作缺少必填字段");
        for (var item : cases.entrySet()) {
            write(file, "hooks:\n  - id: bad\n    " + item.getKey() + "\n" + hook("good", HookEvents.TURN_START, "{type: prompt, message: ok}"));
            var result = HookLoader.load(dir, dir.resolve("home"));
            assertEquals(1, result.hooks().size(), item.getKey()); assertEquals(1, result.errors().size());
            String error = result.errors().getFirst();
            assertTrue(error.contains(file.toString()) && error.contains("第 1 条") && error.contains("id=bad") && error.contains(item.getValue()), error);
        }
    }
    @Test void duplicateIdsAcrossLayersAreRejectedAndMalformedFilesSkipped() throws Exception {
        Path home = dir.resolve("home");
        write(dir.resolve(".acode/hooks.local.yaml"), "hooks:\n" + hook("dup", HookEvents.TURN_START, "{type: prompt, message: ok}"));
        write(dir.resolve(".acode/hooks.yaml"), "hooks:\n" + hook("dup", HookEvents.TURN_START, "{type: prompt, message: ok}"));
        assertTrue(HookLoader.load(dir, home).errors().getFirst().contains("id 重复：dup"));
        for (String invalid : List.of("hooks: [", "a: 1", "[]", "hooks: hi", "hooks: [just-a-string]", "!!java.lang.Runtime {}")) {
            write(dir.resolve(".acode/hooks.yaml"), invalid);
            var result = HookLoader.load(dir, home);
            assertEquals(1, result.hooks().size()); assertTrue(result.errors().isEmpty());
        }
        assertTrue(HookLoader.load(home, home).hooks().isEmpty());
    }
    @Test void defaultsAndDurationUnits() throws Exception {
        write(dir.resolve(".acode/hooks.yaml"), "hooks:\n" + hook("a", HookEvents.TURN_START, "{type: command, command: x}")
                + hook("b", HookEvents.TURN_START, "{type: http, url: 'http://localhost'}")
                + hook("c", HookEvents.TURN_START, "{type: command, command: x, timeout: 2m}")
                + hook("d", HookEvents.TURN_START, "{type: command, command: x, timeout: 15ms}")
                + hook("e", HookEvents.TURN_START, "{type: command, command: x, timeout: 2}"));
        assertEquals(List.of(30000L, 10000L, 120000L, 15L, 2000L), HookLoader.load(dir, dir.resolve("home")).hooks().stream().map(h -> h.action().timeout().toMillis()).toList());
    }
    @Test void cyclicYamlConditionIsRejectedWithoutStackOverflow() throws Exception {
        write(dir.resolve(".acode/hooks.yaml"), "hooks:\n  - event: turn_start\n    if: &cycle {not: *cycle}\n    action: {type: prompt, message: x}\n");
        var result = HookLoader.load(dir, dir.resolve("home"));
        assertTrue(result.hooks().isEmpty()); assertTrue(result.errors().getFirst().contains("条件结构不合法"));
    }
}
