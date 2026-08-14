package com.acode.ui;

import com.acode.provider.ChatListener;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolResult;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 把流式 ChatListener 回调双写：内容模型（OutputPane，已提交内容快照）+ 追加式写屏
 * （LiveRegionRenderer footer 路径）。已完成的文本行（以换行结尾）只写一次进原生回滚
 * （可划选、永不再改）；当前未完成行 + 工具卡片作为 footer，每行截断到终端宽度、
 * 恰占一个物理行、可精确上移重绘——宽度失配不再累积错位。
 * onDelta → 模型尾部替换 + footer 重绘；onToolUse → 文本定稿、运行中卡片进 footer；
 * updateToolCalls → 终态卡片写入内容模型 + footer 重绘；onError → 清 footer、错误行作已提交行。
 * finishTurn → 轮次收尾：剩余文本与终态卡片转正进回滚、状态归零。
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
    /** 当前文本块已提交进回滚的完整行（footer 只负责未完成行与卡片）。 */
    private final List<String> committedLines = new ArrayList<>();

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
            textFinalized = true;
            replaceTail(renderer.render());
        }
        ToolCallDisplay card = new ToolCallDisplay(toolUse.name(),
                ToolCallDisplay.summarizeParams(toolUse.input()));
        card.appendRunning();
        toolCalls.add(card);
        renderFooter();
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
        renderFooter();
    }

    @Override
    public void onComplete() {
        textFinalized = true;
        renderFooter();
        renderer = new MarkdownRenderer();
        responseLines = 0;
        committedLines.clear();
        textFinalized = false;
    }

    @Override
    public void onError(ProviderException error) {
        output.removeLast(responseLines);
        responseLines = 0;
        renderer = new MarkdownRenderer();
        committedLines.clear();
        toolCalls.clear();
        textFinalized = false;
        String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        String errorLine = "（错误：" + msg + "）";
        output.appendLine(errorLine);
        live.commitFooter(writer, List.of(errorLine));
    }

    /** 轮次收尾：剩余文本与终态卡片转正进回滚、footer 清零，状态归零（供下一轮复用）。 */
    public void finishTurn() {
        textFinalized = true;
        renderFooter();
        List<String> commit = new ArrayList<>();
        for (ToolCallDisplay card : toolCalls) {
            commit.addAll(card.renderedLines());
        }
        live.commitFooter(writer, commit);
        renderer = new MarkdownRenderer();
        committedLines.clear();
        toolCalls.clear();
        responseLines = 0;
        textFinalized = false;
    }

    private void replaceTail(String rendered) {
        output.removeLast(responseLines);
        responseLines = 0;
        if (!rendered.isEmpty()) {
            int before = output.lineCount();
            output.append(rendered);
            responseLines = output.lineCount() - before;
        }
        renderFooter();
    }

    /**
     * footer 重绘：新完成的完整行提交进回滚（只写一次），未完成行 + 卡片作为 footer。
     * 完整行数 = 文本已定稿或原文以换行结尾 ? 全部行 : 除最后一行外；依据 renderer 原文判定。
     */
    private void renderFooter() {
        List<String> newly = new ArrayList<>();
        List<String> footer = new ArrayList<>();
        String rendered = renderer.render();
        if (!rendered.isEmpty()) {
            List<String> colorized = splitLines(rendered);
            int complete = (textFinalized || renderer.endsWithNewline()) ? colorized.size() : colorized.size() - 1;
            if (complete > committedLines.size()) {
                for (int i = committedLines.size(); i < complete && i < colorized.size(); i++) {
                    newly.add(colorized.get(i));
                }
                committedLines.addAll(newly);
            }
            if (complete < colorized.size()) {
                footer.add(colorized.get(complete));
            }
        }
        for (ToolCallDisplay card : toolCalls) {
            footer.addAll(card.renderedLines());
        }
        live.redrawFooter(writer, newly, footer);
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
