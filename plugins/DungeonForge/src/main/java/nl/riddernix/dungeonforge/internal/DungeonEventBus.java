package nl.riddernix.dungeonforge.internal;

import nl.riddernix.dungeonforge.DungeonForgePlugin;
import nl.riddernix.dungeonforge.api.DungeonBossDeathEvent;
import nl.riddernix.dungeonforge.api.DungeonBossSummonEvent;
import nl.riddernix.dungeonforge.api.DungeonCompletedEvent;
import nl.riddernix.dungeonforge.api.DungeonDoorOpenedEvent;
import nl.riddernix.dungeonforge.api.DungeonEndEvent;
import nl.riddernix.dungeonforge.api.DungeonEndReason;
import nl.riddernix.dungeonforge.api.DungeonForgeApi;
import nl.riddernix.dungeonforge.api.DungeonInfo;
import nl.riddernix.dungeonforge.api.DungeonKeyObtainedEvent;
import nl.riddernix.dungeonforge.api.DungeonMobDeathEvent;
import nl.riddernix.dungeonforge.api.DungeonSkillClassChangeEvent;
import nl.riddernix.dungeonforge.api.DungeonSkillNodeUnlockEvent;
import nl.riddernix.dungeonforge.api.DungeonSkillNodesGainedEvent;
import nl.riddernix.dungeonforge.api.DungeonSkillNodesRevokedEvent;
import nl.riddernix.dungeonforge.api.DungeonSkillPointsChangeEvent;
import nl.riddernix.dungeonforge.api.DungeonMobInfo;
import nl.riddernix.dungeonforge.api.DungeonMobSpawnEvent;
import nl.riddernix.dungeonforge.api.DungeonPlayerDeathEvent;
import nl.riddernix.dungeonforge.api.DungeonPlayerEnterEvent;
import nl.riddernix.dungeonforge.api.DungeonPlayerLeaveEvent;
import nl.riddernix.dungeonforge.api.DungeonRoomClearedEvent;
import nl.riddernix.dungeonforge.api.DungeonRoomEnterEvent;
import nl.riddernix.dungeonforge.api.DungeonRoomInfo;
import nl.riddernix.dungeonforge.api.DungeonRoomType;
import nl.riddernix.dungeonforge.api.DungeonStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single place any API event is fired from.
 *
 * <p>This exists because the API rotted once already: the mob and room systems
 * were rewritten and nothing failed visibly when call sites went missing.
 * Three things guard against that happening again.</p>
 *
 * <p>One, every event goes through a method here, so a rewrite has to delete a
 * named call to break the API rather than quietly dropping a
 * {@code callEvent} buried in a manager. Two, each method counts its own
 * firings, so {@code /dungeon api status} shows at a glance which events have
 * never fired this session - a dropped call site becomes a zero somebody can
 * see. Three, the event list below is the API's inventory: a type that is not
 * in it is not reported and not testable, which makes adding one without
 * wiring it up an obvious omission rather than a silent one.</p>
 */
public final class DungeonEventBus {

    /** Every event type the API can fire, in the order the docs list them. */
    private static final List<Class<? extends Event>> EVENT_TYPES = List.of(
            DungeonStartEvent.class,
            DungeonPlayerEnterEvent.class,
            DungeonRoomEnterEvent.class,
            DungeonMobSpawnEvent.class,
            DungeonMobDeathEvent.class,
            DungeonRoomClearedEvent.class,
            DungeonKeyObtainedEvent.class,
            DungeonDoorOpenedEvent.class,
            DungeonSkillNodeUnlockEvent.class,
            DungeonSkillNodesGainedEvent.class,
            DungeonSkillNodesRevokedEvent.class,
            DungeonSkillClassChangeEvent.class,
            DungeonSkillPointsChangeEvent.class,
            DungeonBossSummonEvent.class,
            DungeonBossDeathEvent.class,
            DungeonPlayerDeathEvent.class,
            DungeonPlayerLeaveEvent.class,
            DungeonEndEvent.class,
            DungeonCompletedEvent.class);

    private final DungeonForgePlugin plugin;
    private final Map<String, AtomicInteger> fired = new LinkedHashMap<>();
    /** Dungeons already ended, so completion and world deletion cannot both end one. */
    private final Set<String> ended = new HashSet<>();

    public DungeonEventBus(DungeonForgePlugin plugin) {
        this.plugin = plugin;
        for (Class<? extends Event> type : EVENT_TYPES) {
            fired.put(type.getSimpleName(), new AtomicInteger());
        }
    }

    // ------------------------------------------------------------------
    //  Firing
    // ------------------------------------------------------------------

    /** @return false when a listener cancelled the run */
    public boolean fireStart(DungeonInfo dungeon, Player leader) {
        DungeonStartEvent event = new DungeonStartEvent(dungeon, leader);
        fire(event);
        return !event.isCancelled();
    }

    public void firePlayerEnter(DungeonInfo dungeon, Player player) {
        fire(new DungeonPlayerEnterEvent(dungeon, player));
    }

    public void firePlayerLeave(DungeonInfo dungeon, Player player) {
        fire(new DungeonPlayerLeaveEvent(dungeon, player));
    }

    public void firePlayerDeath(DungeonInfo dungeon, Player player, PlayerDeathEvent source, DungeonRoomInfo room) {
        fire(new DungeonPlayerDeathEvent(dungeon, player, source, room));
    }

    public void fireRoomEnter(DungeonInfo dungeon, Player player, DungeonRoomInfo room, boolean firstVisit) {
        fire(new DungeonRoomEnterEvent(dungeon, player, room, firstVisit));
    }

    public void fireRoomCleared(DungeonInfo dungeon, DungeonRoomInfo room, List<UUID> playersInside) {
        fire(new DungeonRoomClearedEvent(dungeon, room, playersInside));
    }

    public void fireMobSpawn(DungeonInfo dungeon, LivingEntity entity, DungeonMobInfo mob) {
        fire(new DungeonMobSpawnEvent(dungeon, entity, mob));
    }

    public void fireMobDeath(DungeonInfo dungeon, LivingEntity entity, EntityDeathEvent source,
                             DungeonMobInfo mob, DungeonRoomInfo room) {
        fire(new DungeonMobDeathEvent(dungeon, entity, source, mob, room));
        if (mob.boss()) {
            fire(new DungeonBossDeathEvent(dungeon, entity, source, mob.theme()));
        }
    }

    public void fireBossSummon(DungeonInfo dungeon, LivingEntity boss, String theme, int durationTicks) {
        fire(new DungeonBossSummonEvent(dungeon, boss, theme, durationTicks));
    }

    public void fireKeyObtained(DungeonInfo dungeon, String guardianRoomId) {
        fire(new DungeonKeyObtainedEvent(dungeon, guardianRoomId));
    }

    /** @return false when a listener vetoed the unlock */
    public boolean fireSkillNodeUnlock(Player player, String classId, String nodeId, int newLevel,
                                       int cost, int pointsBefore) {
        DungeonSkillNodeUnlockEvent event =
                new DungeonSkillNodeUnlockEvent(player, classId, nodeId, newLevel, cost, pointsBefore);
        fire(event);
        return !event.isCancelled();
    }

    public void fireSkillClassChange(Player player, String previousClassId, String classId) {
        fire(new DungeonSkillClassChangeEvent(player, previousClassId, classId));
    }

    /**
     * Fired after the node is stored, so a listener that re-reads the API sees
     * it. The cancellable {@link DungeonSkillNodeUnlockEvent} cannot serve that
     * purpose: it fires before the write and may still be vetoed.
     */
    public void fireSkillNodesGained(Player player, String classId, java.util.Set<String> nodes,
                                     int paid, DungeonSkillNodesGainedEvent.Source source) {
        fire(new DungeonSkillNodesGainedEvent(player, classId, nodes, paid, source));
    }

    public void fireSkillNodesRevoked(Player player, String classId, java.util.Set<String> nodes, int refunded) {
        fire(new DungeonSkillNodesRevokedEvent(player, classId, nodes, refunded));
    }

    public void fireSkillPointsChange(Player player, int previousPoints, int points,
                                      DungeonSkillPointsChangeEvent.Reason reason) {
        fire(new DungeonSkillPointsChangeEvent(player, previousPoints, points, reason));
    }

    public void fireDoorOpened(DungeonInfo dungeon, boolean forced) {
        fire(new DungeonDoorOpenedEvent(dungeon, forced));
    }

    /**
     * Ends a dungeon. Also fires the older {@link DungeonCompletedEvent} on a
     * completion, which stays for plugins written against the first API.
     */
    public void fireEnd(DungeonInfo dungeon, DungeonEndReason reason) {
        // Completion ends a dungeon, and deleting its world would end it
        // again a few seconds later. Exactly one wins.
        if (!ended.add(dungeon.id())) {
            return;
        }
        fire(new DungeonEndEvent(dungeon, reason));
        if (reason == DungeonEndReason.COMPLETED) {
            fire(new DungeonCompletedEvent(dungeon.difficulty(), Set.copyOf(dungeon.partyMembers()),
                    dungeon.runDuration(), dungeon.mobsKilled()));
        }
    }

    private void fire(Event event) {
        AtomicInteger counter = fired.get(event.getClass().getSimpleName());
        if (counter != null) {
            counter.incrementAndGet();
        }
        Bukkit.getPluginManager().callEvent(event);
    }

    // ------------------------------------------------------------------
    //  Diagnostics
    // ------------------------------------------------------------------

    /**
     * One line per event type: how often it has fired this session and which
     * plugins are listening for it.
     *
     * <p>Listener counts are the half that settles arguments between two
     * plugins. A listener count of zero for an event a plugin claims to handle
     * means its handler never reached Bukkit - usually because it shaded a copy
     * of these classes, so it registered against a different class than the one
     * fired here, or because it loaded before DungeonForge.</p>
     */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        for (Class<? extends Event> type : EVENT_TYPES) {
            int count = fired.getOrDefault(type.getSimpleName(), new AtomicInteger()).get();
            lines.add(type.getSimpleName() + ": fired " + count + "x, listeners " + listeners(type));
        }
        return lines;
    }

    /** The plugins registered for one event type, as {@code Plugin(count)}. */
    public String listeners(Class<? extends Event> type) {
        try {
            HandlerList handlers = (HandlerList) type.getMethod("getHandlerList").invoke(null);
            Map<String, Integer> byPlugin = new LinkedHashMap<>();
            for (RegisteredListener listener : handlers.getRegisteredListeners()) {
                byPlugin.merge(listener.getPlugin().getName(), 1, Integer::sum);
            }
            if (byPlugin.isEmpty()) {
                return "none";
            }
            List<String> parts = new ArrayList<>();
            byPlugin.forEach((name, count) -> parts.add(name + "(" + count + ")"));
            return String.join(", ", parts);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return "unavailable: " + exception.getMessage();
        }
    }

    public List<Class<? extends Event>> eventTypes() {
        return EVENT_TYPES;
    }

    /**
     * Fires one of every event at a real player and reports where each one
     * went. A line reading "0 listeners" for an event another plugin claims to
     * handle points at that plugin, not at this one.
     */
    public List<String> fireAll(Player player) {
        DungeonInfo dungeon = plugin.rooms().dungeon(player.getWorld())
                .map(plugin.snapshots()::of)
                .orElseGet(() -> plugin.snapshots().pending("api-test", 1, 0L, List.of(player.getUniqueId())));
        DungeonRoomInfo room = plugin.rooms().room(player).map(plugin.snapshots()::of)
                .orElseGet(() -> new DungeonRoomInfo("api-test-room", DungeonRoomType.NORMAL, 0));
        DungeonMobInfo mob = new DungeonMobInfo(1, dungeon.difficulty(), "swarm", "crypt", false);

        List<String> report = new ArrayList<>();
        report.add("test dungeon: " + dungeon.id() + " (" + dungeon.state() + ")");
        report.add(report(new DungeonStartEvent(dungeon, player)));
        report.add(report(new DungeonPlayerEnterEvent(dungeon, player)));
        report.add(report(new DungeonRoomEnterEvent(dungeon, player, room, true)));
        report.add(report(new DungeonMobSpawnEvent(dungeon, player, mob)));
        report.add(report(new DungeonMobDeathEvent(dungeon, player, null, mob, room)));
        report.add(report(new DungeonRoomClearedEvent(dungeon, room, List.of(player.getUniqueId()))));
        report.add(report(new DungeonKeyObtainedEvent(dungeon, "api-test-room")));
        report.add(report(new DungeonDoorOpenedEvent(dungeon, false)));
        report.add(report(new DungeonSkillNodeUnlockEvent(player, "api-test-class", "api-test-node", 1, 1, 1)));
        report.add(report(new DungeonSkillNodesGainedEvent(player, "api-test-class", java.util.Set.of("api-test-node"),
                1, DungeonSkillNodesGainedEvent.Source.PURCHASED)));
        report.add(report(new DungeonSkillNodesRevokedEvent(player, "api-test-class", java.util.Set.of("api-test-node"), 1)));
        report.add(report(new DungeonSkillClassChangeEvent(player, null, "api-test-class")));
        report.add(report(new DungeonSkillPointsChangeEvent(player, 0, 1, DungeonSkillPointsChangeEvent.Reason.GRANTED)));
        report.add(report(new DungeonBossSummonEvent(dungeon, player, "crypt", 60)));
        report.add(report(new DungeonBossDeathEvent(dungeon, player, null, "crypt")));
        report.add(report(new DungeonPlayerLeaveEvent(dungeon, player)));
        report.add(report(new DungeonEndEvent(dungeon, DungeonEndReason.COMPLETED)));
        report.add(report(new DungeonCompletedEvent(dungeon.difficulty(), Set.copyOf(dungeon.partyMembers()),
                dungeon.runDuration(), dungeon.mobsKilled())));
        report.add("player death is not simulated: it needs a real Bukkit death event");
        return report;
    }

    /**
     * Fires one test event without counting it, since a test firing is not a
     * dungeon actually doing something.
     */
    private String report(Event event) {
        String type = event.getClass().getSimpleName();
        String listeners = listeners(cast(event.getClass()));
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException exception) {
            return type + ": a listener threw " + exception;
        }
        String cancelled = event instanceof org.bukkit.event.Cancellable cancellable && cancellable.isCancelled()
                ? " - CANCELLED by a listener" : "";
        return type + ": delivered to " + listeners + cancelled;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> cast(Class<?> type) {
        return (Class<? extends Event>) type;
    }

    /** What the query side answers for one player, right now. */
    public List<String> queryDump(DungeonForgeApi api, Player player) {
        List<String> lines = new ArrayList<>();
        lines.add("apiVersion=" + api.getApiVersion() + " (compiled against " + DungeonForgeApi.API_VERSION + ")");
        lines.add("isInDungeon=" + api.isInDungeon(player));
        lines.add("getDungeon=" + api.getDungeon(player).map(Object::toString).orElse("empty"));
        lines.add("getDifficulty=" + api.getDifficulty(player));
        lines.add("getCurrentRoom=" + api.getCurrentRoom(player).map(Object::toString).orElse("empty"));
        lines.add("getPartyMembers=" + api.getPartyMembers(player).size() + " member(s)");
        lines.add("getActiveDungeons=" + api.getActiveDungeons().size());
        api.getDungeon(player).ifPresent(dungeon -> {
            lines.add("getRooms=" + api.getRooms(dungeon.id()).size());
            lines.add("progress=" + Math.round(dungeon.progress() * 100.0) + "% ("
                    + dungeon.roomsCleared() + "/" + dungeon.roomsTotal() + " rooms)");
        });
        lines.add("getSkillClasses=" + String.join(",", api.getSkillClasses()));
        lines.add("getActiveClass=" + api.getActiveClass(player).orElse("none"));
        lines.add("getSkillPoints=" + api.getSkillPoints(player)
                + " (spent " + api.getSpentSkillPoints(player) + ")");
        java.util.Map<String, Integer> unlocked = api.getUnlockedSkillNodes(player);
        lines.add("getUnlockedSkillNodes=" + unlocked.size() + (unlocked.isEmpty() ? ""
                : " " + unlocked.keySet().stream().sorted().reduce((left, right) -> left + "," + right).orElse("")));
        return lines;
    }

    /** Total listeners registered across every API event, by any plugin but this one. */
    public int foreignListenerCount() {
        int total = 0;
        for (Class<? extends Event> type : EVENT_TYPES) {
            try {
                HandlerList handlers = (HandlerList) type.getMethod("getHandlerList").invoke(null);
                for (RegisteredListener listener : handlers.getRegisteredListeners()) {
                    if (!listener.getPlugin().equals(plugin)) {
                        total++;
                    }
                }
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                // Reported in detail by status(); the count simply skips it.
            }
        }
        return total;
    }
}
