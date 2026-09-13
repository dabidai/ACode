package com.acode.ui;

/**
 * 选择菜单条目：可选项或纯分隔行。
 * 分隔行不可选：不高亮、上下键跳过、回车不会落到它身上；其文案由菜单渲染，条目本身不带文本。
 */
public record MenuEntry(String label, boolean selectable) {

    /** 可选项 */
    public static MenuEntry item(String label) {
        return new MenuEntry(label, true);
    }

    /** 纯分隔行 */
    public static MenuEntry separator() {
        return new MenuEntry("", false);
    }
}
