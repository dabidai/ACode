package com.acode.team;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Host-bound sender identity. Recipient lookup always uses the current roster. */
public final class TeamMessaging {
    public static final String LEAD = "lead";
    private final Supplier<Team> team;
    private final String actor;
    private final Consumer<String> delivered;

    public TeamMessaging(Supplier<Team> team, String actor) { this(team, actor, recipient -> {}); }
    public TeamMessaging(Supplier<Team> team, String actor, Consumer<String> delivered) {
        this.team = team; this.actor = actor; this.delivered = delivered;
    }

    public int send(String to, String summary, String message, String messageType) {
        String type = messageType == null ? "text" : messageType;
        if (!Set.of("text", "shutdown_request", "shutdown_response", "plan_approval_response").contains(type))
            throw new IllegalArgumentException("不支持的消息类型：" + type);
        if (type.equals("text") || summary != null && !summary.isBlank()) {
            int words = summary == null || summary.isBlank() ? 0 : summary.strip().split("(?U)\\s+").length;
            if (words < 5 || words > 10) throw new IllegalArgumentException("summary 必须为 5～10 个词");
        }
        Team snapshot = team.get();
        boolean isLead = snapshot.leadAgentID().equals(actor);
        if (type.equals("plan_approval_response") && !isLead)
            throw new IllegalArgumentException("只有 Lead 能发送 plan_approval_response");
        String sender = isLead ? LEAD : snapshot.members().stream().filter(m -> m.agentID().equals(actor))
                .map(TeammateInfo::name).findFirst().orElseThrow(() -> new IllegalArgumentException("发送者不在团队中：" + actor));
        List<String> recipients;
        if ("*".equals(to)) recipients = snapshot.members().stream().map(TeammateInfo::name).toList();
        else if (LEAD.equals(to) || snapshot.leadAgentID().equals(to)) recipients = List.of(LEAD);
        else recipients = List.of(snapshot.members().stream().filter(m -> m.name().equals(to) || m.agentID().equals(to))
                .map(TeammateInfo::name).findFirst().orElseThrow(() -> new IllegalArgumentException("收件人不存在：" + to)));
        if (type.equals("shutdown_response") && (!recipients.equals(List.of(LEAD)) || "*".equals(to)))
            throw new IllegalArgumentException("shutdown_response 只能发给 Lead");
        var mailbox = new FileMailbox(snapshot.configPath().getParent());
        for (String recipient : recipients) {
            mailbox.deliver(sender, recipient, summary, message, type);
            delivered.accept(recipient);
        }
        return recipients.size();
    }
}
