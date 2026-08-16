package com.acode.ui;

import org.jline.utils.NonBlockingReader;

import java.io.IOException;

/**
 * 包装 JLine NonBlockingReader 的按键源：识别 CSI `\033[A` 与 SS3 `\033OA` 方向键、Enter、Ctrl+C、裸 Esc、EOF。
 * 逻辑抽取自 ConversationController 原 /resume 菜单 readMenuKey/drainPendingInput；
 * 与旧实现唯一行为差异：EOF(-1) 由「忽略（KEY_NONE）」改为「取消（KEY_CANCEL）」，消除 EOF 死循环风险。
 */
public class TerminalMenuKeySource implements MenuKeySource {

    private final NonBlockingReader reader;

    public TerminalMenuKeySource(NonBlockingReader reader) {
        this.reader = reader;
    }

    @Override
    public int readKey() {
        try {
            int c = reader.read();
            if (c == '\r' || c == '\n') {
                return KEY_ENTER;
            }
            if (c == 0x03) {
                return KEY_CANCEL;
            }
            if (c == 0x1b) {
                int next = reader.peek(50);
                if (next == '[' || next == 'O') {
                    reader.read(0); // 消费 '[' 或 'O'
                    int ch = reader.read(50);
                    if (ch == 'A') {
                        return KEY_UP;
                    }
                    if (ch == 'B') {
                        return KEY_DOWN;
                    }
                    return KEY_NONE;
                }
                return KEY_CANCEL; // 裸 Esc
            }
            if (c == -1) {
                return KEY_CANCEL; // EOF 视为取消
            }
            return KEY_NONE;
        } catch (IOException e) {
            return KEY_NONE;
        }
    }

    @Override
    public void drainPendingInput() {
        try {
            long deadline = System.currentTimeMillis() + 50;
            while (System.currentTimeMillis() < deadline) {
                if (reader.peek(5) == NonBlockingReader.READ_EXPIRED) {
                    break;
                }
                reader.read(0);
            }
        } catch (IOException e) {
            // 读取失败视为无残留
        }
    }
}
