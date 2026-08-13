package com.acode.ui;

import com.acode.provider.ChatListener;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 把流式 ChatListener 回调渲染进输出区：onDelta → MarkdownRenderer 增量着色 →
 * 替换输出区当前的回复块；onToolUse → 提交已流出文本并追加「运行中」工具卡片；
 * 工具执行完成后调用 updateToolCalls() 把卡片批量更新为成功/失败 + 结果摘要。
 * onComplete 收尾；onError 清掉半截回复并显示错误。
 * redraw 由上层（T12 主循环）注入，每次增量后触发终端重绘。
 */
public class StreamPrinter implements ChatListener {

    private final OutputPane output;
    private final Runnable redraw;
    private MarkdownRenderer renderer = new MarkdownRenderer();
    /** 当前回复块占用的行数（完成后归零，行留在输出区作历史）。 */
    private int responseLines = 0;
    /** 本次回复已出现的工具调用卡片（Anthropic 文本块先于 tool_use 块，之后不再有文本）。 */
    private final List<ToolCallDisplay> toolCalls = new ArrayList<>();
    private boolean textFinalized = false;

    public StreamPrinter(OutputPane output, Runnable redraw) {
        this.output = output;
        this.redraw = redraw;
    }

    @Override
    public void onDelta(String delta) {
        if (textFinalized) {
            return;
        }
        renderer.append(delta);
        replaceTail(renderer.render());
    }

    @Override
    public void onToolUse(ToolUseBlock toolUse) {
        if (!textFinalized) {
            replaceTail(renderer.render());
            textFinalized = true;
        }
        ToolCallDisplay card = new ToolCallDisplay(toolUse.name(),
                ToolCallDisplay.summarizeParams(toolUse.input()));
        card.appendRunning(output);
        toolCalls.add(card);
        if (redraw != null) {
            redraw.run();
        }
    }

    /** 工具执行完成后，按顺序把全部卡片更新为终态（成功/失败 + 结果摘要）。 */
    public void updateToolCalls(List<ToolResult> results) {
        if (toolCalls.isEmpty()) {
            return;
        }
        int cardLines = toolCalls.stream().mapToInt(ToolCallDisplay::lineCount).sum();
        output.removeLast(cardLines);
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolResult result = (i < results.size()) ? results.get(i) : null;
            toolCalls.get(i).appendDone(output, result);
        }
        if (redraw != null) {
            redraw.run();
        }
    }

    @Override
    public void onComplete() {
        renderer = new MarkdownRenderer();
        responseLines = 0;
    }

    @Override
    public void onError(ProviderException error) {
        output.removeLast(responseLines);
        responseLines = 0;
        renderer = new MarkdownRenderer();
        String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        output.appendLine("（错误：" + msg + "）");
        if (redraw != null) {
            redraw.run();
        }
    }

    private void replaceTail(String rendered) {
        output.removeLast(responseLines);
        responseLines = 0;
        if (!rendered.isEmpty()) {
            int before = output.lineCount();
            output.append(rendered);
            responseLines = output.lineCount() - before;
        }
        if (redraw != null) {
            redraw.run();
        }
    }
}
