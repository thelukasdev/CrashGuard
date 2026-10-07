package de.crashguard.common;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SessionBufferTest {
    private static final class MutableClock extends Clock {
        Instant time = Instant.EPOCH;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time; }
    }
    @Test void expiresExactlyAtDeadlineAndConsumesOnlyOnce() {
        MutableClock clock = new MutableClock(); var buffer = new SessionBuffer<String>(15, 2, clock); UUID id = UUID.randomUUID();
        buffer.put(id, "backend"); clock.time = Instant.EPOCH.plusSeconds(14); assertEquals("backend", buffer.take(id).orElseThrow()); assertTrue(buffer.take(id).isEmpty());
        buffer.put(id, "expired"); clock.time = clock.time.plusSeconds(15); assertTrue(buffer.take(id).isEmpty());
    }
    @Test void evictsOldestAndRefreshesExistingSession() {
        var buffer = new SessionBuffer<String>(15, 2); UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        buffer.put(a, "a"); buffer.put(b, "b"); buffer.put(a, "new"); buffer.put(c, "c");
        assertEquals(2, buffer.size()); assertTrue(buffer.take(b).isEmpty()); assertEquals("new", buffer.take(a).orElseThrow());
    }
    @Test void escapesWebhookInput() { assertEquals("\"a\\n\\\"\\\\\\u0001\"", CrashGuard.json("a\n\"\\\u0001")); }
}
