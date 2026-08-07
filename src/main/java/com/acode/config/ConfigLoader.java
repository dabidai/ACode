package com.acode.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载并合并两级配置：全局 ~/.acode/config.yaml 为默认，项目级 .acode/config.yaml
 * 只覆盖出现的字段。任一文件缺失字段/类型错误，报错信息带文件路径。
 */
public class ConfigLoader {

    private static final String GLOBAL_FILE = ".acode/config.yaml";
    private static final String PROJECT_FILE = ".acode/config.yaml";
    private static final List<String> KNOWN_KEYS =
            List.of("protocol", "model", "base_url", "api_key", "max_context_tokens");

    /** 生产入口：全局配置在用户主目录，项目级配置在当前工作目录 */
    public static AppConfig loadDefault() {
        Path global = Path.of(System.getProperty("user.home"), GLOBAL_FILE);
        Path projectDir = Path.of("").toAbsolutePath();
        return load(global, projectDir);
    }

    /** 显式路径入口，供测试与外部调用 */
    public static AppConfig load(Path globalConfig, Path projectDir) {
        Path projectConfig = projectDir.resolve(PROJECT_FILE);
        boolean globalExists = Files.exists(globalConfig);
        boolean projectExists = Files.exists(projectConfig);
        if (!globalExists && !projectExists) {
            throw new ConfigException("未找到配置文件：" + globalConfig + " 或 " + projectConfig
                    + "，可参考项目 examples/config.yaml 创建");
        }
        AppConfig config = new AppConfig();
        if (globalExists) {
            apply(config, readYamlMap(globalConfig), globalConfig);
            ConfigValidator.validate(config, globalConfig.toString());
        }
        if (projectExists) {
            apply(config, readYamlMap(projectConfig), projectConfig);
            ConfigValidator.validate(config, projectConfig.toString());
        }
        return config;
    }

    private static Map<String, Object> readYamlMap(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object parsed = new Yaml().load(reader);
            if (parsed == null) {
                return Map.of();
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new ConfigException(file + ": 配置必须是 key: value 映射");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ConfigException(file + ": 配置键必须是字符串");
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (YAMLException e) {
            throw new ConfigException(file + ": YAML 解析失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigException(file + ": 读取失败：" + e.getMessage(), e);
        }
    }

    /** 把配置映射应用到 config：只覆盖出现的字段；未知键、类型错误直接报错 */
    private static void apply(AppConfig config, Map<String, Object> map, Path file) {
        for (String key : map.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigException(file + ": 未知配置项 " + key);
            }
        }
        if (map.containsKey("protocol")) {
            config.setProtocol(stringValue(map, "protocol", file));
        }
        if (map.containsKey("model")) {
            config.setModel(stringValue(map, "model", file));
        }
        if (map.containsKey("base_url")) {
            config.setBaseUrl(stringValue(map, "base_url", file));
        }
        if (map.containsKey("api_key")) {
            config.setApiKey(stringValue(map, "api_key", file));
        }
        if (map.containsKey("max_context_tokens")) {
            Object value = map.get("max_context_tokens");
            if (!(value instanceof Number number)) {
                throw new ConfigException(file + ": max_context_tokens 必须是正整数，当前值 " + value);
            }
            config.setMaxContextTokens(number.intValue());
        }
    }

    private static String stringValue(Map<String, Object> map, String key, Path file) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw new ConfigException(file + ": " + key + " 必须是字符串，当前值 " + value);
        }
        return string;
    }
}
