package com.acode.hook;

import java.time.Duration;
import java.util.Map;

public record HookConfig(String id, String event, HookCondition condition, Action action,
                         boolean reject, boolean once, boolean async) {
    public enum ActionType { COMMAND, PROMPT, HTTP, AGENT }
    public record Action(ActionType type, Map<String, String> values, Duration timeout) {
        public Action { values = Map.copyOf(values); }
        public String value(String key) { return values.getOrDefault(key, ""); }
    }
}
