package de.crashguard.common;

import java.util.List;

public interface Platform {
    String name();
    void log(String message);
    /** Implementations must support calls from the diagnostic executor. */
    void requestShutdown();
    /** Immutable metadata captured at startup; never access server state here. */
    List<String> pluginMetadata();
    /** Main-class package prefixes, only a heuristic for candidate attribution. */
    default java.util.Map<String, String> pluginPackages() { return java.util.Map.of(); }
}
