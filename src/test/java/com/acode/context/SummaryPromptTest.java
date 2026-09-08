package com.acode.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T4：SummaryPrompt 指令含 9 段结构、用户原文优先、两阶段、禁工具 等关键词。 */
class SummaryPromptTest {

    @Test
    void instructionContainsStructuredSectionsAndConstraints() {
        String p = SummaryPrompt.instruction();
        assertTrue(p.contains("意图"), "含 1 意图");
        assertTrue(p.contains("技术要点"), "含 2 技术要点");
        assertTrue(p.contains("文件与关键代码"), "含 3 文件与关键代码");
        assertTrue(p.contains("错误与修复"), "含 4 错误与修复");
        assertTrue(p.contains("解决过程"), "含 5 解决过程");
        assertTrue(p.contains("用户消息"), "含 6 所有用户消息");
        assertTrue(p.contains("待办"), "含 7 待办");
        assertTrue(p.contains("当前工作"), "含 8 当前工作");
        assertTrue(p.contains("下一步"), "含 9 下一步");
        assertTrue(p.contains("尽量原文保留"), "用户消息原文优先");
        assertTrue(p.contains("先起草"), "两阶段：先起草");
        assertTrue(p.contains("草稿会被丢弃") || p.contains("草稿（该区会被丢弃）") || p.contains("会被丢弃"),
                "草稿丢弃");
        assertTrue(p.contains("只输出纯文本"), "只输出纯文本");
        assertTrue(p.contains("禁止调用任何工具"), "禁止工具");
    }
}
