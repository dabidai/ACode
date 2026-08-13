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

    /** 正常结束 */
    void onComplete();

    /** 失败结束 */
    void onError(ProviderException error);
}
