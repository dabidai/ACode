package com.acode.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acode.permission.PermissionRule.RuleEffect;
import org.junit.jupiter.api.Test;

class PermissionRuleTest {

    @Test
    void toolNameMismatchReturnsFalse() {
        PermissionRule r = new PermissionRule("Bash", "git *", RuleEffect.ALLOW);
        assertFalse(r.matches("ReadFile", "git commit -m x"));
    }

    @Test
    void globMatchesCommandAndNotSubstring() {
        PermissionRule r = new PermissionRule("Bash", "git *", RuleEffect.ALLOW);
        assertTrue(r.matches("Bash", "git commit -m x"));
        assertFalse(r.matches("Bash", "gitstatus"));
    }

    @Test
    void flatGlobStarCrossesSlash() {
        PermissionRule r = new PermissionRule("ReadFile", "*.env*", RuleEffect.DENY);
        assertTrue(r.matches("ReadFile", "/abs/project/.env"));
        assertTrue(r.matches("ReadFile", "/abs/project/.env.local"));
        assertFalse(r.matches("ReadFile", "env.md"));
    }

    @Test
    void exactMatchFirstMatchesPersistedContent() {
        // 精确内容命中（exact-first）
        PermissionRule r = new PermissionRule("Bash", "git commit -m \"a*b\"", RuleEffect.ALLOW);
        assertTrue(r.matches("Bash", "git commit -m \"a*b\""));
    }

    @Test
    void escapedMetacharIsLiteralNotWildcard() {
        // 持久化转义后（\*）：按字面匹配，不误匹配 axb
        PermissionRule r = new PermissionRule("Bash", "git commit -m \"a\\*b\"", RuleEffect.ALLOW);
        assertTrue(r.matches("Bash", "git commit -m \"a*b\""));
        assertFalse(r.matches("Bash", "git commit -m \"axb\""));
    }

    @Test
    void bareStarRemainsWildcard() {
        // 用户书写的裸 * 仍是通配符（fnmatch 语义）
        PermissionRule r = new PermissionRule("Bash", "git commit -m \"a*b\"", RuleEffect.ALLOW);
        assertTrue(r.matches("Bash", "git commit -m \"axb\""));
    }

    @Test
    void globSpecialCharsTreatedLiterallyExceptStarQuestion() {
        // + 按字面，* 是通配符
        PermissionRule plus = new PermissionRule("ReadFile", "a+b*c.md", RuleEffect.DENY);
        assertTrue(plus.matches("ReadFile", "a+bXc.md"));
        assertFalse(plus.matches("ReadFile", "aXbXc.md"));

        // ? 匹配单个字符
        PermissionRule q = new PermissionRule("Bash", "git status ?", RuleEffect.ALLOW);
        assertTrue(q.matches("Bash", "git status x"));
        assertFalse(q.matches("Bash", "git status xy"));
    }

    @Test
    void escapeGlobEscapesMetacharacters() {
        assertTrue(PermissionRule.escapeGlob("git commit -m \"a*b\"").contains("\\*"));
        assertTrue(PermissionRule.escapeGlob("git status ?").contains("\\?"));
    }
}
