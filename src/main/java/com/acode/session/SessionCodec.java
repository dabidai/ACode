package com.acode.session;

import com.acode.provider.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * 会话 JSONL 的逐行编解码：一行一个 JSON 对象，字段 {@code role} / {@code content} / {@code ts}。
 * 解析一律 fail soft——非法 JSON、缺字段、{@code ts} 非整数都返回空，由调用方跳过该行，
 * 半个 JSON（进程被杀留下的半行）因此不会让整份会话读不出来。
 */
public final class SessionCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SessionCodec() {}

    /** 编码一条消息为一行文本（不含换行）；无角色或编码失败返回 null（调用方跳过该行） */
    public static String encode(ChatMessage message, long ts) {
        if (message == null || message.role() == null) {
            return null;
        }
        try {
            // 走 ChatMessage 自身的序列化路径：content 的声明类型是 List<ContentBlock>，
            // 只有经它才能写出多态判别用的 type 字段（valueToTree 拿不到声明类型，会丢）
            ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsString(message));
            node.put("ts", ts);
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 解码一行；任何不完整/不合法都返回空 */
    public static Optional<SessionEntry> decode(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (!(node instanceof ObjectNode object)) {
            return Optional.empty();
        }
        JsonNode ts = object.get("ts");
        JsonNode role = object.get("role");
        JsonNode content = object.get("content");
        if (ts == null || !ts.isIntegralNumber() || role == null || !role.isTextual()
                || content == null) {
            return Optional.empty();
        }
        ObjectNode messageNode = object.deepCopy();
        messageNode.remove("ts");
        try {
            // 复用 ChatMessage 自身的反序列化路径：content 既可为块数组，也可为纯字符串
            return Optional.of(new SessionEntry(
                    JSON.treeToValue(messageNode, ChatMessage.class), ts.asLong()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
