package com.acode.hook;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {
    static HookConfig hook(String id, String event, boolean reject, boolean once, boolean async) {
        return new HookConfig(id, event, c -> true, new HookConfig.Action(HookConfig.ActionType.PROMPT, Map.of("message", id), Duration.ofSeconds(1)), reject, once, async);
    }
    @Test void orderedShortCircuitOnceFailuresAndDraining() {
        var calls = new ArrayList<String>(); var marked = new ArrayList<String>();
        var hooks = List.of(hook("a", HookEvents.PRE_TOOL_USE, true, true, false), hook("b", HookEvents.PRE_TOOL_USE, false, false, false));
        try (var engine = new HookEngine(hooks, (h, c) -> { calls.add(h.id()); return HookAction.Result.success(h.id()); }, null, marked::add)) {
            assertFalse(engine.fire(HookEvents.TURN_END, HookContext.lifecycle(HookEvents.TURN_END, "x")).rejected());
            var result = engine.fire(HookEvents.PRE_TOOL_USE, HookContext.lifecycle(HookEvents.PRE_TOOL_USE, null));
            assertTrue(result.rejected()); assertEquals("a", result.reason()); assertEquals(List.of("a"), calls);
            engine.fire(HookEvents.PRE_TOOL_USE, HookContext.lifecycle(HookEvents.PRE_TOOL_USE, null));
            assertEquals(List.of("a", "b"), calls); assertEquals(List.of("a"), marked);
            assertEquals(List.of("a", "b"), engine.drainPrompts()); assertTrue(engine.drainPrompts().isEmpty());
        }
        calls.clear();
        try (var engine = new HookEngine(hooks, (h, c) -> { calls.add(h.id()); throw new IllegalStateException("fail"); }, null, marked::add)) {
            assertFalse(engine.fire(HookEvents.PRE_TOOL_USE, HookContext.lifecycle(HookEvents.PRE_TOOL_USE, null)).rejected());
            engine.fire(HookEvents.PRE_TOOL_USE, HookContext.lifecycle(HookEvents.PRE_TOOL_USE, null));
            assertEquals(List.of("a", "b", "b"), calls);
        }
    }
    @Test void asyncReturnsImmediatelyAndOldSessionCannotInjectIntoNewSession() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var completed = new CountDownLatch(1);
        var count = new AtomicInteger();
        try (var engine = new HookEngine(List.of(hook("a", HookEvents.SESSION_START, false, true, true)), (h, c) -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
            completed.countDown(); return HookAction.Result.success("old");
        }, null, id -> count.incrementAndGet())) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> engine.fire(HookEvents.SESSION_START, HookContext.lifecycle(HookEvents.SESSION_START, null)));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(1, count.get());
            engine.loadOnceIds(Set.of("a")); release.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertTrue(engine.drainPrompts().isEmpty());
        }
    }
    @Test void badConditionIsContainedAndFollowingHookStillExecutes() {
        var good = hook("good", HookEvents.TURN_START, false, false, false);
        var bad = new HookConfig("bad", good.event(), c -> { throw new IllegalStateException(); }, good.action(), false, false, false);
        try (var engine = new HookEngine(List.of(bad, good), (h, c) -> HookAction.Result.success(h.id()), null, null)) {
            assertDoesNotThrow(() -> engine.fire(good.event(), HookContext.lifecycle(good.event(), "x")));
            assertEquals(List.of("good"), engine.drainPrompts());
        }
    }
}
