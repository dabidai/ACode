package com.acode.prompt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects a one-time environment snapshot (working directory, platform, shell,
 * git repo/branch, model, date). {@code detect(model)} reads the JVM working
 * directory; the overload takes an explicit workDir for testability.
 */
public final class EnvironmentDetector {

    private EnvironmentDetector() {}

    public record EnvironmentSnapshot(
            String workDir, String os, String arch, String shell,
            boolean isGitRepo, String gitBranch, String model, String date) {}

    /** Detect for the current JVM working directory. */
    public static EnvironmentSnapshot detect(String model) {
        return detect(model, System.getProperty("user.dir"));
    }

    static EnvironmentSnapshot detect(String model, String workDir) {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "unknown");
        String shell = resolveShell(System.getenv("SHELL"));

        boolean isGitRepo = false;
        String gitBranch = "";
        try {
            if ("true".equals(runGit(workDir, "rev-parse", "--is-inside-work-tree"))) {
                isGitRepo = true;
            }
        } catch (Exception ignored) {
            // not a git repo or git unavailable
        }
        if (isGitRepo) {
            try {
                String branch = runGit(workDir, "rev-parse", "--abbrev-ref", "HEAD");
                if (branch != null && !branch.isEmpty()) {
                    gitBranch = branch;
                }
            } catch (Exception ignored) {
                // branch detection failed
            }
        }
        return new EnvironmentSnapshot(workDir, os, arch, shell, isGitRepo,
                gitBranch, model, LocalDate.now().toString());
    }

    static String resolveShell(String shellEnv) {
        return shellEnv == null || shellEnv.isEmpty() ? "bash" : shellEnv;
    }

    private static String runGit(String workDir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(workDir);
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String line;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            line = reader.readLine();
        }
        p.waitFor();
        return line == null ? "" : line.strip();
    }

    /** Render the snapshot as a "# Environment" field list. */
    public static String render(EnvironmentSnapshot env) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Environment\n");
        sb.append(" - Working directory: ").append(env.workDir()).append('\n');
        sb.append(" - Platform: ").append(env.os()).append('/').append(env.arch()).append('\n');
        sb.append(" - Shell: ").append(env.shell()).append('\n');
        sb.append(" - Is git repo: ").append(env.isGitRepo());
        if (env.isGitRepo() && env.gitBranch() != null && !env.gitBranch().isEmpty()) {
            sb.append('\n').append(" - Git branch: ").append(env.gitBranch());
        }
        if (env.model() != null && !env.model().isEmpty()) {
            sb.append('\n').append(" - Model: ").append(env.model());
        }
        sb.append('\n').append(" - Date: ").append(env.date());
        return sb.toString();
    }
}
