package com.acode.ui;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用单选菜单：渲染 → ↑/↓ 环形移动 → Enter 选中 / Esc/EOF 取消。
 * 菜单作为活跃区 overlay：redraw 只重绘屏幕底部、不进回滚；clear 收敛在本类所有退出路径（Enter/Esc），
 * 调用方无需再手动清。
 */
public class SelectionMenu {

    private final List<String> options;
    private final String header;
    private final int initialSelected;

    public SelectionMenu(List<String> options, String header, int initialSelected) {
        this.options = options;
        this.header = header;
        this.initialSelected = initialSelected;
    }

    /**
     * 阻塞选择：返回选中 index（0..size-1），取消返回 -1。
     * 退出（Enter/Esc）时已 clear 活跃区，返回后 rowsWritten 为 0。
     */
    public int select(LiveRegionRenderer live, Writer writer, MenuKeySource keys) {
        keys.drainPendingInput();
        int selected = initialSelected;
        while (true) {
            live.redraw(writer, render(selected));
            switch (keys.readKey()) {
                case MenuKeySource.KEY_UP ->
                        selected = (selected - 1 + options.size()) % options.size();
                case MenuKeySource.KEY_DOWN -> selected = (selected + 1) % options.size();
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

    private List<String> render(int selected) {
        List<String> lines = new ArrayList<>();
        if (header != null && !header.isEmpty()) {
            lines.add(header);
        }
        for (int i = 0; i < options.size(); i++) {
            lines.add(i == selected
                    ? "\033[7m> " + options.get(i) + "\033[0m"
                    : "  " + options.get(i));
        }
        return lines;
    }
}
