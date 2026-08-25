package com.acode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acode.permission.PermissionMode.Decision;
import com.acode.tool.Permission;
import org.junit.jupiter.api.Test;

class PermissionModeTest {

    @Test
    void defaultAllowsReadAsksWriteAndExec() {
        assertEquals(Decision.ALLOW, PermissionMode.DEFAULT.decide(Permission.READ));
        assertEquals(Decision.ASK, PermissionMode.DEFAULT.decide(Permission.WRITE));
        assertEquals(Decision.ASK, PermissionMode.DEFAULT.decide(Permission.EXEC));
    }

    @Test
    void acceptEditsAllowsReadAndWriteAsksExec() {
        assertEquals(Decision.ALLOW, PermissionMode.ACCEPT_EDITS.decide(Permission.READ));
        assertEquals(Decision.ALLOW, PermissionMode.ACCEPT_EDITS.decide(Permission.WRITE));
        assertEquals(Decision.ASK, PermissionMode.ACCEPT_EDITS.decide(Permission.EXEC));
    }

    @Test
    void planMatchesDefaultForEveryCategory() {
        for (Permission category : Permission.values()) {
            assertEquals(
                    PermissionMode.DEFAULT.decide(category),
                    PermissionMode.PLAN.decide(category),
                    "PLAN 应委托 DEFAULT：" + category);
        }
    }

    @Test
    void bypassAllowsEveryCategory() {
        for (Permission category : Permission.values()) {
            assertEquals(Decision.ALLOW, PermissionMode.BYPASS.decide(category));
        }
    }
}
