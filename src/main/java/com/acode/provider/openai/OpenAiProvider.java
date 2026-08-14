package com.acode.provider.openai;

import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.ContentBlock;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.NetworkException;
import com.acode.provider.ProviderException;
import com.acode.provider.ProviderHttpClient;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.sse.SseParser;
import com.acode.tool.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容后端实现（chat completions + SSE）。
 * 适配 OpenAI 官方与 DeepSeek 等兼容服务；reasoning_content（如 deepseek-reasoner）不输出。
 * base_url 需含 /v1 前缀（如 https://api.openai.com/v1 或 https://api.deepseek.com/v1）。
 * 工具：请求带 tools 数组；消息按 content block 转换（assistant tool_use → tool_calls、
 * user tool_result → 逐条 role:"tool" 消息）。
 */
public class OpenAiProvider implements ChatProvider {

    private static final String ENDPOINT = "/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final boolean teeEnabled;

    public OpenAiProvider(String baseUrl, String apiKey, boolean teeEnabled) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.teeEnabled = teeEnabled;
    }

    @Override
    public void streamChat(ChatRequest request, ChatListener listener) {
        try {
            String body = buildBody(request);
            ProviderHttpClient.Result result = ProviderHttpClient.send(
                    baseUrl + ENDPOINT, body,
                    Map.of("Authorization", "Bearer " + apiKey));
            try (InputStream in = result.body()) {
                OpenAiSseParser parser = new OpenAiSseParser();
                SseParser.parse(in, (eventType, data) -> {
                    sseDiag(data);
                    parser.handle(data, listener);
                });
            }
        } catch (ProviderException e) {
            listener.onError(e);
        } catch (IOException e) {
            listener.onError(new NetworkException("读取响应流失败：" + e.getMessage(), e));
        }
    }

    /** 诊断：tee 开启时把每条原始 SSE data 行写入独立日志（定位 API 内容 vs 解析层）。 */
    private void sseDiag(String data) {
        try {
            if (!teeEnabled) {
                return;
            }
            String line = "sse :: " + data.replace("\r", "\\r").replace("\n", "\\n") + "\n";
            Files.write(Path.of("acode-sse.log"), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 诊断日志失败不影响主流程
        }
    }

    /** 包可见供单测断言请求体结构 */
    String buildBody(ChatRequest request) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", request.model());
            root.put("stream", true);
            root.put("max_tokens", request.maxTokens());
            ArrayNode messages = root.putArray("messages");
            for (ChatMessage message : request.messages()) {
                appendOpenAiMessage(messages, message);
            }
            if (!request.tools().isEmpty()) {
                root.set("tools", toOpenAiTools(request.tools()));
            }
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("请求体构建失败：" + e.getMessage(), e);
        }
    }

    /** 内部结构化消息 → OpenAI messages 数组（一条内部消息可展开成多条） */
    private void appendOpenAiMessage(ArrayNode messages, ChatMessage message)
            throws JsonProcessingException {
        String text = message.content();
        List<ToolUseBlock> toolUses = new ArrayList<>();
        List<ToolResultBlock> toolResults = new ArrayList<>();
        for (ContentBlock block : message.blocks()) {
            switch (block) {
                case ToolUseBlock tu -> toolUses.add(tu);
                case ToolResultBlock tr -> toolResults.add(tr);
                default -> {
                    // 文本块已由 content() 汇总
                }
            }
        }

        if (message.role() == ChatMessage.Role.SYSTEM) {
            messages.addObject().put("role", "system").put("content", text);
            return;
        }
        if (!toolResults.isEmpty()) {
            // OpenAI 以 role:"tool" 消息回传工具结果（每条结果一条消息）
            for (ToolResultBlock result : toolResults) {
                ObjectNode m = messages.addObject();
                m.put("role", "tool");
                m.put("tool_call_id", result.toolUseId());
                String content = result.content() == null ? "" : result.content();
                if (result.isError() && !content.startsWith("[工具执行失败]")) {
                    content = "[工具执行失败] " + content;
                }
                m.put("content", content);
            }
            return;
        }
        if (!toolUses.isEmpty()) {
            ObjectNode m = messages.addObject();
            m.put("role", "assistant");
            if (text.isEmpty()) {
                m.putNull("content");
            } else {
                m.put("content", text);
            }
            ArrayNode calls = m.putArray("tool_calls");
            for (ToolUseBlock use : toolUses) {
                ObjectNode call = calls.addObject();
                call.put("id", use.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", use.name());
                fn.put("arguments", use.input() == null ? "{}" : JSON.writeValueAsString(use.input()));
            }
            return;
        }
        ObjectNode plain = messages.addObject();
        plain.put("role", message.role().name().toLowerCase());
        plain.put("content", text);
    }

    /** Tool → OpenAI tools 数组元素（type:"function" + function 描述） */
    static ArrayNode toOpenAiTools(List<Tool> tools) {
        ArrayNode array = JSON.createArrayNode();
        for (Tool tool : tools) {
            ObjectNode entry = array.addObject();
            entry.put("type", "function");
            ObjectNode fn = entry.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", tool.inputSchema());
        }
        return array;
    }
}
