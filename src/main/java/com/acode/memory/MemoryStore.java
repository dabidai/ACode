package com.acode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两级记忆存储的组合门面：一记忆一文件 + 每根一份 {@code MEMORY.md} 索引（只存指针）。
 * 索引加载受行数与体积双上限约束，超限截断并附警告行；悬空指针只忽略并告警，绝不改写磁盘文件。
 */
public class MemoryStore {

    /** 索引注入上限：行数 */
    public static final int INDEX_MAX_LINES = 200;
    /** 索引注入上限：体积（字节，先到者生效） */
    public static final int INDEX_MAX_BYTES = 25 * 1024;
    public static final String INDEX_TRUNCATED_MARK = "（记忆索引超限，已截断）";
    /** 截断标记连同其前置换行占用的字节数：体积上限必须为它留位 */
    private static final int MARK_COST = byteLength("\n" + INDEX_TRUNCATED_MARK);

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final Pattern INDEX_LINE = Pattern.compile("^- \\[.+]\\(([^)]+)\\).*$");

    private final MemoryScope projectScope;
    private final MemoryScope userScope;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    public MemoryStore(MemoryScope projectScope, MemoryScope userScope) {
        this.projectScope = projectScope;
        this.userScope = userScope;
    }

    public MemoryScope projectScope() {
        return projectScope;
    }

    public MemoryScope userScope() {
        return userScope;
    }

    /** 按类别定位归属根 */
    public MemoryScope scopeFor(MemoryType type) {
        return type.scopeKind() == MemoryScope.Kind.PROJECT ? projectScope : userScope;
    }

    /** 列出一个根内的全部合法记忆；头部残缺/类型非法的文件跳过并告警 */
    public List<MemoryFile> list(MemoryScope scope) {
        List<MemoryFile> memories = new ArrayList<>();
        for (Path file : scope.files()) {
            Optional<MemoryFile> parsed = MemoryFile.read(file);
            if (parsed.isPresent()) {
                memories.add(parsed.get());
            } else {
                warn("记忆文件头部残缺或类型非法，已跳过：" + scope.label() + " " + file.getFileName());
            }
        }
        return memories;
    }

    /** 两级全部记忆：项目级在前、用户级在后（与注入顺序同源） */
    public List<MemoryFile> listAll() {
        List<MemoryFile> memories = new ArrayList<>(list(projectScope));
        memories.addAll(list(userScope));
        return memories;
    }

    /** 创建或更新一条记忆并重建该根索引；返回是否落盘成功 */
    public boolean write(MemoryType type, String name, String description, String body) {
        MemoryScope scope = scopeFor(type);
        MemoryFile memory = new MemoryFile(type, name, description, body);
        Path target = scope.resolve(memory.fileName());
        if (target == null) {
            warn("记忆文件名非法，已拒绝写入：" + memory.fileName());
            return false;
        }
        if (!scope.writable()) {
            // 项目级不可写 → 降级为只写用户级（不阻断、不抛错）
            warn(scope.label() + "记忆目录不可写，已降级写入用户级：" + memory.fileName());
            scope = userScope;
            target = scope.resolve(memory.fileName());
            if (target == null || !scope.writable()) {
                warn("用户级记忆目录也不可写，已放弃写入：" + memory.fileName());
                return false;
            }
        }
        try {
            Files.createDirectories(scope.root());
            Files.writeString(target, MemoryFile.render(memory), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("记忆写入失败：" + memory.fileName() + "：" + e.getMessage());
            return false;
        }
        rebuildIndex(scope);
        return true;
    }

    /** 删除一条记忆并重建索引；两个根都找一遍（可能因降级写在了用户级） */
    public boolean delete(String fileName) {
        if (fileName == null) {
            return false;
        }
        for (MemoryScope scope : List.of(projectScope, userScope)) {
            Path target = scope.resolve(fileName);
            if (target == null || !Files.isRegularFile(target)) {
                continue;
            }
            try {
                Files.delete(target);
            } catch (IOException e) {
                warn("记忆删除失败：" + fileName + "：" + e.getMessage());
                return false;
            }
            rebuildIndex(scope);
            return true;
        }
        return false;
    }

    /** 按文件名读取一条记忆（两个根都找一遍） */
    public Optional<MemoryFile> read(String fileName) {
        for (MemoryScope scope : List.of(projectScope, userScope)) {
            Path target = scope.resolve(fileName);
            if (target != null && Files.isRegularFile(target)) {
                return MemoryFile.read(target);
            }
        }
        return Optional.empty();
    }

    /** 按该根现有记忆重建 {@code MEMORY.md}：每行一条指针 {@code - [name](file) - description} */
    public void rebuildIndex(MemoryScope scope) {
        StringBuilder sb = new StringBuilder();
        for (MemoryFile memory : list(scope)) {
            sb.append(indexLine(memory)).append('\n');
        }
        try {
            Files.createDirectories(scope.root());
            Files.writeString(scope.indexPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("记忆索引写入失败：" + scope.indexPath() + "：" + e.getMessage());
        }
    }

    static String indexLine(MemoryFile memory) {
        return "- [" + memory.name() + "](" + memory.fileName() + ") - " + memory.description();
    }

    /** 一个根的索引视图：注入文本 + 供 /memory 展示的统计 */
    public record IndexView(String label, String text, int memoryCount, int indexLines,
                            int indexBytes, boolean truncated, int danglingPointers) {}

    /**
     * 读一个根的索引：悬空指针行忽略并告警，随后按行数与体积双上限截断（先到者生效）。
     * 体积按真实拼接字节复核，截断标记本身也占预算。磁盘文件不被改写——截断只发生在注入路径上。
     */
    public IndexView loadIndex(MemoryScope scope) {
        int memoryCount = list(scope).size();
        Path index = scope.indexPath();
        if (!Files.isRegularFile(index)) {
            return new IndexView(scope.label(), "", memoryCount, 0, 0, false, 0);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("记忆索引读取失败：" + index + "：" + e.getMessage());
            return new IndexView(scope.label(), "", memoryCount, 0, 0, false, 0);
        }

        List<String> kept = new ArrayList<>();
        int dangling = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Matcher m = INDEX_LINE.matcher(line.strip());
            if (m.matches() && !Files.isRegularFile(scope.root().resolve(m.group(1)))) {
                dangling++;
                continue;
            }
            kept.add(line.strip());
        }

        boolean truncated = false;
        if (kept.size() > INDEX_MAX_LINES) {
            kept = new ArrayList<>(kept.subList(0, INDEX_MAX_LINES));
            truncated = true;
        }
        // 体积按真实拼接字节复核：行数触发的截断也要为标记留位，否则"守住上限"后仍会超限
        if (truncated || byteLength(String.join("\n", kept)) > INDEX_MAX_BYTES) {
            truncated = true;
            kept = fitWithin(kept, INDEX_MAX_BYTES - MARK_COST);
        }
        if (dangling > 0) {
            warn(scope.label() + "索引有 " + dangling + " 条悬空指针，已忽略");
        }

        String text = String.join("\n", kept);
        if (truncated) {
            text = text.isEmpty() ? INDEX_TRUNCATED_MARK : text + "\n" + INDEX_TRUNCATED_MARK;
        }
        int finalBytes = byteLength(text);
        return new IndexView(scope.label(), text, memoryCount, kept.size(), finalBytes,
                truncated, dangling);
    }

    /** 从头逐行装入直到拼接字节超过 budget（行间换行算 1 字节，末尾无换行） */
    private static List<String> fitWithin(List<String> lines, int budget) {
        List<String> within = new ArrayList<>(lines.size());
        int used = 0;
        for (String line : lines) {
            int size = byteLength(line) + (within.isEmpty() ? 0 : 1);
            if (used + size > budget) {
                break;
            }
            within.add(line);
            used += size;
        }
        return within;
    }

    private static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 两级索引视图：项目级在前、用户级在后 */
    public List<IndexView> indexViews() {
        return List.of(loadIndex(projectScope), loadIndex(userScope));
    }

    /** 注入 system 提示的索引文本：项目级、用户级各带层级小标题依次拼接；两级皆空则为空串 */
    public String injectionText() {
        List<String> parts = new ArrayList<>(2);
        for (MemoryScope scope : List.of(projectScope, userScope)) {
            IndexView view = loadIndex(scope);
            if (!view.text().isBlank()) {
                parts.add(heading(scope) + "\n" + view.text());
            }
        }
        return String.join("\n", parts);
    }

    /** 模型可见的层级小标题（与 /memory 展示用的中文 label 分用） */
    private static String heading(MemoryScope scope) {
        return scope.kind() == MemoryScope.Kind.PROJECT ? "## Project" : "## User";
    }

    /** 取出并清空累计告警（供 UI 输出一次） */
    public List<String> drainWarnings() {
        List<String> drained = new ArrayList<>(warnings);
        warnings.clear();
        return drained;
    }

    /** 记录一条告警：同一消息在被取走前只记一次（提取每轮都会重列记忆，否则同一条告警会反复刷屏） */
    void warn(String message) {
        if (warnings.contains(message)) {
            return;
        }
        log.warn("{}", message);
        warnings.add(message);
    }
}
