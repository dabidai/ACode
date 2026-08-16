package com.acode.ui;

/**
 * 菜单按键源抽象：把终端字节流（或测试脚本化的键序列）解析为语义键。
 * 语义键：KEY_NONE（忽略）/ KEY_UP / KEY_DOWN / KEY_ENTER / KEY_CANCEL（Esc/Ctrl+C/EOF 等价）。
 */
@FunctionalInterface
public interface MenuKeySource {

    int KEY_NONE = 0;
    int KEY_UP = 1;
    int KEY_DOWN = 2;
    int KEY_ENTER = 3;
    int KEY_CANCEL = 4;

    /** 读一个语义键；实现应阻塞等待一个完整按键（方向键为多字节序列）。 */
    int readKey();

    /** 进菜单前排空残留输入；测试桩默认 no-op。 */
    default void drainPendingInput() {
    }
}
