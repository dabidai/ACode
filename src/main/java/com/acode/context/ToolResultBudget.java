package com.acode.context;

import com.acode.provider.ToolResultBlock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 大结果入历史预算闸门：对一批新产生的工具结果返回应入历史的内容列表。
 * <ul>
 *   <li>单条正文超过保留上限 → 全文幂等落盘，历史用「定长预览 + 可读文件路径」替换；</li>
 *   <li>同批合计超过聚合上限 → 从未落盘的最大者开始补落盘，直至合计回落；</li>
 *   <li>同 id 重复传入 → 原样重放已冻结预览、不重新决策不重写文件；</li>
 *   <li>≤ 上限的全文保留。</li>
 * </ul>
 * 错误（isError）的超长结果同样走落盘规则，不因错误而跳过。
 */
public class ToolResultBudget {

    /** 一批新产生待评估的工具结果 */
    public record Item(String toolUseId, String content, boolean isError) {
    }

    private final ContextPolicy policy;
    private final SpillStore spillStore;
    private final ContentReplacementState state;

    /** 本批内单个结果的记账：effective 为当前生效文本（全文或预览） */
    private static final class Pending {
        final String id;
        final boolean isError;
        String effective;   // 全文或预览
        boolean spilled;    // 已落盘替换（历史放预览）
        boolean spillFailed; // 尝试落盘但写盘失败：跳过不再重试（防死循环）
        final int originalLength;

        Pending(String id, String content, boolean isError, boolean spilled) {
            this.id = id;
            this.isError = isError;
            this.effective = content;
            this.spilled = spilled;
            this.originalLength = content == null ? 0 : content.length();
        }
    }

    public ToolResultBudget(ContextPolicy policy, SpillStore spillStore, ContentReplacementState state) {
        this.policy = policy;
        this.spillStore = spillStore;
        this.state = state;
    }

    public ContentReplacementState state() {
        return state;
    }

    /** 清空冻结记账（/clear 等历史重置点联动；落盘文件不删） */
    public void resetState() {
        state.reset();
    }

    /** 处理一批工具结果，返回按声明顺序应写入历史的 ToolResultBlock 列表 */
    public List<ToolResultBlock> process(List<Item> items) {
        List<Pending> pending = new ArrayList<>(items.size());
        for (Item item : items) {
            String content = item.content() == null ? "" : item.content();
            String frozen = state.previewFor(item.toolUseId());
            if (frozen != null) {
                // 已冻结：重放预览，不重新决策不重写
                pending.add(new Pending(item.toolUseId(), frozen, item.isError(), true));
                continue;
            }
            if (content.length() > policy.SINGLE_RESULT_KEEP_LIMIT_CHARS) {
                Pending p = new Pending(item.toolUseId(), content, item.isError(), false);
                spill(p);
                pending.add(p);
            } else {
                pending.add(new Pending(item.toolUseId(), content, item.isError(), false));
            }
        }
        reduceAggregate(pending);
        List<ToolResultBlock> blocks = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            blocks.add(new ToolResultBlock(p.id, p.effective, p.isError));
        }
        return blocks;
    }

    /** 把单个未落盘结果落盘并换成预览（冻结决策）；写盘失败返回 false、保留全文（不静默截断不丢信息） */
    private boolean spill(Pending p) {
        String path;
        try {
            path = spillStore.store(p.id, p.effective);
        } catch (IOException e) {
            // 落盘失败：不回退到静默截断，保留全文入历史（宁可占预算也不丢信息）
            p.spillFailed = true;
            return false;
        }
        String preview = previewFor(path, p.effective);
        state.record(p.id, preview);
        p.effective = preview;
        p.spilled = true;
        return true;
    }

    /** 同批合计超过聚合上限 → 从未落盘的最大者开始补落盘，直到合计回落。
     *  写盘失败的项跳过不再重试；全部尽力后仍超限则降级保留全文——绝不空转、绝不静默截断。 */
    private void reduceAggregate(List<Pending> pending) {
        long total = pending.stream().mapToLong(p -> p.effective.length()).sum();
        while (total > policy.BATCH_AGGREGATE_LIMIT_CHARS) {
            Pending candidate = null;
            for (Pending p : pending) {
                if (!p.spilled && !p.spillFailed
                        && (candidate == null || p.originalLength > candidate.originalLength)) {
                    candidate = p;
                }
            }
            if (candidate == null) {
                break; // 全部已落盘或已落盘失败 → 降级：余下保留全文入历史
            }
            long before = candidate.effective.length();
            spill(candidate);
            total += candidate.effective.length() - before;
        }
    }

    /** 生成落盘预览文本：完整结果路径 + 可用读文件工具取回全文的指引 + 省略标记（常量文案含三要素） */
    String previewFor(String path, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("【完整结果过长，已全文保存】\n");
        sb.append("保存路径：").append(path).append("\n");
        sb.append("完整内容可用 ReadFile 读取该文件取回（文件路径见上），勿凭下方预览猜细节。\n");
        sb.append("【正文预览】\n");
        int limit = Math.min(content.length(), policy.PREVIEW_LENGTH_CHARS);
        sb.append(content, 0, limit);
        sb.append("\n…（完整结果过长，已省略，共 ").append(content.length())
                .append(" 字符；请用 ReadFile 读取保存路径中的文件获取全文）");
        return sb.toString();
    }
}
