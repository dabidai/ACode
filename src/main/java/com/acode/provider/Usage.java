package com.acode.provider;

/**
 * 一次流式响应结束前的 token 用量。cacheRead 为命中缓存读入的 token 数，
 * cacheCreation 为写入缓存产生的 token 数（OpenAI 端恒为 0，自动缓存无此字段）。
 */
public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
}
