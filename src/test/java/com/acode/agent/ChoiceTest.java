package com.acode.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChoiceTest {

    @Test
    void awaitReturnsSelectedWhenAnswered() throws Exception {
        Choice choice = new Choice();
        Thread responder = Thread.ofVirtual().start(() -> choice.answer("B"));
        assertEquals("B", choice.await(new AtomicBoolean(false)));
        responder.join(1000);
    }

    @Test
    void awaitReturnsNullWhenAnsweredWithNull() throws Exception {
        Choice choice = new Choice();
        Thread responder = Thread.ofVirtual().start(() -> choice.answer(null));
        assertNull(choice.await(new AtomicBoolean(false)));
        responder.join(1000);
    }

    @Test
    void awaitReturnsNullWhenCancelled() {
        Choice choice = new Choice();
        assertNull(choice.await(new AtomicBoolean(true)));
    }

    @Test
    void awaitReturnsNullAndRestoresInterruptFlagWhenInterrupted() throws Exception {
        Choice choice = new Choice();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread waiting = new Thread(() -> {
            result.set(choice.await(new AtomicBoolean(false)));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        waiting.start();
        waiting.interrupt();
        waiting.join(1000);
        assertNull(result.get(), "中断应返回 null");
        assertTrue(interrupted.get(), "await 被中断应恢复中断位");
    }

    @Test
    void answerIsIdempotentFirstWins() throws Exception {
        Choice choice = new Choice();
        choice.answer("A");
        choice.answer("B"); // 队列容量 1，第二次 offer 忽略
        assertEquals("A", choice.await(new AtomicBoolean(false)));
    }
}
