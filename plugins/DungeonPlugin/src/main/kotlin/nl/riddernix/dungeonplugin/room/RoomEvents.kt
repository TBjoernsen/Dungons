package nl.riddernix.dungeonplugin.room

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** Fired after periodic room detection finds a player entering a dungeon room. */
class DungeonRoomEnterEvent(
    val player: Player,
    val dungeon: DungeonInstance,
    val room: DungeonRoom
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired after periodic room detection finds a player leaving a dungeon room. */
class DungeonRoomLeaveEvent(
    val player: Player,
    val dungeon: DungeonInstance,
    val room: DungeonRoom
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired when periodic detection finds a player entering a registered corridor. */
class DungeonCorridorEnterEvent(
    val player: Player,
    val dungeon: DungeonInstance,
    val corridor: DungeonCorridor,
    /** Null only if the player arrived in the corridor by an unusual route such as teleportation. */
    val comingFrom: DungeonRoom?
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
