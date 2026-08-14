package com.acode.ui;

import com.acode.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.io.Writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 {@link FakeTerminal} 忠实模拟终端（折行/滚动/光标上移/清屏），驱动真实的
 * LiveRegionRenderer + StreamPrinter 走「追加式流式」（完整行提交进回滚 + footer 重绘），
 * 断言「增量流式的最终屏幕」与「一次性渲染最终内容」逐行逐格一致。
 * 追加式下完整行只写一次、footer 每行截断到恰一物理行，宽度失配不再累积错位；
 * 该测试守护的是「真实终端上代码假定宽度 ≠ 终端实际宽度」时不乱码的回归。
 */
class LiveRegionTerminalSimTest {

    /** 模拟模型逐字/逐段流出的中文 pom 介绍文档片段。 */
    private static final String[] FRAGMENTS = {
            "pom.xml 是 Maven 项目的核心配置文件，主要作用包括：\n\n",
            "## 作用\n\n",
            "1. **声明项目信息**：groupId、artifactId、version、packaging。\n",
            "2. **依赖管理**：声明项目依赖，Maven 会自动解析传递依赖。\n",
            "3. **远程仓库镜像**：配置 repository / mirror，从中央仓库或 Nexus 拉取依赖。\n",
            "4. **构建配置**：build 段配置插件、资源目录、finalName、JDK 编译版本。\n\n",
            "## 依赖传递\n\n",
            "传递依赖是体系的大脑，核心作用可概括为：\n",
            "描述项目、打包、控制依赖版本、处理构建、聚合多模块工程。\n",
            "我可以帮您：Maven 冲突排查与解决、多模块 Spring Boot 项目 pom 设计、插件配置。\n",
            "```xml\n<project>\n  <artifactId>acode</artifactId>\n</project>\n```\n",
            "最后：构建打包用 mvn package，跳过测试加 -DskipTests。\n",
    };

    /** 全部片段拼接后的完整文本。 */
    private static String fullText() {
        StringBuilder sb = new StringBuilder();
        for (String f : FRAGMENTS) {
            sb.append(f);
        }
        return sb.toString();
    }

    /** 逐字符流出（模拟真实逐 token 流式）。 */
    private static void streamCharByChar(StreamPrinter printer) {
        for (String fragment : FRAGMENTS) {
            for (int i = 0; i < fragment.length(); i++) {
                printer.onDelta(fragment.substring(i, i + 1));
            }
        }
    }

    /** 增量流式跑在 termWidth 终端上；与「一次性渲染最终内容」逐行逐格比对。 */
    private static void assertIncrementalMatchesClean(int termWidth, int codeWidth, String committed) {
        int height = 24;
        String full = fullText();

        // 实际：逐字符增量流式（追加式：完整行提交 + footer 重绘）
        FakeTerminal actual = new FakeTerminal(termWidth, height);
        LiveRegionRenderer live = new LiveRegionRenderer(codeWidth, height);
        if (!committed.isEmpty()) {
            live.appendCommitted(actual.writer(), committed);
        }
        StreamPrinter printer = new StreamPrinter(new OutputPane(), live, actual.writer());
        streamCharByChar(printer);
        printer.finishTurn();

        // 期望：一次性把完整文本各渲染行作已提交行写屏（屏幕自然滚动）
        FakeTerminal expected = new FakeTerminal(termWidth, height);
        LiveRegionRenderer elive = new LiveRegionRenderer(codeWidth, height);
        if (!committed.isEmpty()) {
            elive.appendCommitted(expected.writer(), committed);
        }
        MarkdownRenderer renderer = new MarkdownRenderer();
        renderer.append(full);
        Writer ew = expected.writer();
        try {
            for (String line : splitLines(renderer.render())) {
                ew.write(line.replace("\r", "") + "\r\n");
            }
            ew.flush();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        StringBuilder diff = new StringBuilder();
        for (int r = 0; r < height; r++) {
            if (!expected.rawLine(r).equals(actual.rawLine(r))) {
                diff.append("row ").append(r)
                        .append("\n  expect: [").append(expected.line(r)).append("]")
                        .append("\n  actual: [").append(actual.line(r)).append("]\n");
            }
        }
        assertTrue(diff.isEmpty(),
                "增量流式与一次性渲染不一致（活跃区重绘错位），共 "
                        + diff.toString().lines().count() / 3 + " 行：\n" + diff
                        + "\nACTUAL SCREEN:\n" + actual.screenText());
    }

    /** 宽度失配下的内容连贯断言：各渲染行按序、恰好一次出现在最终屏幕。 */
    private static void assertCommittedContentCoherent(int termWidth, int codeWidth, String committed) {
        int height = 24;
        String full = fullText();

        FakeTerminal actual = new FakeTerminal(termWidth, height);
        LiveRegionRenderer live = new LiveRegionRenderer(codeWidth, height);
        if (!committed.isEmpty()) {
            live.appendCommitted(actual.writer(), committed);
        }
        StreamPrinter printer = new StreamPrinter(new OutputPane(), live, actual.writer());
        streamCharByChar(printer);
        printer.finishTurn();

        String compact = actual.screenText().replaceAll("\\s", "");
        if (!committed.isEmpty()) {
            assertTrue(compact.contains(committed.replaceAll("\\s", "")),
                    "已提交输入行应完整保留（宽度失配也不抹除）：\n" + actual.screenText());
        }
        int last = -1;
        for (String m : renderedVisibleLines(full)) {
            int idx = compact.indexOf(m, last + 1);
            assertTrue(idx > last,
                    "渲染行应按序完整出现（宽度失配也不错位/抹除）：[" + m + "]\n屏幕：\n" + actual.screenText());
            assertTrue(compact.indexOf(m, idx + 1) == -1,
                    "渲染行不应重复出现（宽度失配也不重复）：[" + m + "]\n屏幕：\n" + actual.screenText());
            last = idx;
        }
    }

    /** full 文本经 MarkdownRenderer 渲染后的可见行（去 ANSI、去空白，空行跳过）。 */
    private static List<String> renderedVisibleLines(String text) {
        MarkdownRenderer renderer = new MarkdownRenderer();
        renderer.append(text);
        List<String> out = new ArrayList<>();
        for (String line : splitLines(renderer.render())) {
            String m = stripAnsi(line).replaceAll("\\s", "");
            if (!m.isEmpty()) {
                out.add(m);
            }
        }
        return out;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*m", "");
    }

    private static List<String> splitLines(String text) {
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            lines.add(line.replace("\r", ""));
        }
        return lines;
    }

    /** 宽度一致：代码 80 / 终端 80，流式输出必须与一次性渲染完全一致（回归基线）。 */
    @Test
    void matchingWidthMatchesCleanRender() {
        assertIncrementalMatchesClean(80, 80, "● 介绍下pom.xml文件的内容");
    }

    /** 无已提交行的纯流式（应用内直接提问无历史时）。 */
    @Test
    void streamingWithoutCommittedLineMatchesCleanRender() {
        assertIncrementalMatchesClean(80, 80, "");
    }

    /** 终端实际宽度比代码假定窄 2/4 列：完整行只写一次、原生折行，已提交内容不乱码。 */
    @Test
    void narrowerTerminalKeepsCommittedContentCoherent() {
        assertCommittedContentCoherent(78, 80, "● 介绍下pom.xml文件的内容");
        assertCommittedContentCoherent(76, 80, "● 介绍下pom.xml文件的内容");
    }

    /** 短回复：屏幕应完整包含最终文本。 */
    @Test
    void shortReplyShowsExpectedText() {
        FakeTerminal term = new FakeTerminal(80, 24);
        LiveRegionRenderer live = new LiveRegionRenderer(80, 24);
        live.appendCommitted(term.writer(), "● 你好");
        StreamPrinter printer = new StreamPrinter(new OutputPane(), live, term.writer());
        printer.onDelta("这是");
        printer.onDelta("一句话");
        printer.onDelta("。");
        printer.finishTurn();
        assertTrue(term.screenText().contains("这是一句话。"), "短回复应完整显示，实际：\n" + term.screenText());
    }

    /** 错误路径：清除半截回复并显示错误行，不残留错位。 */
    @Test
    void errorKeepsScreenCoherent() {
        FakeTerminal term = new FakeTerminal(80, 24);
        LiveRegionRenderer live = new LiveRegionRenderer(80, 24);
        StreamPrinter printer = new StreamPrinter(new OutputPane(), live, term.writer());
        printer.onDelta("部分内容");
        printer.onError(new ProviderException("网络失败"));
        assertEquals(1, countNonBlank(term), "错误后只应剩一行错误提示，实际：\n" + term.screenText());
        assertTrue(term.screenText().contains("网络失败"));
    }

    private static int countNonBlank(FakeTerminal term) {
        int n = 0;
        for (int r = 0; r < term.height(); r++) {
            if (!term.line(r).isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
