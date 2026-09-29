package com.acode.team;

import com.acode.worktree.WorktreeNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Session-local roster. Writes complete before a new immutable snapshot is published. */
public final class TeamManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TEAM_DIRECTORY = Path.of(".acode", "teams");
    private final Path projectRoot;
    private final LongSupplier clock;
    private final Consumer<String> warning;
    private final Map<String, Team> teams = new LinkedHashMap<>();

    public TeamManager(Path projectRoot) { this(projectRoot, System::currentTimeMillis); }

    TeamManager(Path projectRoot, LongSupplier clock) {
        this(projectRoot, clock, message -> LoggerFactory.getLogger(TeamManager.class).warn(message));
    }

    TeamManager(Path projectRoot, LongSupplier clock, Consumer<String> warning) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.clock = clock;
        this.warning = warning;
        // Deliberately do not discover or restore teams from another session.
    }

    static String validateName(String name) {
        WorktreeNames.validate(name);
        if (name.contains("/")) throw new IllegalArgumentException("团队及队员名称不能包含路径分隔符");
        return name;
    }

    public synchronized Team create(String requestedName, String leadAgentID, String description) {
        validateName(requestedName);
        if (leadAgentID == null || leadAgentID.isBlank()) throw new IllegalArgumentException("Lead 标识不能为空");
        try {
            Path parent = ensureDirectory();
            for (int number = 1; ; number++) {
                String suffix = number == 1 ? "" : "-" + number;
                String base = requestedName.substring(0, Math.min(requestedName.length(), 64 - suffix.length()));
                // Truncation must not leave a forbidden trailing dot.
                while (base.endsWith(".")) base = base.substring(0, base.length() - 1);
                String name = validateName(base + suffix);
                if (teams.containsKey(name)) continue;
                Path directory = parent.resolve(name);
                try { Files.createDirectory(directory); }
                catch (FileAlreadyExistsException collision) { continue; }
                Team team = new Team(name, leadAgentID, description, List.of(),
                        directory.resolve("config.json"), clock.getAsLong());
                // A failed initial write leaves the reserved directory for diagnosis;
                // a later create will use the next suffix rather than overwrite it.
                persist(team);
                teams.put(name, team);
                return team;
            }
        } catch (IOException e) { throw new UncheckedIOException("团队配置写入失败", e); }
    }

    public synchronized List<Team> list() { return List.copyOf(teams.values()); }

    public synchronized Optional<Team> find(String name) { return Optional.ofNullable(teams.get(name)); }

    public synchronized Optional<TeammateInfo> member(String teamName, String nameOrID) {
        return requireTeam(teamName).members().stream()
                .filter(member -> member.name().equals(nameOrID) || member.agentID().equals(nameOrID)).findFirst();
    }

    public synchronized Team register(String teamName, TeammateInfo member) {
        Team team = requireTeam(teamName);
        for (TeammateInfo existing : team.members()) {
            if (existing.name().equals(member.name()) || existing.agentID().equals(member.agentID())
                    || existing.name().equals(member.agentID()) || existing.agentID().equals(member.name()))
                throw new IllegalArgumentException("队员名称或标识已存在：" + member.name());
        }
        var roster = new ArrayList<>(team.members());
        roster.add(member);
        return publish(team.withMembers(roster));
    }

    public synchronized Team setActive(String teamName, String nameOrID, boolean active) {
        Team team = requireTeam(teamName);
        TeammateInfo found = member(teamName, nameOrID)
                .orElseThrow(() -> new IllegalArgumentException("队员不存在：" + nameOrID));
        return publish(team.withMembers(team.members().stream()
                .map(member -> member == found ? member.withActive(active) : member).toList()));
    }

    public synchronized Team removeMember(String teamName, String nameOrID) {
        Team team = requireTeam(teamName);
        TeammateInfo found = member(teamName, nameOrID).orElse(null);
        if (found == null) return team;
        // The runtime must already be stopped. Return work before removing the
        // roster entry; a storage failure preserves the member for retry.
        new TeamTaskStore(team.configPath().getParent()).rollbackOwner(found.agentID());
        return publish(team.withMembers(team.members().stream().filter(member -> member != found).toList()));
    }

    /** Read for inspection only. Corruption never replaces the live session roster. */
    public synchronized Optional<Team> readConfiguration(String name) {
        validateName(name);
        try {
            Path file = projectRoot.resolve(TEAM_DIRECTORY).resolve(name).resolve("config.json");
            verifyPath(file);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            JsonNode node = JSON.readTree(file.toFile());
            if (node == null || !node.isObject() || !name.equals(requiredText(node, "name"))
                    || !node.path("members").isArray() || !node.path("createdAt").isIntegralNumber())
                throw new IllegalArgumentException("团队配置结构无效");
            var members = new ArrayList<TeammateInfo>();
            var identifiers = new HashSet<String>();
            for (JsonNode row : node.path("members")) {
                if (!"in-process".equals(requiredText(row, "backendType")))
                    throw new IllegalArgumentException("未知的团队后端");
                String memberName = requiredText(row, "name"), id = requiredText(row, "agentID");
                if (!identifiers.add(memberName) || (!id.equals(memberName) && !identifiers.add(id)))
                    throw new IllegalArgumentException("团队成员标识冲突");
                JsonNode active = row.get("isActive"), approval = row.get("planModeRequired");
                if (active != null && !active.isNull() && !active.isBoolean())
                    throw new IllegalArgumentException("isActive 必须为布尔值");
                if (approval != null && !approval.isBoolean())
                    throw new IllegalArgumentException("planModeRequired 必须为布尔值");
                String worktree = nullableText(row, "worktreePath");
                members.add(new TeammateInfo(memberName, id, nullableText(row, "agentType"),
                        nullableText(row, "model"), worktree == null ? null : Path.of(worktree),
                        TeammateInfo.BackendType.IN_PROCESS,
                        active == null || active.isNull() ? null : active.booleanValue(),
                        approval != null && approval.booleanValue()));
            }
            return Optional.of(new Team(name, requiredText(node, "leadAgentID"),
                    nullableText(node, "description"), members, file, node.path("createdAt").longValue()));
        } catch (IOException | RuntimeException e) {
            warning.accept("团队配置损坏或不可读，按空团队处理：" + name + " (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    private Team requireTeam(String name) {
        Team team = teams.get(name);
        if (team == null) throw new IllegalArgumentException("团队不存在：" + name);
        return team;
    }

    private Team publish(Team team) {
        try { persist(team); }
        catch (IOException e) { throw new UncheckedIOException("团队配置写入失败", e); }
        teams.put(team.name(), team);
        return team;
    }

    private Path ensureDirectory() throws IOException {
        Path parent = projectRoot.resolve(TEAM_DIRECTORY);
        verifyPath(parent);
        Files.createDirectories(parent);
        verifyPath(parent);
        return parent;
    }

    /** Refuse links/junctions below the project root, including an existing config. */
    private void verifyPath(Path target) throws IOException {
        Path root = projectRoot.toRealPath();
        Path cursor = projectRoot;
        for (Path part : projectRoot.relativize(target.toAbsolutePath().normalize())) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(cursor)
                    || !cursor.toRealPath().equals(root.resolve(projectRoot.relativize(cursor)))))
                throw new IOException("团队配置路径越界或包含链接：" + cursor);
        }
    }

    private void persist(Team team) throws IOException {
        verifyPath(team.configPath());
        ObjectNode node = JSON.createObjectNode().put("name", team.name())
                .put("leadAgentID", team.leadAgentID()).put("description", team.description())
                .put("createdAt", team.createdAt());
        var rows = node.putArray("members");
        for (TeammateInfo member : team.members()) {
            ObjectNode row = rows.addObject().put("name", member.name()).put("agentID", member.agentID())
                    .put("agentType", member.agentType()).put("model", member.model())
                    .put("worktreePath", member.worktreePath() == null ? null : member.worktreePath().toString())
                    .put("backendType", member.backendType().value()).put("planModeRequired", member.planModeRequired());
            if (member.isActive() == null) row.putNull("isActive"); else row.put("isActive", member.isActive());
        }
        Path temporary = Files.createTempFile(team.configPath().getParent(), ".config-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), node);
            verifyPath(team.configPath());
            try { Files.move(temporary, team.configPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) {
                // Fail closed: readers must never see a partially replaced roster.
                throw new IOException("团队配置目录不支持原子替换", e);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少字段：" + field);
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("字段必须为字符串：" + field);
        return value.textValue();
    }
}
