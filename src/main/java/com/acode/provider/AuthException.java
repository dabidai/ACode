package com.acode.provider;

/** 认证失败：HTTP 401/403，如 api_key 无效 */
public class AuthException extends ProviderException {

    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
