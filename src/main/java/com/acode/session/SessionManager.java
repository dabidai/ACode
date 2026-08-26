package com.acode.session;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.HistoryRenderer;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import com.acode.ui.SelectionMenu;
import com.acode.ui.TerminalMenuKeySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 会话持久化：恢复/选择/加载/保存与历史消息渲染。 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final SessionStore sessionStore;
    private final Conversation conversation;
    private OutputPane output;
    private RenderContext renderContext;
    private AcodeTerminal tui;

    /** 最近一次加载/恢复的会话消息对象（对象同一性比较：resume 后未新增消息则退出不重复存档）。 */
    private List<ChatMessage> loadedMessages;

    public SessionManager(SessionStore sessionStore, Conversation conversation) {
        this.sessionStore = sessionStore;
        this.conversation = conversation;
    }

    /** 绑定 UI（start() 与委托路径调用；幂等）。 */
    public void attachUi(OutputPane output, RenderContext renderContext, AcodeTerminal tui) {
        this.output = output;
        this.renderContext = renderContext;
        this.tui = tui;
    }

    public void restoreIfResume(boolean resume) {
        if (!resume) {
            return;
        }
        Optional<Session> latest = sessionStore.readLatest();
        if (latest.isEmpty()) {
            output.appendLine("（没有可恢复的会话）");
            renderContext.liveRenderer().appendCommitted(renderContext.screenWriter(), "（没有可恢复的会话）");
            return;
        }
        Session session = latest.get();
        loadedMessages = session.getMessages();
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        for (ChatMessage message : session.getMessages()) {
            conversation.addMessage(message);
            appendHistoryMessage(message, live, writer);
        }
        output.appendLine("（已恢复会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
        live.appendCommitted(writer, "（已恢复会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
    }

    /**
     * /resume：列出历史会话，↑/↓ 选择、回车加载、Esc 取消。
     * 菜单作为活跃区 overlay 渲染：只重绘屏幕底部、不进回滚；选定/取消后清掉菜单，历史再追加。
     */
    public void selectSession() {
        List<Session> sessions = sessionStore.list();
        if (sessions.isEmpty()) {
            output.appendLine("（没有可恢复的会话）");
            renderContext.liveRenderer().appendCommitted(renderContext.screenWriter(), "（没有可恢复的会话）");
            return;
        }
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        live.commitRegion(); // 上次活跃区已留在屏上作历史，菜单从下方空白处画起
        List<String> entries = new ArrayList<>();
        for (Session session : sessions) {
            entries.add(session.getId() + "  " + session.getMessages().size() + " 条 · " + preview(session));
        }
        int selected = new SelectionMenu(entries, "（↑/↓ 选择会话，回车加载，Esc 取消）", sessions.size() - 1)
                .select(live, writer, new TerminalMenuKeySource(tui.terminal().reader()));
        if (selected >= 0) {
            loadSession(sessions.get(selected));
        } else {
            output.appendLine("（已取消）");
            live.appendCommitted(writer, "（已取消）");
        }
    }

    private static String preview(Session session) {
        for (ChatMessage m : session.getMessages()) {
            if (m.role() == ChatMessage.Role.USER) {
                String text = m.content().replace('\n', ' ').trim();
                return text.length() > 30 ? text.substring(0, 30) + "…" : text;
            }
        }
        return "（无用户消息）";
    }

    /** 加载会话即替换当前视图：先清屏（滚动缓冲保留）+ 重置输出模型，历史再追加式渲染进回滚。 */
    public void loadSession(Session session) {
        conversation.clear();
        loadedMessages = session.getMessages();
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        live.clearScreen(writer);
        output.clear();
        output.appendLine("（已加载会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
        live.appendCommitted(writer, "（已加载会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
        for (ChatMessage message : session.getMessages()) {
            conversation.addMessage(message);
            appendHistoryMessage(message, live, writer);
        }
    }

    /** 把一条历史消息渲染进回滚：内容模型 + 活跃区追加式写屏，工具块压缩为单行摘要。 */
    private void appendHistoryMessage(ChatMessage message, LiveRegionRenderer live, Writer writer) {
        String rendered = HistoryRenderer.renderHistoryMessage(message);
        if (rendered.isEmpty()) {
            return;
        }
        if (message.role() == ChatMessage.Role.USER) {
            output.append("● " + rendered + "\n");
            live.appendCommitted(writer, "● " + rendered);
        } else {
            output.append(rendered);
            live.appendCommitted(writer, rendered);
        }
    }

    /** 退出时把完整历史存为新会话文件；空会话不存；resume 后未新增消息的原样会话也不重复存档。 */
    public void saveSession() {
        if (conversation.messageCount() == 0) {
            return;
        }
        if (isUnchangedReload()) {
            return;
        }
        try {
            sessionStore.save(new Session(null, System.currentTimeMillis(), conversation.history()));
        } catch (RuntimeException e) {
            log.warn("保存会话失败：{}", e.getMessage());
        }
    }

    /** conversation 是否仍是某次加载/恢复的原样内容（同一批消息对象、数量未增也未清空重来）。 */
    private boolean isUnchangedReload() {
        if (loadedMessages == null) {
            return false;
        }
        List<ChatMessage> history = conversation.history();
        if (history.size() != loadedMessages.size()) {
            return false;
        }
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) != loadedMessages.get(i)) {
                return false;
            }
        }
        return true;
    }
}
