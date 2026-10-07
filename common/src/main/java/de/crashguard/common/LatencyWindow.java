package de.crashguard.common;

import java.util.*;

/** Consecutive recent samples compared with an earlier median, never one isolated ping. */
public final class LatencyWindow {
    private record Sample(int ping, long time) {}
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final int count, absolute, rise, multiplier;
    public LatencyWindow(int count, int absolute, int rise, int multiplier) {
        this.count = count; this.absolute = absolute; this.rise = rise; this.multiplier = multiplier;
    }
    public void sample(int ping, long now) {
        if (ping < 0) return;
        samples.addLast(new Sample(ping, now));
        while (samples.size() > count + 20) samples.removeFirst();
    }
    public boolean unstable(long now) {
        List<Sample> list = List.copyOf(samples);
        if (list.size() < count) return false;
        int start = list.size() - count;
        // Do not infer a recent connection failure from stale pre-freeze measurements.
        if (now - list.get(list.size() - 1).time() > 5_000_000_000L) return false;
        if (list.get(list.size() - 1).time() - list.get(start).time() > count * 2_000_000_000L) return false;
        int[] baseline = list.subList(0, start).stream().mapToInt(Sample::ping).sorted().toArray();
        int median = baseline.length == 0 ? 0 : baseline[baseline.length / 2];
        for (Sample s : list.subList(start, list.size())) {
            boolean absoluteHigh = s.ping() >= absolute;
            boolean relativeHigh = baseline.length >= count && s.ping() >= (long)median + rise && s.ping() >= (long)median * multiplier;
            if (!absoluteHigh && !relativeHigh) return false;
        }
        return true;
    }
}
