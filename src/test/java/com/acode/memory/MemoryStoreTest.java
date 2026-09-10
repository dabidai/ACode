package com.acode.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private MemoryScope project;
    private MemoryScope user;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        project = MemoryScope.project(tempDir.resolve("proj"));
        user = MemoryScope.user(tempDir.resolve("home"));
        store = new MemoryStore(project, user);
    }

    private static String longText(int length) {
        return "字".repeat(length);
    }

    private void seed(MemoryScope scope, MemoryType type, int count, String description)
            throws IOException {
        Files.createDirectories(scope.root());
        for (int i = 0; i < count; i++) {
            MemoryFile memory = new MemoryFile(type, "m" + i, description, "正文");
            Files.writeString(scope.root().resolve(memory.fileName()),
                    MemoryFile.render(memory), StandardCharsets.UTF_8);
        }
        store.rebuildIndex(scope);
    }

    @Test
    void writesFourTypesIntoTheirOwnRoots() {
        assertTrue(store.write(MemoryType.USER, "any", "偏好 any", "正文"));
        assertTrue(store.write(MemoryType.FEEDBACK, "testing", "反馈", "正文"));
        assertTrue(store.write(MemoryType.PROJECT, "deadline", "项目知识", "正文"));
        assertTrue(store.write(MemoryType.REFERENCE, "links", "参考", "正文"));

        assertTrue(Files.isRegularFile(user.root().resolve("user-any.md")));
        assertTrue(Files.isRegularFile(user.root().resolve("feedback-testing.md")));
        assertTrue(Files.isRegularFile(project.root().resolve("project-deadline.md")));
        assertTrue(Files.isRegularFile(project.root().resolve("reference-links.md")));
    }

    @Test
    void indexLineUsesTheFixedPointerFormat() {
        store.write(MemoryType.PROJECT, "deadline", "数据库迁移工具选型", "正文");
        String index = read(project.indexPath());
        assertEquals("- [deadline](project-deadline.md) - 数据库迁移工具选型\n", index);
    }

    @Test
    void writeThenReadRoundTrips() {
        store.write(MemoryType.USER, "any", "偏好 any", "**Why**：简单\n**How to apply**：默认用 any");
        MemoryFile memory = store.read("user-any.md").orElseThrow();
        assertEquals("偏好 any", memory.description());
        assertTrue(memory.body().contains("**How to apply**："));
        assertTrue(store.read("nope.md").isEmpty());
    }

    @Test
    void updateOverwritesExistingMemoryAndKeepsOneFile() {
        store.write(MemoryType.USER, "any", "旧摘要", "旧正文");
        store.write(MemoryType.USER, "any", "新摘要", "新正文");

        List<MemoryFile> memories = store.list(user);
        assertEquals(1, memories.size(), "同名记忆只应有一个文件");
        assertEquals("新摘要", memories.get(0).description());
        assertTrue(read(user.indexPath()).contains("新摘要"));
    }

    @Test
    void deleteRemovesFileAndIndexPointer() {
        store.write(MemoryType.PROJECT, "deadline", "项目知识", "正文");
        assertTrue(store.delete("project-deadline.md"));

        assertTrue(store.list(project).isEmpty());
        assertFalse(read(project.indexPath()).contains("project-deadline.md"));
        assertFalse(store.delete("project-deadline.md"), "重复删除应返回 false");
    }

    @Test
    void listSkipsFilesWithMalformedHeaders() throws IOException {
        store.write(MemoryType.USER, "good", "正常", "正文");
        Files.writeString(user.root().resolve("user-broken.md"), "没有 frontmatter");
        Files.writeString(user.root().resolve("user-unknown.md"),
                "---\nname: x\ndescription: y\ntype: mystery\n---\n");

        assertEquals(List.of("good"), store.list(user).stream().map(MemoryFile::name).toList());
        assertTrue(store.drainWarnings().stream().anyMatch(w -> w.contains("头部残缺")));
    }

    @Test
    void injectionTextPutsProjectLevelFirst() {
        store.write(MemoryType.PROJECT, "deadline", "项目知识摘要", "正文");
        store.write(MemoryType.USER, "any", "用户偏好摘要", "正文");

        String text = store.injectionText();
        int projectAt = text.indexOf("项目知识摘要");
        int userAt = text.indexOf("用户偏好摘要");
        assertTrue(projectAt >= 0 && userAt >= 0, text);
        assertTrue(projectAt < userAt, "项目级索引必须排在用户级之前：" + text);
        assertFalse(text.contains("MEMORY.md"), "索引只存指针，不含索引文件名本身");
    }

    @Test
    void injectionTextIsEmptyWhenNothingIsStored() {
        assertEquals("", store.injectionText());
    }

    @Test
    void indexLimitsMatchTheDocumentedDefaults() {
        assertEquals(200, MemoryStore.INDEX_MAX_LINES, "索引行数上限 200");
        assertEquals(25 * 1024, MemoryStore.INDEX_MAX_BYTES, "索引体积上限 25 KB");
        assertEquals("（记忆索引超限，已截断）", MemoryStore.INDEX_TRUNCATED_MARK);
    }

    @Test
    void indexTruncatesByLineLimit() throws IOException {
        seed(user, MemoryType.USER, MemoryStore.INDEX_MAX_LINES + 10, "短摘要");
        MemoryStore.IndexView view = store.loadIndex(user);

        assertTrue(view.truncated());
        assertEquals(MemoryStore.INDEX_MAX_LINES, view.indexLines());
        assertTrue(view.text().endsWith(MemoryStore.INDEX_TRUNCATED_MARK), view.text());
    }

    @Test
    void indexTruncatesByByteLimit() throws IOException {
        seed(user, MemoryType.USER, 30, longText(1000));
        MemoryStore.IndexView view = store.loadIndex(user);

        assertTrue(view.truncated());
        assertTrue(view.indexLines() < 30, "体积超限应先于行数上限生效：" + view.indexLines());
        assertTrue(view.indexBytes() <= MemoryStore.INDEX_MAX_BYTES);
        assertTrue(view.text().endsWith(MemoryStore.INDEX_TRUNCATED_MARK));
    }

    @Test
    void danglingPointerIsIgnoredWithoutRewritingTheFile() throws IOException {
        store.write(MemoryType.USER, "any", "偏好", "正文");
        Path index = user.indexPath();
        String withDangling = read(index) + "- [ghost](user-ghost.md) - 指向已删文件\n";
        Files.writeString(index, withDangling, StandardCharsets.UTF_8);

        MemoryStore.IndexView view = store.loadIndex(user);

        assertEquals(1, view.danglingPointers());
        assertFalse(view.text().contains("user-ghost.md"), "悬空指针行应被忽略");
        assertEquals(withDangling, read(index), "加载不自动改写索引文件");
        assertTrue(store.drainWarnings().stream().anyMatch(w -> w.contains("悬空指针")));
    }

    @Test
    void unwritableProjectRootDegradesToUserRoot() throws IOException {
        Path blocked = tempDir.resolve("blocked");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve(".acode"), "not a directory");
        MemoryStore degraded = new MemoryStore(MemoryScope.project(blocked), user);

        assertTrue(degraded.write(MemoryType.PROJECT, "deadline", "项目知识", "正文"),
                "项目级不可写不应抛错，应降级");
        assertTrue(Files.isRegularFile(user.root().resolve("project-deadline.md")),
                "降级后应落在用户级根");
        assertTrue(degraded.drainWarnings().stream().anyMatch(w -> w.contains("降级")));
    }

    @Test
    void rejectsIllegalFileNamesOnWrite() {
        assertFalse(store.write(MemoryType.USER, "../escape", "摘要", "正文"));
        assertTrue(store.drainWarnings().stream().anyMatch(w -> w.contains("文件名非法")));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
