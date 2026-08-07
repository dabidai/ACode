package com.acode.provider;

/**
 * 重试策略：仅瞬时性错误（限流、服务端、网络）重试，确定性错误（认证、请求参数）直接抛。
 * 退避间隔 1s/2s/4s（指数），最多重试 {@link #MAX_RETRIES} 次。
 */
public final class RetryPolicy {

    /** 最大重试次数（不含首次请求），即共发 1 + MAX_RETRIES 次请求 */
    public static final int MAX_RETRIES = 3;

    private static final long BASE_BACKOFF_MS = 1000;

    private RetryPolicy() {
    }

    public static boolean isRetryable(ProviderException e) {
        return e instanceof RateLimitException
                || e instanceof ServerException
                || e instanceof NetworkException;
    }

    /** attempt 从 1 开始：1→1s、2→2s、3→4s */
    public static long backoffMs(int attempt) {
        return BASE_BACKOFF_MS << (attempt - 1);
    }
}
