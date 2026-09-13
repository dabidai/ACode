package com.acode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acode.permission.PermissionChecker.CheckResult;
import com.acode.permission.PermissionMode.Decision;
import com.acode.permission.PermissionRule.RuleEffect;
import com.acode.permission.RuleEngine.LayerRules;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T4：权限规则三态（deny > ask > allow 跨层判定）与 /permission 只读列举所需的清单/计数入口 */
class PermissionCommandSupportTest {

    @TempDir
    Path projectRoot;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path userFile;
    private Path projectFile;
    private Path localFile;

    @BeforeEach
    void setUp() {
        userFile = projectRoot.resolve("home/.acode/permissions.yaml");
        projectFile = projectRoot.resolve(".acode/permissions.yaml");
        localFile = projectRoot.resolve(".acode/permissions.local.yaml");
    }

    /** 写完规则文件后再构造引擎（构造即加载），保证读到测试体写入的内容 */
    private RuleEngine engine() {
        return new RuleEngine(userFile, projectFile, localFile);
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String yamlOf(String... rulesAndEffects) {
        StringBuilder sb = new StringBuilder("rules:\n");
        for (int i = 0; i < rulesAndEffects.length; i += 2) {
            sb.append("  - rule: ").append(rulesAndEffects[i]).append('\n')
              .append("    effect: ").append(rulesAndEffects[i + 1]).append('\n');
        }
        return sb.toString();
    }

    private PermissionChecker checker(PermissionMode mode) {
        return new PermissionChecker(mode, projectRoot, engine());
    }

    private Tool tool(String name, Permission permission) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Permission permission() {
                return permission;
            }

            @Override
            public JsonNode inputSchema() {
                return mapper.createObjectNode();
            }

            @Override
            public ToolResult execute(JsonNode input, ToolContext context) {
                return ToolResult.success("");
            }
        };
    }

    private ObjectNode args(String key, String value) {
        ObjectNode n = mapper.createObjectNode();
        n.put(key, value);
        return n;
    }

    // ---- 三态求值 ----

    @Test
    void evaluateReturnsAllowDenyAskForThreeEffects() throws IOException {
        write(userFile, yamlOf("Bash(git *)", "allow"));
        write(projectFile, yamlOf("Bash(rm *)", "deny"));
        write(localFile, yamlOf("Bash(npm *)", "ask"));
        assertEquals(RuleEffect.ALLOW, engine().evaluate("Bash", "git push"));
        assertEquals(RuleEffect.DENY, engine().evaluate("Bash", "rm -rf /"));
        assertEquals(RuleEffect.ASK, engine().evaluate("Bash", "npm install"));
    }

    @Test
    void crossLayerDenyNotOverriddenByAnyLayerAsk() throws IOException {
        write(userFile, yamlOf("Bash(rm *)", "deny"));
        write(localFile, yamlOf("Bash(rm *)", "ask"));
        assertEquals(RuleEffect.DENY, engine().evaluate("Bash", "rm -rf /tmp/x"));
    }

    @Test
    void crossLayerAskNotCoveredByWiderAllow() throws IOException {
        // 用户级 ask + 项目级更宽的 allow → 仍 ASK（询问跨层穿透）
        write(userFile, yamlOf("Bash(npm *)", "ask"));
        write(projectFile, yamlOf("Bash(*)", "allow"));
        assertEquals(RuleEffect.ASK, engine().evaluate("Bash", "npm install"));
        // 未命中 ask 的内容照常享受项目级宽放行
        assertEquals(RuleEffect.ALLOW, engine().evaluate("Bash", "echo x"));
    }

    @Test
    void noMatchInAllThreeLayersReturnsNull() throws IOException {
        write(userFile, yamlOf("Bash(a *)", "allow"));
        write(projectFile, yamlOf("Bash(b *)", "deny"));
        write(localFile, yamlOf("Bash(c *)", "ask"));
        assertNull(engine().evaluate("Bash", "unrelated command"));
    }

    @Test
    void sameLayerLaterRuleOverridesEarlierForAllThreeEffects() throws IOException {
        write(projectFile, yamlOf("Bash(npm *)", "allow", "Bash(npm *)", "ask"));
        assertEquals(RuleEffect.ASK, engine().evaluate("Bash", "npm install"));
        write(projectFile, yamlOf("Bash(npm *)", "ask", "Bash(npm *)", "deny"));
        assertEquals(RuleEffect.DENY, engine().evaluate("Bash", "npm install"));
    }

    // ---- 规则文件解析 ----

    @Test
    void handWrittenAskRuleIsLoadedNotDropped() throws IOException {
        write(localFile, "rules:\n  - rule: Bash(npm *)\n    effect: ask\n");
        assertEquals(RuleEffect.ASK, engine().evaluate("Bash", "npm install"));
    }

    @Test
    void invalidEffectsAndMalformedEntriesDroppedOthersRemain() throws IOException {
        write(localFile,
                "rules:\n"
                + "  - rule: Bash(echo x)\n"
                + "    effect: maybe\n"
                + "  - rule: Bash(e\n"
                + "    effect: allow\n"
                + "  - rule: Bash(npm *)\n"
                + "    effect: ask\n"
                + "  - rule: Bash(git *)\n"
                + "    effect: allow\n");
        assertNull(engine().evaluate("Bash", "echo x"));
        assertNull(engine().evaluate("Bash", "e"));
        assertEquals(RuleEffect.ASK, engine().evaluate("Bash", "npm install"));
        assertEquals(RuleEffect.ALLOW, engine().evaluate("Bash", "git push"));
    }

    // ---- 决策链第 ⑥ 层三态派发 ----

    @Test
    void askRuleCheckReturnsAskNeitherAllowNorDeny() throws IOException {
        write(projectFile, yamlOf("Bash(git *)", "ask"));
        CheckResult r = checker(PermissionMode.DEFAULT).check(
                tool("Bash", Permission.EXEC), args("command", "git commit -m x"));
        assertEquals(Decision.ASK, r.decision());
    }

    @Test
    void askRuleNotSwallowedByBypassModeMatrix() throws IOException {
        // bypass 的模式矩阵本会放行：规则层的 ask 必须仍返回询问态
        write(projectFile, yamlOf("Bash(git *)", "ask"));
        CheckResult r = checker(PermissionMode.BYPASS).check(
                tool("Bash", Permission.EXEC), args("command", "git commit -m x"));
        assertEquals(Decision.ASK, r.decision());
    }

    @Test
    void allowAlwaysStillEffectiveAfterAskRule() throws IOException {
        // 命中 ask 规则不就地返回：先落第 ⑦ 层「始终允许」，命中即放行（不再反复询问）
        write(projectFile, yamlOf("Bash(git *)", "ask"));
        PermissionChecker c = checker(PermissionMode.DEFAULT);
        Tool bash = tool("Bash", Permission.EXEC);
        ObjectNode call = args("command", "git commit -m \"fix\"");
        assertEquals(Decision.ASK, c.check(bash, call).decision());
        c.addAllowAlwaysRule("Bash", "git commit -m \"fix\"");
        assertEquals(Decision.ALLOW, c.check(bash, call).decision());
    }

    @Test
    void denyRuleStillDeniesInBypassMode() throws IOException {
        write(projectFile, yamlOf("Bash(git *)", "deny"));
        CheckResult r = checker(PermissionMode.BYPASS).check(
                tool("Bash", Permission.EXEC), args("command", "git commit -m x"));
        assertEquals(Decision.DENY, r.decision());
    }

    // ---- 规则文件写回不损坏数据 ----

    @Test
    void alwaysAllowPersistWritesAllowAndPreservesExistingAskRules() throws IOException {
        write(localFile, "rules:\n  - rule: Bash(npm *)\n    effect: ask\n");
        RuleEngine e = engine();
        assertTrue(e.appendLocalRule("Bash", "git push"));
        String dumped = Files.readString(localFile);
        // 新写入的规则仍是 allow（「始终允许」通道语义不变）
        assertTrue(dumped.contains("effect: allow"));
        // 已有 ask 规则在回写后仍是 ask，不得被改写成 deny
        assertTrue(dumped.contains("effect: ask"));
        assertFalse(dumped.contains("effect: deny"));
        assertEquals(RuleEffect.ASK, e.evaluate("Bash", "npm install"));
        assertEquals(RuleEffect.ALLOW, e.evaluate("Bash", "git push"));
    }

    // ---- 规则清单与计数 ----

    @Test
    void ruleListingReflectsLayerContentsAndPaths() throws IOException {
        write(userFile, yamlOf("Bash(git *)", "allow"));
        write(projectFile, yamlOf("Bash(npm *)", "ask", "ReadFile(*.env*)", "deny"));
        // 本地文件不存在 → 0 条
        List<LayerRules> layers = engine().layers();
        assertEquals(3, layers.size());

        LayerRules user = layers.get(0);
        assertEquals(userFile, user.file());
        assertEquals(1, user.count());
        PermissionRule userRule = user.rules().get(0);
        assertEquals("Bash", userRule.toolName());
        assertEquals("git *", userRule.pattern());
        assertEquals(RuleEffect.ALLOW, userRule.effect());

        LayerRules project = layers.get(1);
        assertEquals(projectFile, project.file());
        assertEquals(2, project.count());

        LayerRules local = layers.get(2);
        assertEquals(localFile, local.file());
        assertEquals(0, local.count());
    }

    @Test
    void checkerRuleLayersDelegatesToEngine() throws IOException {
        write(localFile, yamlOf("ReadFile(*)", "allow"));
        PermissionChecker c = checker(PermissionMode.DEFAULT);
        List<LayerRules> layers = c.ruleLayers();
        assertEquals(3, layers.size());
        assertEquals(localFile, layers.get(2).file());
        assertEquals(1, layers.get(2).count());
        assertEquals(RuleEffect.ALLOW, layers.get(2).rules().get(0).effect());
    }
}
