package com.acode.permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * 规则引擎：三层规则文件（用户级 ~/.acode/permissions.yaml、项目级/本地级 {项目}/.acode/*.yaml）。
 * 求值（R4）：每层内从后往前、后匹配者覆盖先匹配者，得该层最终结果；
 * 跨层按 deny > ask > allow 三级判定——任一层 DENY → DENY（跨层 deny 不可翻转）；
 * 否则任一层 ASK → ASK（询问跨层穿透，更宽的 allow 盖不掉）；
 * 否则 local > project > user 取第一个非 null；三层皆无匹配返回 null。
 */
public class RuleEngine {

    private static final Pattern RULE_PATTERN = Pattern.compile("^(\\w+)\\((.+)\\)$");

    /** 一层规则清单：文件路径 + 规则列表（供命令层逐条打印「工具名(模式) → 效果」） */
    public record LayerRules(Path file, List<PermissionRule> rules) {
        public int count() {
            return rules.size();
        }
    }

    private final Path userFile;
    private final Path projectFile;
    private final Path localFile;
    private final Yaml yaml = new Yaml();

    private volatile List<PermissionRule> userRules = List.of();
    private volatile List<PermissionRule> projectRules = List.of();
    private volatile List<PermissionRule> localRules = List.of();

    public RuleEngine(Path userFile, Path projectFile, Path localFile) {
        this.userFile = userFile;
        this.projectFile = projectFile;
        this.localFile = localFile;
        reload();
    }

    /** 返回 ALLOW/DENY/ASK；三层皆无匹配返回 null。跨层按 deny > ask > allow 判定 */
    public PermissionRule.RuleEffect evaluate(String toolName, String content) {
        PermissionRule.RuleEffect user = layerEffect(userRules, toolName, content);
        PermissionRule.RuleEffect project = layerEffect(projectRules, toolName, content);
        PermissionRule.RuleEffect local = layerEffect(localRules, toolName, content);
        // 任一层 DENY 即返回 DENY（跨层 deny 不可翻转）
        if (user == PermissionRule.RuleEffect.DENY
                || project == PermissionRule.RuleEffect.DENY
                || local == PermissionRule.RuleEffect.DENY) {
            return PermissionRule.RuleEffect.DENY;
        }
        // 任一层 ASK 即返回 ASK（询问跨层穿透：更宽的 allow 盖不掉）
        if (user == PermissionRule.RuleEffect.ASK
                || project == PermissionRule.RuleEffect.ASK
                || local == PermissionRule.RuleEffect.ASK) {
            return PermissionRule.RuleEffect.ASK;
        }
        // local > project > user 取第一个非 null
        if (local != null) {
            return local;
        }
        if (project != null) {
            return project;
        }
        return user;
    }

    /** /permission 无参列举用：三层规则清单（文件路径 + 规则摘要），顺序为用户级 → 项目级 → 项目本地级 */
    public List<LayerRules> layers() {
        return List.of(
                new LayerRules(userFile, userRules),
                new LayerRules(projectFile, projectRules),
                new LayerRules(localFile, localRules));
    }

    private PermissionRule.RuleEffect layerEffect(List<PermissionRule> rules, String toolName, String content) {
        PermissionRule.RuleEffect result = null;
        // 从前往后遍历，后匹配者覆盖先匹配者（同层后定义优先）
        for (PermissionRule rule : rules) {
            if (rule.matches(toolName, content)) {
                result = rule.effect();
            }
        }
        return result;
    }

    /**
     * 追加一条 ALLOW 到本地文件并重载。写回失败（目录不可写/磁盘满）不抛异常、返回 false（R9）。
     * 持久化的模式经 escapeGlob 转义，保证「始终允许」内容按字面匹配。
     */
    public boolean appendLocalRule(String toolName, String pattern) {
        try {
            Path parent = localFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<PermissionRule> existing = loadRulesFile(localFile);
            String escaped = PermissionRule.escapeGlob(pattern);

            List<Map<String, String>> rules = new ArrayList<>();
            for (PermissionRule r : existing) {
                rules.add(entry(r.toolName() + "(" + r.pattern() + ")",
                        r.effect().keyword()));
            }
            rules.add(entry(toolName + "(" + escaped + ")", "allow"));

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("rules", rules);
            String dumped = yaml.dump(root);

            // 临时文件 + 原子 rename，避免半写损坏规则文件
            Path tmp = Files.createTempFile(parent, "permissions", ".yaml.tmp");
            try {
                Files.writeString(tmp, dumped, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, localFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, localFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            this.localRules = loadRulesFile(localFile);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<String, String> entry(String rule, String effect) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("effect", effect);
        return m;
    }

    private void reload() {
        this.userRules = loadRulesFile(userFile);
        this.projectRules = loadRulesFile(projectFile);
        this.localRules = loadRulesFile(localFile);
    }

    /** 极致容错：文件不存在 / 读失败 / YAML 解析失败返回空列表；坏条目静默跳过 */
    List<PermissionRule> loadRulesFile(Path file) {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        try {
            Object root = yaml.load(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> map)) {
                return List.of();
            }
            Object rulesObj = map.get("rules");
            if (!(rulesObj instanceof List<?> rules)) {
                return List.of();
            }
            List<PermissionRule> out = new ArrayList<>();
            for (Object item : rules) {
                PermissionRule r = parseRule(item);
                if (r != null) {
                    out.add(r);
                }
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private PermissionRule parseRule(Object item) {
        if (!(item instanceof Map<?, ?> entry)) {
            return null;
        }
        Object ruleStr = entry.get("rule");
        Object effectStr = entry.get("effect");
        if (!(ruleStr instanceof String s) || !(effectStr instanceof String e)) {
            return null;
        }
        PermissionRule.RuleEffect effect = switch (e) {
            case "allow" -> PermissionRule.RuleEffect.ALLOW;
            case "deny" -> PermissionRule.RuleEffect.DENY;
            case "ask" -> PermissionRule.RuleEffect.ASK;
            default -> null;
        };
        if (effect == null) {
            return null;
        }
        Matcher m = RULE_PATTERN.matcher(s);
        if (!m.matches()) {
            return null;
        }
        return new PermissionRule(m.group(1), m.group(2), effect);
    }
}
