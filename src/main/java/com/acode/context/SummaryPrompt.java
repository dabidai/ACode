package com.acode.context;

/**
 * 摘要生成的指令与格式。生成采取「先起草、后成文、草稿丢弃」的两阶段方式：
 * 模型先写一份 <analysis> 草稿（丢弃），再在 <summary>…</summary> 中给出最终正文（只保留它）。
 */
public final class SummaryPrompt {

    private SummaryPrompt() {
    }

    /** 草稿区开始标记 */
    public static final String ANALYSIS_OPEN = "<analysis>";
    /** 草稿区结束标记 */
    public static final String ANALYSIS_CLOSE = "</analysis>";
    /** 最终正文开始标记 */
    public static final String SUMMARY_OPEN = "<summary>";
    /** 最终正文结束标记 */
    public static final String SUMMARY_CLOSE = "</summary>";

    /**
     * 摘要系统指令（作为摘要请求首条 message 注入）。要点均显式成文，
     * 供测试以关键词断言 prompt 内容完整。
     */
    public static String instruction() {
        return """
                你是 ACode 的历史压缩器。把下方给出的对话历史压成一份结构化的 <summary> 摘要，供模型在后续对话中当作记忆使用。

                【必须遵循】
                - 先起草再成文、草稿丢弃：先在 <analysis>…</analysis> 里写草稿理清脉络（该区会被丢弃），再在 <summary>…</summary> 中给出最终正文。只保留 <summary> 内容。
                - 只输出纯文本，禁止调用任何工具；不要假装执行命令或读写文件。
                - 用户消息尽量原文保留：用户说过的话按原文保留优先级最高（这是优先级指引而非硬性逐字全量；空间不足时自行取舍为要点，但保留其意图与诉求）。
                - 当前工作写最详细；不要因空间省掉正在推进任务的细节。
                - 摘要正文要能被后续模型独立理解，不依赖未给出的原文。

                【摘要正文结构（9 段，逐段成文）】
                1. 意图/任务：用户想达成什么、本段对话总体目标。
                2. 技术要点与已确认决定：拍板过的方案、取舍、约束。
                3. 涉及文件与关键代码位置：文件路径、关键函数/类、行级线索。
                4. 错误与修复：踩过什么坑、怎么修的、教训。
                5. 解决过程：主要步骤推进脉络。
                6. 所有用户消息（尽量原文）：用户的提问与诉求逐条尽量保留原话。
                7. 待办：已列出但尚未完成的事项。
                8. 当前工作：正在做什么、做到哪一步，本段最详细的部分。
                9. 下一步：接下来该做什么，模型后续可据此直接继续。

                只输出 <analysis> 与 <summary> 两种标签内容，不要输出其他解释或寒暄。
                """;
    }

    /**
     * 带保留重点的摘要指令：在固定指令末尾追加「压缩时请特别保留：<重点>」。
     * focus 为 null 或空白时与 instruction() 逐字相同。
     */
    public static String instruction(String focus) {
        if (focus == null || focus.isBlank()) {
            return instruction();
        }
        return instruction() + "压缩时请特别保留：" + focus + "\n";
    }
}
