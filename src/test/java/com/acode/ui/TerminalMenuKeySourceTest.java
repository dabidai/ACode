package com.acode.ui;

import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalMenuKeySourceTest {

    /** 脚本化字节序列的假 NonBlockingReader：只实现两个抽象方法，不触真实终端。 */
    static class FakeReader extends NonBlockingReader {
        private final int[] script;
        private int pos;
        private int consumed;

        FakeReader(int... script) {
            this.script = script;
        }

        int consumed() {
            return consumed;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        protected int read(long timeout, boolean isPeek) throws IOException {
            if (pos >= script.length) {
                return READ_EXPIRED; // 终端仍开、暂无输入
            }
            int value = script[pos];
            if (!isPeek) {
                pos++;
                consumed++;
            }
            return value;
        }

        @Override
        public int readBuffered(char[] b, int off, int len, long timeout) throws IOException {
            int n = 0;
            while (n < len && pos < script.length) {
                b[off + n++] = (char) script[pos++];
                consumed++;
            }
            return n;
        }
    }

    private static int readKey(int... bytes) {
        return new TerminalMenuKeySource(new FakeReader(bytes)).readKey();
    }

    @Test
    void enterKeyMappedFromCarriageReturnAndNewline() {
        assertEquals(MenuKeySource.KEY_ENTER, readKey('\r'));
        assertEquals(MenuKeySource.KEY_ENTER, readKey('\n'));
    }

    @Test
    void ctrlCMappedToCancel() {
        assertEquals(MenuKeySource.KEY_CANCEL, readKey(0x03));
    }

    @Test
    void csiArrowMappedToUp() {
        assertEquals(MenuKeySource.KEY_UP, readKey(0x1b, '[', 'A'));
    }

    @Test
    void ss3ArrowMappedToDown() {
        assertEquals(MenuKeySource.KEY_DOWN, readKey(0x1b, 'O', 'B'));
    }

    @Test
    void bareEscMappedToCancel() {
        // 裸 Esc：peek 无后续字节（READ_EXPIRED）→ 取消
        assertEquals(MenuKeySource.KEY_CANCEL, readKey(0x1b));
    }

    @Test
    void eofMappedToCancel() {
        assertEquals(MenuKeySource.KEY_CANCEL, readKey(NonBlockingReader.EOF));
    }

    @Test
    void unknownCsiFinalByteMappedToNone() {
        assertEquals(MenuKeySource.KEY_NONE, readKey(0x1b, '[', 'C')); // 右方向键暂不识别
    }

    @Test
    void unknownKeyMappedToNone() {
        assertEquals(MenuKeySource.KEY_NONE, readKey('a'));
    }

    @Test
    void drainPendingInputConsumesResidualBytes() {
        FakeReader reader = new FakeReader('x', 'y', 'z');
        TerminalMenuKeySource source = new TerminalMenuKeySource(reader);
        source.drainPendingInput();
        assertEquals(3, reader.consumed(), "排空应消费全部残留字节");
    }

    @Test
    void drainPendingInputIsNoOpWhenEmpty() {
        FakeReader reader = new FakeReader();
        TerminalMenuKeySource source = new TerminalMenuKeySource(reader);
        source.drainPendingInput();
        assertEquals(0, reader.consumed(), "无残留时排空应立即返回");
    }
}
