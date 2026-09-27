package com.acode.hook;

@FunctionalInterface
public interface HookAction {
    Result execute(HookConfig hook, HookContext context) throws Exception;
    record Result(boolean ok, String output) {
        public static Result success(String output) { return new Result(true, output); }
        public static Result failure(String output) { return new Result(false, output); }
    }
}
