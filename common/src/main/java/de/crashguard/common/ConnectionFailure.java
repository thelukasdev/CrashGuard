package de.crashguard.common;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.*;

/** Narrow transport classification: packet decoding and plugin bugs remain reportable. */
public final class ConnectionFailure {
    private ConnectionFailure() {}
    public static boolean expected(String logger, Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean transport = false;
        boolean playerNetwork = playerNetworkClass(Objects.toString(logger, ""));
        for (Throwable t = failure; t != null && seen.size() < 16 && seen.add(t); t = t.getCause()) {
            String name = t.getClass().getName();
            if (t instanceof Error || name.startsWith("io.netty.handler.codec.") || t instanceof IllegalStateException || t instanceof NullPointerException) return false;
            for (StackTraceElement frame : t.getStackTrace()) if (playerNetworkClass(frame.getClassName())) playerNetwork = true;
            if (t instanceof ClosedChannelException || t instanceof SocketTimeoutException
                    || name.equals("io.netty.handler.timeout.ReadTimeoutException")
                    || name.equals("io.netty.handler.timeout.WriteTimeoutException")) transport = true;
            if (t instanceof SocketException) {
                String message = Objects.toString(t.getMessage(), "").toLowerCase(Locale.ROOT);
                if (message.equals("connection reset") || message.equals("connection reset by peer")
                        || message.equals("socket closed") || message.equals("broken pipe")
                        || message.equals("an existing connection was forcibly closed by the remote host")) transport = true;
            }
        }
        return transport && playerNetwork;
    }
    private static boolean playerNetworkClass(String name) {
        return name.startsWith("net.minecraft.network.") || name.startsWith("net.md_5.bungee.netty.")
                || name.startsWith("net.md_5.bungee.connection.") || name.startsWith("com.velocitypowered.proxy.connection.");
    }
}
