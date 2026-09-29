package com.acode.worktree;

import com.acode.config.AppConfig;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;

final class WorktreeSetup {
    private final Path root;
    private final GitRunner git;
    private final Consumer<String> warning;
    WorktreeSetup(Path root, GitRunner git, Consumer<String> warning) {
        this.root = root; this.git = git; this.warning = warning;
    }
    interface Step { void run() throws IOException; }
    private void attempt(String name, Step step) {
        try { step.run(); } catch (IOException | RuntimeException e) {
            warning.accept("警告：Worktree 创建后设置跳过 " + name + "（" + e.getMessage() + "）");
        }
    }
    void apply(Path target, AppConfig config) {
        attempt("本地配置", () -> {
            if (Files.exists(root.resolve("settings.local.json"))) copy(target, "settings.local.json");
        });
        attempt("hooks", () -> {
            var enabled = git.run(root, "config", "--bool", "extensions.worktreeConfig");
            if (enabled.code() != 0 || !enabled.output().strip().equals("true")) {
                warning.accept("警告：未启用 extensions.worktreeConfig，保留共享 hooks 配置");
                return;
            }
            var configured = git.run(root, "config", "--path", "core.hooksPath");
            Path hooks;
            if (configured.code() == 0 && !configured.output().isBlank()) {
                hooks = Path.of(configured.output().strip());
                if (!hooks.isAbsolute()) hooks = root.resolve(hooks);
            } else if (Files.isDirectory(root.resolve(".husky"))) hooks = root.resolve(".husky");
            else hooks = Path.of(git.run(root, "rev-parse", "--path-format=absolute", "--git-common-dir").require().strip()).resolve("hooks");
            git.run(target, "config", "--worktree", "core.hooksPath", hooks.toAbsolutePath().toString()).require();
        });
        for (String directory : config.getWorktreeSymlinkDirectories()) attempt("软链接 " + directory, () -> {
            Path source = root.resolve(directory).normalize();
            Path link = target.resolve(directory).normalize();
            if (!source.getParent().equals(root) || !link.getParent().equals(target)
                    || directory.equalsIgnoreCase(".git") || directory.equalsIgnoreCase(".acode"))
                throw new IOException("非法依赖目录");
            if (!Files.isDirectory(source) || !source.toRealPath().startsWith(root.toRealPath()))
                throw new IOException("源目录不存在或越界");
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目标已存在，未覆盖");
            try { Files.createSymbolicLink(link, source.toAbsolutePath()); }
            catch (IOException | UnsupportedOperationException e) {
                if (!System.getProperty("os.name").startsWith("Windows")) throw new IOException(e);
                // Validated single-segment name; reject shell expansion/metacharacters in ancestor paths too.
                if ((link.toString() + source).chars().anyMatch(c -> c == 34 || c == 37 || c == 33 || c == 38 || c == 124 || c == 60 || c == 62 || c == 94 || c == 40 || c == 41 || c == 10 || c == 13)) throw new IOException("路径不适合目录联接命令", e);
                git.execute(root, List.of("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), source.toString())).require();
            }
        });
        attempt("忽略文件", () -> {
            Path include = root.resolve(".worktreeinclude");
            if (!Files.exists(include)) return;
            if (!include.toRealPath().startsWith(root.toRealPath())) throw new IOException("清单越界");
            IncludeMatcher matcher = new IncludeMatcher(Files.readAllLines(include));
            String ignored = git.run(root, "ls-files", "--others", "--ignored", "--exclude-standard", "-z").require();
            for (String path : ignored.split("\u0000")) {
                String first = path.split("/", 2)[0];
                if (path.isEmpty() || first.equalsIgnoreCase(".git") || first.equalsIgnoreCase(".acode")) continue;
                if (matcher.matches(path)) attempt("复制 " + path, () -> copy(target, path));
            }
        });
    }
    private void copy(Path target, String relative) throws IOException {
        Path source = root.resolve(relative).normalize();
        Path dest = target.resolve(relative).normalize();
        if (!source.startsWith(root) || !dest.startsWith(target) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !source.toRealPath().startsWith(root.toRealPath())) throw new IOException("复制路径越界或非普通文件");
        Path parent = dest.getParent();
        for (Path p = parent; p != null && p.startsWith(target); p = p.getParent())
            if (Files.exists(p) && !p.toRealPath().startsWith(target.toRealPath())) throw new IOException("目标父目录越界");
        Files.createDirectories(parent);
        Files.copy(source, dest); // deliberately no REPLACE_EXISTING
    }
}
