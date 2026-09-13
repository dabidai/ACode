package com.acode.ui;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 通用单选菜单：渲染 → ↑/↓ 环形移动 → Enter 选中 / Esc/EOF 取消。
 * 支持不可选的分隔行（{@link MenuEntry#separator()}）：不高亮、上下键跳过、回车不会落到它身上；
 * 不传分隔行（全部可选项）时行为与改动前逐字相同。
 * 菜单作为活跃区 overlay：redraw 只重绘屏幕底部、不进回滚；clear 收敛在本类所有退出路径（Enter/Esc），
 * 调用方无需再手动清。
 */
public class SelectionMenu {

    /** 分隔行文案（对照 checklist 的 /memory 菜单示例：两空格缩进 + 横线） */
    static final String SEPARATOR_LINE = "  " + "─".repeat(114);

    private final List<MenuEntry> entries;
    private final String header;
    private final int initialSelected;

    public SelectionMenu(List<String> options, String header, int initialSelected) {
        this(options.stream().map(MenuEntry::item).toList(), header, initialSelected);
    }

    /** 带分隔行的菜单（构造器与 {@link #SelectionMenu(List, String, int)} 擦除冲突，故用静态工厂） */
    public static SelectionMenu of(List<MenuEntry> entries, String header, int initialSelected) {
        return new SelectionMenu(entries, header, initialSelected);
    }

    /** 用 Collection 形参避开与 List 构造器的擦除冲突 */
    private SelectionMenu(Collection<MenuEntry> entries, String header, int initialSelected) {
        this.entries = List.copyOf(entries);
        this.header = header;
        this.initialSelected = initialSelected;
    }

    /**
     * 阻塞选择：返回选中 index（entries 下标，0..size-1），取消返回 -1。
     * 退出（Enter/Esc）时已 clear 活跃区，返回后 rowsWritten 为 0。
     */
    public int select(LiveRegionRenderer live, Writer writer, MenuKeySource keys) {
        keys.drainPendingInput();
        int selected = initialSelected;
        while (true) {
            live.redraw(writer, render(selected));
            switch (keys.readKey()) {
                case MenuKeySource.KEY_UP -> selected = move(selected, -1);
                case MenuKeySource.KEY_DOWN -> selected = move(selected, 1);
                case MenuKeySource.KEY_ENTER -> {
                    live.clear(writer);
                    return selected;
                }
                case MenuKeySource.KEY_CANCEL -> {
                    live.clear(writer);
                    return -1;
                }
                default -> {
                    // 忽略无关按键，保持菜单
                }
            }
        }
    }

    /** 沿方向找下一个可选项（环形移动、跳过分隔行）；无任何可选项时原地不动 */
    private int move(int from, int delta) {
        int n = entries.size();
        if (n == 0) {
            return from;
        }
        int idx = from;
        do {
            idx = (idx + delta + n) % n;
            if (entries.get(idx).selectable()) {
                return idx;
            }
        } while (idx != from);
        return from;
    }

    private List<String> render(int selected) {
        List<String> lines = new ArrayList<>();
        if (header != null && !header.isEmpty()) {
            lines.add(header);
        }
        for (int i = 0; i < entries.size(); i++) {
            MenuEntry entry = entries.get(i);
            if (!entry.selectable()) {
                lines.add(SEPARATOR_LINE);
            } else if (i == selected) {
                lines.add("\033[7m> " + entry.label() + "\033[0m");
            } else {
                lines.add("  " + entry.label());
            }
        }
        return lines;
    }
}
