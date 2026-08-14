package com.acode.agent;

/**
 * plan 模式提醒：首轮注入完整引导，后续轮次用稀疏一行提醒，避免重复冗长指令占用上下文。
 */
public class PlanModePrompt {

    private static final String FULL =
            "你现在处于规划模式：只能做只读探索（读文件、搜索），不要修改任何文件。"
                    + "请把完整计划写在回复文本中，完成计划后调用 ExitPlanMode 工具交付。"
                    + "计划将保存到工作目录的 .acode/plans/ 目录。";

    private static final String SPARSE =
            "继续规划：只读探索，把计划写在回复文本中，完成后调用 ExitPlanMode 交付。";

    public static String buildReminder(int iteration) {
        return iteration <= 1 ? FULL : SPARSE;
    }

    private PlanModePrompt() {
    }
}
