package com.acode.provider;

/**
 * 一次流式响应结束前的 token 用量。cacheRead 为命中缓存读入的 token 数，
 * cacheCreation 为写入缓存产生的 token 数（OpenAI 端恒为 0，自动缓存无此字段）。
 *
 * <p>promptTokens 是本次请求真正吃掉的上下文总量，两家协议口径不同、必须各自构造：
 * Anthropic 的 input_tokens **不含**缓存读/写，总量为三者之和（四参构造器按此计算）；
 * OpenAI 的 prompt_tokens **已含** cached_tokens，总量就是它本身（用 openAi 工厂）。
 */
public record Usage(long inputTokens, long outputTokens, long cacheReadTokens,
                    long cacheCreationTokens, long promptTokens) {

    /** Anthropic 口径：input_tokens 不含缓存，上下文总量为三者之和。 */
    public Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
        this(inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens,
                inputTokens + cacheReadTokens + cacheCreationTokens);
    }

    /** OpenAI 口径：prompt_tokens 已含 cached_tokens，再加一次会双计。 */
    public static Usage openAi(long promptTokens, long completionTokens, long cachedTokens) {
        return new Usage(promptTokens, completionTokens, cachedTokens, 0, promptTokens);
    }
}
