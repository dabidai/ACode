package com.acode.team.tools;

import com.acode.team.TeamTaskStore;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TaskToolsTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private ToolResult call(Tool tool, String json) throws Exception { return tool.execute(JSON.readTree(json), new ToolContext(root)); }

    @Test void schemasAndPermissionsMatchContract() {
        var store = new TeamTaskStore(root);
        var tools = List.of(new TaskCreateTool(() -> store, "a"), new TaskGetTool(() -> store, "a"),
                new TaskListTool(() -> store, "a"), new TaskUpdateTool(() -> store, "a"));
        var expectedFields = List.of(List.of("title", "description", "addBlockedBy", "addBlocks"), List.of("taskID"),
                List.<String>of(), List.of("taskID", "status", "addBlockedBy", "addBlocks"));
        var required = List.of(List.of("title"), List.of("taskID"), List.<String>of(), List.of("taskID"));
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            var schema = tool.inputSchema();
            var actual = new java.util.ArrayList<String>();
            schema.path("properties").fieldNames().forEachRemaining(actual::add);
            assertEquals(expectedFields.get(i), actual);
            assertEquals(JSON.valueToTree(required.get(i)), schema.path("required"));
            for (String key : expectedFields.get(i)) {
                var property = schema.path("properties").path(key);
                assertEquals(key.startsWith("add") ? "array" : "string", property.path("type").asText());
                if (key.startsWith("add")) assertEquals("string", property.path("items").path("type").asText());
            }
            assertEquals(i == 1 || i == 2 ? Permission.READ : Permission.WRITE, tool.permission());
            assertTrue(tool.description().contains("FYI"));
        }
        assertEquals(JSON.valueToTree(List.of("in_progress", "completed")), tools.get(3).inputSchema().path("properties").path("status").path("enum"));
    }

    @Test void toolFlowPreservesDependenciesAndReturnsExactBusinessErrors() throws Exception {
        var store = new TeamTaskStore(root);
        var create = new TaskCreateTool(() -> store, "alice");
        var update = new TaskUpdateTool(() -> store, "alice");
        assertEquals("1", JSON.readTree(call(create, "{\"title\":\"first\"}").output()).path("id").asText());
        assertTrue(call(create, "{\"title\":\"second\",\"addBlockedBy\":[\"1\"]}").isSuccess());
        assertEquals("任务 2 存在未完成的依赖，不能认领", call(update, "{\"taskID\":\"2\",\"status\":\"in_progress\"}").content());
        assertTrue(call(update, "{\"taskID\":\"1\",\"status\":\"in_progress\"}").isSuccess());
        assertTrue(call(update, "{\"taskID\":\"1\",\"status\":\"completed\"}").isSuccess());
        assertFalse(store.get("2").blocked());
        assertEquals("TaskUpdate 至少需要 status、addBlockedBy、addBlocks 中的一项", call(update, "{\"taskID\":\"2\"}").content());
        assertEquals("任务不存在：99", call(new TaskGetTool(() -> store, "a"), "{\"taskID\":\"99\"}").content());
        assertTrue(call(create, "{\"title\":\"bad\",\"addBlocks\":[1]}").isError());
        assertEquals(2, store.list().size());
        var detail = JSON.readTree(call(new TaskGetTool(() -> store, "a"), "{\"taskID\":\"2\"}").output());
        assertEquals("1", detail.path("blockedBy").get(0).asText());
        assertEquals(2, JSON.readTree(call(new TaskListTool(() -> store, "a"), "{}").output()).size());
    }

    @Test void concurrentToolClaimsHaveOneWinner() throws Exception {
        var store = new TeamTaskStore(root);
        store.create("task", null, null, null);
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<ToolResult>>();
            for (String actor : List.of("alice", "bob")) results.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return call(new TaskUpdateTool(() -> new TeamTaskStore(root), actor), "{\"taskID\":\"1\",\"status\":\"in_progress\"}");
            }));
            var a = results.get(0).get(10, TimeUnit.SECONDS);
            var b = results.get(1).get(10, TimeUnit.SECONDS);
            assertNotEquals(a.isSuccess(), b.isSuccess());
            assertEquals("任务 1 已被认领", a.isError() ? a.content() : b.content());
        }
    }
}
