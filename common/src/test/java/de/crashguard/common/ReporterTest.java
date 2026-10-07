package de.crashguard.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReporterTest {
    @TempDir Path folder;
    private Platform platform() { return new Platform() {
        public String name() { return "test"; }
        public void log(String m) {}
        public void requestShutdown() { fail("Default config must not stop server"); }
        public List<String> pluginMetadata() { return List.of("PluginX 1.2.3"); }
    }; }
    @Test void createsDiagnosticAndRemovesMarkerOnCleanClose() throws Exception {
        try (CrashGuard guard = new CrashGuard(folder, platform())) {
            guard.start(); assertTrue(Files.exists(folder.resolve("running.marker")));
            guard.context(List.of("world=world_nether x=120 z=-450")); guard.report("manual");
        }
        assertFalse(Files.exists(folder.resolve("running.marker")));
        try (var files = Files.list(folder.resolve("reports"))) {
            String report = Files.readString(files.findFirst().orElseThrow());
            assertTrue(report.contains("PluginX 1.2.3")); assertTrue(report.contains("world_nether")); assertTrue(report.contains("## Threads"));
        }
    }
    @Test void detectsPreviousUncleanProcess() throws Exception {
        Files.writeString(folder.resolve("running.marker"), "previous");
        Files.writeString(folder.resolve("context-journal.txt"), "last observed chunk");
        try (CrashGuard guard = new CrashGuard(folder, platform())) { guard.start(); }
        try (var files = Files.list(folder.resolve("reports"))) { String report = Files.readString(files.findFirst().orElseThrow()); assertTrue(report.contains("last observed chunk")); assertTrue(report.contains("Ursache unbekannt")); }
    }
    @Test void readsEvidenceWithoutClaimingCausality() {
        String raw = "Description: Ticking entity\n\tEntity Type: minecraft:armor_stand\n\tEntity's Exact location: 120.0, 64.0, -450.0\n\tLevel name: world_nether\n\tat example.plugin.Listener.tick(Listener.java:42)";
        String hints = CrashEvidence.summarize(raw, Map.of("example.plugin", "PluginX 1.2.3", "example.plug", "wrong"));
        assertTrue(hints.contains("minecraft:armor_stand")); assertTrue(hints.contains("PluginX 1.2.3")); assertFalse(hints.contains("wrong")); assertTrue(hints.contains("unconfirmed"));
    }
    @Test void invalidRecoveryWindowFailsStartup() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "recovery:\n  window-seconds: 0\n");
        assertThrows(java.io.IOException.class, () -> new Settings(folder));
    }
    @Test void wrongBooleanAndFractionalWindowsAreRejected() throws Exception {
        Files.writeString(folder.resolve("config.yml"), "watchdog:\n  enabled: 'false'\n");
        assertThrows(java.io.IOException.class, () -> new Settings(folder));
        Files.writeString(folder.resolve("config.yml"), "recovery:\n  window-seconds: 1.5\n");
        assertThrows(java.io.IOException.class, () -> new Settings(folder));
    }
}
