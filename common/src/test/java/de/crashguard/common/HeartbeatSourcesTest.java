package de.crashguard.common;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class HeartbeatSourcesTest {
    @Test void healthyRegionCannotMaskAnotherFrozenRegionAndRetirementClearsIt() {
        AtomicLong time = new AtomicLong(); var sources = new HeartbeatSources(time::get);
        sources.beat("region-a"); sources.beat("region-b");
        time.set(29); sources.beat("region-a"); assertTrue(sources.stalled(30).isEmpty());
        time.set(30); var stalled = sources.stalled(30);
        assertEquals(java.util.Set.of("region-b"), stalled.keySet());
        assertEquals(Thread.currentThread().threadId(), stalled.get("region-b").threadId());
        sources.remove("region-b"); assertTrue(sources.stalled(30).isEmpty());
        sources.beat("region-b-new-session"); assertTrue(sources.stalled(30).isEmpty());
    }
    @Test void concurrentRegionUpdatesRemainIndependent() throws Exception {
        var sources = new HeartbeatSources(() -> 0);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) { String key = "region-" + i; futures.add(executor.submit(() -> sources.beat(key))); }
            for (var future : futures) future.get();
        }
        assertEquals(100, sources.stalled(0).size());
    }
    @Test void regionThatNeverExecutesFirstScheduledTickIsStillDetected() {
        AtomicLong time = new AtomicLong(); var sources = new HeartbeatSources(time::get);
        sources.watch("new-region"); time.set(30);
        assertEquals(-1, sources.stalled(30).get("new-region").threadId());
        sources.beat("new-region"); assertTrue(sources.stalled(30).isEmpty());
    }
}
