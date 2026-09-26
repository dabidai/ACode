package com.acode.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Discovery is explicit; hot reads are restricted to the previously selected source. */
public final class SkillRepository {
    private final Path project, home;
    private final ClassLoader resources;
    private final Consumer<String> warning;
    private final java.util.concurrent.ConcurrentLinkedQueue<String> warnings = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final SkillParser parser = new SkillParser();
    private final Map<SkillSource, SkillDefinition> cache = new HashMap<>();
    private Map<String, SkillDefinition> definitions = Map.of();

    public SkillRepository(Path project, Path home, Consumer<String> warning) {
        this(project, home, SkillRepository.class.getClassLoader(), warning);
    }
    public SkillRepository(Path project, Path home, ClassLoader resources, Consumer<String> warning) {
        this.project = project; this.home = home; this.resources = resources;
        this.warning = message -> {
            org.slf4j.LoggerFactory.getLogger(SkillRepository.class).warn("{}", message);
            warnings.add(message);
            warning.accept(message);
        };
    }
    public synchronized void reload() {
        Map<String, SkillDefinition> next = new TreeMap<>();
        try (var in = resources.getResourceAsStream("skills/index.txt")) {
            if (in != null) for (String entry : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (entry.isBlank() || entry.startsWith("#")) continue;
                String name = entry.strip();
                var source = new SkillSource("builtin", "classpath:skills/" + name + "/SKILL.md", null);
                read(source, name, false).ifPresent(d -> next.put(d.name(), d));
            }
        } catch (IOException | RuntimeException e) { warning.accept("skills/index.txt: " + e.getMessage()); }
        scan(home, "user", next);
        scan(project, "project", next);
        definitions = Collections.unmodifiableMap(next);
    }
    private void scan(Path root, String layer, Map<String, SkillDefinition> next) {
        if (root == null) return;
        Path dir = root.resolve(".acode/skills");
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            // Single files first, directories second: directory form wins within a layer.
            var paths = stream.sorted(Comparator.<Path, Boolean>comparing(Files::isDirectory)
                    .thenComparing(p -> p.getFileName().toString())).toList();
            Set<String> seen = new HashSet<>();
            for (Path path : paths) {
                boolean directory = Files.isDirectory(path);
                String filename = path.getFileName().toString();
                if (!directory && !filename.endsWith(".md")) continue;
                Path file = directory ? path.resolve("SKILL.md") : path;
                if (!Files.isRegularFile(file)) continue;
                String name = directory ? filename : filename.substring(0, filename.length() - 3);
                read(SkillSource.file(layer, file), name, false).ifPresent(d -> {
                    if (!seen.add(name)) warning.accept("Duplicate Skill " + name + ": " + file + " wins");
                    next.put(name, d);
                });
            }
        } catch (IOException | RuntimeException e) { warning.accept(dir + ": " + e.getMessage()); }
    }
    private Optional<SkillDefinition> read(SkillSource source, String name, boolean fallback) {
        try {
            String text;
            if (source.file() != null) text = Files.readString(source.file());
            else try (var in = resources.getResourceAsStream(source.location().substring("classpath:".length()))) {
                if (in == null) throw new IOException("missing resource");
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            SkillDefinition definition = parser.parse(text, name, source);
            cache.put(source, definition);
            return Optional.of(definition);
        } catch (IOException | RuntimeException e) {
            warning.accept("Skill " + name + " (" + source.location() + "): " + e.getMessage());
            return Optional.ofNullable(fallback ? cache.get(source) : null);
        }
    }
    public synchronized Optional<SkillDefinition> load(String name) {
        SkillDefinition definition = definitions.get(name);
        return definition == null ? Optional.empty() : read(definition.source(), name, true);
    }
    public synchronized List<SkillDefinition> list() { return List.copyOf(definitions.values()); }
    public List<String> drainWarnings() {
        List<String> result = new ArrayList<>();
        for (String message; (message = warnings.poll()) != null;) result.add(message);
        return result;
    }
    public synchronized String indexText() {
        return String.join("\n", definitions.values().stream()
                .map(d -> d.name() + ": " + singleLine(d.description())).toList());
    }
    public static String singleLine(String text) { return text.replaceAll("[\\p{C}\\p{Z}\\s]+", " ").strip(); }
}
