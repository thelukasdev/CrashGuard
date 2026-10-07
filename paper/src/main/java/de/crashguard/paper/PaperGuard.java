package de.crashguard.paper;

import de.crashguard.common.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import java.util.*;

public final class PaperGuard extends JavaPlugin implements Listener {
    private record Snapshot(Location location, Vector velocity) {}
    private record Frozen(boolean gravity, boolean ai) {}
    private CrashGuard guard;
    private SessionBuffer<Snapshot> sessions;
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Set<UUID> kicked = new HashSet<>();
    private final Set<UUID> timeoutKicks = new HashSet<>();
    private final Map<UUID, LatencyWindow> latency = new HashMap<>();
    private final Set<UUID> pendingRecovery = new HashSet<>();
    private Set<UUID> isolated = Set.of();
    private ExceptionCapture exceptionCapture;
    private final Map<UUID, Frozen> frozen = new HashMap<>();
    @Override public void onEnable() {
        try {
            List<String> plugins = Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(p -> p.getName() + " " + p.getDescription().getVersion()).toList();
            Map<String, String> packages = new HashMap<>();
            for (var plugin : Bukkit.getPluginManager().getPlugins()) packages.put(plugin.getClass().getPackageName(), plugin.getName() + " " + plugin.getDescription().getVersion());
            guard = new CrashGuard(getDataFolder().toPath(), new Platform() {
                public String name() { return "Paper"; }
                public void log(String message) { getLogger().info(message); }
                public void requestShutdown() { Bukkit.getScheduler().runTask(PaperGuard.this, Bukkit::shutdown); }
                public List<String> pluginMetadata() { return plugins; }
                public Map<String, String> pluginPackages() { return Map.copyOf(packages); }
            });
            Settings c = guard.settings();
            sessions = new SessionBuffer<>(c.integer("recovery.window-seconds"), c.integer("recovery.max-sessions"));
            Set<UUID> ids = new HashSet<>(); for (String id : c.list("isolation.entity-uuids")) ids.add(UUID.fromString(id)); isolated = Set.copyOf(ids);
            guard.start();
            exceptionCapture = new ExceptionCapture(guard);
            java.util.logging.Logger.getLogger("").addHandler(exceptionCapture);
            Bukkit.getPluginManager().registerEvents(this, this);
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                guard.beat();
                protectedUntil.values().removeIf(deadline -> deadline <= System.nanoTime());
            }, 1, 1);
            Bukkit.getScheduler().runTaskTimer(this, this::captureContext, 20, 20);
            if (c.bool("isolation.enabled")) for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) freeze(chunk);
        } catch (Exception e) { getLogger().severe(guard == null ? "CrashGuard startup failed: " + e : guard.settings().message("startup-failed", Map.of("error", e.toString()))); Bukkit.getPluginManager().disablePlugin(this); }
    }
    private void captureContext() {
        Settings config = guard.settings();
        if (config.bool("recovery.enabled") && config.bool("recovery.latency.enabled")) {
            for (Player player : Bukkit.getOnlinePlayers()) latency.computeIfAbsent(player.getUniqueId(), id -> new LatencyWindow(
                    config.integer("recovery.latency.consecutive-samples"), config.integer("recovery.latency.high-ping-ms"),
                    config.integer("recovery.latency.rise-ms"), config.integer("recovery.latency.baseline-multiplier")))
                    .sample(player.getPing(), System.nanoTime());
        }
        int limit = guard.settings().integer("reports.max-context");
        List<String> lines = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (lines.size() >= limit) break;
            Location l = player.getLocation();
            lines.add("Player " + player.getUniqueId() + " world=" + l.getWorld().getName() + " x=" + l.getBlockX() + " y=" + l.getBlockY() + " z=" + l.getBlockZ());
        }
        // Loaded state only, no chunk loads or unsafe asynchronous world reads.
        for (UUID id : isolated) {
            if (lines.size() >= limit) break;
            Entity e = Bukkit.getEntity(id);
            if (e != null) lines.add("Isolation target " + id + " type=" + e.getType() + " location=" + e.getLocation());
        }
        guard.context(lines);
    }
    private boolean eligible(Player p) {
        Settings c = guard.settings();
        if (!c.bool("recovery.enabled") || !p.hasPermission("crashguard.recovery") || (c.bool("recovery.exclude-dead") && p.isDead()) || (c.bool("recovery.exclude-creative") && p.getGameMode() == GameMode.CREATIVE)) return false;
        for (var registration : Bukkit.getServicesManager().getRegistrations(RecoveryPolicy.class)) if (!registration.getProvider().allow(p)) return false;
        return true;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kick(PlayerKickEvent e) {
        if (e.getCause() == PlayerKickEvent.Cause.TIMEOUT) timeoutKicks.add(e.getPlayer().getUniqueId());
        else kicked.add(e.getPlayer().getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer(); protectedUntil.remove(p.getUniqueId());
        pendingRecovery.remove(p.getUniqueId());
        LatencyWindow window = latency.remove(p.getUniqueId());
        boolean blocked = kicked.remove(p.getUniqueId());
        Settings c = guard.settings();
        boolean unstable = c.bool("recovery.latency.enabled") && window != null && window.unstable(System.nanoTime());
        String reason = timeoutKicks.remove(p.getUniqueId()) ? "TIMED_OUT" : e.getReason() == null ? "UNKNOWN" : e.getReason().name();
        boolean buffer = ReconnectDecision.recover(reason, blocked, c.bool("recovery.recover-ambiguous-disconnects"), unstable) && eligible(p);
        if (c.bool("recovery.log-decisions")) getLogger().info(c.message("recovery-decision", Map.of(
                "player", p.getUniqueId().toString(), "reason", reason, "ping", Integer.toString(p.getPing()),
                "unstable", Boolean.toString(unstable), "buffered", Boolean.toString(buffer))));
        if (!buffer) return;
        sessions.put(p.getUniqueId(), new Snapshot(p.getLocation().clone(), p.getVelocity().clone()));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        var snapshot = sessions.take(p.getUniqueId());
        if (snapshot.isEmpty() || !eligible(p)) return;
        Snapshot s = snapshot.get();
        Location arrival = p.getLocation().clone();
        pendingRecovery.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(this, () -> {
            if (!pendingRecovery.remove(p.getUniqueId())) return;
            if (!p.isOnline() || !eligible(p)) return;
            Settings c = guard.settings();
            if (c.bool("recovery.respect-other-plugins") && (!samePosition(arrival, s.location()) || !samePosition(p.getLocation(), arrival))) return;
            World world = Bukkit.getWorld(s.location().getWorld().getUID());
            if (world == null) return;
            Location destination = s.location().clone(); destination.setWorld(world);
            if (c.bool("recovery.restore-location") && !p.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) return;
            if (c.bool("recovery.restore-velocity")) p.setVelocity(s.velocity()); else p.setVelocity(new Vector());
            p.setFallDistance(0);
            if (c.bool("recovery.protect-after-reconnect")) protectedUntil.put(p.getUniqueId(), System.nanoTime() + c.integer("recovery.protection-seconds") * 1_000_000_000L);
            if (c.bool("recovery.notify-player")) p.sendMessage(c.message("recovered", Map.of()));
        });
    }
    private boolean samePosition(Location a, Location b) {
        return a.getWorld().getUID().equals(b.getWorld().getUID()) && a.distanceSquared(b) <= 1.0;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        // Our own restore removes the token first. A spawn/login plugin takes precedence.
        if (guard.settings().bool("recovery.respect-other-plugins")) pendingRecovery.remove(e.getPlayer().getUniqueId());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && protectedUntil.getOrDefault(p.getUniqueId(), 0L) > System.nanoTime()) e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent hit) {
            Entity attacker = hit.getDamager();
            if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) attacker = shooter;
            if (attacker instanceof Player p && protectedUntil.getOrDefault(p.getUniqueId(), 0L) > System.nanoTime()) e.setCancelled(true);
        }
    }
    @EventHandler public void chunk(ChunkLoadEvent e) { if (guard.settings().bool("isolation.enabled")) freeze(e.getChunk()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void unload(ChunkUnloadEvent e) { for (Entity entity : e.getChunk().getEntities()) unfreeze(entity); }
    private void unfreeze(Entity entity) {
        Frozen original = frozen.remove(entity.getUniqueId());
        if (original == null) return;
        entity.setGravity(original.gravity());
        if (entity instanceof LivingEntity living) living.setAI(original.ai());
    }
    private void freeze(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Player || !isolated.contains(e.getUniqueId())) continue;
            frozen.putIfAbsent(e.getUniqueId(), new Frozen(e.hasGravity(), e instanceof LivingEntity living && living.hasAI()));
            if (guard.settings().bool("isolation.freeze-gravity")) { e.setGravity(false); e.setVelocity(new Vector()); }
            if (guard.settings().bool("isolation.freeze-ai") && e instanceof LivingEntity living) living.setAI(false);
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Settings c = guard.settings();
        if (!sender.hasPermission("crashguard.admin")) { sender.sendMessage(c.message("no-permission", Map.of())); return true; }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("report")) guard.report(c.message("manual-report", Map.of()));
        else if (!sub.equals("status")) { sender.sendMessage(c.message("command-usage", Map.of())); return true; }
        sender.sendMessage(c.message("status", Map.of("platform", "Paper", "sessions", Integer.toString(sessions.size()), "state", guard.state())));
        return true;
    }
    @Override public void onDisable() {
        for (UUID id : List.copyOf(frozen.keySet())) { Entity entity = Bukkit.getEntity(id); if (entity != null) unfreeze(entity); } frozen.clear();
        if (exceptionCapture != null) java.util.logging.Logger.getLogger("").removeHandler(exceptionCapture);
        if (guard != null) guard.close(); if (sessions != null) sessions.clear(); protectedUntil.clear(); kicked.clear(); timeoutKicks.clear(); latency.clear(); pendingRecovery.clear();
    }
}
