package com.acode;

import com.acode.config.AppConfig;
import com.acode.permission.PermissionResponse;
import com.acode.provider.*;
import com.acode.ui.OutputPane;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TeamIntegrationTest {
    @TempDir Path root;
    @Test void leadUsesRegisteredTeamToolsThroughMainExchange() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", root.resolve("home").toString());
        ConversationController controller = null;
        try {
            var json = new ObjectMapper();
            var provider = FakeProvider.scripted(List.of(
                    List.of(FakeProvider.toolUse("create", "TeamCreate", json.readTree("{\"team_name\":\"team\"}")), FakeProvider.complete()),
                    List.of(FakeProvider.toolUse("task", "TaskCreate", json.readTree("{\"title\":\"main flow task\"}")), FakeProvider.complete()),
                    List.of(FakeProvider.toolUse("list", "TaskList", json.createObjectNode()), FakeProvider.complete()),
                    List.of(FakeProvider.toolUse("delete", "TeamDelete", json.readTree("{\"team_name\":\"team\"}")), FakeProvider.complete()),
                    List.of(FakeProvider.delta("done"), FakeProvider.complete())));
            var config = new AppConfig(); config.setModel("test"); config.setProtocol("anthropic");
            config.setMaxContextTokens(32000); config.setMemoryAuto(false);
            controller = new ConversationController(provider, config, false);
            controller.setProjectRoot(root); controller.setOutput(new OutputPane()); controller.setScreenWriter(new StringWriter());
            controller.setConfirmAnswerer(event -> PermissionResponse.ALLOW);
            controller.handleExchange("建队并创建一个任务，查看后清理团队", () -> false, () -> {});
            assertEquals(5, provider.receivedRequests().size());
            assertTrue(provider.receivedRequests().getFirst().tools().stream().map(t -> t.name()).toList()
                    .containsAll(List.of("TeamCreate", "TeamDelete", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage")));
            var results = controller.conversation().history().stream().flatMap(m -> m.blocks().stream())
                    .filter(b -> b instanceof ToolResultBlock).map(b -> (ToolResultBlock) b).toList();
            assertEquals(4, results.size()); assertTrue(results.stream().noneMatch(ToolResultBlock::isError));
            assertTrue(results.get(2).content().contains("main flow task"));
            assertFalse(Files.exists(root.resolve(".acode/teams/team")));
        } finally {
            if (controller != null) { controller.closeSession(); controller.closeMcpManager(); }
            System.setProperty("user.home", home);
        }
    }
}
