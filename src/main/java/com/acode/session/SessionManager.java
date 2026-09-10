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

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 会话持久化：持有活跃会话句柄、维护 /resume 列表与选择、渲染加载出来的历史。
 * 恢复四步与压缩联动不在这里——选中后回调 {@code loader}（由 ConversationController 注入）。
 */
public final class SessionManager {

    private final SessionStore sessionStore;
    private final SessionRecorder recorder;

    private OutputPane output;
    private RenderContext renderContext;
    private AcodeTerminal tui;

    /** 选中会话后的加载动作（ConversationController 注入）；缺省空操作，便于单测单独装配 */
    private Consumer<Session> loader = session -> { };

    public SessionManager(SessionStore sessionStore, Conversation conversation) {
        this.sessionStore = sessionStore;
        this.recorder = new SessionRecorder(sessionStore);
        // 会话侧持久化：消息追加逐条落盘，压缩重建整段重写
        conversation.addAppendListener(recorder::append);
        conversation.addRebuildListener(recorder::rewrite);
    }

    public SessionStore store() {
        return sessionStore;
    }

    public SessionRecorder recorder() {
        return recorder;
    }

    public void setLoader(Consumer<Session> loader) {
        if (loader != null) {
            this.loader = loader;
        }
    }

    /** 绑定 UI（start() 与委托路径调用；幂等）。 */
    public void attachUi(OutputPane output, RenderContext renderContext, AcodeTerminal tui) {
        this.output = output;
        this.renderContext = renderContext;
        this.tui = tui;
    }

    /**
     * /resume：列出历史会话（活跃在前、过期有标注），↑/↓ 选择、回车加载、Esc 取消。
     * 菜单作为活跃区 overlay 渲染：只重绘屏幕底部、不进回滚；选定/取消后清掉菜单，历史再追加。
     */
    public void selectSession() {
        List<Session> sessions = sessionStore.list();
        if (sessions.isEmpty()) {
            notice("（没有可恢复的会话）");
            return;
        }
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        live.commitRegion(); // 上次活跃区已留在屏上作历史，菜单从下方空白处画起
        List<String> entries = entryLabels(sessions);
        int selected = new SelectionMenu(entries, "（↑/↓ 选择会话，回车加载，Esc 取消）", sessions.size() - 1)
                .select(live, writer, new TerminalMenuKeySource(tui.terminal().reader()));
        if (selected >= 0) {
            open(sessions.get(selected));
        } else {
            notice("（已取消）");
        }
    }

    /** 菜单条目文案：id + 条数 +（已过期）+ 首条用户消息预览 */
    static List<String> entryLabels(List<Session> sessions) {
        List<String> entries = new ArrayList<>(sessions.size());
        for (Session session : sessions) {
            entries.add(session.id() + "  " + session.messages().size() + " 条"
                    + (session.expired() ? "（已过期）" : "") + " · " + preview(session));
        }
        return entries;
    }

    /** 打开一个会话：交给注入的加载动作（菜单选中走这里；测试可直接驱动）。 */
    public void open(Session session) {
        loader.accept(session);
    }

    private static String preview(Session session) {
        for (ChatMessage message : session.messages()) {
            if (message.role() == ChatMessage.Role.USER) {
                String text = message.content().replace('\n', ' ').trim();
                return text.length() > 30 ? text.substring(0, 30) + "…" : text;
            }
        }
        return "（无用户消息）";
    }

    /**
     * 把加载出来的历史渲染进回滚：先清屏（滚动缓冲保留）+ 重置输出模型，历史再追加式渲染。
     * 渲染的是截断后的消息列表（与真正进会话的内容一致）。
     */
    public void renderLoaded(String action, String id, List<ChatMessage> messages) {
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        live.clearScreen(writer);
        output.clear();
        String banner = "（已" + action + "会话 " + id + "，共 " + messages.size() + " 条消息）";
        output.appendLine(banner);
        live.appendCommitted(writer, banner);
        for (ChatMessage message : messages) {
            appendHistoryMessage(message, live, writer);
        }
    }

    /** 输出一行提示（进回滚）。 */
    public void notice(String line) {
        output.appendLine(line);
        renderContext.liveRenderer().appendCommitted(renderContext.screenWriter(), line);
    }

    /** 退出路径：关闭活跃会话句柄（不整存、不新建文件）；幂等 */
    public void closeSession() {
        recorder.close();
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
}
