package com.acode.sse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseParserTest {

    private List<String[]> parse(String text) throws IOException {
        List<String[]> events = new ArrayList<>();
        SseParser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                (eventType, data) -> events.add(new String[]{eventType, data}));
        return events;
    }

    @Test
    void 标准帧解析event和data() throws IOException {
        List<String[]> events = parse("event: foo\ndata: {\"a\":1}\n\n");
        assertEquals(1, events.size());
        assertEquals("foo", events.get(0)[0]);
        assertEquals("{\"a\":1}", events.get(0)[1]);
    }

    @Test
    void 无event行只有data() throws IOException {
        List<String[]> events = parse("data: hello\n\n");
        assertEquals(1, events.size());
        assertEquals("", events.get(0)[0]);
        assertEquals("hello", events.get(0)[1]);
    }

    @Test
    void 多行data用换行拼接() throws IOException {
        List<String[]> events = parse("data: a\ndata: b\n\n");
        assertEquals(1, events.size());
        assertEquals("a\nb", events.get(0)[1]);
    }

    @Test
    void 末尾无空行也触发事件() throws IOException {
        List<String[]> events = parse("data: end");
        assertEquals(1, events.size());
        assertEquals("end", events.get(0)[1]);
    }

    @Test
    void 多个事件按空行分隔() throws IOException {
        List<String[]> events = parse("data: one\n\ndata: two\n\n");
        assertEquals(2, events.size());
        assertEquals("one", events.get(0)[1]);
        assertEquals("two", events.get(1)[1]);
    }

    @Test
    void 忽略注释和id行() throws IOException {
        List<String[]> events = parse(": comment\nid: 1\nretry: 100\ndata: x\n\n");
        assertEquals(1, events.size());
        assertEquals("x", events.get(0)[1]);
    }
}
