package com.acode.ui;

/**
 * 输入分流：把用户输入行归类为命令（/quit /clear /help）或普通消息。
 * 纯逻辑，无终端依赖，便于单测。
 */
public final class CommandRouter {

    /** 命令动作。 */
    public enum Action { QUIT, CLEAR, HELP, RESUME, CHAT, SKIP }

    /** /help 展示的命令说明；T11 补齐 /clear 文案时同步更新。 */
    public static final String HELP_TEXT = """
            /quit   退出程序
            /clear  清空界面与对话上下文
            /resume 加载历史会话（↑/↓ 选择）
            /help   显示本帮助
            """;

    private CommandRouter() {
    }

    /**
     * 输入 null（EOF）→ QUIT；空白 → SKIP；斜杠命令（区分大小写）→ 对应动作；其余 → CHAT。
     */
    public static Action route(String input) {
        if (input == null) {
            return Action.QUIT;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return Action.SKIP;
        }
        return switch (trimmed) {
            case "/quit" -> Action.QUIT;
            case "/clear" -> Action.CLEAR;
            case "/help" -> Action.HELP;
            case "/resume" -> Action.RESUME;
            default -> Action.CHAT;
        };
    }
}
