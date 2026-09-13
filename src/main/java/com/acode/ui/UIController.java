package com.acode.ui;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令可用的界面操作接口：命令通过受控操作影响界面，不直接操作终端对象。
 * 菜单条目与标题都由调用方给出（/resume 与 /memory 共用同一个选择入口，接口不内置任何菜单内容）；
 * 只设命令层实际用到的操作——刷新状态栏由主循环自己维护，不进接口。
 */
public interface UIController {

    /** 上下文占用快照：used 为当前估算 token 数、max 为上下文窗口上限 */
    record ContextUsage(int used, int max) {
    }

    /** 追加一行系统消息（进输出区与回滚） */
    void appendSystemMessage(String text);

    /** 把文本当作用户输入发给 Agent（走既有对话入口） */
    void submitUserInput(String text);

    /** 切换规划模式开关 */
    void setPlanMode(boolean enabled);

    /** 读取当前上下文占用 */
    ContextUsage contextUsage();

    /** 弹出交互式选择菜单：条目与标题由调用方给出；返回选中下标，取消返回 -1 */
    int selectMenu(List<MenuEntry> entries, String title);

    /** 清屏并开启新会话（/clear 用）：当前对话保存后开启新会话，然后清空屏幕与输出区。默认 no-op（旧测试桩不必改） */
    default void clearScreenAndNewSession() {
    }

    /** 读取最近一次规划交付的计划落盘位置（/do 用）；无交付记录时为 null。默认返回 null */
    default Path lastDeliveredPlanPath() {
        return null;
    }
}
