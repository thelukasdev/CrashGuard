package de.crashguard.paper;

import org.bukkit.entity.Player;

/** Register through Bukkit ServicesManager to veto recovery for combat/minigames. */
@FunctionalInterface
public interface RecoveryPolicy {
    boolean allow(Player player);
}
