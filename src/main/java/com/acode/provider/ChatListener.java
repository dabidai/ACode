package com.acode.provider;

/**
 * 流式响应的回调。实现方保证三个方法互斥调用：
 * 要么以 onComplete 结束，要么以 onError 结束；onDelta 可调用多次。
 */
public interface ChatListener {

    /** 收到一段增量文本（可能含未完成的多字节字符边界，上层按需处理） */
    void onDelta(String delta);

    /** 收到一个完整的工具调用（input_json_delta 碎片拼接解析后触发） */
    default void onToolUse(ToolUseBlock toolUse) {
        // 默认忽略，兼容阶段一无工具场景
    }

    /** 正常结束（无流结束原因，兼容旧实现） */
    default void onComplete() {
        // 默认空操作：带参版会回落到这里；零覆写实现安全结束，不陷入递归
    }

    /**
     * 正常结束并携带流结束原因（Anthropic stop_reason / OpenAI finish_reason）。
     * 上层据原因区分自然收尾与输出截断；解析器改调带参版。
     * <p>
     * 带参默认回落到无参版：存量实现只覆写无参版仍能收到完成信号（无论解析器以哪种
     * 签名结束），只覆写带参版的新收集器也能收到原因；零覆写实现不会递归爆栈。
     */
    default void onComplete(String stopReason) {
        onComplete();
    }

    /** 收到本轮 token 用量（流结束前触发，含缓存命中字段）。 */
    default void onUsage(Usage usage) {
        // 默认忽略，兼容旧实现
    }

    /** 失败结束 */
    void onError(ProviderException error);
}
