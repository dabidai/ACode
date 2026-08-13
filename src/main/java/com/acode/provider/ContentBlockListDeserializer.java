package com.acode.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * ChatMessage 的 content 字段反序列化：兼容旧版纯文本会话文件
 * （content 为字符串 → 包成单个 TextBlock）与新版内容块数组（按 type 多态解析）。
 */
public class ContentBlockListDeserializer extends JsonDeserializer<List<ContentBlock>> {

    private static final TypeReference<List<ContentBlock>> TYPE = new TypeReference<>() {};

    @Override
    public List<ContentBlock> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return List.of(new TextBlock(p.getText()));
        }
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode node = mapper.readTree(p);
        return mapper.convertValue(node, TYPE);
    }
}
