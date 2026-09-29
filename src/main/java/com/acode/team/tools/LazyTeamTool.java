package com.acode.team.tools;

import com.acode.team.TeamSession;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Supplier;

/** Defer session binding until the controller's project and conversation are initialized. */
public final class LazyTeamTool implements Tool {
    private final String name;
    private final Supplier<TeamSession> session;
    public LazyTeamTool(String name, Supplier<TeamSession> session) { this.name = name; this.session = session; }
    private Tool delegate() { return session.get().leadTools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(); }
    public String name() { return name; }
    public Permission permission() { return name.equals("TaskGet") || name.equals("TaskList") ? Permission.READ : Permission.WRITE; }
    public String description() { return delegate().description(); }
    public JsonNode inputSchema() { return delegate().inputSchema(); }
    public ToolResult execute(JsonNode input, ToolContext context) { return delegate().execute(input, context); }
}
