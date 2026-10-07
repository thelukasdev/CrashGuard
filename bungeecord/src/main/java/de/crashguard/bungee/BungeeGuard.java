package de.crashguard.bungee;

import de.crashguard.common.*;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BungeeGuard extends Plugin implements Listener {
    private CrashGuard guard;
    private SessionBuffer<String> sessions;
    private ScheduledTask heartbeat;
    private ExceptionCapture exceptionCapture;
    private final Set<UUID> kicked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Override public void onEnable() {
        try {
            List<String> metadata = getProxy().getPluginManager().getPlugins().stream().map(p -> p.getDescription().getName() + " " + p.getDescription().getVersion()).toList();
            Map<String, String> packages = new HashMap<>();
            for (var plugin : getProxy().getPluginManager().getPlugins()) packages.put(plugin.getClass().getPackageName(), plugin.getDescription().getName() + " " + plugin.getDescription().getVersion());
            guard = new CrashGuard(getDataFolder().toPath(), new Platform() {
                public String name() { return "BungeeCord (scheduler liveness)"; }
                public void log(String message) { getLogger().info(message); }
                public void requestShutdown() { getProxy().stop(); }
                public List<String> pluginMetadata() { return metadata; }
                public Map<String, String> pluginPackages() { return Map.copyOf(packages); }
            });
            Settings c = guard.settings(); sessions = new SessionBuffer<>(c.integer("recovery.window-seconds"), c.integer("recovery.max-sessions"));
            guard.start();
            exceptionCapture = new ExceptionCapture(guard); java.util.logging.Logger.getLogger("").addHandler(exceptionCapture);
            heartbeat = getProxy().getScheduler().schedule(this, guard::beat, 1, 1, TimeUnit.SECONDS);
            getProxy().getPluginManager().registerListener(this, this);
            getProxy().getPluginManager().registerCommand(this, new Command("crashguard") {
                @Override public void execute(CommandSender sender, String[] args) {
                    if (!sender.hasPermission("crashguard.admin")) { send(sender, c.message("no-permission", Map.of())); return; }
                    if (args.length > 0 && args[0].equalsIgnoreCase("report")) guard.report(c.message("manual-report", Map.of()));
                    else if (args.length > 0 && !args[0].equalsIgnoreCase("status")) { send(sender, c.message("command-usage", Map.of())); return; }
                    send(sender, c.message("status", Map.of("platform", "BungeeCord", "sessions", Integer.toString(sessions.size()), "state", guard.state())));
                }
            });
        } catch (Exception e) { getLogger().severe("CrashGuard startup failed: " + e); onDisable(); }
    }
    private void send(CommandSender sender, String text) { sender.sendMessage(TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text))); }
    @EventHandler public void kick(ServerKickEvent e) { if (!e.isCancelled()) kicked.add(e.getPlayer().getUniqueId()); }
    @EventHandler public void disconnect(PlayerDisconnectEvent e) {
        if (sessions == null || kicked.remove(e.getPlayer().getUniqueId()) || !guard.settings().bool("recovery.enabled")) return;
        if (e.getPlayer().getServer() != null) sessions.put(e.getPlayer().getUniqueId(), e.getPlayer().getServer().getInfo().getName());
    }
    @EventHandler public void connect(ServerConnectEvent e) {
        if (sessions == null || e.isCancelled() || e.getReason() != ServerConnectEvent.Reason.JOIN_PROXY || !guard.settings().bool("recovery.enabled")) return;
        sessions.take(e.getPlayer().getUniqueId()).ifPresent(name -> {
            var target = getProxy().getServerInfo(name);
            if (target != null) {
                e.setTarget(target);
                if (guard.settings().bool("recovery.notify-player")) send(e.getPlayer(), guard.settings().message("proxy-recovered", Map.of()));
            }
        });
    }
    @Override public void onDisable() {
        if (exceptionCapture != null) java.util.logging.Logger.getLogger("").removeHandler(exceptionCapture);
        if (heartbeat != null) heartbeat.cancel();
        getProxy().getPluginManager().unregisterListeners(this); getProxy().getPluginManager().unregisterCommands(this);
        if (guard != null) guard.close(); if (sessions != null) sessions.clear(); kicked.clear();
    }
}
