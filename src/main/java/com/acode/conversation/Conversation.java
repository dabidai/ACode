package com.acode.conversation;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ContentBlock;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 对话编排：维护完整消息历史，组装请求时按上下文窗口上限从最早消息开始丢弃。
 * token 估算按字符数 ÷ 4 粗略计算；兜底规则：当前问题本身超限时只保留该问题（避免死循环）。
 */
public class Conversation {

    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private volatile String model;
    private final boolean thinking;
    private final int maxTokens;
    private final int maxContextTokens;

    /** 历史代次：每次新 exchange 递增，旧 agent 线程的迟到写入被带代次校验忽略（防取消后并发写） */
    private long epoch = 0;

    /** 会话级 system 提示词：会话启动构建一次，会话内字节稳定（可缓存）；不进历史 */
    private String systemPrompt;

    /** 会话级环境快照（渲染好的环境 system-reminder）：每轮作为 messages 首条注入、不进历史 */
    private ChatMessage environment;

    /** 最近一次 provider 回传的真实 prompt token 数；0 表示还没拿到，ctx 占用退回字符估算 */
    private volatile long lastPromptTokens = 0;

    public Conversation(String model, boolean thinking, int maxTokens, int maxContextTokens) {
        this.model = model;
        this.thinking = thinking;
        this.maxTokens = maxTokens;
        this.maxContextTokens = maxContextTokens;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int maxContextTokens() {
        return maxContextTokens;
    }

    /** 上下文占用比例 [0,1]：优先用 provider 回传的真实 prompt token，尚未拿到时退回字符估算。 */
    public double contextUsageFraction() {
        if (maxContextTokens <= 0) {
            return 0;
        }
        long tokens = lastPromptTokens > 0 ? lastPromptTokens : estimateContextTokens();
        return Math.min(1.0, (double) tokens / maxContextTokens);
    }

    /**
     * 记录 provider 回传的真实上下文占用（{@link com.acode.provider.Usage#promptTokens()}，
     * 已按协议口径归一：Anthropic 含缓存读/写，OpenAI 的 prompt_tokens 本就含缓存）。
     */
    public void recordPromptTokens(long promptTokens) {
        if (promptTokens > 0) {
            lastPromptTokens = promptTokens;
        }
    }

    /** 估算上下文占用：历史 + 会话级 system 提示词 + 环境快照（后两者不进历史，但每轮都发）。 */
    private long estimateContextTokens() {
        long total = 0;
        for (ChatMessage msg : messages) {
            total += estimateTokens(msg);
        }
        if (systemPrompt != null) {
            total += estimateTokens(systemPrompt);
        }
        if (environment != null) {
            total += estimateTokens(environment);
        }
        return total;
    }

    /** 追加一条消息到完整历史；截断只发生在组装请求时，不改变已存历史 */
    public void addMessage(ChatMessage message) {
        messages.add(message);
    }

    /** 带代次校验的追加：仅当代次仍当前时写入（旧 agent 线程的迟到写入被忽略） */
    public synchronized void addMessage(long epoch, ChatMessage message) {
        if (epoch == this.epoch) {
            messages.add(message);
        }
    }

    /** 把一批工具执行结果作为一条 user 消息追加进历史（Anthropic 要求同批 tool_result 放一条消息） */
    public void addToolResults(List<ToolResultBlock> results) {
        messages.add(new ChatMessage(ChatMessage.Role.USER, new ArrayList<>(results)));
    }

    /** 带代次校验的结果追加：与 addMessage(long, ...) 同理，防旧线程残留写入错乱历史 */
    public synchronized void addToolResults(long epoch, List<ToolResultBlock> results) {
        if (epoch == this.epoch) {
            messages.add(new ChatMessage(ChatMessage.Role.USER, new ArrayList<>(results)));
        }
    }

    /** 开启新的历史代次并返回其编号；与带代次写入同锁，杜绝"校验后、写入前被抢"的半写窗口 */
    public synchronized long nextEpoch() {
        return ++epoch;
    }

    /** 当前代次 */
    public synchronized long currentEpoch() {
        return epoch;
    }

    public int messageCount() {
        return messages.size();
    }

    /** 设置会话级 system 提示词（不进历史，只进请求首位） */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /** 设置会话级环境 system-reminder（渲染好的消息；每轮注入 messages 首条、不进历史） */
    public void setEnvironment(ChatMessage environment) {
        this.environment = environment;
    }

    /** 清空全部消息历史（/clear 用）。system prompt 与环境快照留在会话状态，下一轮仍注入。 */
    public void clear() {
        messages.clear();
        lastPromptTokens = 0; // 真实用量随历史一起作废，否则页脚还显示清空前的 ctx 占用
    }

    public List<ChatMessage> history() {
        return Collections.unmodifiableList(messages);
    }

    /** 按字符数 ÷ 4 估算 token 数 */
    public static int estimateTokens(String text) {
        return text.length() / 4;
    }

    /** 估算一条消息的 token：遍历所有内容块（文本、工具参数、工具结果都计入） */
    public static int estimateTokens(ChatMessage message) {
        int sum = 0;
        for (ContentBlock block : message.blocks()) {
            sum += switch (block) {
                case TextBlock t -> estimateTokens(t.text());
                case ToolUseBlock tu -> estimateTokens(tu.name()) + estimateTokens(String.valueOf(tu.input()));
                case ToolResultBlock tr -> estimateTokens(tr.content());
            };
        }
        return sum;
    }

    /**
     * 组装请求：按「system → 环境 → 历史 → 轮次级」四段拼接，四段都在 trim 之外独立注入、不进历史。
     * systemPrompt 非空时首位为 SYSTEM 消息；environment 非空时紧跟一条环境 system-reminder（会话状态）；
     * turnReminder 非空时尾插为最后一条 user 消息（近因效应）。工具列表独立传递，不随历史裁剪。
     */
    public ChatRequest buildRequest(List<Tool> requestTools, ChatMessage turnReminder) {
        List<ChatMessage> requestMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestMessages.add(ChatMessage.of(ChatMessage.Role.SYSTEM, systemPrompt));
        }
        if (environment != null) {
            requestMessages.add(environment);
        }
        requestMessages.addAll(trim());
        if (turnReminder != null) {
            requestMessages.add(turnReminder);
        }
        return ChatRequest.builder()
                .model(model)
                .thinking(thinking)
                .maxTokens(maxTokens)
                .tools(requestTools)
                .messages(requestMessages)
                .build();
    }

    private List<ChatMessage> trim() {
        if (estimateTotal(messages) <= maxContextTokens) {
            return sanitize(messages);
        }
        List<ChatMessage> result = new ArrayList<>(messages);
        while (result.size() > 1 && estimateTotal(result) > maxContextTokens) {
            removeTurnUnit(result);
        }
        return sanitize(result);
    }

    private static int estimateTotal(List<ChatMessage> list) {
        return list.stream().mapToInt(Conversation::estimateTokens).sum();
    }

    /**
     * 删除一个"轮次单元"：普通消息按单条删；若首条是 assistant 且含 tool_use，
     * 连同其后紧邻的"全 tool_result 的 user 消息"一起删（Agent 同批结果合一为一条）。
     * 避免拆散 tool_use/tool_result 配对导致孤儿 tool_result 触发 API 400。
     */
    private static void removeTurnUnit(List<ChatMessage> list) {
        ChatMessage first = list.remove(0);
        if (first.role() == ChatMessage.Role.ASSISTANT && containsToolUse(first) && !list.isEmpty()) {
            ChatMessage next = list.get(0);
            if (next.role() == ChatMessage.Role.USER && isAllToolResults(next)) {
                list.remove(0);
            }
        }
    }

    /**
     * 请求出口清洗：剔除无配对的 tool_use / tool_result 块，删空的消息整体丢弃。
     * 覆盖脏会话恢复与 epoch 拦截遗留的悬空 tool_use；干净时返回原列表引用、不改历史。
     */
    private static List<ChatMessage> sanitize(List<ChatMessage> messages) {
        Set<String> useIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        for (ChatMessage m : messages) {
            for (ContentBlock b : m.blocks()) {
                if (b instanceof ToolUseBlock tu) {
                    useIds.add(tu.id());
                } else if (b instanceof ToolResultBlock tr) {
                    resultIds.add(tr.toolUseId());
                }
            }
        }
        List<ChatMessage> cleaned = new ArrayList<>(messages.size());
        boolean dirty = false;
        for (ChatMessage m : messages) {
            List<ContentBlock> kept = new ArrayList<>(m.blocks().size());
            boolean messageDirty = false;
            for (ContentBlock b : m.blocks()) {
                boolean keep = switch (b) {
                    case ToolUseBlock tu -> resultIds.contains(tu.id());
                    case ToolResultBlock tr -> useIds.contains(tr.toolUseId());
                    case TextBlock ignored -> true;
                };
                if (keep) {
                    kept.add(b);
                } else {
                    messageDirty = true;
                }
            }
            if (messageDirty) {
                dirty = true;
                if (!kept.isEmpty()) {
                    cleaned.add(new ChatMessage(m.role(), kept));
                }
            } else {
                cleaned.add(m);
            }
        }
        return dirty ? cleaned : messages;
    }

    private static boolean containsToolUse(ChatMessage message) {
        return message.blocks().stream().anyMatch(b -> b instanceof ToolUseBlock);
    }

    private static boolean isAllToolResults(ChatMessage message) {
        return !message.blocks().isEmpty()
                && message.blocks().stream().allMatch(b -> b instanceof ToolResultBlock);
    }
}
