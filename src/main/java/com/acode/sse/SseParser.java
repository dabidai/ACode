package com.acode.sse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 通用 SSE 帧解析：按空行切分事件，收集 event:/data: 行。
 * 一个完整事件触发一次 {@link EventHandler#onEvent}。
 * 兼容无 event 行（OpenAI 仅 data:）与多行 data 的情况。
 */
public final class SseParser {

    private SseParser() {
    }

    @FunctionalInterface
    public interface EventHandler {
        void onEvent(String eventType, String data);
    }

    public static void parse(InputStream in, EventHandler handler) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String eventType = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                emit(handler, eventType, data);
                eventType = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                eventType = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
            // 忽略 : 注释行、id:、retry: 等字段
        }
        // 流结束前未以空行收尾的事件
        emit(handler, eventType, data);
    }

    private static void emit(EventHandler handler, String eventType, StringBuilder data) {
        if (!data.isEmpty()) {
            handler.onEvent(eventType == null ? "" : eventType, data.toString());
        }
    }
}
