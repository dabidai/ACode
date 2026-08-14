package com.acode.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三级配置加载：内置默认（classpath config.yaml）为最底层，全局 ~/.acode/config.yaml
 * 覆盖，项目级 .acode/config.yaml 再覆盖。每一级只覆盖出现的字段；外部每一级
 * 覆盖后立即校验，报错信息带文件路径定位。内置默认是仓库受控内容，本身不校验。
 */
public class ConfigLoader {

    private static final String GLOBAL_FILE = ".acode/config.yaml";
    private static final String PROJECT_FILE = ".acode/config.yaml";
    private static final String BUILTIN_RESOURCE = "config.yaml";
    private static final List<String> KNOWN_KEYS =
            List.of("protocol", "model", "base_url", "api_key",
                    "max_context_tokens", "max_iterations", "tee");

    /** 生产入口：全局配置在用户主目录，项目级配置在当前工作目录 */
    public static AppConfig loadDefault() {
        Path global = Path.of(System.getProperty("user.home"), GLOBAL_FILE);
        Path projectDir = Path.of("").toAbsolutePath();
        return load(global, projectDir);
    }

    /** 显式路径入口，供测试与外部调用 */
    public static AppConfig load(Path globalConfig, Path projectDir) {
        AppConfig config = new AppConfig();
        apply(config, readResourceMap(BUILTIN_RESOURCE), "classpath:" + BUILTIN_RESOURCE);
        Path projectConfig = projectDir.resolve(PROJECT_FILE);
        if (Files.exists(globalConfig)) {
            apply(config, readYamlMap(globalConfig), globalConfig.toString());
            ConfigValidator.validate(config, globalConfig.toString());
        }
        if (Files.exists(projectConfig)) {
            apply(config, readYamlMap(projectConfig), projectConfig.toString());
            ConfigValidator.validate(config, projectConfig.toString());
        }
        return config;
    }

    private static Map<String, Object> readYamlMap(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parseYaml(in, file.toString());
        } catch (IOException e) {
            throw new ConfigException(file + ": 读取失败：" + e.getMessage(), e);
        }
    }

    private static Map<String, Object> readResourceMap(String name) {
        InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new ConfigException("classpath 缺少内置默认配置 " + name);
        }
        try (InputStream resource = in) {
            return parseYaml(resource, "classpath:" + name);
        } catch (IOException e) {
            throw new ConfigException("classpath:" + name + ": 读取失败：" + e.getMessage(), e);
        }
    }

    private static Map<String, Object> parseYaml(InputStream in, String source) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object parsed = new Yaml().load(reader);
            if (parsed == null) {
                return Map.of();
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new ConfigException(source + ": 配置必须是 key: value 映射");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ConfigException(source + ": 配置键必须是字符串");
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (YAMLException e) {
            throw new ConfigException(source + ": YAML 解析失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigException(source + ": 读取失败：" + e.getMessage(), e);
        }
    }

    /** 把配置映射应用到 config：只覆盖出现的字段；未知键、类型错误直接报错 */
    private static void apply(AppConfig config, Map<String, Object> map, String source) {
        for (String key : map.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigException(source + ": 未知配置项 " + key);
            }
        }
        if (map.containsKey("protocol")) {
            config.setProtocol(stringValue(map, "protocol", source));
        }
        if (map.containsKey("model")) {
            config.setModel(stringValue(map, "model", source));
        }
        if (map.containsKey("base_url")) {
            config.setBaseUrl(stringValue(map, "base_url", source));
        }
        if (map.containsKey("api_key")) {
            config.setApiKey(stringValue(map, "api_key", source));
        }
        if (map.containsKey("max_context_tokens")) {
            Object value = map.get("max_context_tokens");
            if (!(value instanceof Number number)) {
                throw new ConfigException(source + ": max_context_tokens 必须是正整数，当前值 " + value);
            }
            config.setMaxContextTokens(number.intValue());
        }
        if (map.containsKey("max_iterations")) {
            Object value = map.get("max_iterations");
            if (!(value instanceof Number number)) {
                throw new ConfigException(source + ": max_iterations 必须是正整数，当前值 " + value);
            }
            config.setMaxIterations(number.intValue());
        }
        if (map.containsKey("tee")) {
            Object value = map.get("tee");
            if (!(value instanceof Boolean teeValue)) {
                throw new ConfigException(source + ": tee 必须是 true/false，当前值 " + value);
            }
            config.setTee(teeValue);
        }
    }

    private static String stringValue(Map<String, Object> map, String key, String source) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw new ConfigException(source + ": " + key + " 必须是字符串，当前值 " + value);
        }
        return string;
    }
}
