package de.crashguard.common;

import java.io.*;
import java.lang.management.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Shared watchdog and reporter. Does not call world/player APIs off-thread. */
public final class CrashGuard implements AutoCloseable {
    private final Settings config;
    private final Platform platform;
    private final Path folder;
    private final ScheduledExecutorService worker;
    private final ScheduledExecutorService monitor;
    private final AtomicLong heartbeat = new AtomicLong(System.nanoTime());
    private volatile long heartbeatThread = -1;
    private volatile List<String> context = List.of();
    private volatile String state = "healthy";
    private final AtomicBoolean reportQueued = new AtomicBoolean();
    private boolean stalled;
    private boolean deadlocked;
    private volatile boolean closed;
    private final AtomicLong lastException = new AtomicLong();
    private volatile String evidence = "";
    private final Deque<String> history = new ArrayDeque<>();
    private final HeartbeatSources sources = new HeartbeatSources();
    private volatile long startedAt;
    public void beatSource(String source) { sources.beat(source); }
    public void watchSource(String source) { sources.watch(source); }
    public void forgetHeartbeat(String source) { sources.remove(source); }
    public CrashGuard(Path folder, Platform platform) throws IOException {
        this.folder = folder; this.platform = platform; config = new Settings(folder);
        if (!Set.of("report", "shutdown").contains(config.string("watchdog.action"))) throw new IOException("Invalid watchdog.action");
        Files.createDirectories(folder.resolve("reports"));
        worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "CrashGuard-Diagnostics"); t.setDaemon(true); return t; });
        monitor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "CrashGuard-Watchdog"); t.setDaemon(true); return t; });
    }
    public void start() throws IOException {
        startedAt = System.nanoTime();
        Path marker = folder.resolve("running.marker");
        boolean previous = Files.exists(marker);
        if (previous) {
            if (Files.exists(folder.resolve("context-journal.txt"))) evidence = Files.readString(folder.resolve("context-journal.txt"));
            if (config.bool("reports.import-crash-reports")) importCrashReport(Files.getLastModifiedTime(marker).toMillis());
        }
        Files.writeString(marker, Instant.now().toString());
        if (previous) report(config.message("unexpected-exit", Map.of()));
        monitor.scheduleWithFixedDelay(this::checkSafely, config.integer("watchdog.check-seconds"), config.integer("watchdog.check-seconds"), TimeUnit.SECONDS);
        if (config.bool("reports.journal.enabled")) worker.scheduleWithFixedDelay(this::journal, config.integer("reports.journal.interval-seconds"), config.integer("reports.journal.interval-seconds"), TimeUnit.SECONDS);
        platform.log(config.message("startup", Map.of("platform", platform.name())));
    }
    public Settings settings() { return config; }
    public String state() { return state; }
    public void beat() { heartbeatThread = Thread.currentThread().threadId(); heartbeat.set(System.nanoTime()); }
    public void context(List<String> snapshot) {
        context = List.copyOf(snapshot.subList(0, Math.min(snapshot.size(), config.integer("reports.max-context"))));
        synchronized (history) {
            history.addLast("Snapshot time: " + Instant.now() + "\n" + String.join("\n", context));
            while (history.size() > config.integer("reports.journal.history-snapshots")) history.removeFirst();
        }
    }
    public void report(String reason) {
        if (closed || !config.bool("reports.enabled") || !reportQueued.compareAndSet(false, true)) return;
        try { worker.execute(() -> { try { writeReport(reason); } finally { reportQueued.set(false); } }); }
        catch (RejectedExecutionException e) { reportQueued.set(false); }
    }
    public void exception(String logger, Throwable failure) {
        if (!config.bool("reports.capture-severe-exceptions") || closed) return;
        if (config.bool("reports.ignore-expected-connection-errors") && ConnectionFailure.expected(logger, failure)) return;
        long now = System.nanoTime(), previous = lastException.get();
        if (previous != 0 && now - previous < TimeUnit.SECONDS.toNanos(config.integer("reports.exception-cooldown-seconds"))) return;
        if (!lastException.compareAndSet(previous, now)) return;
        // Bound exception traversal, including malicious/self-referential causes.
        StringBuilder trace = new StringBuilder("Logger: ").append(logger).append('\n');
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.size() < 8 && seen.add(t); t = t.getCause()) {
            trace.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 100); i++) trace.append("at ").append(frames[i]).append('\n');
        }
        evidence = trace.toString(); report(failure.getClass().getSimpleName());
    }
    private void journal() {
        String snapshots; synchronized (history) { snapshots = String.join("\n\n", history); }
        try { atomicWrite(folder.resolve("context-journal.txt"), snapshots); }
        catch (IOException e) { platform.log(config.message("report-failed", Map.of("error", e.getClass().getSimpleName()))); }
    }
    private void importCrashReport(long since) throws IOException {
        Path directory = Path.of(config.string("reports.crash-report-directory"));
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            Path newest = files.filter(p -> p.getFileName().toString().endsWith(".txt") && Files.isRegularFile(p)).filter(p -> {
                try { return Files.getLastModifiedTime(p).toMillis() >= since; } catch (IOException e) { return false; }
            }).max(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; } })).orElse(null);
            if (newest != null) try (InputStream in = Files.newInputStream(newest)) {
                evidence += "\nNative crash report: " + newest.getFileName() + "\n" + new String(in.readNBytes(98304), StandardCharsets.UTF_8);
            }
        }
    }
    private void checkSafely() {
        try {
            if (closed || !config.bool("watchdog.enabled")) return;
            if (System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(config.integer("watchdog.startup-grace-seconds"))) return;
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] ids = mx.isSynchronizerUsageSupported() ? mx.findDeadlockedThreads() : mx.findMonitorDeadlockedThreads();
            if (ids != null && !deadlocked) { deadlocked = true; report("JVM deadlock detected: " + Arrays.toString(ids)); }
            else if (ids == null) deadlocked = false;
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - heartbeat.get());
            Map<String, HeartbeatSources.Beat> regions = sources.stalled(TimeUnit.SECONDS.toNanos(config.integer("watchdog.timeout-seconds")));
            boolean frozen = seconds >= config.integer("watchdog.timeout-seconds") || !regions.isEmpty();
            if (frozen && !stalled) {
                stalled = true; state = "stalled";
                String reason = seconds >= config.integer("watchdog.timeout-seconds")
                    ? config.message("freeze-detected", Map.of("seconds", Long.toString(seconds)))
                    : config.message("region-freeze-detected", Map.of("sources", regions.keySet().toString(), "count", Integer.toString(regions.size())));
                platform.log(reason); report(reason);
                if (config.string("watchdog.action").equals("shutdown")) platform.requestShutdown();
            } else if (!frozen && stalled) {
                stalled = false; state = "healthy"; platform.log(config.message("freeze-recovered", Map.of()));
            }
        } catch (RuntimeException e) { platform.log(config.message("report-failed", Map.of("error", e.toString()))); }
    }
    private void writeReport(String reason) {
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            StringBuilder body = new StringBuilder("# ").append(config.string("reports.title")).append("\n\n")
                .append("Time: ").append(Instant.now()).append("\nPlatform: ").append(platform.name())
                .append("\nReason: ").append(reason).append("\nHeartbeat thread: ").append(heartbeatThread)
                .append("\nHeap used/max: ").append(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).append('/').append(Runtime.getRuntime().maxMemory())
                .append("\n\n## Plugins\n").append(String.join("\n", platform.pluginMetadata()))
                .append("\n\n## Last observed context (not causal attribution)\n").append(String.join("\n", context));
            String captured = evidence;
            StringBuilder relevantStack = new StringBuilder(captured);
            body.append("\n\n## Recorded crash evidence\n").append(captured);
            long[] deadlocks;
            try { deadlocks = mx.findDeadlockedThreads(); } catch (UnsupportedOperationException e) { deadlocks = mx.findMonitorDeadlockedThreads(); }
            body.append("\n\nDeadlocked thread IDs: ").append(Arrays.toString(deadlocks));
            Map<String, HeartbeatSources.Beat> regions = sources.stalled(TimeUnit.SECONDS.toNanos(config.integer("watchdog.timeout-seconds")));
            body.append("\nStalled region observations: ").append(regions);
            body.append("\n\n## Threads – ").append(config.string("messages.suspect-label")).append('\n');
            for (ThreadInfo thread : mx.dumpAllThreads(false, false)) {
                body.append('\n').append(thread.getThreadName()).append(" [").append(thread.getThreadId()).append("] ").append(thread.getThreadState()).append('\n');
                for (StackTraceElement frame : thread.getStackTrace()) {
                    body.append("  at ").append(frame).append('\n');
                    if (thread.getThreadId() == heartbeatThread || regions.values().stream().anyMatch(beat -> beat.threadId() == thread.getThreadId())) relevantStack.append("at ").append(frame).append('\n');
                }
            }
            String hints = CrashEvidence.summarize(relevantStack.toString(), platform.pluginPackages());
            body.append("\n\n## Extracted hints\n").append(hints);
            Path file = folder.resolve("reports").resolve("report-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".md");
            atomicWrite(file, body.toString());
            prune();
            platform.log(config.message("report-created", Map.of("path", file.toString())));
            if (config.bool("reports.discord.enabled")) webhook(reason, hints);
        } catch (Exception e) { platform.log(config.message("report-failed", Map.of("error", e.getClass().getSimpleName()))); }
    }
    private void prune() throws IOException {
        try (var files = Files.list(folder.resolve("reports"))) {
            List<Path> sorted = files.filter(p -> p.getFileName().toString().startsWith("report-") && p.toString().endsWith(".md")).sorted(Comparator.comparing(Path::toString).reversed()).toList();
            for (int i = config.integer("reports.max-files"); i < sorted.size(); i++) Files.deleteIfExists(sorted.get(i));
        }
    }
    private void webhook(String reason, String hints) throws IOException, InterruptedException {
        URI uri = URI.create(config.string("reports.discord.webhook-url"));
        if (!"https".equals(uri.getScheme()) || !Set.of("discord.com", "discordapp.com").contains(uri.getHost()) || !uri.getPath().startsWith("/api/webhooks/")) throw new IOException("Invalid Discord webhook");
        String description = reason + "\nPlatform: " + platform.name();
        if (!hints.isBlank()) description += "\n" + hints;
        if (config.bool("reports.discord.include-context")) description += "\n" + String.join("\n", context);
        if (description.length() > 3500) description = description.substring(0, 3500);
        String payload = "{\"username\":" + json(config.string("reports.discord.username")) + ",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":" + json(config.string("reports.title")) + ",\"description\":" + json(description) + ",\"color\":15158332}]}";
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) throw new IOException("Webhook HTTP " + response.statusCode());
        }
    }
    static String json(String text) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : text.toCharArray()) { switch (c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t"); default -> { if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); } } }
        return b.append('"').toString();
    }
    private static void atomicWrite(Path target, String body) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "pending-", ".tmp");
        try {
            Files.writeString(temp, body, StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; monitor.shutdownNow(); worker.shutdown();
        try { if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException e) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
        try { Files.deleteIfExists(folder.resolve("running.marker")); }
        catch (IOException e) { platform.log(config.message("report-failed", Map.of("error", e.toString()))); }
        platform.log(config.message("shutdown", Map.of()));
    }
}
