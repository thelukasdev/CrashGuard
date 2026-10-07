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
import org.bukkit.persistence.PersistentDataType;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;

public final class PaperGuard extends JavaPlugin implements Listener {
    private record Snapshot(UUID world, double x, double y, double z, float yaw, float pitch, Vector velocity) {
        Location location(World world) { return new Location(world, x, y, z, yaw, pitch); }
    }
    private record Tracked(String key, LatencyWindow latency, AtomicReference<String> context, AtomicReference<ScheduledTask> task) {}
    private record RestoreTarget(Object token, Location location) {}
    private CrashGuard guard;
    private SessionBuffer<Snapshot> sessions;
    private final Map<UUID, Long> protectedUntil = new ConcurrentHashMap<>();
    private final Set<UUID> kicked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> timeoutKicks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, Object> pendingRecovery = new ConcurrentHashMap<>();
    private final Map<UUID, RestoreTarget> restoreTargets = new ConcurrentHashMap<>();
    private Set<UUID> isolated = Set.of();
    private ExceptionCapture exceptionCapture;
    private final Map<UUID, Entity> frozen = new ConcurrentHashMap<>();
    private boolean folia;
    private volatile boolean stopping;
    private NamespacedKey originalGravity, originalAI;
    private String platformName() { return folia ? "Folia" : "Paper"; }
    @Override public void onEnable() {
        try {
            try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; } catch (ClassNotFoundException ignored) {}
            originalGravity = new NamespacedKey(this, "original_gravity"); originalAI = new NamespacedKey(this, "original_ai");
            List<String> plugins = Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(p -> p.getName() + " " + p.getDescription().getVersion()).toList();
            Map<String, String> packages = new HashMap<>();
            for (var plugin : Bukkit.getPluginManager().getPlugins()) packages.put(plugin.getClass().getPackageName(), plugin.getName() + " " + plugin.getDescription().getVersion());
            guard = new CrashGuard(getDataFolder().toPath(), new Platform() {
                public String name() { return platformName(); }
                public void log(String message) { getLogger().info(message); }
                public void requestShutdown() { Bukkit.getGlobalRegionScheduler().execute(PaperGuard.this, Bukkit::shutdown); }
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
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                guard.beat();
                protectedUntil.values().removeIf(deadline -> deadline <= System.nanoTime());
            }, 1, 1);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> captureContext(), 20, 20);
            if (!folia && c.bool("isolation.enabled")) for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) freeze(chunk);
        } catch (Exception e) { getLogger().severe(guard == null ? "CrashGuard startup failed: " + e : guard.settings().message("startup-failed", Map.of("error", e.toString()))); onDisable(); }
    }
    private void captureContext() {
        if (stopping) return;
        // Enumerate references only. Entity state is captured exclusively by its scheduler.
        for (Player player : Bukkit.getOnlinePlayers()) track(player);
        guard.context(tracked.values().stream().map(t -> t.context().get()).filter(Objects::nonNull)
                .limit(guard.settings().integer("reports.max-context")).toList());
    }
    private void track(Player player) {
        UUID id = player.getUniqueId(); Settings c = guard.settings();
        if (tracked.containsKey(id)) return;
        Tracked observation = new Tracked("player:" + id + ":" + UUID.randomUUID(), new LatencyWindow(
                c.integer("recovery.latency.consecutive-samples"), c.integer("recovery.latency.high-ping-ms"),
                c.integer("recovery.latency.rise-ms"), c.integer("recovery.latency.baseline-multiplier")), new AtomicReference<>(), new AtomicReference<>());
        if (stopping || tracked.putIfAbsent(id, observation) != null) return;
        if (folia && c.bool("watchdog.folia.player-regions")) guard.watchSource(observation.key());
        ScheduledTask task = player.getScheduler().runAtFixedRate(this, tick -> {
            if (stopping || tracked.get(id) != observation) { tick.cancel(); return; }
            if (folia && c.bool("watchdog.folia.player-regions")) guard.beatSource(observation.key());
            if (c.bool("recovery.enabled") && c.bool("recovery.latency.enabled")) observation.latency().sample(player.getPing(), System.nanoTime());
            Location l = player.getLocation();
            observation.context().set("Player " + id + " world=" + l.getWorld().getName() + " x=" + l.getBlockX() + " y=" + l.getBlockY() + " z=" + l.getBlockZ());
        }, () -> retire(id, observation), 1, 20);
        observation.task().set(task);
        if (task == null) retire(id, observation);
        else if (tracked.get(id) != observation) task.cancel();
    }
    private void retire(UUID id, Tracked observation) {
        tracked.remove(id, observation); guard.forgetHeartbeat(observation.key());
        ScheduledTask task = observation.task().get(); if (task != null) task.cancel();
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
        Tracked observation = tracked.get(p.getUniqueId());
        LatencyWindow window = observation == null ? null : observation.latency();
        if (observation != null) retire(p.getUniqueId(), observation);
        restoreTargets.remove(p.getUniqueId());
        boolean blocked = kicked.remove(p.getUniqueId());
        Settings c = guard.settings();
        boolean unstable = c.bool("recovery.latency.enabled") && window != null && window.unstable(System.nanoTime());
        String reason = timeoutKicks.remove(p.getUniqueId()) ? "TIMED_OUT" : e.getReason() == null ? "UNKNOWN" : e.getReason().name();
        boolean buffer = ReconnectDecision.recover(reason, blocked, c.bool("recovery.recover-ambiguous-disconnects"), unstable) && eligible(p);
        if (c.bool("recovery.log-decisions")) getLogger().info(c.message("recovery-decision", Map.of(
                "player", p.getUniqueId().toString(), "reason", reason, "ping", Integer.toString(p.getPing()),
                "unstable", Boolean.toString(unstable), "buffered", Boolean.toString(buffer))));
        if (!buffer) return;
        Location location = p.getLocation();
        sessions.put(p.getUniqueId(), new Snapshot(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), p.getVelocity().clone()));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        track(p);
        var snapshot = sessions.take(p.getUniqueId());
        if (snapshot.isEmpty() || !eligible(p)) return;
        Snapshot s = snapshot.get();
        Location arrival = p.getLocation().clone();
        Object token = new Object(); pendingRecovery.put(p.getUniqueId(), token);
        Runnable retired = () -> pendingRecovery.remove(p.getUniqueId(), token);
        boolean accepted = p.getScheduler().execute(this, () -> {
            if (stopping || pendingRecovery.get(p.getUniqueId()) != token) return;
            if (!p.isOnline() || !eligible(p)) { retired.run(); return; }
            Settings c = guard.settings();
            World world = Bukkit.getWorld(s.world());
            if (world == null) { retired.run(); return; }
            Location destination = s.location(world);
            if (c.bool("recovery.respect-other-plugins") && (!samePosition(arrival, destination) || !samePosition(p.getLocation(), arrival))) { retired.run(); return; }
            if (!c.bool("recovery.restore-location")) { finishRecovery(p, s, token); return; }
            RestoreTarget target = new RestoreTarget(token, destination);
            restoreTargets.put(p.getUniqueId(), target);
            p.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, failure) -> {
                restoreTargets.remove(p.getUniqueId(), target);
                if (stopping || failure != null || !Boolean.TRUE.equals(success)) { retired.run(); return; }
                boolean scheduled = p.getScheduler().execute(this, () -> finishRecovery(p, s, token), retired, 1);
                if (!scheduled) retired.run();
            });
        }, retired, 1);
        if (!accepted) retired.run();
    }
    private void finishRecovery(Player p, Snapshot snapshot, Object token) {
        if (!pendingRecovery.remove(p.getUniqueId(), token) || stopping || !p.isOnline() || !eligible(p)) return;
        Settings c = guard.settings();
        if (c.bool("recovery.restore-velocity")) p.setVelocity(snapshot.velocity()); else p.setVelocity(new Vector());
        p.setFallDistance(0);
        if (c.bool("recovery.protect-after-reconnect")) protectedUntil.put(p.getUniqueId(), System.nanoTime() + c.integer("recovery.protection-seconds") * 1_000_000_000L);
        if (c.bool("recovery.notify-player")) p.sendMessage(c.message("recovered", Map.of()));
    }
    private boolean samePosition(Location a, Location b) {
        return a.getWorld().getUID().equals(b.getWorld().getUID()) && a.distanceSquared(b) <= 1.0;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        RestoreTarget ownTarget = restoreTargets.get(e.getPlayer().getUniqueId());
        boolean ownRestore = ownTarget != null && pendingRecovery.get(e.getPlayer().getUniqueId()) == ownTarget.token()
                && e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN && Objects.equals(ownTarget.location(), e.getTo());
        if (guard.settings().bool("recovery.respect-other-plugins") && !ownRestore) pendingRecovery.remove(e.getPlayer().getUniqueId());
        Tracked observation = tracked.get(e.getPlayer().getUniqueId());
        if (observation != null) guard.forgetHeartbeat(observation.key());
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
        var data = entity.getPersistentDataContainer();
        Byte gravity = data.get(originalGravity, PersistentDataType.BYTE);
        Byte ai = data.get(originalAI, PersistentDataType.BYTE);
        if (gravity != null) { entity.setGravity(gravity != 0); data.remove(originalGravity); }
        if (ai != null && entity instanceof LivingEntity living) { living.setAI(ai != 0); data.remove(originalAI); }
        frozen.remove(entity.getUniqueId(), entity);
    }
    @EventHandler public void added(EntityAddToWorldEvent e) {
        if (stopping) return;
        // Runs on the owning region. Restore persisted originals even when isolation is disabled.
        unfreeze(e.getEntity());
        if (guard.settings().bool("isolation.enabled")) freeze(e.getEntity());
    }
    @EventHandler public void removed(EntityRemoveFromWorldEvent e) {
        frozen.remove(e.getEntity().getUniqueId(), e.getEntity());
    }
    private void freeze(Chunk chunk) {
        for (Entity e : chunk.getEntities()) freeze(e);
    }
    private void freeze(Entity e) {
        if (e instanceof Player || !isolated.contains(e.getUniqueId())) return;
        frozen.putIfAbsent(e.getUniqueId(), e);
        var data = e.getPersistentDataContainer();
        if (guard.settings().bool("isolation.freeze-gravity")) {
            if (!data.has(originalGravity, PersistentDataType.BYTE)) data.set(originalGravity, PersistentDataType.BYTE, (byte)(e.hasGravity() ? 1 : 0));
            e.setGravity(false); e.setVelocity(new Vector());
        }
        if (guard.settings().bool("isolation.freeze-ai") && e instanceof LivingEntity living) {
            if (!data.has(originalAI, PersistentDataType.BYTE)) data.set(originalAI, PersistentDataType.BYTE, (byte)(living.hasAI() ? 1 : 0));
            living.setAI(false);
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (stopping || guard == null) return true;
        Settings c = guard.settings();
        if (!sender.hasPermission("crashguard.admin")) { sender.sendMessage(c.message("no-permission", Map.of())); return true; }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("report")) guard.report(c.message("manual-report", Map.of()));
        else if (!sub.equals("status")) { sender.sendMessage(c.message("command-usage", Map.of())); return true; }
        sender.sendMessage(c.message("status", Map.of("platform", platformName(), "sessions", Integer.toString(sessions.size()), "state", guard.state())));
        return true;
    }
    @Override public void onDisable() {
        stopping = true;
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        tracked.forEach(this::retire);
        // Folia shutdown is global: never mutate entities owned by another region here.
        // Their original flags remain in PDC and are restored on the next entity load.
        if (!folia) for (Entity entity : List.copyOf(frozen.values())) unfreeze(entity);
        frozen.clear();
        if (exceptionCapture != null) java.util.logging.Logger.getLogger("").removeHandler(exceptionCapture);
        if (guard != null) guard.close(); if (sessions != null) sessions.clear(); protectedUntil.clear(); kicked.clear(); timeoutKicks.clear(); tracked.clear(); pendingRecovery.clear(); restoreTargets.clear();
    }
}
