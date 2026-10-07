package de.crashguard.common;

import java.util.logging.*;

/** JUL bridge; platform adapters install and remove it from their server logger. */
public final class ExceptionCapture extends Handler {
    private final CrashGuard guard;
    public ExceptionCapture(CrashGuard guard) { this.guard = guard; setLevel(Level.SEVERE); }
    @Override public void publish(LogRecord record) {
        if (record.getLevel().intValue() < Level.SEVERE.intValue() || record.getThrown() == null || Thread.currentThread().getName().startsWith("CrashGuard-")) return;
        guard.exception(record.getLoggerName(), record.getThrown());
    }
    @Override public void flush() {}
    @Override public void close() {}
}
