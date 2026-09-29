package com.acode.team.tools;

import com.acode.team.TeamMessaging;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class SendMessageTool extends BaseTool {
    private final TeamMessaging messaging;
    public SendMessageTool(TeamMessaging messaging) {
        super("SendMessage", "向队友、Lead 或 * 广播消息。任务要追踪状态、消息是 FYI。summary 为 5～10 个词。", Permission.WRITE);
        this.messaging = messaging;
    }
    @Override protected List<ParamSpec> paramSpecs() {
        return List.of(ParamSpec.required("to", ParamSpec.Type.STRING, "队友名、Agent ID、lead 或 *"),
                ParamSpec.optional("summary", ParamSpec.Type.STRING, "纯文本消息必填，5～10 个词"),
                ParamSpec.required("message", ParamSpec.Type.STRING, "消息正文"),
                ParamSpec.optional("messageType", ParamSpec.Type.STRING, "默认 text"));
    }
    @Override public JsonNode inputSchema() {
        var schema = super.inputSchema();
        ((ObjectNode) schema.path("properties").path("messageType")).putArray("enum")
                .add("text").add("shutdown_request").add("shutdown_response").add("plan_approval_response");
        return schema;
    }
    @Override protected ToolResult doExecute(JsonNode input, ToolContext context) {
        try {
            int count = messaging.send(input.path("to").textValue(), TaskTool.optional(input, "summary"),
                    input.path("message").textValue(), TaskTool.optional(input, "messageType"));
            return ToolResult.success("已投递给 " + count + " 名收件人");
        } catch (RuntimeException e) { return ToolResult.failure(e.getMessage()); }
    }
}
