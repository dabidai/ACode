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
    /** 真实终端路径的渲染器单例：跨组件共享 rowsWritten 等定位状态。 */
    private LiveRegionRenderer cachedLive;
    private Writer screenWriter;
    private AcodeTerminal tui;

    public RenderContext(AppConfig config) {
        this.config = config;
    }

    /** 绑定真实终端（start() 调用）；测试注入路径不调用。换终端时缓存失效。 */
    public void attachTui(AcodeTerminal tui) {
        if (this.tui != tui) {
            cachedLive = null;
        }
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

    /**
     * 活跃区渲染器：测试注入优先；真实终端路径下**建一次、全程复用**。
     * 复用是必需的——rowsWritten 记录「活跃区已写行数」，重绘时靠它上移回区顶；
     * 若每次调用都新建，各调用点（本轮对话 / 会话菜单 / 命令输出）各记各的账，
     * 上移行数就会与实际屏幕内容对不上、重绘错位。尺寸仍是随读随取（构造器收的是 supplier）。
     */
    public LiveRegionRenderer liveRenderer() {
        // 测试注入的假渲染器
        if (live != null) {
            return live;
        }
        // 真实终端：单例复用，跨组件共享定位状态
        if (tui != null) {
            if (cachedLive == null) {
                cachedLive = new LiveRegionRenderer(tui::width, tui::height);
            }
            return cachedLive;
        }
        // 无终端时不缓存：避免测试间共享状态
        return new LiveRegionRenderer(80, 24);
    }

    /** 活跃区输出目标：测试注入优先，否则用终端 writer；无终端时丢弃到 StringWriter。 */
    public Writer screenWriter() {
        // 测试注入的StringWriter
        if (screenWriter != null) {
            return screenWriter;
        }
        if (tui != null) {
            Writer w = tui.terminal().writer();
            if (!config.isTeeEnabled()) {
                return w;
            }
            // 输出日志
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

        /** 输出内容的同时也写入了日志 */
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
