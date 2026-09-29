package com.acode.team;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentNameRegistryTest {
    @Test void resolvesNamesAndIDsAndRejectsAmbiguousAddresses() {
        var registry = new AgentNameRegistry();
        registry.register("alice", "agent-a");
        assertEquals(registry.resolve("alice"), registry.resolve("agent-a"));
        assertTrue(registry.resolve("missing").isEmpty());
        assertTrue(registry.resolve("*").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.register("alice", "b"));
        assertThrows(IllegalArgumentException.class, () -> registry.register("agent-a", "b"));
        assertThrows(IllegalArgumentException.class, () -> registry.register("bob", "alice"));
        var snapshot = registry.snapshot();
        registry.unregister("agent-a");
        assertTrue(registry.resolve("alice").isEmpty());
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test void duplicateRegistrationHasExactlyOneWinner() throws Exception {
        var registry = new AgentNameRegistry();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> race = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { registry.register("alice", "agent-a"); return true; }
                catch (IllegalArgumentException duplicate) { return false; }
            };
            var a = pool.submit(race); var b = pool.submit(race);
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertEquals(1, registry.snapshot().size());
        }
    }
}
