package com.acode.team;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Atomic team task transactions; blocked and reverse dependencies are derived, not stored. */
public final class TeamTaskStore {
    public record Task(String id, String title, String description, String status,
                       List<String> blockedBy, List<String> blocks, String owner, boolean blocked) {
        public Task { blockedBy = List.copyOf(blockedBy); blocks = List.copyOf(blocks); }
    }
    private record Entry(String id, String title, String description, String status, List<String> blockedBy, String owner) {
        Entry { blockedBy = List.copyOf(blockedBy); }
        Entry dependencies(List<String> ids) { return new Entry(id, title, description, status, ids, owner); }
        Entry state(String next, String actor) { return new Entry(id, title, description, next, blockedBy, actor); }
    }
    private final TeamJsonFile file;
    private final Consumer<String> warning;

    public TeamTaskStore(Path teamDirectory) {
        this(teamDirectory, message -> LoggerFactory.getLogger(TeamTaskStore.class).warn(message));
    }

    TeamTaskStore(Path teamDirectory, Consumer<String> warning) {
        file = new TeamJsonFile(teamDirectory.resolve("tasks.json"));
        this.warning = warning;
    }

    public Task create(String title, String description, List<String> addBlockedBy, List<String> addBlocks) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("任务标题不能为空");
        try (var ignored = file.lock()) {
            var entries = load();
            long maximum = entries.keySet().stream().mapToLong(Long::parseLong).max().orElse(0);
            String id = Long.toString(Math.addExact(maximum, 1));
            entries.put(id, new Entry(id, title, description == null ? "" : description, "pending", List.of(), null));
            addDependencies(entries, id, addBlockedBy, addBlocks);
            save(entries);
            return view(entries, id);
        } catch (IOException e) { throw new UncheckedIOException("任务写入失败：" + e.getMessage(), e); }
    }

    public List<Task> list() {
        try {
            var entries = load();
            return entries.keySet().stream().map(id -> view(entries, id)).toList();
        } catch (IOException | RuntimeException e) {
            warning.accept("任务文件损坏或不可读，按空列表处理：" + e.getMessage());
            return List.of();
        }
    }

    public Task get(String id) {
        return list().stream().filter(task -> task.id().equals(id)).findFirst()
                .orElseThrow(() -> missing(id));
    }

    public Task update(String id, String actor, String status, List<String> addBlockedBy, List<String> addBlocks) {
        if (status == null && empty(addBlockedBy) && empty(addBlocks))
            throw new IllegalArgumentException("TaskUpdate 至少需要 status、addBlockedBy、addBlocks 中的一项");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("任务操作者不能为空");
        if (status != null && !Set.of("in_progress", "completed").contains(status))
            throw new IllegalArgumentException("不支持的任务状态：" + status);
        try (var ignored = file.lock()) {
            var entries = load();
            Entry entry = require(entries, id);
            addDependencies(entries, id, addBlockedBy, addBlocks);
            entry = require(entries, id);
            if ("in_progress".equals(status)) {
                if (!entry.status().equals("pending")) throw new IllegalArgumentException("任务 " + id + " 已被认领");
                if (blocked(entries, entry)) throw new IllegalArgumentException("任务 " + id + " 存在未完成的依赖，不能认领");
                entries.put(id, entry.state("in_progress", actor));
            } else if ("completed".equals(status)) {
                if (!entry.status().equals("in_progress") || !actor.equals(entry.owner()))
                    throw new IllegalArgumentException("只能完成自己已认领的任务：" + id);
                entries.put(id, entry.state("completed", actor));
            }
            save(entries);
            return view(entries, id);
        } catch (IOException e) { throw new UncheckedIOException("任务写入失败：" + e.getMessage(), e); }
    }

    /** Caller must stop the member runtime before making its tasks available again. */
    public int rollbackOwner(String actor) {
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("任务操作者不能为空");
        try (var ignored = file.lock()) {
            var entries = load();
            int count = 0;
            for (Entry entry : new ArrayList<>(entries.values())) {
                if (entry.status().equals("in_progress") && actor.equals(entry.owner())) {
                    entries.put(entry.id(), entry.state("pending", null));
                    count++;
                }
            }
            if (count > 0) save(entries);
            return count;
        } catch (IOException e) { throw new UncheckedIOException("任务回滚失败：" + e.getMessage(), e); }
    }

    private LinkedHashMap<String, Entry> load() throws IOException {
        var entries = new LinkedHashMap<String, Entry>();
        for (JsonNode row : file.read()) {
            String id = TeamJsonFile.text(row, "id");
            if (!id.matches("[1-9][0-9]*")) throw new IOException("任务 ID 无效");
            try { Long.parseLong(id); } catch (NumberFormatException e) { throw new IOException("任务 ID 超出范围", e); }
            String status = TeamJsonFile.text(row, "status");
            if (!Set.of("pending", "in_progress", "completed").contains(status)
                    || !row.path("blockedBy").isArray() || !row.has("owner")) throw new IOException("任务结构无效");
            String owner = row.path("owner").isNull() ? null : TeamJsonFile.text(row, "owner");
            if (status.equals("pending") ? owner != null : owner == null || owner.isBlank())
                throw new IOException("任务状态与认领人不一致");
            List<String> dependencies = new ArrayList<>();
            for (JsonNode dependency : row.path("blockedBy")) {
                if (!dependency.isTextual() || dependencies.contains(dependency.textValue()))
                    throw new IOException("任务依赖结构无效");
                dependencies.add(dependency.textValue());
            }
            String title = TeamJsonFile.text(row, "title");
            if (title.isBlank() || entries.putIfAbsent(id, new Entry(id, title,
                    TeamJsonFile.text(row, "description"), status, dependencies, owner)) != null)
                throw new IOException("任务标题为空或 ID 重复");
        }
        try { validateGraph(entries); }
        catch (IllegalArgumentException e) { throw new IOException("任务依赖图无效：" + e.getMessage(), e); }
        for (Entry entry : entries.values()) {
            if (!entry.status().equals("pending") && blocked(entries, entry))
                throw new IOException("已认领任务仍有未完成的依赖");
        }
        return entries;
    }

    private void save(Map<String, Entry> entries) throws IOException {
        var array = TeamJsonFile.JSON.createArrayNode();
        for (Entry entry : entries.values()) {
            var row = array.addObject().put("id", entry.id()).put("title", entry.title())
                    .put("description", entry.description()).put("status", entry.status()).put("owner", entry.owner());
            var dependencies = row.putArray("blockedBy");
            entry.blockedBy().forEach(dependencies::add);
        }
        file.write(array);
    }

    private static void addDependencies(Map<String, Entry> entries, String id, List<String> blockedBy, List<String> blocks) {
        for (String dependency : safe(blockedBy)) addDependency(entries, id, dependency);
        for (String dependent : safe(blocks)) addDependency(entries, dependent, id);
        validateGraph(entries);
    }

    private static void addDependency(Map<String, Entry> entries, String id, String dependency) {
        Entry entry = require(entries, id);
        require(entries, dependency);
        if (id.equals(dependency)) throw new IllegalArgumentException("任务不能依赖自身");
        if (entry.blockedBy().contains(dependency)) return;
        if (!entry.status().equals("pending")) throw new IllegalArgumentException("只能修改待认领任务的依赖：" + id);
        var ids = new ArrayList<>(entry.blockedBy()); ids.add(dependency);
        entries.put(id, entry.dependencies(ids));
    }

    private static void validateGraph(Map<String, Entry> entries) {
        var visited = new HashSet<String>();
        for (String id : entries.keySet()) visit(entries, id, new LinkedHashSet<>(), visited);
    }

    private static void visit(Map<String, Entry> entries, String id, LinkedHashSet<String> path, Set<String> visited) {
        if (visited.contains(id)) return;
        Entry entry = require(entries, id);
        if (!path.add(id)) throw new IllegalArgumentException("存在循环依赖：" + String.join(" -> ", path) + " -> " + id);
        for (String dependency : entry.blockedBy()) visit(entries, dependency, path, visited);
        path.remove(id); visited.add(id);
    }

    private static Task view(Map<String, Entry> entries, String id) {
        Entry entry = require(entries, id);
        List<String> blocks = entries.values().stream().filter(other -> other.blockedBy().contains(id)).map(Entry::id).toList();
        return new Task(id, entry.title(), entry.description(), entry.status(), entry.blockedBy(), blocks, entry.owner(), blocked(entries, entry));
    }

    private static boolean blocked(Map<String, Entry> entries, Entry entry) {
        return entry.blockedBy().stream().anyMatch(id -> !require(entries, id).status().equals("completed"));
    }

    private static Entry require(Map<String, Entry> entries, String id) {
        Entry entry = entries.get(id);
        if (entry == null) throw missing(id);
        return entry;
    }

    private static IllegalArgumentException missing(String id) { return new IllegalArgumentException("任务不存在：" + id); }
    private static boolean empty(List<String> ids) { return ids == null || ids.isEmpty(); }
    private static List<String> safe(List<String> ids) { return ids == null ? List.of() : ids; }
}
