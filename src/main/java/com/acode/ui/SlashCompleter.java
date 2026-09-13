package com.acode.ui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * T2 探针：斜杠命令 Tab 补全最小实现。
 * 候选硬编码为本章内置命令；T11 按验证结论换成注册中心可见清单。
 */
public class SlashCompleter implements Completer {

    private static final List<Candidate> COMMANDS = List.of(
            new Candidate("/help", "/help", null, "列出可用命令或查看命令详情", null, null, true),
            new Candidate("/compact", "/compact", null, "压缩上下文，可带保留重点", null, null, true),
            new Candidate("/resume", "/resume", null, "恢复历史会话", null, null, true),
            new Candidate("/memory", "/memory", null, "查看 / 创建三层指令文件", null, null, true),
            new Candidate("/permission", "/permission", null, "查看权限规则或切换模式", null, null, true),
            new Candidate("/status", "/status", null, "一屏查看当前状态", null, null, true),
            new Candidate("/quit", "/quit", null, "退出 ACode", null, null, true),
            new Candidate("/clear", "/clear", null, "清空当前对话", null, null, true),
            new Candidate("/plan", "/plan", null, "切换规划模式", null, null, true),
            new Candidate("/do", "/do", null, "执行最近交付的计划", null, null, true),
            new Candidate("/review", "/review", null, "审查未提交变更", null, null, true));

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        if (!buffer.startsWith("/") || buffer.indexOf(' ') >= 0) {
            return;
        }
        for (Candidate cmd : COMMANDS) {
            if (cmd.value().startsWith(buffer)) {
                candidates.add(cmd);
            }
        }
    }
}
