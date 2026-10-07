package de.crashguard.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WatchdogTest {
    @TempDir Path folder;
    @Test void detectsStallOnceAndReturnsToHealthyWhenHeartbeatResumes() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "watchdog:\n  timeout-seconds: 5\n  check-seconds: 1\nreports:\n  journal:\n    enabled: false\n");
        CountDownLatch stalled = new CountDownLatch(1), resumed = new CountDownLatch(1);
        List<String> freezes = new CopyOnWriteArrayList<>();
        Platform platform = new Platform() {
            public String name() { return "test"; }
            public List<String> pluginMetadata() { return List.of(); }
            public void requestShutdown() { fail("Reporting must not request shutdown"); }
            public void log(String text) {
                if (text.contains("ausgeblieben")) { freezes.add(text); stalled.countDown(); }
                if (text.contains("läuft wieder")) resumed.countDown();
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            try (CrashGuard guard = new CrashGuard(folder, platform)) {
                guard.start(); assertTrue(stalled.await(8, TimeUnit.SECONDS));
                assertEquals("stalled", guard.state()); guard.beat();
                assertTrue(resumed.await(3, TimeUnit.SECONDS)); assertEquals("healthy", guard.state()); assertEquals(1, freezes.size());
            }
            try (var files = Files.list(folder.resolve("reports"))) { assertEquals(1, files.count()); }
        });
    }
}
