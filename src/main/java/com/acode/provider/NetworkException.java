package com.acode.provider;

/** 网络失败：连接建立失败、超时、连接中断 */
public class NetworkException extends ProviderException {

    public NetworkException(String message) {
        super(message);
    }

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
