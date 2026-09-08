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
 * 对话编排：维护完整消息历史，组装请求时把「system → 环境 → 历史 → 轮次级」整体带出。
 * 本阶段移除「组装请求时从最早消息静默裁剪」的兜底：超窗交给上下文管理的自动/紧急压缩守卫；
 * 残余的单条消息自身超窗走可见错误，绝不静默丢历史。token 估算按字符数 ÷ 4 粗略计算。
 */
public class Conversation {

    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final String model;
    private final boolean thinking;
    private final int maxTokens;
    private final int maxContextTokens;

    /** 历史代次：每次新 exchange 递增，旧 agent 线程的迟到写入被带代次校验忽略（防取消后并发写） */
    private long epoch = 0;

    /** 会话级 system 提示词：会话启动构建一次，会话内字节稳定（可缓存）；不进历史 */
    private String systemPrompt;

    /** 会话级环境快照（渲染好的环境 system-reminder）：每轮作为 messages 首条注入、不进历史 */
    private ChatMessage environment;

    /** clear 钩子：/clear、加载会话等清空点联动重置运行期状态（上下文管理冻结/熔断，见 ch07） */
    private final List<Runnable> clearHooks = new CopyOnWriteArrayList<>();

    public Conversation(String model, boolean thinking, int maxTokens, int maxContextTokens) {
        this.model = model;
        this.thinking = thinking;
        this.maxTokens = maxTokens;
        this.maxContextTokens = maxContextTokens;
    }

    /** 追加一条消息到完整历史（不分代次，无条件写入） */
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

    /** 原子替换整段历史（重建/压缩专用）：与 nextEpoch / 带代次写入同锁，重建期间旧线程迟到写入被忽略 */
    public synchronized void replaceAll(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
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

    /** 注册清空钩子：clear() 时在清空完成后触发（ch07 用于重置冻结/熔断等运行期状态） */
    public void addClearHook(Runnable hook) {
        if (hook != null) {
            clearHooks.add(hook);
        }
    }

    /** 清空全部消息历史（/clear 用）。system prompt 与环境快照留在会话状态，下一轮仍注入。 */
    public void clear() {
        messages.clear();
        for (Runnable hook : clearHooks) {
            hook.run();
        }
    }

    public List<ChatMessage> history() {
        return Collections.unmodifiableList(messages);
    }

    /** 请求级模型名（摘要等独立请求复用同一模型） */
    public String model() {
        return model;
    }

    /** 上下文窗口上限（token）：由配置注入，压缩触发点据此计算 */
    public int maxContextTokens() {
        return maxContextTokens;
    }

    /** 按字符数 ÷ 4 估算 token 数 */
    public static int estimateTokens(String text) {
        return text == null ? 0 : text.length() / 4;
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
     * 整体估算「当前请求将携带的内容」token：系统提示 + 环境快照 + 全部历史消息加总。
     * 供压缩触发判断、自动触发守卫与压缩前后对比展示统一使用（口径与 checklist 一致）。
     */
    public int estimateContextTokens() {
        int sum = 0;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sum += estimateTokens(systemPrompt);
        }
        if (environment != null) {
            sum += estimateTokens(environment);
        }
        sum += estimateMessages(messages);
        return sum;
    }

    private static int estimateMessages(List<ChatMessage> list) {
        return list.stream().mapToInt(Conversation::estimateTokens).sum();
    }

    /**
     * 组装请求：按「system → 环境 → 历史 → 轮次级」四段拼接。systemPrompt 非空时首位为 SYSTEM 消息；
     * environment 非空时紧跟一条环境 system-reminder（会话状态）；turnReminder 非空时尾插为最后一条
     * user 消息（近因效应）。历史整体原样带出（仅过 sanitize 清洗），不做运行时最旧裁剪——
     * 超窗由上下文管理守卫处理（残余单条超窗走可见错误）。工具列表独立传递，不随历史裁剪。
     */
    public ChatRequest buildRequest(List<Tool> requestTools, ChatMessage turnReminder) {
        List<ChatMessage> requestMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestMessages.add(ChatMessage.of(ChatMessage.Role.SYSTEM, systemPrompt));
        }
        if (environment != null) {
            requestMessages.add(environment);
        }
        requestMessages.addAll(sanitize(messages));
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

    /**
     * 请求出口清洗：剔除无配对的 tool_use / tool_result 块，删空的消息整体丢弃。
     * 覆盖脏会话恢复与 epoch 拦截遗留的悬空 tool_use；干净时返回原列表引用、不改历史。
     * 公开静态：摘要重建（ch07）也用同一清洗校验无孤儿工具块。
     */
    public static List<ChatMessage> sanitize(List<ChatMessage> messages) {
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
}
