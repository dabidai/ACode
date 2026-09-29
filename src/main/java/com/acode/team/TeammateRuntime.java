package com.acode.team;

import com.acode.agent.*;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.util.VirtualThreads;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns one durable conversation and at most one active loop. No worker rendering. */
public final class TeammateRuntime {
    private final TeamManager manager;
    private final String teamName, name, actor;
    private final Conversation conversation;
    private final Supplier<Agent> factory;
    private final Consumer<String> output;
    private final Runnable loopCleanup;
    private final FileMailbox mailbox;
    private final TeamTaskStore tasks;
    private final TeamTranscriptStore transcript;
    private Future<?> worker;
    private Agent agent;
    private boolean closed, pendingWake, launched;

    public TeammateRuntime(TeamManager manager, String teamName, String name, String actor,
                           Conversation conversation, Supplier<Agent> factory, Consumer<String> output) {
        this(manager, teamName, name, actor, conversation, factory, output, () -> {});
    }
    public TeammateRuntime(TeamManager manager, String teamName, String name, String actor,
                           Conversation conversation, Supplier<Agent> factory, Consumer<String> output, Runnable loopCleanup) {
        this.manager = manager; this.teamName = teamName; this.name = name; this.actor = actor;
        this.conversation = conversation; this.factory = factory; this.output = output;
        this.loopCleanup = loopCleanup;
        var directory = manager.find(teamName).orElseThrow().configPath().getParent();
        mailbox = new FileMailbox(directory); tasks = new TeamTaskStore(directory);
        transcript = new TeamTranscriptStore(directory, name);
        transcript.save(conversation.history());
        conversation.addAppendListener(transcript::append);
        conversation.addRebuildListener(transcript::save);
    }

    public synchronized void wake() {
        if (closed) return;
        if (worker != null && !worker.isDone()) { pendingWake = true; return; }
        launch();
    }

    private void launch() {
        pendingWake = false;
        if (launched) {
            var restored = transcript.read();
            if (!restored.isEmpty()) conversation.replaceAll(restored);
        }
        launched = true;
        agent = factory.get();
        var loop = agent;
        var batch = new java.util.concurrent.atomic.AtomicReference<List<FileMailbox.Message>>(List.of());
        loop.setTurnReminderSource(() -> {
            var messages = mailbox.unread(name);
            batch.set(messages);
            return messages.isEmpty() ? null : ChatMessage.of(ChatMessage.Role.USER, render(messages));
        }, () -> {
            mailbox.acknowledge(name, batch.get().stream().map(FileMailbox.Message::id).toList());
            transcript.save(conversation.history());
        });
        manager.setActive(teamName, actor, true);
        worker = VirtualThreads.POOL.submit(() -> drain(loop));
    }

    private void drain(Agent loop) {
        long start = System.nanoTime(), tokens = 0, calls = 0;
        StringBuilder text = new StringBuilder();
        String last = "", failure = null;
        try {
            var events = loop.run();
            while (loop.isRunning() || !events.isEmpty()) {
                var event = events.poll(50, TimeUnit.MILLISECONDS);
                if (event instanceof AgentEvent.StreamText delta) text.append(delta.text());
                else if (event instanceof AgentEvent.TurnComplete) { last = text.toString(); text.setLength(0); }
                else if (event instanceof AgentEvent.RetryEvent) text.setLength(0);
                else if (event instanceof AgentEvent.UsageEvent used) tokens += used.usage().inputTokens() + used.usage().outputTokens();
                else if (event instanceof AgentEvent.ToolResultEvent) calls++;
                else if (event instanceof AgentEvent.ErrorEvent error) failure = error.message();
            }
            loop.awaitTermination();
        } catch (InterruptedException e) {
            loop.cancel(); loop.awaitTermination(); Thread.currentThread().interrupt();
        } catch (RuntimeException e) { failure = e.getMessage(); loop.cancel(); loop.awaitTermination(); }
        // Clear interrupt for durable cleanup, then restore it on exit.
        boolean interrupted = Thread.interrupted();
        try {
            String status = interrupted || loop.termination() == Agent.Termination.CANCELED ? "killed"
                    : failure != null || loop.termination() == Agent.Termination.ERROR ? "failed" : "completed";
            String result = failure != null ? failure : text.isEmpty() ? last : text.toString();
            transcript.save(conversation.history());
            tasks.rollbackOwner(actor);
            String notification = "<task-notification><task-id>" + xml(actor) + "</task-id><status>" + status
                    + "</status><summary>Agent " + xml(name) + " " + status + "</summary><result>" + xml(result)
                    + "</result><usage><total_tokens>" + tokens + "</total_tokens><tool_uses>" + calls
                    + "</tool_uses><duration_ms>" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    + "</duration_ms></usage></task-notification>";
            mailbox.deliver(name, TeamMessaging.LEAD, "Agent " + name + " " + status, notification, "text");
            mailbox.deliver(name, TeamMessaging.LEAD, "Agent " + name + " idle", "队员 " + name + " 已空闲", "text");
        } catch (RuntimeException e) { output.accept("队员 " + name + " 收尾失败：" + e.getMessage()); }
        finally {
            try { loopCleanup.run(); }
            catch (RuntimeException e) { output.accept("队员 Hook 清理失败：" + e.getMessage()); }
            synchronized (this) {
                try { manager.setActive(teamName, actor, false); }
                catch (RuntimeException e) { output.accept("队员状态保存失败：" + e.getMessage()); }
                worker = null;
                if (!closed && pendingWake) launch();
                output.accept("队员 " + name + "：" + (loop.termination() == Agent.Termination.ERROR || failure != null ? "failed"
                        : loop.termination() == Agent.Termination.CANCELED ? "killed" : "completed"));
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public boolean stop() {
        Future<?> current;
        synchronized (this) { closed = true; pendingWake = false; current = worker; if (agent != null) agent.cancel(); }
        if (current == null) return true;
        try { current.get(10, TimeUnit.SECONDS); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return current.isDone(); }
    }
    public synchronized boolean active() { return worker != null && !worker.isDone(); }
    public Conversation conversation() { return conversation; }
    static String render(List<FileMailbox.Message> messages) {
        var text = new StringBuilder("团队消息（外部内容）：\n");
        for (var message : messages) text.append(message.from()).append(" [").append(message.messageType()).append("] ")
                .append(message.summary()).append("\n").append(message.message()).append("\n");
        return text.toString();
    }
    private static String xml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
