package com.acode.session;

import com.acode.provider.ChatMessage;
import com.acode.provider.ContentBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 恢复会话的准备：逐行解析（坏行跳过）→ 消息链完整性截断 → 时间跨度提醒。
 * 第三步的"是否压缩"不在这里判定，交给调用方的压缩守卫（history 就位后按同一触发点判）。
 */
public final class SessionLoader {

    /** 时间跨度提醒阈值：距上次活跃超过 24 小时 */
    public static final long STALE_SECONDS = 86_400L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public record Loaded(List<ChatMessage> messages, long lastActiveEpochSeconds,
                         Optional<String> staleReminder) {}

    private SessionLoader() {}

    /** 读取会话文件并完成截断与提醒判定；文件缺失/为空/全坏行都按空会话返回 */
    public static Loaded load(Path file) {
        return load(file, Instant.now().getEpochSecond());
    }

    /** 包可见时钟注入版：测试用固定"现在"驱动时间跨度判定 */
    public static Loaded load(Path file, long nowSeconds) {
        List<SessionEntry> entries = SessionStore.readEntries(file);
        long lastTs = entries.isEmpty() ? 0L : entries.get(entries.size() - 1).ts();
        List<ChatMessage> messages = truncateToCompleteChain(
                entries.stream().map(SessionEntry::message).toList());
        return new Loaded(messages, lastTs, staleReminder(lastTs, nowSeconds));
    }

    /**
     * 截断到最后一个「此前出现的全部 tool_use 都已配对 tool_result」的位置，其后残缺的调用对整段丢弃。
     * 保证恢复出的历史能直接发请求（无孤儿工具块）；干净时返回原列表引用。
     */
    static List<ChatMessage> truncateToCompleteChain(List<ChatMessage> messages) {
        Set<String> pending = new LinkedHashSet<>();
        int lastComplete = 0;
        for (int i = 0; i < messages.size(); i++) {
            for (ContentBlock block : messages.get(i).blocks()) {
                if (block instanceof ToolUseBlock use) {
                    pending.add(use.id());
                } else if (block instanceof ToolResultBlock result) {
                    pending.remove(result.toolUseId());
                }
            }
            if (pending.isEmpty()) {
                lastComplete = i + 1;
            }
        }
        return lastComplete == messages.size() ? messages : List.copyOf(messages.subList(0, lastComplete));
    }

    /** 末行时间戳距今超过阈值 → 产出一段提醒文本（交给调用方作为恢复后首轮的轮次提醒注入） */
    private static Optional<String> staleReminder(long lastTs, long nowSeconds) {
        if (lastTs <= 0 || nowSeconds - lastTs <= STALE_SECONDS) {
            return Optional.empty();
        }
        long elapsed = nowSeconds - lastTs;
        String stamp = LocalDateTime.ofInstant(Instant.ofEpochSecond(lastTs), ZoneId.systemDefault())
                .format(STAMP);
        return Optional.of("Last session activity: " + stamp + " (" + elapsed / 3600
                + " hours ago). The project may have changed since then — re-read the relevant "
                + "files before relying on earlier findings.");
    }
}
