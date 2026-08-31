package nl.riddernix.dungeonforge.skills;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Optional link to ClassSkills, which owns a player's class and their
 * level-derived point budget.
 *
 * <p>The split is deliberate and each side owns exactly one half:</p>
 * <ul>
 *   <li><strong>ClassSkills</strong> owns the class, the level and the point
 *       budget that level buys.</li>
 *   <li><strong>DungeonForge</strong> owns which nodes were bought, and
 *       therefore what has been spent.</li>
 * </ul>
 *
 * <p>Available points are then {@code budget + granted - spent}, computed
 * here rather than stored anywhere. That matters because ClassSkills'
 * {@code getAvailableSkillPoints} is documented to return <em>our</em>
 * balance when we are installed: asking it back would be a loop. This class
 * therefore reads the budget, the class and the unlocked difficulty, and
 * never the available points.</p>
 *
 * <p>Reflection rather than a compile-time dependency, purely because the
 * ClassSkills jar is not part of this build. Everything degrades to "not
 * installed" if a signature ever moves, and DungeonForge keeps working on its
 * own stored balance.</p>
 */
public final class ClassSkillsIntegration implements Listener {

    private static final String SERVICE = "dev.thorb.classskills.api.ClassSkillsApi";

    private final DungeonForgePlugin plugin;
    private Object api;
    private Method getPlayerProfile;
    private Method getTotalSkillPointBudget;
    private Method hasUnlockedDifficulty;
    private Method profileClassId;
    private boolean warned;
    /**
     * Reflection is far too slow for {@code hasSkillNode}, which a combat
     * plugin calls on every hit. Answers are held for a second, which is
     * immediate to a player and turns thousands of lookups into one.
     */
    private final Map<UUID, Cached> cache = new HashMap<>();
    private static final long CACHE_TICKS = 20L;

    public ClassSkillsIntegration(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        bind();
    }

    /** True once ClassSkills is up and its methods resolved. */
    public boolean isAvailable() {
        return api != null;
    }

    /**
     * The class ClassSkills says this player is, which outranks anything
     * DungeonForge stored: choosing a class lives on that side now.
     */
    public Optional<String> activeClass(Player player) {
        if (api == null || getPlayerProfile == null || profileClassId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cached(player).classId);
    }

    /** The level-derived point budget for the player's class. */
    public OptionalInt budget(Player player) {
        if (api == null || getTotalSkillPointBudget == null) {
            return OptionalInt.empty();
        }
        Cached entry = cached(player);
        return entry.budget < 0 ? OptionalInt.empty() : OptionalInt.of(entry.budget);
    }

    private Cached cached(Player player) {
        long now = Bukkit.getCurrentTick();
        Cached entry = cache.get(player.getUniqueId());
        if (entry != null && now - entry.readAtTick < CACHE_TICKS) {
            return entry;
        }
        Cached fresh = new Cached(now, null, -1);
        try {
            Object profile = getPlayerProfile.invoke(api, player);
            Object classId = profile == null ? null : profileClassId.invoke(profile);
            fresh = new Cached(now,
                    classId == null ? null : classId.toString().toLowerCase(Locale.ROOT),
                    ((Number) getTotalSkillPointBudget.invoke(api, player)).intValue());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            report(exception);
        }
        cache.put(player.getUniqueId(), fresh);
        return fresh;
    }

    /** Drops a player's cached answers, so the next read is live. */
    public void invalidate(Player player) {
        cache.remove(player.getUniqueId());
    }

    private record Cached(long readAtTick, String classId, int budget) { }

    /** Whether the player has unlocked a difficulty, for gating that lives there. */
    public boolean hasUnlockedDifficulty(Player player, int difficulty) {
        if (api == null || hasUnlockedDifficulty == null) {
            return true;
        }
        try {
            return (Boolean) hasUnlockedDifficulty.invoke(api, player, difficulty);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            report(exception);
            return true;
        }
    }

    // ------------------------------------------------------------------

    /** ClassSkills may enable after this plugin, so binding is not one-shot. */
    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (SERVICE.equals(event.getProvider().getService().getName())) {
            bind();
        }
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (SERVICE.equals(event.getProvider().getService().getName())) {
            api = null;
            plugin.getLogger().info("ClassSkills went away; skill points fall back to DungeonForge's own balance.");
        }
    }

    private void bind() {
        api = null;
        cache.clear();
        Class<?> serviceClass;
        try {
            serviceClass = Class.forName(SERVICE);
        } catch (ClassNotFoundException exception) {
            return;
        }
        Object provider = Bukkit.getServicesManager().load(serviceClass);
        if (provider == null) {
            return;
        }
        try {
            getPlayerProfile = serviceClass.getMethod("getPlayerProfile", Player.class);
            getTotalSkillPointBudget = serviceClass.getMethod("getTotalSkillPointBudget", Player.class);
            hasUnlockedDifficulty = serviceClass.getMethod("hasUnlockedDifficulty", Player.class, int.class);
            profileClassId = getPlayerProfile.getReturnType().getMethod("classId");
        } catch (NoSuchMethodException exception) {
            plugin.getLogger().warning("ClassSkills is installed but its API does not look the way this build "
                    + "expects (" + exception.getMessage() + "). Skill points stay on DungeonForge's own balance.");
            return;
        }
        api = provider;
        plugin.getLogger().info("ClassSkills detected: it owns the active class and the level-derived point "
                + "budget; DungeonForge owns which nodes are bought.");
    }

    /** One line per session: a broken link must not spam a log every hit. */
    private void report(Exception exception) {
        if (warned) {
            return;
        }
        warned = true;
        plugin.getLogger().warning("Reading from ClassSkills failed: " + exception
                + ". Falling back to DungeonForge's own values for the rest of this session.");
        api = null;
    }
}
