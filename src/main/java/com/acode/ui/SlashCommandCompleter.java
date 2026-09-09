package com.acode.ui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tab 补全：输入 / 开头时补全命令名，/permission-mode 后补全模式名，/model 后补全模型名。
 * 按 Tab 弹出匹配列表，↑/↓ 选择后回车填入。
 */
public class SlashCommandCompleter implements Completer {

    private static final List<String> COMMANDS = List.of(
            "/quit", "/clear", "/help", "/resume", "/plan", "/do",
            "/permission-mode", "/model");

    private static final List<String> PERMISSION_MODES = List.of(
            "default", "acceptEdits", "plan", "bypassPermissions");

    private final Supplier<List<String>> modelOptionsSupplier;

    public SlashCommandCompleter(Supplier<List<String>> modelOptionsSupplier) {
        this.modelOptionsSupplier = modelOptionsSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buf = line.line();
        int wordIdx = line.wordIndex();

        if (wordIdx == 0) {
            String prefix = line.word();
            if (!prefix.startsWith("/")) {
                return;
            }
            for (String cmd : COMMANDS) {
                if (cmd.startsWith(prefix)) {
                    candidates.add(new Candidate(cmd, cmd, null, null, null, null, true));
                }
            }
        } else if (wordIdx == 1 && buf.startsWith("/permission-mode")) {
            String prefix = line.word();
            for (String mode : PERMISSION_MODES) {
                if (mode.startsWith(prefix)) {
                    candidates.add(new Candidate(mode));
                }
            }
        } else if (wordIdx == 1 && buf.startsWith("/model")) {
            String prefix = line.word();
            List<String> models = modelOptionsSupplier.get();
            if (models != null) {
                for (String model : models) {
                    if (model.startsWith(prefix)) {
                        candidates.add(new Candidate(model));
                    }
                }
            }
        }
    }
}
