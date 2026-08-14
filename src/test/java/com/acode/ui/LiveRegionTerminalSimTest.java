package com.acode.ui;

import com.acode.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 {@link FakeTerminal} 忠实模拟终端（折行/滚动），驱动真实的 LiveRegionRenderer +
 * StreamPrinter 走「纯追加式流式」（每个完成的渲染行经 appendCommitted 写屏一次、无任何
 * 光标操作序列），断言「增量流式的最终屏幕」与「一次性渲染最终内容」逐行逐格一致，
 * 并断言全程不发射 \033[NA / \033[J（追加式无重绘，宽度失配在真实终端也不可能错位）。
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

        // 实际：逐字符增量流式（纯追加式）
        FakeTerminal actual = new FakeTerminal(termWidth, height);
        LiveRegionRenderer live = new LiveRegionRenderer(codeWidth, height);
        if (!committed.isEmpty()) {
            live.appendCommitted(actual.writer(), committed);
        }
        StreamPrinter printer = new StreamPrinter(new OutputPane(), live, actual.writer());
        streamCharByChar(printer);
        printer.finishTurn();

        // 期望：一次性把完整文本各渲染行作已提交行追加写屏（与流式同一机制）
        FakeTerminal expected = new FakeTerminal(termWidth, height);
        LiveRegionRenderer elive = new LiveRegionRenderer(codeWidth, height);
        if (!committed.isEmpty()) {
            elive.appendCommitted(expected.writer(), committed);
        }
        MarkdownRenderer renderer = new MarkdownRenderer();
        renderer.append(full);
        for (String line : splitLines(renderer.render())) {
            elive.appendCommitted(expected.writer(), line + "\n");
        }

        StringBuilder diff = new StringBuilder();
        for (int r = 0; r < height; r++) {
            if (!expected.rawLine(r).equals(actual.rawLine(r))) {
                diff.append("row ").append(r)
                        .append("\n  expect: [").append(expected.line(r)).append("]")
                        .append("\n  actual: [").append(actual.line(r)).append("]\n");
            }
        }
        String debug = actual.debugLog();
        assertTrue(!hasCursorOps(debug),
                "追加式流式不应发射光标上移/清屏序列：\n" + debug);
        assertTrue(diff.isEmpty(),
                "增量流式与一次性渲染不一致（追加式应逐行一致），共 "
                        + diff.toString().lines().count() / 3 + " 行：\n" + diff
                        + "\nACTUAL SCREEN:\n" + actual.screenText());
    }

    /** debugLog 中是否存在光标上移（\033[NA）或清屏（\033[J）序列（SGR 以 m 结尾不算）。 */
    private static boolean hasCursorOps(String s) {
        char esc = '\033';
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == esc && s.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < s.length() && (s.charAt(j) >= '0' && s.charAt(j) <= '9'
                        || s.charAt(j) == ';' || s.charAt(j) == '?')) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == 'A' || s.charAt(j) == 'J')) {
                    return true;
                }
            }
        }
        return false;
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

    /** 终端实际宽度与代码假定不一致（更窄 / 远窄于渲染行）：追加式下逐行一致、不重绘。 */
    @Test
    void mismatchedWidthStillMatchesCleanRender() {
        assertIncrementalMatchesClean(78, 80, "● 介绍下pom.xml文件的内容");
        assertIncrementalMatchesClean(76, 80, "● 介绍下pom.xml文件的内容");
    }

    /** 渲染行长于终端宽度（原生折行）：追加式下两侧仍逐行一致。 */
    @Test
    void linesLongerThanTerminalWidthStillMatch() {
        assertIncrementalMatchesClean(60, 80, "● 介绍下pom.xml文件的内容");
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

    /** 错误路径：丢弃半截未完成行、追加错误行，屏幕无残留错位。 */
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
