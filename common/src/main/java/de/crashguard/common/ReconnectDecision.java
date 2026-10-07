package de.crashguard.common;

public final class ReconnectDecision {
    private ReconnectDecision() {}
    public static boolean recover(String quitReason, boolean blockedKick, boolean allowAmbiguous, boolean unstableLatency) {
        if (blockedKick) return false;
        if ("TIMED_OUT".equals(quitReason)) return true;
        return "DISCONNECTED".equals(quitReason) && allowAmbiguous && unstableLatency;
    }
}
