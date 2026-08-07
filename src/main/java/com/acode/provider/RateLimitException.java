package com.acode.provider;

/** 限流：HTTP 429，可重试 */
public class RateLimitException extends ProviderException {

    public RateLimitException(String message) {
        super(message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
