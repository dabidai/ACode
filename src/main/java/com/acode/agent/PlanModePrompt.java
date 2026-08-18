package com.acode.agent;

/**
 * Plan-mode reminders injected at each planning turn. Round 1 (and every 5th round
 * after it: 6, 11, ...) carries the full instruction set; the rounds in between use
 * a sparse one-liner to keep the context lean.
 * <p>
 * Note: the reference implementation (MewCode) used {@code (iteration-1)/5 % 5 == 0},
 * which wrongly emits the full reminder on rounds 2-5 continuously; we follow the
 * source text's intent instead ({@code (iteration-1) % 5 == 0}).
 */
public class PlanModePrompt {

    private static final int FULL_EVERY_N_ROUNDS = 5;

    private static final String FULL =
            "Plan mode is active. Take READ-ONLY actions only: explore the codebase by "
                    + "reading files and searching. Do NOT modify any files, run write tools, "
                    + "or change the system in any way.\n"
                    + "\n"
                    + "When you are satisfied with the plan, write it in this reply's text and "
                    + "call the ExitPlanMode tool to deliver it. The delivered plan is saved under "
                    + "the .acode/plans/ directory for later execution.\n"
                    + "\n"
                    + "When requirements are unclear or there are multiple reasonable approaches, "
                    + "ask the user with AskUserQuestion before finalizing. Do not call ExitPlanMode "
                    + "until the plan is complete and can be executed as written.";

    private static final String SPARSE =
            "Plan mode still active (full instructions earlier in conversation): read-only "
                    + "exploration only. Write the plan in this reply's text and call ExitPlanMode "
                    + "to deliver.";

    public static String buildReminder(int iteration) {
        return iteration == 1 || (iteration - 1) % FULL_EVERY_N_ROUNDS == 0 ? FULL : SPARSE;
    }

    private PlanModePrompt() {
    }
}
