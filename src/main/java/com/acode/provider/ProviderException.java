package com.acode.provider;

/**
 * Provider 调用失败的基类。按失败原因分为五类子类，
 * 上层据此决定重试（限流/服务端）或直接提示。
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
