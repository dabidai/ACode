package com.acode.ui;

import com.acode.config.AppConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;

/** 活跃区渲染设施：live region 渲染器与屏幕输出目标的装配（测试注入优先、终端实时新建、tee 诊断）。 */
public final class RenderContext {

    private final AppConfig config;
    private LiveRegionRenderer live;
    private Writer screenWriter;
    private AcodeTerminal tui;

    public RenderContext(AppConfig config) {
        this.config = config;
    }

    /** 绑定真实终端（start() 调用）；测试注入路径不调用。 */
    public void attachTui(AcodeTerminal tui) {
        this.tui = tui;
    }

    /** 测试用：注入活跃区渲染器（断言流式重绘；真实流程按终端尺寸新建） */
    public void setLive(LiveRegionRenderer live) {
        this.live = live;
    }

    /** 测试用：注入活跃区输出目标（真实流程用终端 writer） */
    public void setScreenWriter(Writer writer) {
        this.screenWriter = writer;
    }

    /** 活跃区渲染器：测试注入优先，否则按终端尺寸实时新建（窗口变化随读随取）。 */
    public LiveRegionRenderer liveRenderer() {
        if (live != null) {
            return live;
        }
        if (tui != null) {
            return new LiveRegionRenderer(tui::width, tui::height);
        }
        return new LiveRegionRenderer(80, 24);
    }

    /** 活跃区输出目标：测试注入优先，否则用终端 writer；无终端时丢弃到 StringWriter。 */
    public Writer screenWriter() {
        if (screenWriter != null) {
            return screenWriter;
        }
        if (tui != null) {
            Writer w = tui.terminal().writer();
            if (!config.isTeeEnabled()) {
                return w;
            }
            TeeWriter tw = new TeeWriter(w);
            try {
                tw.logOnly("\n== ACODE TEE w=" + tui.width() + " h=" + tui.height() + " ==\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return tw;
        }
        return new StringWriter();
    }

    /** 诊断用：把写进终端的每个字节按原样追加到日志文件（含 ANSI 与 \r\n），tee 开关控制。 */
    private static final class TeeWriter extends Writer {
        private final Writer target;
        private final BufferedWriter log;

        TeeWriter(Writer target) {
            this.target = target;
            try {
                this.log = new BufferedWriter(new java.io.FileWriter("acode-terminal.log", true));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            target.write(cbuf, off, len);
            log.write("[" + Thread.currentThread().getName() + "]");
            log.write(cbuf, off, len);
            log.flush();
        }

        /** 只写日志文件、不进终端（诊断头信息用）。 */
        void logOnly(String s) throws IOException {
            log.write(s);
            log.flush();
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }
}
