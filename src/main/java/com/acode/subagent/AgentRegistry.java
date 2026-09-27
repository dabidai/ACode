package com.acode.subagent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Loaded once per application; later sources override earlier ones. */
public final class AgentRegistry {
    private final Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    public AgentRegistry(Path project, Path home, boolean verification) {
        loadPlugins();
        try (var stream = getClass().getResourceAsStream("/agents/index.txt")) {
            if (stream == null) throw new IOException("缺少 agents/index.txt");
            for (String file : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank()).toList()) {
                try (var input = getClass().getResourceAsStream("/agents/" + file)) {
                    if (input == null) throw new IOException("缺少 " + file);
                    load(new String(input.readAllBytes(), StandardCharsets.UTF_8), "classpath:agents/" + file, "builtin");
                }
            }
        } catch (IOException e) { warnings.add("加载内置 Agent 失败：" + e.getMessage()); }
        loadDirectory(home.resolve(".acode/agents"), "user");
        loadDirectory(project.resolve(".acode/agents"), "project");
        if (!verification) definitions.remove("Verification");
    }
    private void loadPlugins() { /* Reserved lowest-priority source. */ }
    private void loadDirectory(Path directory, String source) {
        if (!Files.exists(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                try { load(Files.readString(path), path.toString(), source); }
                catch (IOException e) { warnings.add("跳过 Agent 定义 " + path + "：" + e.getMessage()); }
            }
        } catch (IOException e) { warnings.add("跳过 Agent 定义 " + directory + "：" + e.getMessage()); }
    }
    private void load(String text, String path, String source) {
        try {
            var definition = new AgentDefinitionParser().parse(text, path, source, warnings::add);
            definitions.put(definition.agentType(), definition);
        } catch (RuntimeException e) { warnings.add("跳过 Agent 定义 " + path + "：" + e.getMessage()); }
    }
    public AgentDefinition get(String name) { return definitions.get(name); }
    public List<String> names() { return List.copyOf(definitions.keySet()); }
    public List<String> warnings() { return List.copyOf(warnings); }
}
