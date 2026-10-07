package de.crashguard.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionRecoveryTest {
    @TempDir Path folder;
    @Test void transportDisconnectsAreNotCrashErrorsButPluginBugsRemainVisible() {
        assertTrue(ConnectionFailure.expected("net.minecraft.network.Connection", new SocketException("Connection reset")));
        assertTrue(ConnectionFailure.expected("net.md_5.bungee.netty.HandlerBoss", new ClosedChannelException()));
        assertFalse(ConnectionFailure.expected("PluginX", new SocketTimeoutException("API timeout")));
        assertFalse(ConnectionFailure.expected("net.minecraft.network.Connection", new IllegalStateException("plugin bug", new SocketException("Connection reset"))));
        assertFalse(ConnectionFailure.expected("net.minecraft.network.Connection", new OutOfMemoryError()));
        assertFalse(ConnectionFailure.expected("net.minecraft.network.Connection", new SocketException("Permission denied")));
    }
    @Test void normalReconnectNeedsNoRecoveryAndLatencyCannotOverrideBans() {
        assertFalse(ReconnectDecision.recover("DISCONNECTED", false, false, true));
        assertFalse(ReconnectDecision.recover("DISCONNECTED", false, true, false));
        assertTrue(ReconnectDecision.recover("DISCONNECTED", false, true, true));
        assertTrue(ReconnectDecision.recover("TIMED_OUT", false, false, false));
        assertFalse(ReconnectDecision.recover("TIMED_OUT", true, true, true));
        assertFalse(ReconnectDecision.recover("KICKED", false, true, true));
        assertFalse(ReconnectDecision.recover("ERRONEOUS_STATE", false, true, true));
    }
    @Test void sustainedLatencyRiseComparedWithBaselineButNotSingleSpike() {
        var window = new LatencyWindow(3, 600, 300, 3);
        long now = 1_000_000_000L;
        for (int i = 0; i < 5; i++) { window.sample(40, now); now += 1_000_000_000L; }
        window.sample(450, now); assertFalse(window.unstable(now)); now += 1_000_000_000L;
        window.sample(450, now); now += 1_000_000_000L; window.sample(450, now);
        assertTrue(window.unstable(now)); assertFalse(window.unstable(now + 6_000_000_000L));
        window.sample(40, now + 1_000_000_000L); assertFalse(window.unstable(now + 1_000_000_000L));
    }
    @Test void ignoredConnectionErrorDoesNotConsumeCooldownOrGenerateReport() throws Exception {
        Platform platform = new Platform() {
            public String name() { return "test"; } public void log(String text) {}
            public void requestShutdown() { fail(); } public List<String> pluginMetadata() { return List.of(); }
        };
        try (CrashGuard guard = new CrashGuard(folder, platform)) {
            guard.start(); guard.exception("net.minecraft.network.Connection", new SocketException("Connection reset"));
            guard.exception("PluginX", new IllegalStateException("real plugin bug"));
        }
        try (var files = Files.list(folder.resolve("reports"))) {
            var reports = files.toList(); assertEquals(1, reports.size());
            String report = Files.readString(reports.getFirst()); assertTrue(report.contains("real plugin bug")); assertFalse(report.contains("Connection reset"));
        }
    }
}
