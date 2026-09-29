package com.acode.worktree;

import com.acode.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Project-scoped manager. Git is the authority for identity; metadata only grants cleanup eligibility. */
public final class WorktreeManager {
    public record Entry(String name, String path, String branch, String baseline, boolean temporary) {}
    public record Session(String name, String path, String branch, String originalDirectory,
                          String originalBranch, String originalHead) {}
    public record View(Entry entry, boolean current, String status) {}
    public record Created(Entry entry, boolean recovered) {}
    private record Registration(Path path, String branch, boolean locked) {}
    private final Path root;
    private final Path state;
    private final Path trees;
    private final AppConfig config;
    private final GitRunner git;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> warnings = new ArrayList<>();
    private Session current;

    public WorktreeManager(Path root, AppConfig config) { this(root, config, new GitRunner()); }
    public WorktreeManager(Path root, AppConfig config, GitRunner git) {
        this.root = root.toAbsolutePath().normalize(); this.config = config; this.git = git;
        state = this.root.resolve(".acode"); trees = state.resolve("worktrees");
    }
    public synchronized Path workingDirectory() { return current == null ? root : Path.of(current.path()); }
    public synchronized Session session() { return current; }
    public synchronized List<String> drainWarnings() { var result = List.copyOf(warnings); warnings.clear(); return result; }
    private void repository() throws IOException {
        var result = git.run(root, "rev-parse", "--show-toplevel");
        if (result.code() != 0) throw new IOException("当前目录不是 Git 仓库，无法使用 Worktree");
        if (!Path.of(result.output().strip()).toRealPath().equals(root.toRealPath()))
            throw new IOException("请在 Git 仓库根目录使用 Worktree");
        safe(state); safe(trees);
    }
    private void safe(Path path) throws IOException {
        if (!path.normalize().startsWith(root)) throw new IOException("Worktree 路径越界");
        for (Path p = path; p != null && !p.equals(root); p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(p) || !p.toRealPath().equals(root.toRealPath().resolve(root.relativize(p)))))
                throw new IOException("Worktree 路径包含链接或重定向：" + p);
        }
    }
    private Path path(String name) throws IOException {
        Path result = trees.resolve(WorktreeNames.validate(name)); safe(result); return result;
    }
    private String branch(String name) { return "worktree-" + WorktreeNames.validate(name); }
    private Map<String, Entry> registry() throws IOException {
        Path file = state.resolve("worktree_registry.json"); safe(file);
        if (!Files.exists(file)) return new LinkedHashMap<>();
        Map<String, Entry> result = json.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Entry>>() {});
        if (result == null) throw new IOException("Worktree 元数据损坏，保留现场");
        return result;
    }
    private void write(String filename, Object value) throws IOException {
        safe(state); Files.createDirectories(state);
        Path dest = state.resolve(filename); safe(dest);
        Path temp = Files.createTempFile(state, "worktree-", ".tmp");
        try {
            json.writeValue(temp.toFile(), value);
            try { Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private List<Registration> registrations() throws IOException {
        String raw = git.run(root, "worktree", "list", "--porcelain", "-z").require();
        List<Registration> result = new ArrayList<>();
        Path location = null; String ref = null; boolean locked = false;
        for (String field : raw.split("\u0000", -1)) {
            if (field.isEmpty()) {
                if (location != null) result.add(new Registration(location.toAbsolutePath().normalize(), ref, locked));
                location = null; ref = null; locked = false;
            } else if (field.startsWith("worktree ")) location = Path.of(field.substring(9));
            else if (field.startsWith("branch refs/heads/")) ref = field.substring(18);
            else if (field.startsWith("locked")) locked = true;
        }
        return result;
    }
    private Entry verify(String name, boolean removing) throws IOException {
        repository();
        Path target = path(name);
        Registration found = registrations().stream().filter(r -> r.path().equals(target)).findFirst()
                .orElseThrow(() -> new IOException("Worktree 不存在或未注册：" + name));
        if (!Objects.equals(found.branch(), branch(name))) throw new IOException("Worktree 分支身份不匹配：" + name);
        if (removing && found.locked()) throw new IOException("Worktree 已锁定，未删除：" + name);
        if (!Files.isDirectory(target)) throw new IOException("Worktree 目录不存在：" + name);
        String common = git.run(root, "rev-parse", "--path-format=absolute", "--git-common-dir").require().strip();
        String actual = git.run(target, "rev-parse", "--path-format=absolute", "--git-common-dir").require().strip();
        if (!Path.of(common).toRealPath().equals(Path.of(actual).toRealPath())) throw new IOException("Worktree 所属仓库不匹配");
        String top = git.run(target, "rev-parse", "--show-toplevel").require().strip();
        if (!Path.of(top).toRealPath().equals(target.toRealPath())) throw new IOException("Worktree 实际目录不匹配");
        String actualBranch = git.run(target, "symbolic-ref", "--quiet", "--short", "HEAD").require().strip();
        if (!actualBranch.equals(branch(name))) throw new IOException("Worktree 实际分支不匹配");
        Entry saved = registry().get(name);
        if (saved != null && (!saved.name().equals(name) || !saved.path().equals(target.toString()) || !saved.branch().equals(branch(name))))
            throw new IOException("Worktree 元数据身份不匹配");
        return saved == null ? new Entry(name, target.toString(), branch(name), null, false) : saved;
    }
    public synchronized Created create(String name) throws IOException { return create(name, false); }
    public synchronized Created createTemporary(String name) throws IOException {
        if (!WorktreeNames.temporary(name)) throw new IllegalArgumentException("临时 Worktree 名称不匹配");
        return create(name, true);
    }
    private Created create(String name, boolean temporary) throws IOException {
        repository(); Path target = path(name); var records = registry();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return new Created(verify(name, false), true);
        if (records.containsKey(name)) throw new IOException("Worktree 元数据已存在，请检查残留：" + name);
        String baseline = git.run(root, "rev-parse", "HEAD").require().strip();
        git.run(root, "check-ref-format", "--branch", branch(name)).require();
        Files.createDirectories(trees);
        git.run(root, "worktree", "add", "-b", branch(name), target.toString(), baseline).require();
        Entry entry = new Entry(name, target.toString(), branch(name), baseline, temporary);
        records.put(name, entry); write("worktree_registry.json", records);
        new WorktreeSetup(root, git, warnings::add).apply(target, config);
        if (git.run(root, "check-ignore", "-q", ".acode/worktrees/").code() != 0)
            warnings.add("警告：工作树目录未被忽略，请自行检查 .gitignore（未自动修改）");
        return new Created(entry, false);
    }
    public synchronized Entry enter(String name) throws IOException {
        Entry entry = verify(name, false);
        var ref = git.run(root, "symbolic-ref", "--quiet", "--short", "HEAD");
        Session next = new Session(name, entry.path(), entry.branch(), root.toString(),
                ref.code() == 0 ? ref.output().strip() : "", git.run(root, "rev-parse", "HEAD").require().strip());
        write("worktree_session.json", next); current = next; return entry;
    }
    public synchronized String exit() throws IOException {
        if (current == null) throw new IOException("当前没有进入任何 Worktree");
        String name = current.name(); write("worktree_session.json", null); current = null; return name;
    }
    public synchronized String restore() {
        try {
            Path file = state.resolve("worktree_session.json"); safe(file);
            if (!Files.exists(file)) return null;
            Session saved = json.readValue(file.toFile(), Session.class);
            if (saved == null) return null;
            Entry entry = verify(saved.name(), false);
            if (!Objects.equals(saved.path(), entry.path()) || !Objects.equals(saved.branch(), entry.branch())
                    || !Objects.equals(saved.originalDirectory(), root.toString())) throw new IOException("会话身份不匹配");
            current = saved;
            return "已恢复 Worktree 会话：" + saved.name();
        } catch (IOException | RuntimeException e) {
            current = null;
            try { write("worktree_session.json", null); } catch (IOException ignored) { warnings.add("警告：无法清除失效 Worktree 会话"); }
            return "Worktree 会话已失效，已回到主目录（" + e.getMessage() + "）";
        }
    }
    private boolean dirty(Entry entry) throws IOException {
        Path target = Path.of(entry.path());
        return !git.run(target, "status", "--porcelain=v1", "--untracked-files=all", "--ignored", "-z").require().isEmpty();
    }
    private boolean changed(Entry entry) throws IOException {
        if (entry.baseline() == null || !entry.baseline().matches("[0-9a-f]{40,64}")) return true;
        return dirty(entry) || !git.run(Path.of(entry.path()), "rev-parse", "HEAD").require().strip().equals(entry.baseline());
    }
    public synchronized List<View> list() throws IOException {
        repository(); List<View> result = new ArrayList<>();
        for (Registration reg : registrations()) {
            if (!Objects.equals(reg.path().getParent(), trees)) continue;
            String name = reg.path().getFileName().toString().replace('+', '/');
            try {
                Entry entry = verify(name, false);
                String status;
                try { status = changed(entry) ? "有变更或基准未知" : "干净"; }
                catch (IOException e) { status = "未知"; }
                result.add(new View(entry, current != null && current.name().equals(name), status));
            } catch (IOException | RuntimeException e) { warnings.add("警告：跳过身份不明 Worktree：" + name); }
        }
        return result;
    }
    public synchronized void remove(String name, boolean force) throws IOException {
        Entry entry = verify(name, true);
        if (current != null && current.name().equals(name)) throw new IOException("请先 /worktree exit 再删除当前 Worktree");
        if (!force && changed(entry)) throw new IOException("Worktree 有未提交改动或新 commit，未删除。确认丢弃请执行 /worktree remove " + name + " --force");
        // Revalidate immediately before the destructive Git command; never recursively delete ourselves.
        verify(name, true);
        List<String> args = new ArrayList<>(List.of("worktree", "remove"));
        if (force) args.add("--force"); args.add(entry.path());
        detachLinks(Path.of(entry.path()), force);
        git.run(root, args.toArray(String[]::new)).require();
        var branchResult = git.run(root, "branch", force ? "-D" : "-d", entry.branch());
        if (branchResult.code() != 0) warnings.add("警告：已移除目录，保留分支 " + entry.branch() + "：" + branchResult.output().strip());
        var records = registry(); records.remove(name); write("worktree_registry.json", records);
    }
    /** Git for Windows can recurse through junctions. Unlink reparse points before Git removes a tree. */
    private void detachLinks(Path target, boolean force) throws IOException {
        Path realRoot = target.toRealPath();
        List<Path> links = new ArrayList<>();
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            private boolean redirected(Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                return attrs.isSymbolicLink() || attrs.isOther()
                        || !path.toRealPath().equals(realRoot.resolve(target.relativize(path)));
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(target) && redirected(dir, attrs)) {
                    links.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (redirected(file, attrs)) links.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        if (!force && !links.isEmpty()) throw new IOException("Worktree 包含链接，未删除；确认丢弃请使用 remove <名称> --force");
        for (Path link : links) Files.delete(link); // RemoveDirectory removes the junction, never its target.
    }
    private boolean persistedActive(String name) throws IOException {
        Path file = state.resolve("worktree_session.json"); safe(file);
        if (!Files.exists(file)) return false;
        Session saved = json.readValue(file.toFile(), Session.class);
        return saved != null && name.equals(saved.name());
    }
    public synchronized boolean cleanup(String name) {
        try {
            Entry entry = verify(name, true);
            if (!entry.temporary() || !WorktreeNames.temporary(name) || persistedActive(name)
                    || current != null && current.name().equals(name)) return false;
            if (changed(entry)) { warnings.add("保留 Worktree " + name + "：有变更或基准未知"); return false; }
            if (!git.run(Path.of(entry.path()), "rev-list", "--max-count=1", "HEAD", "--not", "--remotes").require().isBlank()) {
                warnings.add("保留 Worktree " + name + "：存在未推送提交"); return false;
            }
            remove(name, false); return true;
        } catch (IOException | RuntimeException e) { warnings.add("警告：保留 Worktree " + name + "（" + e.getMessage() + "）"); return false; }
    }
    public synchronized int prune() throws IOException {
        repository(); int count = 0;
        Instant cutoff = Instant.now().minus(config.getWorktreeStaleAfterDays(), ChronoUnit.DAYS);
        for (Entry entry : registry().values()) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!entry.temporary() || !WorktreeNames.temporary(entry.name())) continue;
            try {
                Path target = path(entry.name());
                if (Files.getLastModifiedTime(target).toInstant().isBefore(cutoff) && cleanup(entry.name())) count++;
            } catch (IOException | RuntimeException e) { warnings.add("警告：保留 Worktree " + entry.name() + "（" + e.getMessage() + "）"); }
        }
        return count;
    }
}
