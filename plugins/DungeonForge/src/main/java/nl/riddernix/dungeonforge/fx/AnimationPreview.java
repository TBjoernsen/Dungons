package nl.riddernix.dungeonforge.fx;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Plays a boss entrance where a player stands, without generating a dungeon.
 *
 * <p>The stand-in is the real boss entity of the theme, at the theme's own
 * scale and summoning duration, so radius, rise depth and timing can be tuned
 * against what the fight will actually look like. It is tagged as a testing
 * mob, which means a stray one is swept up by {@code /dungeon summon clear}.</p>
 */
public final class AnimationPreview {

    /**
     * Grace period after the outro before the stand-in is taken away. Long
     * enough to actually look at what the entrance ended on, which a second
     * and a bit was not.
     */
    private static final int LINGER_TICKS = 80;

    private final DungeonForgePlugin plugin;
    private final Map<UUID, UUID> running = new HashMap<>();

    public AnimationPreview(DungeonForgePlugin plugin) {
        this.plugin = plugin;
    }

    public Result start(Player player, String name, String requestedTheme) {
        if (SpawnAnimations.names().stream().noneMatch(known -> known.equalsIgnoreCase(name))) {
            return new Result(Status.UNKNOWN_ANIMATION, null, 0, null);
        }
        if (running.containsKey(player.getUniqueId())) {
            return new Result(Status.ALREADY_RUNNING, null, 0, null);
        }
        String theme = requestedTheme == null || requestedTheme.isBlank() ? themeUsing(name) : requestedTheme.toLowerCase(Locale.ROOT);
        if (theme == null || plugin.getConfig().getConfigurationSection("mobs.themes." + theme) == null) {
            return new Result(Status.UNKNOWN_THEME, theme, 0, null);
        }

        String bossPath = "mobs.themes." + theme + ".boss.";
        EntityType type = entityType(plugin.getConfig().getString(bossPath + "type", "WARDEN"));
        if (type == null) {
            return new Result(Status.INVALID_BOSS, theme, 0, null);
        }
        int duration = Math.max(1, plugin.getConfig().getInt(bossPath + "summoning.duration-ticks",
                plugin.getConfig().getInt("mobs.boss-summoning.duration-ticks", 50)));
        boolean invulnerable = plugin.getConfig().getBoolean(bossPath + "summoning.invulnerable",
                plugin.getConfig().getBoolean("mobs.boss-summoning.invulnerable", true));

        Location origin = player.getLocation().getBlock().getLocation().add(0.5, 0.0, 0.5);
        if (origin.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            // Said up front, because the stand-in will not survive the tick.
            plugin.messages().send(player, "animate-peaceful", Messages.ph("entity", type.name()));
        }
        Entity spawned = origin.getWorld().spawnEntity(origin, type);
        if (!(spawned instanceof LivingEntity stand)) {
            spawned.remove();
            return new Result(Status.INVALID_BOSS, theme, 0, null);
        }
        prepare(stand, theme);

        SpawnAnimation animation = SpawnAnimations.create(plugin, stand, name, invulnerable);
        if (animation == null) {
            stand.remove();
            return new Result(Status.UNKNOWN_ANIMATION, theme, 0, null);
        }
        running.put(player.getUniqueId(), stand.getUniqueId());
        if (!SpawnAnimations.beginSafely(plugin, animation)) {
            running.remove(player.getUniqueId());
            stand.remove();
            return new Result(Status.FAILED, theme, 0, null);
        }
        run(player.getUniqueId(), stand, animation, duration);
        return new Result(Status.SUCCESS, theme, duration, SpawnAnimations.describe(animation));
    }

    /** Removes this player's preview, whether it is mid-animation or lingering. */
    public boolean stop(Player player) {
        UUID standId = running.remove(player.getUniqueId());
        if (standId == null) {
            return false;
        }
        Entity stand = Bukkit.getEntity(standId);
        if (stand != null) {
            stand.remove();
        }
        return true;
    }

    /** Removes every preview, used when the plugin reloads or shuts down. */
    public void stopAll() {
        for (UUID standId : new ArrayList<>(running.values())) {
            Entity stand = Bukkit.getEntity(standId);
            if (stand != null) {
                stand.remove();
            }
        }
        running.clear();
    }

    public List<String> animationNames() {
        return SpawnAnimations.names();
    }

    /** Themes that already ask for this animation, offered as tab completions. */
    public List<String> themes() {
        var section = plugin.getConfig().getConfigurationSection("mobs.themes");
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
    }

    // ------------------------------------------------------------------

    private void run(UUID owner, LivingEntity stand, SpawnAnimation animation, int duration) {
        new BukkitRunnable() {
            private int ticks;
            private int lingering = -1;
            private boolean standReported;

            @Override
            public void run() {
                // A losing stand-in used to wipe the whole entrance in silence.
                // The visuals are the entire point of a preview, so they play
                // on without it and the disappearance is reported instead.
                if ((!stand.isValid() || stand.isDead()) && !standReported) {
                    standReported = true;
                    reportMissingStand(owner, stand);
                }
                if (lingering >= 0) {
                    // The outro is already playing; only the clean-up is left.
                    if (lingering++ < LINGER_TICKS) {
                        return;
                    }
                    running.remove(owner);
                    stand.remove();
                    cancel();
                    return;
                }
                // Mirrors what BossSummoningSequence does every tick, so the
                // preview turns exactly like the real entrance does.
                if (stand.isValid()) {
                    stand.setRotation(stand.getLocation().getYaw() + 12.0F, 0.0F);
                }
                if (!SpawnAnimations.tickSafely(plugin, animation, ticks, duration)) {
                    // tickSafely already logged the cause and cleaned up.
                    running.remove(owner);
                    stand.remove();
                    cancel();
                    return;
                }
                if (++ticks >= duration) {
                    animation.finish();
                    lingering = 0;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * The server took the stand-in away. On Peaceful every monster is removed
     * the tick after it spawns, and a plugin cancelling CreatureSpawnEvent has
     * the same effect, so name both rather than leaving an empty arena.
     */
    private void reportMissingStand(UUID owner, LivingEntity stand) {
        plugin.getLogger().warning("Animation preview stand-in " + stand.getType() + " was removed by the server"
                + " (world difficulty " + stand.getWorld().getDifficulty() + "). The entrance keeps playing without it;"
                + " on PEACEFUL every monster is removed one tick after spawning.");
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            plugin.messages().send(player, "animate-stand-gone",
                    Messages.ph("entity", stand.getType().name()),
                    Messages.ph("difficulty", stand.getWorld().getDifficulty().name()));
        }
    }

    private void prepare(LivingEntity stand, String theme) {
        stand.setAI(false);
        stand.setInvulnerable(true);
        stand.setRemoveWhenFarAway(false);
        stand.setPersistent(false);
        if (stand instanceof Ageable ageable) {
            ageable.setAdult();
        }
        // Same scale the real fight uses, because rise depth has to clear it.
        AttributeInstance scale = stand.getAttribute(Attribute.SCALE);
        double configured = bossScale(theme);
        if (scale != null && configured > 0.0) {
            scale.setBaseValue(configured);
        }
        stand.getPersistentDataContainer().set(plugin.dungeonMobTestKey(), PersistentDataType.BYTE, (byte) 1);
    }

    /** The scale of the first difficulty band that uses this theme. */
    private double bossScale(String theme) {
        for (int difficulty = 1; difficulty <= 9; difficulty++) {
            String path = "mobs.difficulties." + difficulty + ".";
            if (theme.equalsIgnoreCase(plugin.getConfig().getString(path + "theme", ""))) {
                return plugin.getConfig().getDouble(path + "boss.scale", 1.0);
            }
        }
        return 1.0;
    }

    /** The first theme whose boss already asks for this animation. */
    private String themeUsing(String name) {
        var section = plugin.getConfig().getConfigurationSection("mobs.themes");
        if (section == null) {
            return null;
        }
        for (String theme : section.getKeys(false)) {
            String configured = plugin.getConfig().getString("mobs.themes." + theme + ".boss.summoning.animation", "");
            if (configured != null && configured.trim().equalsIgnoreCase(name)) {
                return theme;
            }
        }
        return null;
    }

    private static EntityType entityType(String value) {
        try {
            EntityType type = EntityType.valueOf(value == null ? "WARDEN" : value.trim().toUpperCase(Locale.ROOT));
            return type.getEntityClass() != null && LivingEntity.class.isAssignableFrom(type.getEntityClass()) ? type : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public enum Status {
        SUCCESS,
        UNKNOWN_ANIMATION,
        UNKNOWN_THEME,
        INVALID_BOSS,
        ALREADY_RUNNING,
        FAILED
    }

    /** {@code detail} names what the animation created, or null when it never started. */
    public record Result(Status status, String theme, int durationTicks, String detail) {
    }
}
