package com.acode.session;

import com.acode.provider.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRecorderTest {

    @TempDir
    Path tempDir;

    private static final long NOW = 1_800_000_000L;

    private SessionStore store() {
        return new SessionStore(tempDir, () -> NOW);
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static long sessionFileCount(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).count();
        }
    }

    @Test
    void emptySessionCreatesNoFile() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.close();
        assertNull(recorder.file());
        assertEquals(0, sessionFileCount(store().dir()));
    }

    @Test
    void firstMessageCreatesFileWithOneLine() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.append(ChatMessage.of(USER, "第一问"));

        assertNotNull(recorder.file());
        assertEquals(List.of("第一问"), readContents(recorder.file()));
        assertEquals(NOW, SessionStore.readEntries(recorder.file()).get(0).ts());
    }

    @Test
    void secondMessageAppendsWithoutTouchingTheFirstLine() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.append(ChatMessage.of(USER, "第一问"));
        String firstLine = lines(recorder.file()).get(0);

        recorder.append(ChatMessage.of(ASSISTANT, "第一答"));

        List<String> raw = lines(recorder.file());
        assertEquals(2, raw.size(), "第二条应追加为第 2 行");
        assertEquals(firstLine, raw.get(0), "前一行内容逐字不变");
        assertEquals(List.of("第一问", "第一答"), readContents(recorder.file()));
    }

    @Test
    void writeFailureDoesNotThrowAndKeepsHistoryUsable() throws IOException {
        // 让 <项目根>/.acode 是一个普通文件：createDirectories 必然失败，模拟写盘不可用
        Path projectRoot = tempDir.resolve("proj");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve(".acode"), "not a directory");
        SessionRecorder recorder = new SessionRecorder(new SessionStore(projectRoot, () -> NOW), () -> NOW);

        recorder.append(ChatMessage.of(USER, "写不进去也不该抛"));

        assertNull(recorder.file(), "写盘失败不应留下半成品指向");
        assertEquals(0, sessionFileCount(projectRoot.resolve(".acode").resolve("sessions")));
    }

    @Test
    void bindAppendsIntoTheTargetFile() throws IOException {
        Files.createDirectories(store().dir());
        Path target = store().resolve("20260101-000000-abcd");
        Files.writeString(target, SessionCodec.encode(ChatMessage.of(USER, "旧行"), NOW) + "\n");

        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.bind(target);
        recorder.append(ChatMessage.of(USER, "续写"));

        assertEquals(target, recorder.file());
        assertEquals(List.of("旧行", "续写"), readContents(target));
        assertEquals(1, sessionFileCount(store().dir()), "续写不应新建第二个文件");
    }

    @Test
    void continuedSessionMovesToTheFrontOfTheList() throws IOException {
        AtomicLong clock = new AtomicLong(NOW);
        SessionStore store = new SessionStore(tempDir, clock::get);
        Files.createDirectories(store.dir());
        writeOneLine(store.resolve("20260101-000000-aaaa"), "更旧", NOW - 100);
        writeOneLine(store.resolve("20260101-000001-bbbb"), "更新", NOW - 50);
        assertEquals("20260101-000001-bbbb", store.list().get(0).id(), "更新的一条原本排在前面");

        SessionRecorder recorder = new SessionRecorder(store, clock::get);
        recorder.bind(store.resolve("20260101-000000-aaaa")); // 恢复续写：句柄切到被选中的会话
        clock.set(NOW + 30); // 续写发生在稍后
        recorder.append(ChatMessage.of(USER, "续写"));

        List<Session> sessions = store.list();
        assertEquals("20260101-000000-aaaa", sessions.get(0).id(), "续写后该会话升到列表最前");
        assertEquals(NOW + 30, sessions.get(0).lastActiveEpochSeconds(), "末行 ts 已更新为落盘时刻");
        assertEquals(2, sessionFileCount(store.dir()), "续写不应新建文件");
    }

    private static void writeOneLine(Path file, String text, long ts) throws IOException {
        Files.writeString(file, SessionCodec.encode(ChatMessage.of(USER, text), ts) + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void closeIsIdempotent() {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.append(ChatMessage.of(USER, "一"));
        recorder.close();
        recorder.close();
        recorder.append(ChatMessage.of(USER, "二"));
        assertEquals(List.of("一", "二"), readContents(recorder.file()), "关闭后追加应重新打开同一文件");
    }

    @Test
    void rewriteReplacesWholeFileWithoutResidue() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.append(ChatMessage.of(USER, "旧一"));
        recorder.append(ChatMessage.of(ASSISTANT, "旧二"));
        recorder.append(ChatMessage.of(USER, "旧三"));

        recorder.rewrite(List.of(ChatMessage.of(USER, "重建一"), ChatMessage.of(ASSISTANT, "重建二")));

        assertEquals(2, lines(recorder.file()).size(), "重写后行数 = 重建后条数");
        assertEquals(List.of("重建一", "重建二"), readContents(recorder.file()));
        assertEquals(1, sessionFileCount(store().dir()), "重写不得留下临时文件");
    }

    @Test
    void appendAfterRewriteLandsAfterRebuiltHistory() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.append(ChatMessage.of(USER, "旧"));
        recorder.rewrite(List.of(ChatMessage.of(USER, "重建")));

        recorder.append(ChatMessage.of(USER, "追加"));

        assertEquals(List.of("重建", "追加"), readContents(recorder.file()));
    }

    @Test
    void rewriteOnNeverAppendedSessionCreatesFile() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.rewrite(List.of(ChatMessage.of(USER, "首次落盘")));

        assertNotNull(recorder.file());
        assertEquals(List.of("首次落盘"), readContents(recorder.file()));
    }

    @Test
    void failedRewriteKeepsOriginalLinesUntouched() throws IOException {
        Files.createDirectories(store().dir());
        Path target = store().resolve("20260101-000000-abcd");
        Files.writeString(target, SessionCodec.encode(ChatMessage.of(USER, "原样"), NOW) + "\n");

        // 让 store.dir() 变成"父路径被普通文件占住"→ 建临时文件必然失败
        Path brokenRoot = tempDir.resolve("broken");
        Files.createDirectories(brokenRoot);
        Files.writeString(brokenRoot.resolve(".acode"), "not a directory");
        SessionRecorder recorder = new SessionRecorder(new SessionStore(brokenRoot, () -> NOW), () -> NOW);
        recorder.bind(target);

        recorder.rewrite(List.of(ChatMessage.of(USER, "不该写进去")));

        assertEquals(List.of("原样"), readContents(target), "重写失败必须保持原文件逐行不变");
    }

    @Test
    void suspendedRewriteIsIgnored() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.append(ChatMessage.of(USER, "原样"));

        recorder.suspend();
        recorder.rewrite(List.of(ChatMessage.of(USER, "加载时不该重写")));
        assertEquals(List.of("原样"), readContents(recorder.file()), "挂起期间的重写被忽略");

        recorder.resume();
        recorder.rewrite(List.of(ChatMessage.of(USER, "恢复后可重写")));
        assertEquals(List.of("恢复后可重写"), readContents(recorder.file()));
    }

    @Test
    void appendNotifiesNothingWhenEncodingFails() throws IOException {
        SessionRecorder recorder = new SessionRecorder(store(), () -> NOW);
        recorder.append(new ChatMessage(null, List.of()));
        assertNull(recorder.file(), "无法编码的消息不应创建会话文件");
        assertFalse(Files.exists(store().dir()));
    }

    /** 读出文件里每条消息的纯文本内容，用于断言落盘顺序 */
    private static List<String> readContents(Path file) {
        return SessionStore.readEntries(file).stream()
                .map(entry -> entry.message().content())
                .toList();
    }
}
