package com.acode.team;

import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamApprovalTest {
    @Test void malformedRepliesCannotGrantAndRejectionRevokesExistingGrant() throws Exception {
        var approval = new TeamApproval();
        var call = new ToolUseBlock("id", "WriteFile", new ObjectMapper().readTree("{\"file_path\":\"a.txt\"}"));
        for (String invalid : new String[]{"", "null", "{}", "{", "{\"approved\":true}", "{\"approved\":true,\"operations\":[{}]}"}) {
            assertThrows(IllegalArgumentException.class, () -> approval.reply(invalid));
            assertFalse(approval.permits(call));
        }
        approval.reply("{\"approved\":true,\"operations\":[{\"tool\":\"WriteFile\",\"input\":{\"file_path\":\"a.txt\"}}]}");
        assertTrue(approval.permits(call));
        approval.reply("{\"approved\":false}");
        assertFalse(approval.permits(call));
    }
}
