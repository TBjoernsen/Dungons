package nl.riddernix.dungeonforge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.util.Optional;

/**
 * A player confirmed a class at a skill panel. Notification only.
 *
 * <p>Fires after the change is stored, so queries already answer with the new
 * class. Unlocks are kept per class: switching away and back loses nothing.
 * Also fires for the very first confirmation, with an empty previous
 * class.</p>
 */
public final class DungeonSkillClassChangeEvent extends DungeonSkillEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String previousClassId;
    private final String classId;

    public DungeonSkillClassChangeEvent(Player player, String previousClassId, String classId) {
        super(player);
        this.previousClassId = previousClassId;
        this.classId = classId;
    }

    /** Empty when this is the player's first confirmed class. */
    public Optional<String> getPreviousClassId() {
        return Optional.ofNullable(previousClassId);
    }

    public String getClassId() {
        return classId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
