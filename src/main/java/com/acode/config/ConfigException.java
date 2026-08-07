package com.acode.config;

/**
 * 配置加载失败时抛出，消息含配置文件路径与原因，供上层转中文提示。
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
