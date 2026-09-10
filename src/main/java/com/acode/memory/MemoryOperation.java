package com.acode.memory;

/**
 * 提取模型返回的一条操作。{@code name} 是短横线 slug，兼作索引标题与文件名主体。
 */
public record MemoryOperation(Op op, MemoryType type, String name, String description, String body) {

    public enum Op { CREATE, UPDATE, DELETE }

    public String fileName() {
        return type.slug() + "-" + name + ".md";
    }

    /** 结构合法性：任一项不合法即整轮放弃（宁可漏记，不可错写） */
    public boolean valid() {
        return op != null && type != null && name != null
                && MemoryFile.NAME.matcher(name).matches()
                && (op == Op.DELETE || (description != null && !description.isBlank()));
    }
}
