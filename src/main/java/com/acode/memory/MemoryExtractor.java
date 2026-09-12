package com.acode.memory;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.ProviderException;
import com.acode.provider.TextBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动记忆提取：一次独立的模型调用（不携带工具、thinking 关闭、独立 max_tokens、直接经请求构造器，
 * 不经 {@code Conversation} 的历史组装，避免被裁剪或注入轮次提醒），把索引 + 现有记忆清单 +
 * 最近一轮对话发给模型，解析结构化操作列表后逐项落盘。
 *
 * <p>JSON 坏掉即整轮放弃（零写入）；数组内单个元素非法只跳过该元素并计数上报；
 * 单条落盘失败只跳过该项，其余项照常。
 */
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一轮提取的结果统计 */
    public record Outcome(int created, int updated, int deleted, boolean failed) {
        static Outcome failure() {
            return new Outcome(0, 0, 0, true);
        }

        static Outcome empty() {
            return new Outcome(0, 0, 0, false);
        }

        public boolean nothing() {
            return created == 0 && updated == 0 && deleted == 0;
        }
    }

    private final ChatProvider provider;
    private final MemoryStore store;
    private final Conversation conversation;
    private final AtomicInteger calls = new AtomicInteger();

    public MemoryExtractor(ChatProvider provider, MemoryStore store, Conversation conversation) {
        this.provider = provider;
        this.store = store;
        this.conversation = conversation;
    }

    /** 已发出的提取调用次数（测试断言"运行中不再触发"用） */
    public int callCount() {
        return calls.get();
    }

    /** 一轮提取解析出的操作：{@code operations} 为合法项，{@code skipped} 为被跳过的非法元素数 */
    public record Parsed(List<MemoryOperation> operations, int skipped) {}

    /** 发一次提取请求并解析；调用失败或 JSON 坏掉都返回空（调用方据此零写入） */
    public Optional<Parsed> extract() {
        calls.incrementAndGet();
        Collector collector = new Collector();
        provider.streamChat(request(), collector);
        if (collector.error != null) {
            log.warn("记忆提取调用失败：{}", collector.error.getMessage());
            return Optional.empty();
        }
        return parse(collector.text.toString());
    }

    /** 同步跑一轮：提取 + 落盘 */
    public Outcome run() {
        Optional<Parsed> parsed = extract();
        if (parsed.isEmpty()) {
            return Outcome.failure();
        }
        return apply(parsed.get());
    }

    /** 逐项落盘；非法元素在解析时已跳过，单条落盘失败只跳过该项，其余项照常 */
    public Outcome apply(Parsed parsed) {
        if (parsed.skipped() > 0) {
            store.warn("记忆提取有 " + parsed.skipped() + " 条操作格式非法，已跳过");
        }
        if (parsed.operations().isEmpty() && parsed.skipped() > 0) {
            return Outcome.failure(); // 一个合法项都没有
        }
        int created = 0;
        int updated = 0;
        int deleted = 0;
        for (MemoryOperation op : parsed.operations()) {
            switch (op.op()) {
                case DELETE -> {
                    if (store.delete(op.fileName())) {
                        deleted++;
                    }
                }
                case CREATE, UPDATE -> {
                    boolean existed = store.read(op.fileName()).isPresent();
                    if (store.write(op.type(), op.name(), op.description(), op.body())) {
                        if (existed) {
                            updated++;
                        } else {
                            created++;
                        }
                    }
                }
            }
        }
        return new Outcome(created, updated, deleted, false);
    }

    /** 提取请求：SYSTEM 指令 + USER 输入；不设 tools、thinking 关闭、max_tokens 用独立常量 */
    private ChatRequest request() {
        return ChatRequest.builder()
                .model(conversation.model())
                .thinking(false)
                .maxTokens(MemoryExtractionPrompt.MAX_TOKENS)
                .messages(List.of(
                        ChatMessage.of(ChatMessage.Role.SYSTEM, MemoryExtractionPrompt.instruction()),
                        ChatMessage.of(ChatMessage.Role.USER, extractionInput())))
                .build();
    }

    private String extractionInput() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Current memory index\n");
        String index = store.injectionText();
        sb.append(index.isBlank() ? "(empty)" : index).append("\n\n");
        sb.append("## Existing memories\n");
        List<MemoryFile> memories = store.listAll();
        if (memories.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (MemoryFile memory : memories) {
                sb.append("- ").append(memory.fileName()).append(": ")
                        .append(memory.description()).append('\n');
            }
        }
        sb.append("\n## Most recent exchange\n");
        List<ChatMessage> turn = recentTurn();
        if (turn.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (ChatMessage message : turn) {
                sb.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 最近一轮对话：从最后一条「带文本的用户消息」到历史末尾 */
    List<ChatMessage> recentTurn() {
        List<ChatMessage> history = conversation.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (message.role() == ChatMessage.Role.USER
                    && message.blocks().stream().anyMatch(b -> b instanceof TextBlock)) {
                return List.copyOf(history.subList(i, history.size()));
            }
        }
        return List.of();
    }

    /**
     * 解析模型输出：JSON 坏掉、不是数组 → 空（整轮放弃）；
     * 数组内单个元素不合法（op/type/name/description 结构不符）→ 只跳过该元素并计数，其余照常落盘。
     */
    static Optional<Parsed> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        JsonNode array;
        try {
            array = JSON.readTree(text.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (array == null || !array.isArray()) {
            return Optional.empty();
        }
        List<MemoryOperation> operations = new ArrayList<>(array.size());
        int skipped = 0;
        for (JsonNode node : array) {
            MemoryOperation operation = toOperation(node);
            if (operation == null) {
                skipped++;
                continue;
            }
            operations.add(operation);
        }
        return Optional.of(new Parsed(operations, skipped));
    }

    private static MemoryOperation toOperation(JsonNode node) {
        String opText = text(node, "op");
        MemoryOperation.Op op = opText == null ? null : switch (opText) {
            case "create" -> MemoryOperation.Op.CREATE;
            case "update" -> MemoryOperation.Op.UPDATE;
            case "delete" -> MemoryOperation.Op.DELETE;
            default -> null;
        };
        MemoryType type = MemoryType.fromSlug(text(node, "type"));
        MemoryOperation operation = new MemoryOperation(op, type, text(node, "name"),
                text(node, "description"), text(node, "body"));
        return operation.valid() ? operation : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 同步收集提取回复文本 */
    private static final class Collector implements ChatListener {
        final StringBuilder text = new StringBuilder();
        ProviderException error;

        @Override
        public void onDelta(String delta) {
            text.append(delta);
        }

        @Override
        public void onError(ProviderException e) {
            this.error = e;
        }
    }
}
