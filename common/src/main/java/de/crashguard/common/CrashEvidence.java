package de.crashguard.common;

import java.util.*;
import java.util.regex.*;

/** Extract only fields the native crash report actually contains. */
public final class CrashEvidence {
    private CrashEvidence() {}
    public static String summarize(String raw, Map<String, String> packages) {
        List<String> result = new ArrayList<>();
        for (String label : List.of("Description", "Entity Type", "Entity ID", "Entity's Exact location", "Entity's Block location", "Level name", "Block location")) {
            Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(label) + ":\\s*([^\\r\\n]+)").matcher(raw);
            if (matcher.find()) result.add(label + ": " + matcher.group(1));
        }
        Set<String> candidates = new TreeSet<>();
        packages.forEach((prefix, plugin) -> {
            if (prefix.isBlank()) return;
            if (Pattern.compile("(?m)\\bat\\s+(?:[^\\s/]+/)?" + Pattern.quote(prefix) + "\\.[\\w.$]+\\(").matcher(raw).find()) candidates.add(plugin);
        });
        if (!candidates.isEmpty()) result.add("Plugin candidates (stack namespace, unconfirmed): " + String.join(", ", candidates));
        return String.join("\n", result);
    }
}
