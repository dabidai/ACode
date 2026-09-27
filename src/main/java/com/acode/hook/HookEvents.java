package com.acode.hook;

import java.util.Set;

public final class HookEvents {
    private HookEvents() {}
    public static final String PRE_TOOL_USE = "pre_tool_use";
    public static final String POST_TOOL_USE = "post_tool_use";
    public static final String SESSION_START = "session_start";
    public static final String TURN_START = "turn_start";
    public static final String TURN_END = "turn_end";
    public static final Set<String> ALL = Set.of(PRE_TOOL_USE, POST_TOOL_USE, SESSION_START, TURN_START, TURN_END);
    public static final String NAMES = String.join(" / ", PRE_TOOL_USE, POST_TOOL_USE, SESSION_START, TURN_START, TURN_END);
}
