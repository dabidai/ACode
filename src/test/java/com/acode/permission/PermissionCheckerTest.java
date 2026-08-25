package com.acode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acode.permission.PermissionChecker.CheckResult;
import com.acode.permission.PermissionMode.Decision;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionCheckerTest {

    @TempDir
    Path projectRoot;

    private final ObjectMapper mapper = new ObjectMapper();
    private RuleEngine ruleEngine;
    private Path projectRulesFile;

    @BeforeEach
    void setUp() throws IOException {
        projectRulesFile = projectRoot.resolve(".acode/permissions.yaml");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.createDirectories(projectRoot.resolve(".acode/plans"));
    }

    private PermissionChecker checker(PermissionMode mode) {
        ruleEngine = new RuleEngine(
                projectRoot.resolve("user-none/permissions.yaml"),
                projectRulesFile,
                projectRoot.resolve("local-none/permissions.local.yaml"));
        return new PermissionChecker(mode, projectRoot, ruleEngine);
    }

    private ObjectNode args(String key, String value) {
        ObjectNode n = mapper.createObjectNode();
        n.put(key, value);
        return n;
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

    /** 带 contentField 声明的匿名工具：模拟新工具按权限分类而非名字硬编码 */
    private Tool fieldTool(String name, Permission permission, String contentField) {
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

            @Override
            public String contentField() {
                return contentField;
            }
        };
    }

    // ---- 内容提取 ----

    @Test
    void extractContentMapsToolFields() {
        assertEquals("ls -la", PermissionChecker.extractContent(
                tool("Bash", Permission.EXEC), args("command", "ls -la")));
        assertEquals("a.txt", PermissionChecker.extractContent(
                tool("ReadFile", Permission.READ), args("file_path", "a.txt")));
        assertEquals("a.txt", PermissionChecker.extractContent(
                tool("WriteFile", Permission.WRITE), args("file_path", "a.txt")));
        assertEquals("a.txt", PermissionChecker.extractContent(
                tool("EditFile", Permission.WRITE), args("file_path", "a.txt")));
        assertEquals("**/*.java", PermissionChecker.extractContent(
                tool("Glob", Permission.READ), args("pattern", "**/*.java")));
        assertEquals("class Foo", PermissionChecker.extractContent(
                tool("Grep", Permission.READ), args("pattern", "class Foo")));
    }

    @Test
    void extractContentUnregisteredToolReturnsNull() {
        assertNull(PermissionChecker.extractContent(tool("Foo", Permission.READ), args("file_path", "x")));
        assertNull(PermissionChecker.extractContent(tool("Bash", Permission.EXEC), mapper.createObjectNode()));
    }

    // ---- #8：权限分类按 permission/contentField，而非工具名字符串 ----

    @Test
    void nonBashNamedExecToolStillTriggersDangerousCommand() {
        CheckResult r = checker(PermissionMode.BYPASS)
                .check(fieldTool("Shell", Permission.EXEC, "command"), args("command", "rm -rf /"));
        assertEquals(Decision.DENY, r.decision(), "不叫 Bash 的 EXEC 工具也应被危险命令黑名单硬拦截");
        assertTrue(r.reason().contains("危险命令"));
    }

    @Test
    void nonBashNamedExecToolSafeCommandAutoAllowed() {
        CheckResult r = checker(PermissionMode.BYPASS)
                .check(fieldTool("Shell", Permission.EXEC, "command"), args("command", "ls -la"));
        assertEquals(Decision.ALLOW, r.decision(), "EXEC 工具的安全只读命令应自动放行");
    }

    @Test
    void newWriteToolWithPathFieldEnforcedBySandbox() {
        String outside = Path.of(System.getProperty("user.home")).resolve("secret.txt").toString();
        CheckResult r = checker(PermissionMode.DEFAULT)
                .check(fieldTool("MoveFile", Permission.WRITE, "file_path"), args("file_path", outside));
        assertEquals(Decision.DENY, r.decision(), "新写工具声明 file_path 内容字段后应受路径沙箱约束（项目外拒绝）");
    }

    @Test
    void writeToolWithoutContentFieldFallsToModeMatrixAsk() {
        CheckResult r = checker(PermissionMode.DEFAULT).check(tool("MoveFile", Permission.WRITE), args("x", "y"));
        assertEquals(Decision.ASK, r.decision(), "无内容字段的新写工具按模式矩阵 ASK（default 下写需确认）");
    }

    // ---- 决策链 ----

    @Test
    void dangerousCommandDeniedBeforeAnythingElse() {
        CheckResult r = checker(PermissionMode.BYPASS).check(tool("Bash", Permission.EXEC), args("command", "rm -rf /"));
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("危险命令"));
        assertFalse(r.reason().contains("权限拒绝"));
    }

    @Test
    void safeCommandAllowedWithoutPrompt() {
        CheckResult r = checker(PermissionMode.DEFAULT).check(tool("Bash", Permission.EXEC), args("command", "ls -la"));
        assertEquals(Decision.ALLOW, r.decision());
    }

    @Test
    void sandboxDeniesOutsidePath() {
        String outside = Path.of(System.getProperty("user.home")).resolve("secret.txt").toString();
        CheckResult r = checker(PermissionMode.DEFAULT).check(tool("ReadFile", Permission.READ), args("file_path", outside));
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("超出沙箱范围"));
    }

    @Test
    void sandboxAllowsInsidePathThenModeMatrix() {
        CheckResult r = checker(PermissionMode.DEFAULT).check(tool("ReadFile", Permission.READ), args("file_path", "src/main/java/A.java"));
        assertEquals(Decision.ALLOW, r.decision());
    }

    @Test
    void ruleDenyReturnsRuleReason() throws IOException {
        Files.writeString(projectRulesFile,
                "rules:\n  - rule: ReadFile(*.env*)\n    effect: deny\n");
        CheckResult r = checker(PermissionMode.DEFAULT).check(tool("ReadFile", Permission.READ), args("file_path", ".env"));
        assertEquals(Decision.DENY, r.decision());
        assertTrue(r.reason().contains("规则"));
    }

    @Test
    void ruleAllowShortCircuitsAsk() throws IOException {
        Files.writeString(projectRulesFile,
                "rules:\n  - rule: Bash(git *)\n    effect: allow\n");
        CheckResult r = checker(PermissionMode.DEFAULT).check(tool("Bash", Permission.EXEC), args("command", "git commit -m x"));
        assertEquals(Decision.ALLOW, r.decision());
    }

    @Test
    void sessionAlwaysAllowHitsOnSecondCall() {
        PermissionChecker c = checker(PermissionMode.DEFAULT);
        Tool bash = tool("Bash", Permission.EXEC);
        ObjectNode call = args("command", "git commit -m \"fix\"");
        assertEquals(Decision.ASK, c.check(bash, call).decision());
        c.addAllowAlwaysRule("Bash", "git commit -m \"fix\"");
        assertEquals(Decision.ALLOW, c.check(bash, call).decision());
    }

    @Test
    void defaultModeMatrix() {
        PermissionChecker c = checker(PermissionMode.DEFAULT);
        assertEquals(Decision.ALLOW, c.check(tool("ReadFile", Permission.READ), args("file_path", "src/main/java/A.java")).decision());
        assertEquals(Decision.ASK, c.check(tool("WriteFile", Permission.WRITE), args("file_path", "src/main/java/A.java")).decision());
        assertEquals(Decision.ASK, c.check(tool("Bash", Permission.EXEC), args("command", "git push")).decision());
    }

    @Test
    void bypassModeAllowsAllExceptBlacklist() {
        PermissionChecker c = checker(PermissionMode.BYPASS);
        assertEquals(Decision.ALLOW, c.check(tool("WriteFile", Permission.WRITE), args("file_path", "src/main/java/A.java")).decision());
        assertEquals(Decision.ALLOW, c.check(tool("Bash", Permission.EXEC), args("command", "git push")).decision());
        // 黑名单最高优先：bypass 下 rm -rf / 仍 DENY
        assertEquals(Decision.DENY, c.check(tool("Bash", Permission.EXEC), args("command", "rm -rf /")).decision());
    }

    @Test
    void planModeAllowsPlansDirWriteAsksOtherWrite() {
        PermissionChecker c = checker(PermissionMode.PLAN);
        assertEquals(Decision.ALLOW, c.check(tool("ReadFile", Permission.READ), args("file_path", "src/main/java/A.java")).decision());
        assertEquals(Decision.ALLOW, c.check(tool("WriteFile", Permission.WRITE), args("file_path", ".acode/plans/plan-x.md")).decision());
        assertEquals(Decision.ASK, c.check(tool("WriteFile", Permission.WRITE), args("file_path", "src/main/java/A.java")).decision());
    }

    @Test
    void planModeRejectsTraversalEscapeFromPlansDir() {
        PermissionChecker c = checker(PermissionMode.PLAN);
        // .acode/plans/../secret.txt 逃逸：不命中计划例外，走常规决策（ASK）
        assertEquals(Decision.ASK, c.check(tool("WriteFile", Permission.WRITE), args("file_path", ".acode/plans/../secret.txt")).decision());
    }

    @Test
    void planModeSymlinkInPlansDirEscapingDeniedBySandbox() throws IOException {
        // 计划目录内符号链接指向外部：沙箱先于 plan 例外拦截（防沙箱逃逸）
        Path outside = Path.of(System.getProperty("user.home")).resolve("acode-plans-escape.txt");
        Files.writeString(outside, "secret");
        try {
            Path link = projectRoot.resolve(".acode/plans/link");
            Files.createSymbolicLink(link, outside);
            PermissionChecker c = checker(PermissionMode.PLAN);
            assertEquals(Decision.DENY,
                    c.check(tool("WriteFile", Permission.WRITE), args("file_path", ".acode/plans/link")).decision());
        } catch (IOException | UnsupportedOperationException e) {
            // 无权限创建符号链接时跳过该用例
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void planModeSessionAlwaysAllowOverridesPlanLimitAcrossModes() {
        PermissionChecker c = checker(PermissionMode.PLAN);
        String outsidePlan = "src/main/java/A.java";
        assertEquals(Decision.ASK, c.check(tool("WriteFile", Permission.WRITE), args("file_path", outsidePlan)).decision());
        c.addAllowAlwaysRule("WriteFile", outsidePlan);
        assertEquals(Decision.ALLOW, c.check(tool("WriteFile", Permission.WRITE), args("file_path", outsidePlan)).decision());
        // 切回 default 后同会话规则仍生效（跨模式）
        c.setMode(PermissionMode.DEFAULT);
        assertEquals(Decision.ALLOW, c.check(tool("WriteFile", Permission.WRITE), args("file_path", outsidePlan)).decision());
    }

    @Test
    void missingArgsFallsToModeMatrixNoException() {
        PermissionChecker c = checker(PermissionMode.DEFAULT);
        assertEquals(Decision.ALLOW, c.check(tool("ReadFile", Permission.READ), mapper.createObjectNode()).decision());
        assertEquals(Decision.ASK, c.check(tool("WriteFile", Permission.WRITE), mapper.createObjectNode()).decision());
        assertEquals(Decision.ASK, c.check(tool("Bash", Permission.EXEC), mapper.createObjectNode()).decision());
    }

    @Test
    void unregisteredToolContentNullFallsToModeMatrix() {
        PermissionChecker c = checker(PermissionMode.DEFAULT);
        // 未注册工具：content=null → 跳过内容层 → 落模式矩阵
        assertEquals(Decision.ALLOW, c.check(tool("Foo", Permission.READ), args("file_path", "/etc/passwd")).decision());
        assertEquals(Decision.ASK, c.check(tool("Foo", Permission.WRITE), args("file_path", "/etc/passwd")).decision());
    }

    @Test
    void setModeIsVolatileVisibleToConcurrentReads() {
        PermissionChecker c = checker(PermissionMode.DEFAULT);
        assertEquals(Decision.ASK, c.check(tool("WriteFile", Permission.WRITE), args("file_path", "src/main/java/A.java")).decision());
        c.setMode(PermissionMode.ACCEPT_EDITS);
        assertEquals(Decision.ALLOW, c.check(tool("WriteFile", Permission.WRITE), args("file_path", "src/main/java/A.java")).decision());
    }
}
