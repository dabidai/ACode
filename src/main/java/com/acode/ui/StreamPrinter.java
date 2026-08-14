package com.acode.ui;

import com.acode.provider.ChatListener;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolResult;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 把流式 ChatListener 回调双写：内容模型（OutputPane，已提交内容快照）+ 活跃区渲染
 * （LiveRegionRenderer，屏幕底部原地重绘）。onDelta → 模型尾部替换 + 活跃区重绘；
 * onToolUse → 运行中卡片只进活跃区、不提交内容模型；updateToolCalls → 终态卡片写入
 * 内容模型 + 活跃区重绘；onError → 清除半截回复并在活跃区显示错误行。
 */
public class StreamPrinter implements ChatListener {

    private final OutputPane output;
    private final LiveRegionRenderer live;
    private final Writer writer;
    private MarkdownRenderer renderer = new MarkdownRenderer();
    /** 当前回复块占用的模型行数（完成后保留在模型中作历史）。 */
    private int responseLines = 0;
    /** 本次回复已出现的工具调用卡片（Anthropic 文本块先于 tool_use 块，之后不再有文本）。 */
    private final List<ToolCallDisplay> toolCalls = new ArrayList<>();
    private boolean textFinalized = false;

    public StreamPrinter(OutputPane output, LiveRegionRenderer live, Writer writer) {
        this.output = output;
        this.live = live;
        this.writer = writer;
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
        card.appendRunning();
        toolCalls.add(card);
        redrawLive();
    }

    /** 工具执行完成后，按顺序把全部卡片更新为终态（成功/失败 + 结果摘要）并写入内容模型。 */
    public void updateToolCalls(List<ToolResult> results) {
        if (toolCalls.isEmpty()) {
            return;
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolResult result = (i < results.size()) ? results.get(i) : null;
            List<String> lines = toolCalls.get(i).appendDone(result);
            for (String line : lines) {
                output.appendLine(line);
            }
        }
        redrawLive();
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
        toolCalls.clear();
        String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        String errorLine = "（错误：" + msg + "）";
        output.appendLine(errorLine);
        live.redraw(writer, List.of(errorLine));
    }

    private void replaceTail(String rendered) {
        output.removeLast(responseLines);
        responseLines = 0;
        if (!rendered.isEmpty()) {
            int before = output.lineCount();
            output.append(rendered);
            responseLines = output.lineCount() - before;
        }
        redrawLive();
    }

    /** 活跃区渲染行 = 当前回复文本行 + 各卡片当前渲染行；每帧触发一次活跃区重绘。 */
    private void redrawLive() {
        List<String> regionLines = new ArrayList<>();
        String rendered = renderer.render();
        if (!rendered.isEmpty()) {
            regionLines.addAll(splitLines(rendered));
        }
        for (ToolCallDisplay card : toolCalls) {
            regionLines.addAll(card.renderedLines());
        }
        live.redraw(writer, regionLines);
    }

    private static List<String> splitLines(String text) {
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            lines.add(line.replace("\r", ""));
        }
        return lines;
    }
}
