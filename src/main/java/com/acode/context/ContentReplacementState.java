package com.acode.context;

import java.util.HashMap;
import java.util.Map;

/**
 * 替换决策的冻结记账：记录「已决定落盘替换的工具结果」与其对应预览串。
 * 决策只做一次、整会话冻结：同 id 再次传入时原样重放已存预览，不重新决策、不重写文件——
 * 保证历史前缀逐字节稳定，prompt cache 持续命中。
 */
public class ContentReplacementState {

    /** 已落盘替换的 toolUseId → 预览串（含文件路径） */
    private final Map<String, String> previewByToolUseId = new HashMap<>();

    /** 该 id 是否已决定落盘替换 */
    public synchronized boolean isReplaced(String toolUseId) {
        return previewByToolUseId.containsKey(toolUseId);
    }

    /** 重放某 id 的已存预览；未替换过则返回 null */
    public synchronized String previewFor(String toolUseId) {
        return previewByToolUseId.get(toolUseId);
    }

    /** 记录一次落盘替换决策（冻结） */
    public synchronized void record(String toolUseId, String preview) {
        previewByToolUseId.put(toolUseId, preview);
    }

    /** 清空冻结记账（/clear、加载会话等历史重置点联动；落盘文件本身不删） */
    public synchronized void reset() {
        previewByToolUseId.clear();
    }

    /** 当前已冻结的 id 数（测试用） */
    public synchronized int size() {
        return previewByToolUseId.size();
    }
}
