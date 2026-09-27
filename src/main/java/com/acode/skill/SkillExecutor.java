package com.acode.skill;

import com.acode.ui.UIController;

public final class SkillExecutor {
    private final SkillRuntime runtime;
    public SkillExecutor(SkillRuntime runtime) { this.runtime = runtime; }
    public void execute(String name, String arguments, UIController ui) {
        if (runtime.isFork(name)) {
            ui.runForegroundTask(() -> runtime.prepare(name, arguments).result());
            runtime.drainWarnings().forEach(ui::appendSystemMessage);
            return;
        }
        SkillActivation activation = runtime.prepare(name, arguments);
        runtime.drainWarnings().forEach(ui::appendSystemMessage);
        if (!activation.successful()) { ui.appendSystemMessage(activation.result().content()); return; }
        if (runtime.isDuplicate(activation)) ui.appendSystemMessage(activation.result().content());
        else ui.submitPreparedInput(() -> runtime.commit(activation));
    }
}
