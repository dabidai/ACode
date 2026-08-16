package com.acode.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationTest {

    @Test
    void awaitReturnsAnswerWhenPublished() throws Exception {
        Confirmation confirmation = new Confirmation();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread publisher = Thread.ofVirtual().start(() -> {
            started.countDown();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            confirmation.answer(true);
        });
        started.await(1, TimeUnit.SECONDS);
        assertTrue(confirmation.await(cancelled));
        publisher.join(TimeUnit.SECONDS.toMillis(2));
    }

    @Test
    void awaitReturnsFalseWhenAnswerIsReject() throws Exception {
        Confirmation confirmation = new Confirmation();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        confirmation.answer(false);
        assertFalse(confirmation.await(cancelled));
    }

    @Test
    void awaitReturnsFalseWhenCancelledBeforeAnswer() {
        Confirmation confirmation = new Confirmation();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertFalse(confirmation.await(cancelled));
    }

    @Test
    void answerIsIdempotentFirstWins() {
        Confirmation confirmation = new Confirmation();
        confirmation.answer(true);
        confirmation.answer(false);
        assertTrue(confirmation.await(new AtomicBoolean(false)));
    }
}
