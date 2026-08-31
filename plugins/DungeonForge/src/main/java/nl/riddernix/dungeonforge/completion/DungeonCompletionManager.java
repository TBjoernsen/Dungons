package nl.riddernix.dungeonforge.completion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonEndReason;
import nl.riddernix.dungeonforge.party.DungeonParty;
import nl.riddernix.dungeonforge.room.DungeonInstance;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns the one-shot transition from a defeated boss to a cleaned-up instance. */
public final class DungeonCompletionManager {
    private final DungeonForgePlugin plugin;
    private final Map<String, CompletionRun> completions = new HashMap<>();
    private final Set<UUID> cleanupLockedMembers = new LinkedHashSet<>();

    public DungeonCompletionManager(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Completes a dungeon once. Duplicate boss-death notifications are ignored. */
    public void complete(DungeonInstance dungeon) {
        if (!dungeon.complete()) return;
        DungeonParty party = plugin.parties().partyForWorld(dungeon.world().getName()).orElse(null);
        Set<UUID> partyMembers = party == null ? playerIds(dungeon.world()) : party.members();
        Set<UUID> occupants = party == null ? playerIds(dungeon.world()) : plugin.parties().instanceMembers(party);
        Duration duration = party == null ? Duration.ZERO : plugin.parties().runDuration(party);
        CompletionRun run = new CompletionRun(dungeon, party, Set.copyOf(partyMembers), Set.copyOf(occupants), duration, dungeon.mobKillCount());
        completions.put(dungeon.id(), run);
        cleanupLockedMembers.addAll(partyMembers);

        plugin.mobs().despawnDungeonMobs(dungeon);
        // The bus fires the older DungeonCompletedEvent alongside this, so
        // plugins written against the first API keep working.
        plugin.events().fireEnd(plugin.snapshots().ending(dungeon, true), DungeonEndReason.COMPLETED);
        announce(run);

        long grace = Math.max(0L, plugin.getConfig().getLong("completion.grace-period-ticks", 160L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> beginReturn(run), grace);
    }

    /** Prevents a member from starting another instance until this cleanup ends. */
    public boolean isCleaningUp(UUID playerId) {
        return cleanupLockedMembers.contains(playerId);
    }

    private void announce(CompletionRun run) {
        String duration = format(run.duration);
        for (UUID memberId : run.partyMembers) {
            Player player = Bukkit.getPlayer(memberId);
            if (player == null || !player.isOnline()) continue;
            player.showTitle(Title.title(
                    plugin.messages().bare("dungeon-completed-title", Messages.ph("difficulty", run.dungeon.difficulty()), Messages.ph("duration", duration), Messages.ph("kills", run.mobKillCount)),
                    plugin.messages().bare("dungeon-completed-subtitle", Messages.ph("difficulty", run.dungeon.difficulty()), Messages.ph("duration", duration), Messages.ph("kills", run.mobKillCount)),
                    Title.Times.times(Duration.ofMillis(Math.max(0L, plugin.getConfig().getLong("completion.title.fade-in-millis", 400L))),
                            Duration.ofMillis(Math.max(0L, plugin.getConfig().getLong("completion.title.stay-millis", 3000L))),
                            Duration.ofMillis(Math.max(0L, plugin.getConfig().getLong("completion.title.fade-out-millis", 600L))))));
            player.playSound(player.getLocation(), completionSound(),
                    (float) plugin.getConfig().getDouble("completion.sound.volume", 1.0),
                    (float) plugin.getConfig().getDouble("completion.sound.pitch", 1.0));
            plugin.messages().send(player, "dungeon-completed",
                    Messages.ph("difficulty", run.dungeon.difficulty()), Messages.ph("duration", duration), Messages.ph("kills", run.mobKillCount));
        }
    }

    private void beginReturn(CompletionRun run) {
        if (completions.get(run.dungeon.id()) != run) return;
        for (UUID memberId : run.occupants) {
            Location destination = returnLocation(run, memberId);
            plugin.parties().queueCompletionReturn(memberId, destination);
            Player player = Bukkit.getPlayer(memberId);
            if (player == null || !player.isOnline()) continue;
            if (player.isDead()) {
                player.spigot().respawn();
            } else {
                player.teleport(destination);
                plugin.parties().takeCompletionReturn(memberId);
                plugin.rooms().refresh(player);
            }
        }
        waitForRespawns(run, 0);
    }

    private void waitForRespawns(CompletionRun run, int attempts) {
        if (completions.get(run.dungeon.id()) != run) return;
        boolean deadMember = run.occupants.stream()
                .map(Bukkit::getPlayer)
                .anyMatch(player -> player != null && player.isOnline() && player.isDead());
        int maximumAttempts = Math.max(1, plugin.getConfig().getInt("completion.respawn-settle-attempts", 20));
        if (deadMember && attempts < maximumAttempts) {
            for (UUID memberId : run.occupants) {
                Player player = Bukkit.getPlayer(memberId);
                if (player != null && player.isOnline() && player.isDead()) player.spigot().respawn();
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> waitForRespawns(run, attempts + 1), 1L);
            return;
        }
        cleanUp(run);
    }

    private void cleanUp(CompletionRun run) {
        if (completions.remove(run.dungeon.id()) != run) return;
        World world = run.dungeon.world();
        for (Player player : world.getPlayers().toArray(Player[]::new)) {
            if (player.isDead()) continue;
            player.teleport(returnLocation(run, player.getUniqueId()));
            plugin.rooms().refresh(player);
        }
        if (run.party != null) plugin.parties().endInstance(run.party);
        plugin.worlds().deleteWorld(world.getName());
        cleanupLockedMembers.removeAll(run.partyMembers);
    }

    private Location returnLocation(CompletionRun run, UUID playerId) {
        return run.party == null ? fallbackLocation() : plugin.parties().returnLocation(run.party, playerId).orElseGet(this::fallbackLocation);
    }

    private static Set<UUID> playerIds(World world) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Player player : world.getPlayers()) ids.add(player.getUniqueId());
        return ids;
    }

    private Location fallbackLocation() {
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private Sound completionSound() {
        String raw = plugin.getConfig().getString("completion.sound.type", "UI_TOAST_CHALLENGE_COMPLETE");
        Sound sound = raw == null ? null : Registry.SOUNDS.get(NamespacedKey.minecraft(raw.toLowerCase().replace('_', '.')));
        return sound == null ? Sound.UI_TOAST_CHALLENGE_COMPLETE : sound;
    }

    private static String format(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }

    private record CompletionRun(DungeonInstance dungeon, DungeonParty party, Set<UUID> partyMembers,
                                 Set<UUID> occupants, Duration duration, int mobKillCount) { }
}
