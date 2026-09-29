package com.acode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileMailboxTest {
    @TempDir Path directory;

    @Test void readingWithoutAcknowledgmentKeepsMailForRetryAndLateMailStaysUnread() {
        var mailbox = new FileMailbox(directory, message -> fail(message));
        assertTrue(mailbox.unread("bob").isEmpty());
        var first = mailbox.deliver("alice", "bob", "first", "body", null);
        var batch = mailbox.unread("bob");
        assertEquals(List.of(first), batch);
        assertFalse(first.read());
        assertTrue(first.ts() > 0);
        assertEquals(batch, mailbox.unread("bob"), "failed or cancelled handoff must not consume mail");
        var second = mailbox.deliver("alice", "bob", "second", "body", "text");
        mailbox.acknowledge("bob", batch.stream().map(FileMailbox.Message::id).toList());
        mailbox.acknowledge("bob", List.of(first.id()));
        assertEquals(List.of(second), mailbox.unread("bob"));
        assertEquals(2, mailbox.readAll("bob").size());
        assertTrue(mailbox.readAll("bob").getFirst().read());
        assertEquals(mailbox.readAll("bob"), new FileMailbox(directory).readAll("bob"));
    }

    @Test void concurrentMailboxInstancesDoNotLoseMessages() throws Exception {
        for (int round = 0; round < 3; round++) {
            String recipient = "bob-" + round;
            var start = new CountDownLatch(1);
            var ready = new CountDownLatch(8);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 8; i++) {
                    String sender = "sender-" + i;
                    futures.add(executor.submit(() -> {
                        var mailbox = new FileMailbox(directory);
                        ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int j = 0; j < 5; j++) mailbox.deliver(sender, recipient, "summary", "body-" + j, "text");
                        return null;
                    }));
                }
                try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
                finally { start.countDown(); }
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            }
            var messages = new FileMailbox(directory).unread(recipient);
            assertEquals(40, messages.size());
            assertEquals(40, messages.stream().map(FileMailbox.Message::id).distinct().count());
            assertEquals(40, messages.stream().map(message -> message.from() + message.message()).distinct().count());
        }
    }

    @Test void corruptionIsReportedAndCannotBeOverwrittenByDeliveryOrAcknowledgment() throws Exception {
        var warnings = new ArrayList<String>();
        var mailbox = new FileMailbox(directory, warnings::add);
        mailbox.deliver("alice", "bob", "summary", "body", "text");
        Path file = directory.resolve("mailbox/bob.json");
        Files.writeString(file, "[");
        assertTrue(mailbox.unread("bob").isEmpty());
        assertEquals(1, warnings.size());
        assertThrows(UncheckedIOException.class, () -> mailbox.deliver("alice", "bob", "summary", "body", "text"));
        assertThrows(UncheckedIOException.class, () -> mailbox.acknowledge("bob", List.of("id")));
        assertEquals("[", Files.readString(file));
    }

    @Test void recipientCannotEscapeMailboxDirectory() {
        var mailbox = new FileMailbox(directory);
        for (String name : List.of("../outside", "a/b", "NUL", "*"))
            assertThrows(IllegalArgumentException.class, () -> mailbox.deliver("alice", name, "summary", "body", "text"));
    }
}
