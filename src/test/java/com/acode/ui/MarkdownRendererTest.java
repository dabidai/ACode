package com.acode.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    @Test
    void plainTextRenderedAsIs() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("hello world");
        assertEquals("hello world", r.render());
    }

    @Test
    void multipleAppendsAccumulate() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("你好");
        r.append("，世界");
        assertEquals("你好，世界", r.render());
    }

    @Test
    void emptyAndNullDeltasIgnored() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("");
        r.append(null);
        assertEquals("", r.render());
    }

    @Test
    void headingLineStyled() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("# 标题");
        String out = r.render();
        assertTrue(out.contains(MarkdownRenderer.STYLE_HEADING + "# 标题" + MarkdownRenderer.RESET));
    }

    @Test
    void multiHashHeadingStyled() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("## 副标题");
        assertTrue(r.render().contains(MarkdownRenderer.STYLE_HEADING));
    }

    @Test
    void hashWithoutSpaceIsNotHeading() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("#abc");
        assertFalse(r.render().contains(MarkdownRenderer.STYLE_HEADING));
    }

    @Test
    void hashInsideTextIsNotHeading() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("a # b");
        String out = r.render();
        assertFalse(out.contains(MarkdownRenderer.STYLE_HEADING));
        assertTrue(out.contains("a # b"));
    }

    @Test
    void boldStyledAroundText() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("**bold**");
        String out = r.render();
        assertTrue(out.contains(MarkdownRenderer.STYLE_BOLD + "bold" + MarkdownRenderer.RESET));
    }

    @Test
    void inlineCodeStyled() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("`code`");
        String out = r.render();
        assertTrue(out.contains(MarkdownRenderer.STYLE_INLINE_CODE + "code" + MarkdownRenderer.RESET));
    }

    @Test
    void mixedInlineTokensStyled() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("a **b** c `d`");
        String out = r.render();
        assertTrue(out.contains(MarkdownRenderer.STYLE_BOLD + "b" + MarkdownRenderer.RESET));
        assertTrue(out.contains(MarkdownRenderer.STYLE_INLINE_CODE + "d" + MarkdownRenderer.RESET));
        assertTrue(out.startsWith("a "));
    }

    @Test
    void codeFenceAcrossDeltasKeepsStyle() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```java\n");
        r.append("int x = 1;\n");
        r.append("```\n");
        String out = r.render();
        assertTrue(out.contains(MarkdownRenderer.STYLE_CODE_BLOCK + "int x = 1;" + MarkdownRenderer.RESET));
    }

    @Test
    void codeFenceContentIsNotBoldStyled() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```\n");
        r.append("**x**\n");
        r.append("```\n");
        String out = r.render();
        assertTrue(out.contains(MarkdownRenderer.STYLE_CODE_BLOCK + "**x**" + MarkdownRenderer.RESET));
        assertFalse(out.contains(MarkdownRenderer.STYLE_BOLD));
    }

    @Test
    void unclosedFenceStillResetsAtEnd() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("```\n");
        r.append("code");
        String out = r.render();
        assertTrue(out.contains(MarkdownRenderer.STYLE_CODE_BLOCK + "code" + MarkdownRenderer.RESET));
        assertTrue(out.endsWith(MarkdownRenderer.RESET));
    }

    @Test
    void trailingNewlineDoesNotCreateExtraLine() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.append("line1\n");
        assertEquals("line1", r.render());
    }
}
