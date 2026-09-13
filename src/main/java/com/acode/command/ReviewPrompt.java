package com.acode.command;

/**
 * /review 的预设审查提示词：只构造文本，实际审查工作交给 Agent。
 * 要求分析当前工作区的未提交变更（git diff 语义），按「问题 / 风险 / 建议」组织输出，
 * 指明具体文件与位置，并明确只审查不修改。带参数时把参数作为额外关注点并入。
 */
public final class ReviewPrompt {

    private ReviewPrompt() {
    }

    /** 审查指令正文：要点均显式成文，供测试以关键词断言内容完整 */
    public static String instruction() {
        return """
                请审查当前工作区的未提交变更（即 git diff 的内容）。用只读方式查看尚未提交的改动，然后给出审查结论。

                【输出结构】
                按「问题 / 风险 / 建议」三部分组织：
                - 问题：明确存在的缺陷（逻辑错误、边界遗漏、错误处理缺失等）
                - 风险：潜在隐患（并发、性能、安全、兼容性等）
                - 建议：改进方向（结构、命名、测试、可读性等）

                【要求】
                - 每一项都要指明具体文件与位置，不要泛泛而谈
                - 只审查不修改：不要改动任何文件，也不要做审查之外的任何操作
                """;
    }

    /** 带额外关注点的审查指令：在固定指令末尾追加「额外关注：<参数>」。
     * focus 为 null 或空白时与 instruction() 逐字相同。 */
    public static String instruction(String focus) {
        if (focus == null || focus.isBlank()) {
            return instruction();
        }
        return instruction() + "额外关注：" + focus + "\n";
    }
}
