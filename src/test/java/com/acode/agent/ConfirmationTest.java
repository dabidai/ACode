package com.acode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acode.permission.PermissionResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

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
            confirmation.answer(PermissionResponse.ALLOW);
        });
        started.await(1, TimeUnit.SECONDS);
        assertEquals(PermissionResponse.ALLOW, confirmation.await(cancelled));
        publisher.join(TimeUnit.SECONDS.toMillis(2));
    }

    @Test
    void awaitReturnsDenyWhenAnswerIsReject() {
        Confirmation confirmation = new Confirmation();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        confirmation.answer(PermissionResponse.DENY);
        assertEquals(PermissionResponse.DENY, confirmation.await(cancelled));
    }

    @Test
    void awaitReturnsAllowAlways() {
        Confirmation confirmation = new Confirmation();
        confirmation.answer(PermissionResponse.ALLOW_ALWAYS);
        assertEquals(PermissionResponse.ALLOW_ALWAYS, confirmation.await(new AtomicBoolean(false)));
    }

    @Test
    void awaitReturnsDenyWhenCancelledBeforeAnswer() {
        Confirmation confirmation = new Confirmation();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertEquals(PermissionResponse.DENY, confirmation.await(cancelled));
    }

    @Test
    void answerIsIdempotentFirstWins() {
        Confirmation confirmation = new Confirmation();
        confirmation.answer(PermissionResponse.ALLOW_ALWAYS);
        confirmation.answer(PermissionResponse.DENY);
        assertEquals(PermissionResponse.ALLOW_ALWAYS, confirmation.await(new AtomicBoolean(false)));
    }
}
