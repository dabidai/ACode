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
                    "max_context_tokens", "max_iterations", "tee", "permission_mode", "thinking",
                    "memory_auto", "mcp_servers", "verification_agent", "worktree");

    /** 生产入口：全局配置在用户主目录，项目级配置在当前工作目录 */
    public static AppConfig loadDefault() {
        Path global = Path.of(System.getProperty("user.home"), GLOBAL_FILE);
        // 获取当前目录，作为工作目录
        Path projectDir = Path.of("").toAbsolutePath();
        return load(global, projectDir);
    }

    /** 显式路径入口，供测试与外部调用 */
    // TODO 不只是服务于Java
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
            // Preserve legacy YAML semantics for existing keys, but this new switch accepts only true/false.
            String yamlText = new java.io.BufferedReader(reader).lines().collect(java.util.stream.Collectors.joining("\n"));
            var yaml = new Yaml();
            var node = yaml.compose(new java.io.StringReader(yamlText));
            if (node instanceof org.yaml.snakeyaml.nodes.MappingNode mapping) {
                for (var tuple : mapping.getValue()) {
                    if (tuple.getKeyNode() instanceof org.yaml.snakeyaml.nodes.ScalarNode key
                            && key.getValue().equals("verification_agent")
                            && (!(tuple.getValueNode() instanceof org.yaml.snakeyaml.nodes.ScalarNode value)
                            || !value.getTag().equals(org.yaml.snakeyaml.nodes.Tag.BOOL)
                            || !List.of("true", "false").contains(value.getValue())))
                        throw new ConfigException(source + ": verification_agent 必须是 true/false");
                }
            }
            Object parsed = yaml.load(yamlText);
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
            // 把配置中写的出错的键的问题暴露出来，防止静默失败
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
        if (map.containsKey("worktree")) {
            if (!(map.get("worktree") instanceof Map<?, ?> worktree))
                throw new ConfigException(source + ": worktree 必须是映射");
            for (Object key : worktree.keySet())
                if (!List.of("symlinkDirectories", "staleAfterDays").contains(key))
                    throw new ConfigException(source + ": worktree 未知字段 " + key);
            if (worktree.containsKey("staleAfterDays")) {
                Object days = worktree.get("staleAfterDays");
                if (!(days instanceof Integer n) || n <= 0)
                    throw new ConfigException(source + ": worktree.staleAfterDays 必须是正整数，当前值 " + days);
                config.setWorktreeStaleAfterDays(n);
            }
            if (worktree.containsKey("symlinkDirectories")) {
                if (!(worktree.get("symlinkDirectories") instanceof List<?> dirs))
                    throw new ConfigException(source + ": worktree.symlinkDirectories 必须是列表");
                var checked = new java.util.ArrayList<String>();
                for (Object value : dirs) {
                    if (!(value instanceof String dir) || !dir.matches("[a-zA-Z0-9._-]+")
                            || dir.equals(".") || dir.equals("..") || dir.endsWith(".")
                            || dir.equalsIgnoreCase(".git") || dir.equalsIgnoreCase(".acode")
                            || com.acode.worktree.WorktreeNames.device(dir))
                        throw new ConfigException(source + ": worktree.symlinkDirectories 含非法项 " + value + "：只允许单段相对目录名（如 node_modules）");
                    checked.add(dir);
                }
                config.setWorktreeSymlinkDirectories(checked);
            }
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
        if (map.containsKey("permission_mode")) {
            config.setPermissionMode(stringValue(map, "permission_mode", source));
        }
        if (map.containsKey("thinking")) {
            Object value = map.get("thinking");
            if (!(value instanceof Boolean thinkingValue)) {
                throw new ConfigException(source + ": thinking 必须是 true/false，当前值 " + value);
            }
            config.setThinking(thinkingValue);
        }
        if (map.containsKey("memory_auto")) {
            Object value = map.get("memory_auto");
            if (!(value instanceof Boolean memoryAutoValue)) {
                throw new ConfigException(source + ": memory_auto 必须是 true/false，当前值 " + value);
            }
            config.setMemoryAuto(memoryAutoValue);
        }
        if (map.containsKey("verification_agent")) {
            Object value = map.get("verification_agent");
            if (!(value instanceof Boolean enabled)) throw new ConfigException(source + ": verification_agent 必须是 true/false");
            config.setVerificationAgent(enabled);
        }
        if (map.containsKey("mcp_servers")) {
            Object value = map.get("mcp_servers");
            if (!(value instanceof Map<?, ?> servers)) {
                throw new ConfigException(source + ": mcp_servers 必须是 server 名到配置的映射");
            }
            Map<String, McpServerConfig> merged = new LinkedHashMap<>(config.getMcpServers());
            for (Map.Entry<?, ?> entry : servers.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new ConfigException(source + ": mcp_servers 键必须是字符串");
                }
                if (!(entry.getValue() instanceof Map<?, ?> serverMap)) {
                    throw new ConfigException(source + ": mcp_servers." + name + " 必须是映射");
                }
                merged.put(name, McpServerConfig.fromYaml(name, serverMap, source));
            }
            config.setMcpServers(merged);
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
