package de.crashguard.common;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Independent liveness sources; one healthy region must not mask another stalled region. */
public final class HeartbeatSources {
    public record Beat(long time, long threadId) {}
    private final Map<String, Beat> sources = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    public HeartbeatSources() { this(System::nanoTime); }
    public HeartbeatSources(LongSupplier clock) { this.clock = clock; }
    public void watch(String source) { sources.putIfAbsent(source, new Beat(clock.getAsLong(), -1)); }
    public void beat(String source) { sources.put(source, new Beat(clock.getAsLong(), Thread.currentThread().threadId())); }
    public void remove(String source) { sources.remove(source); }
    public Map<String, Beat> stalled(long timeoutNanos) {
        long now = clock.getAsLong(); Map<String, Beat> result = new TreeMap<>();
        sources.forEach((source, beat) -> { if (now - beat.time() >= timeoutNanos) result.put(source, beat); });
        return Map.copyOf(result);
    }
}
