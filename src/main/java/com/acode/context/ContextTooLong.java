package com.acode.context;

import com.acode.provider.ProviderException;

/**
 * 判定 provider 异常是否属于「上下文超长」。按异常文案短语容错匹配
 * （ProviderHttpClient.classify 只透传 error.message、不带错误码/type）：
 * Anthropic 文案含 prompt is too long；OpenAI/DeepSeek 文案含 maximum context length。
 */
public final class ContextTooLong {

    private ContextTooLong() {
    }

    /** 命中任一典型短语即视为超长；大小写与前后缀容错；空消息/普通错误返回 false、不抛错 */
    public static boolean matches(ProviderException e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("prompt is too long")
                || lower.contains("maximum context length");
    }
}
