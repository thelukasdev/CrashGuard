package de.crashguard.common;

import java.time.*;
import java.util.*;

/** Bounded, synchronized, one-use reconnect buffer with an injectable clock. */
public final class SessionBuffer<T> {
    private record Entry<T>(T value, Instant expires) {}
    private final Map<UUID, Entry<T>> entries = new LinkedHashMap<>();
    private final Clock clock;
    private final Duration lifetime;
    private final int capacity;
    public SessionBuffer(int seconds, int capacity) { this(seconds, capacity, Clock.systemUTC()); }
    public SessionBuffer(int seconds, int capacity, Clock clock) {
        if (seconds < 1 || capacity < 1) throw new IllegalArgumentException("Invalid session bounds");
        this.clock = clock; this.lifetime = Duration.ofSeconds(seconds); this.capacity = capacity;
    }
    public synchronized void put(UUID id, T value) {
        purge(); entries.remove(id);
        if (entries.size() >= capacity) entries.remove(entries.keySet().iterator().next());
        entries.put(id, new Entry<>(value, clock.instant().plus(lifetime)));
    }
    public synchronized Optional<T> take(UUID id) { purge(); Entry<T> e = entries.remove(id); return e == null ? Optional.empty() : Optional.of(e.value()); }
    public synchronized int size() { purge(); return entries.size(); }
    public synchronized void clear() { entries.clear(); }
    private void purge() { Instant now = clock.instant(); entries.values().removeIf(e -> !e.expires().isAfter(now)); }
}
