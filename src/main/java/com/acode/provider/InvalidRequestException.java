package com.acode.provider;

/** 请求参数错误：HTTP 4xx 其余（400/404/422 等），如 model 不存在、消息格式非法 */
public class InvalidRequestException extends ProviderException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
