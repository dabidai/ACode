package com.acode.ui;

import com.acode.provider.ChatListener;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolResult;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 把流式 ChatListener 回调双写：内容模型（OutputPane，已提交内容快照）+ 追加式写屏。
 * 追加式：每个完成的渲染行（以换行结尾）经 appendCommitted 写屏一次，原生折行进回滚、
 * 可划选、永不再改——不发射任何光标操作序列，宽度失配/终端差异不可能造成错位。
 * 未完成尾行暂不显示，等换行到达（或 finishTurn/onComplete 定稿）再提交；
 * onError 时丢弃（本就没写屏，无需清理）。工具卡片为静态两行：先「⏳ 调用工具」，
 * 结果到达后追加终态行。内容模型行为不变（运行中卡片不进模型、终态卡片进模型）。
 */
public class StreamPrinter implements ChatListener {

    private final OutputPane output;
    private final LiveRegionRenderer live;
    private final Writer writer;
    private final boolean teeEnabled;
    private MarkdownRenderer renderer = new MarkdownRenderer();
    /** 当前回复块占用的模型行数（完成后保留在模型中作历史）。 */
    private int responseLines = 0;
    /** 本次回复已出现的工具调用卡片（Anthropic 文本块先于 tool_use 块，之后不再有文本）。 */
    private final List<ToolCallDisplay> toolCalls = new ArrayList<>();
    private boolean textFinalized = false;
    /** 当前文本块已提交进回滚的完整渲染行（去重：onDelta 全量 render，只追加新完成行）。 */
    private final List<String> committedLines = new ArrayList<>();

    public StreamPrinter(OutputPane output, LiveRegionRenderer live, Writer writer, boolean teeEnabled) {
        this.output = output;
        this.live = live;
        this.writer = writer;
        this.teeEnabled = teeEnabled;
    }

    @Override
    public void onDelta(String delta) {
        diag("delta", delta);
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
        flushCards();
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
        flushCards();
    }

    @Override
    public void onComplete() {
        textFinalized = true;
        flushCompletedLines();
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
        live.appendCommitted(writer, errorLine);
    }

    /** 轮次收尾：剩余尾行与卡片转正进回滚，状态归零（供下一轮复用）。 */
    public void finishTurn() {
        textFinalized = true;
        flushCompletedLines();
        flushCards();
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
        flushCompletedLines();
    }

    /**
     * 追加新完成的渲染行进回滚（只写一次）。完整行数 = 文本已定稿或原文以换行结尾
     * ? 全部行 : 除最后一行外（未完成尾行暂不显示）；依据 renderer 原文判定。
     */
    private void flushCompletedLines() {
        String rendered = renderer.render();
        if (rendered.isEmpty() && !renderer.endsWithNewline()) {
            return; // 空轮次收尾不写多余空行
        }
        List<String> colorized = splitLines(rendered);
        int complete = (textFinalized || renderer.endsWithNewline()) ? colorized.size() : colorized.size() - 1;
        for (int i = committedLines.size(); i < complete && i < colorized.size(); i++) {
            live.appendCommitted(writer, colorized.get(i) + "\n");
            committedLines.add(colorized.get(i));
            diag("commit[" + (committedLines.size() - 1) + "]", colorized.get(i));
        }
    }

    /** 诊断：tee 开启时把每个 delta / 提交行追加到独立日志（字节级）。 */
    private void diag(String tag, String content) {
        try {
            if (!teeEnabled) {
                return;
            }
            String line = tag + " :: " + content.replace("\r", "\\r").replace("\n", "\\n") + "\n";
            Files.write(Path.of("acode-streamprinter.log"), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 诊断日志失败不影响主流程
        }
    }

    /** 追加各卡片尚未写屏的渲染行进回滚（运行中一行、终态一行，各只写一次）。 */
    private void flushCards() {
        for (ToolCallDisplay card : toolCalls) {
            List<String> lines = card.renderedLines();
            int from = card.screenAppended();
            if (from < lines.size()) {
                for (int i = from; i < lines.size(); i++) {
                    live.appendCommitted(writer, lines.get(i) + "\n");
                }
                card.markAppended(lines.size());
            }
        }
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
