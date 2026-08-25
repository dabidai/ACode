package com.acode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acode.permission.PermissionRule.RuleEffect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleEngineTest {

    @TempDir
    Path tempDir;

    private Path userFile;
    private Path projectFile;
    private Path localFile;

    @BeforeEach
    void setUp() {
        userFile = tempDir.resolve("user/permissions.yaml");
        projectFile = tempDir.resolve("project/permissions.yaml");
        localFile = tempDir.resolve("local/permissions.local.yaml");
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private RuleEngine engine() {
        return new RuleEngine(userFile, projectFile, localFile);
    }

    private static String yamlOf(String... rulesAndEffects) {
        StringBuilder sb = new StringBuilder("rules:\n");
        for (int i = 0; i < rulesAndEffects.length; i += 2) {
            sb.append("  - rule: ").append(rulesAndEffects[i]).append('\n')
              .append("    effect: ").append(rulesAndEffects[i + 1]).append('\n');
        }
        return sb.toString();
    }

    @Test
    void missingFilesReturnNullNoException() {
        assertNull(engine().evaluate("Bash", "git commit"));
    }

    @Test
    void sameLayerLaterRuleOverridesEarlier() throws IOException {
        // 同层先 deny、后 allow → ALLOW（后定义优先）
        write(projectFile, yamlOf("Bash(rm *)", "deny", "Bash(rm -rf ./build)", "allow"));
        assertEquals(RuleEffect.ALLOW, engine().evaluate("Bash", "rm -rf ./build"));
        assertEquals(RuleEffect.DENY, engine().evaluate("Bash", "rm -rf /"));
    }

    @Test
    void localAllowWinsOverProjectAndUser() throws IOException {
        write(userFile, yamlOf("Bash(echo x)", "allow"));
        write(projectFile, yamlOf("Bash(echo x)", "allow"));
        write(localFile, yamlOf("Bash(echo x)", "allow"));
        assertEquals(RuleEffect.ALLOW, engine().evaluate("Bash", "echo x"));
    }

    @Test
    void userDenyCannotBeOverriddenByLocalAllow() throws IOException {
        // 用户级 deny + 本地级 allow → DENY（deny 跨层不可翻转）
        write(userFile, yamlOf("Bash(rm *)", "deny"));
        write(localFile, yamlOf("Bash(rm *)", "allow"));
        assertEquals(RuleEffect.DENY, engine().evaluate("Bash", "rm -rf /tmp/x"));
    }

    @Test
    void projectDenyOverridesLocalAlwaysAllowPersist() throws IOException {
        // 项目级 deny + 本地级 allow（模拟「始终允许」落盘）→ DENY（后加 deny 覆盖先前显式授权）
        write(projectFile, yamlOf("Bash(echo x)", "deny"));
        write(localFile, yamlOf("Bash(echo x)", "allow"));
        assertEquals(RuleEffect.DENY, engine().evaluate("Bash", "echo x"));
    }

    @Test
    void badYamlAndBadEntriesSkippedOthersRemain() throws IOException {
        // 坏 YAML（整体解析失败）→ 该层视为空
        write(projectFile, "rule: [broken\n");
        // 坏条目（缺 effect / 缺 rule / effect 非法 / 规则串缺右括号）→ 逐条跳过，其余仍生效
        write(localFile,
                "rules:\n"
                + "  - rule: Bash(git *)\n"
                + "  - effect: allow\n"
                + "  - rule: Bash(echo x)\n"
                + "    effect: maybe\n"
                + "  - rule: Bash(e\n"
                + "    effect: allow\n"
                + "  - rule: ReadFile(*.env*)\n"
                + "    effect: deny\n");
        RuleEngine engine = engine();
        assertNull(engine.evaluate("Bash", "git commit"));
        assertNull(engine.evaluate("Bash", "echo x"));
        assertEquals(RuleEffect.DENY, engine.evaluate("ReadFile", "/proj/.env"));
    }

    @Test
    void appendLocalRuleReflectsImmediatelyAndPersists() throws IOException {
        RuleEngine engine = engine();
        assertNull(engine.evaluate("Bash", "git commit -m \"fix\""));
        assertTrue(engine.appendLocalRule("Bash", "git commit -m \"fix\""));
        assertEquals(RuleEffect.ALLOW, engine.evaluate("Bash", "git commit -m \"fix\""));
        assertNull(engine.evaluate("Bash", "git commit -m \"other\""));
        assertTrue(Files.readString(localFile).contains("effect: allow"));
    }

    @Test
    void appendLocalRuleEscapesMetacharsInPattern() throws IOException {
        RuleEngine engine = engine();
        assertTrue(engine.appendLocalRule("Bash", "git commit -m \"a*b\""));
        // 持久化转义：精确内容放行，glob 相似串不放行
        assertEquals(RuleEffect.ALLOW, engine.evaluate("Bash", "git commit -m \"a*b\""));
        assertNull(engine.evaluate("Bash", "git commit -m \"axb\""));
    }

    @Test
    void appendLocalRuleWriteFailureReturnsFalseNoException() throws IOException {
        // 让 localFile 的父路径变成一个「文件」，createDirectories 抛 IOException
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "i am a file");
        Path brokenLocal = blocker.resolve("permissions.local.yaml");
        RuleEngine engine = new RuleEngine(userFile, projectFile, brokenLocal);
        assertFalse(engine.appendLocalRule("Bash", "echo x"));
    }
}
