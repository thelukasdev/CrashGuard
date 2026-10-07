package de.crashguard.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.crashguard.common.*;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Plugin(id = "crashguard", name = "CrashGuard", version = "1.0.0", description = "Crash diagnostics and backend reconnect routing")
public final class VelocityGuard {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private CrashGuard guard;
    private SessionBuffer<String> sessions;
    private ScheduledTask heartbeat;
    private final Set<UUID> kicked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Inject public VelocityGuard(ProxyServer proxy, Logger logger, @DataDirectory Path directory) { this.proxy = proxy; this.logger = logger; this.directory = directory; }
    @Subscribe public void start(ProxyInitializeEvent e) throws Exception {
        List<String> metadata = proxy.getPluginManager().getPlugins().stream().map(p -> p.getDescription().getId() + " " + p.getDescription().getVersion().orElse("unknown")).toList();
        Map<String, String> packages = new HashMap<>();
        proxy.getPluginManager().getPlugins().forEach(plugin -> plugin.getInstance().ifPresent(instance -> packages.put(instance.getClass().getPackageName(), plugin.getDescription().getId() + " " + plugin.getDescription().getVersion().orElse("unknown"))));
        guard = new CrashGuard(directory, new Platform() {
            public String name() { return "Velocity (scheduler liveness)"; }
            public void log(String message) { logger.info(message); }
            public void requestShutdown() { proxy.shutdown(); }
            public List<String> pluginMetadata() { return metadata; }
            public Map<String, String> pluginPackages() { return Map.copyOf(packages); }
        });
        Settings c = guard.settings(); sessions = new SessionBuffer<>(c.integer("recovery.window-seconds"), c.integer("recovery.max-sessions"));
        guard.start();
        // Velocity has no Bukkit-style main tick thread. This measures scheduler liveness only.
        heartbeat = proxy.getScheduler().buildTask(this, guard::beat).repeat(1, TimeUnit.SECONDS).schedule();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("crashguard").plugin(this).build(), new SimpleCommand() {
            @Override public void execute(Invocation invocation) {
                var source = invocation.source();
                if (!source.hasPermission("crashguard.admin")) { source.sendMessage(Component.text(c.message("no-permission", Map.of()))); return; }
                String[] args = invocation.arguments();
                if (args.length > 0 && args[0].equalsIgnoreCase("report")) guard.report(c.message("manual-report", Map.of()));
                else if (args.length > 0 && !args[0].equalsIgnoreCase("status")) { source.sendMessage(Component.text(c.message("command-usage", Map.of()))); return; }
                source.sendMessage(Component.text(c.message("status", Map.of("platform", "Velocity", "sessions", Integer.toString(sessions.size()), "state", guard.state()))));
            }
        });
    }
    @Subscribe public void disconnect(DisconnectEvent e) {
        if (guard == null || kicked.remove(e.getPlayer().getUniqueId()) || !guard.settings().bool("recovery.enabled")) return;
        e.getPlayer().getCurrentServer().ifPresent(server -> sessions.put(e.getPlayer().getUniqueId(), server.getServerInfo().getName()));
    }
    @Subscribe(order = PostOrder.LAST) public void kick(KickedFromServerEvent e) {
        if (e.getResult() instanceof KickedFromServerEvent.DisconnectPlayer) kicked.add(e.getPlayer().getUniqueId());
    }
    @Subscribe public void choose(PlayerChooseInitialServerEvent e) {
        if (guard == null || !guard.settings().bool("recovery.enabled")) return;
        sessions.take(e.getPlayer().getUniqueId()).flatMap(proxy::getServer).ifPresent(server -> {
            e.setInitialServer(server);
            if (guard.settings().bool("recovery.notify-player")) e.getPlayer().sendMessage(Component.text(guard.settings().message("proxy-recovered", Map.of())));
        });
    }
    @Subscribe public void stop(ProxyShutdownEvent e) {
        if (heartbeat != null) heartbeat.cancel();
        proxy.getCommandManager().unregister("crashguard");
        if (guard != null) guard.close(); if (sessions != null) sessions.clear(); kicked.clear();
    }
}
