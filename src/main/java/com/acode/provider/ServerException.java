package com.acode.provider;

/** 服务端错误：HTTP 5xx，可重试 */
public class ServerException extends ProviderException {

    public ServerException(String message) {
        super(message);
    }

    public ServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
