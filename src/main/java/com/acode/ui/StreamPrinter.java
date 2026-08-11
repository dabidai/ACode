package com.acode.ui;

import com.acode.provider.ChatListener;
import com.acode.provider.ProviderException;

/**
 * 把流式 ChatListener 回调渲染进输出区：onDelta → MarkdownRenderer 增量着色 →
 * 替换输出区当前的回复块；onComplete 收尾；onError 清掉半截回复并显示错误。
 * redraw 由上层（T12 主循环）注入，每次增量后触发终端重绘。
 */
public class StreamPrinter implements ChatListener {

    private final OutputPane output;
    private final Runnable redraw;
    private MarkdownRenderer renderer = new MarkdownRenderer();
    /** 当前回复块占用的行数（完成后归零，行留在输出区作历史）。 */
    private int responseLines = 0;

    public StreamPrinter(OutputPane output, Runnable redraw) {
        this.output = output;
        this.redraw = redraw;
    }

    @Override
    public void onDelta(String delta) {
        renderer.append(delta);
        replaceTail(renderer.render());
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
