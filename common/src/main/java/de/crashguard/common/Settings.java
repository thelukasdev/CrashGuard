package de.crashguard.common;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable configuration; missing keys fall back to the shipped defaults. */
public final class Settings {
    private final Map<String, Object> values;
    private final Map<String, Object> defaults;
    public Settings(Path folder) throws IOException {
        Files.createDirectories(folder);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = Settings.class.getResourceAsStream("/crashguard-default.yml")) {
            if (in == null) throw new IOException("Missing default configuration");
            defaults = yaml.load(in);
        }
        Path file = folder.resolve("config.yml");
        if (!Files.exists(file)) try (InputStream in = Settings.class.getResourceAsStream("/crashguard-default.yml")) { Files.copy(in, file); }
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed = yaml.load(in);
            if (!(parsed instanceof Map<?, ?>)) throw new IOException("config.yml must contain a mapping");
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) parsed;
            values = map;
        } catch (RuntimeException e) { throw new IOException("Invalid config.yml", e); }
        validate(values, defaults, "");
        range("watchdog.timeout-seconds", 5, 3600);
        range("watchdog.check-seconds", 1, 60);
        range("watchdog.startup-grace-seconds", 0, 3600);
        range("recovery.window-seconds", 1, 120);
        range("recovery.max-sessions", 1, 100000);
        range("reports.max-files", 1, 10000);
        range("reports.max-context", 1, 10000);
        range("recovery.protection-seconds", 0, 30);
        range("reports.journal.interval-seconds", 1, 3600);
        range("reports.journal.history-snapshots", 1, 120);
        range("reports.exception-cooldown-seconds", 1, 3600);
        range("recovery.latency.consecutive-samples", 2, 20);
        range("recovery.latency.high-ping-ms", 50, 60000);
        range("recovery.latency.rise-ms", 1, 60000);
        range("recovery.latency.baseline-multiplier", 2, 20);
    }
    private void validate(Map<?, ?> actual, Map<?, ?> schema, String prefix) throws IOException {
        for (var entry : actual.entrySet()) {
            String key = prefix + entry.getKey(); Object v = entry.getValue(), expected = schema.get(entry.getKey());
            if (expected == null) continue;
            if (expected instanceof Map<?, ?> nested) {
                if (!(v instanceof Map<?, ?> map)) throw new IOException(key + " must be a mapping");
                validate(map, nested, key + ".");
            } else if (expected instanceof Boolean && !(v instanceof Boolean)
                    || expected instanceof Number && (!(v instanceof Number n) || n.doubleValue() != n.intValue())
                    || expected instanceof String && !(v instanceof String)
                    || expected instanceof List<?> && (!(v instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof String)))) {
                throw new IOException("Invalid value type for " + key);
            }
        }
    }
    private void range(String key, int min, int max) throws IOException {
        int n = integer(key); if (n < min || n > max) throw new IOException(key + " must be between " + min + " and " + max);
    }
    private Object lookup(Map<String, Object> root, String key) {
        Object v = root;
        for (String part : key.split("\\.")) { if (!(v instanceof Map<?, ?> map)) return null; v = map.get(part); }
        return v;
    }
    private Object get(String key) { Object v = lookup(values, key); return v == null ? lookup(defaults, key) : v; }
    public String string(String key) { return Objects.toString(get(key), ""); }
    public boolean bool(String key) { Object v = get(key); if (!(v instanceof Boolean b)) throw new IllegalArgumentException(key + " must be a boolean"); return b; }
    public int integer(String key) { Object v = get(key); if (!(v instanceof Number n)) throw new IllegalArgumentException(key + " must be a number"); return n.intValue(); }
    public List<String> list(String key) { Object v = get(key); return v instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(); }
    public String message(String key, Map<String, String> replacements) {
        String text = string("messages." + key);
        for (var entry : replacements.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text;
    }
}
