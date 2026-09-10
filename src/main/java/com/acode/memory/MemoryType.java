package com.acode.memory;

import java.util.List;

/**
 * 四类长期记忆与归属根。用户偏好 / 纠正反馈跨项目共享（用户级），
 * 项目知识 / 参考信息随项目走（项目级）。
 */
public enum MemoryType {

    PROJECT("project", MemoryScope.Kind.PROJECT),
    REFERENCE("reference", MemoryScope.Kind.PROJECT),
    USER("user", MemoryScope.Kind.USER),
    FEEDBACK("feedback", MemoryScope.Kind.USER);

    private final String slug;
    private final MemoryScope.Kind scopeKind;

    MemoryType(String slug, MemoryScope.Kind scopeKind) {
        this.slug = slug;
        this.scopeKind = scopeKind;
    }

    public String slug() {
        return slug;
    }

    public MemoryScope.Kind scopeKind() {
        return scopeKind;
    }

    /** 固定枚举顺序：项目级在前、用户级在后——与索引注入顺序同源 */
    public static List<MemoryType> inOrder() {
        return List.of(PROJECT, REFERENCE, USER, FEEDBACK);
    }

    /** 按 slug 解析；非四类之一返回 null */
    public static MemoryType fromSlug(String slug) {
        for (MemoryType type : values()) {
            if (type.slug.equals(slug)) {
                return type;
            }
        }
        return null;
    }
}
