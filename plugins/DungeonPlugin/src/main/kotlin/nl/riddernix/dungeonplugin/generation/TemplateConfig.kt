package nl.riddernix.dungeonplugin.generation

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale
import java.util.logging.Logger

/**
 * The template that replaces free-form layout generation: a fixed dungeon
 * flow whose slots are read from config, stretched per difficulty by three
 * numbers - combat rooms, keys, and minibosses - without code changes.
 */
class TemplateConfig(config: FileConfiguration, private val logger: Logger) {

    /** Exterior footprint of one size class; a room file must match exactly. */
    data class SizeClass(val name: String, val x: Int, val y: Int, val z: Int) {
        fun dimensions(): String = "${x}x${y}x$z"
    }

    /** How a slot attaches to the one before it. */
    enum class Connection { DIRECT, CORRIDOR }

    /**
     * One room of the expanded template. [role] is what the room is for
     * (signs, reports); [pool] is the room-file prefix it draws from; [mobs]
     * names a mobs.room-roles recipe or null for a quiet room.
     */
    data class Slot(
        val role: String,
        val pool: String,
        val sizeClass: SizeClass,
        val mobs: String?,
        val connection: Connection,
        val stairsDown: Boolean,
        val lockedDoor: Boolean,
        val miniboss: Boolean,
        val deadEnd: Boolean
    )

    /** A key detour: its rooms leave the main path at [attachCombat] (1-based combat index). */
    data class KeyBranch(val attachCombat: Int, val rooms: List<Slot>)

    /** The full plan for one difficulty, before any placement. */
    data class Expansion(val difficulty: Int, val mainPath: List<Slot>, val branches: List<KeyBranch>)

    val sizeClasses: Map<String, SizeClass>
    private val flow: List<RawSlot>
    private val extraLink: RawSlot?
    private val extraRoom: RawSlot?
    private val extraMobs: List<String>
    private val branchRooms: List<RawSlot>
    private val branchConnection: Connection
    private val attachToCombat: Int
    private val minibossMobs: String
    private val difficulties: Map<Int, Numbers>
    private val problems = ArrayList<String>()

    private data class Numbers(val combatRooms: Int, val keys: Int, val minibosses: Int)

    private data class RawSlot(val role: String, val pool: String, val className: String, val mobs: String?,
                               val stairsDown: Boolean, val lockedDoor: Boolean, val deadEnd: Boolean)

    init {
        val classes = LinkedHashMap<String, SizeClass>()
        val classSection = config.getConfigurationSection("generation.size-classes")
        if (classSection == null) {
            problems.add("generation.size-classes is missing.")
        } else {
            for (name in classSection.getKeys(false)) {
                val key = name.lowercase(Locale.ROOT)
                classes[key] = SizeClass(key,
                    maxOf(3, config.getInt("generation.size-classes.$name.x", 3)),
                    maxOf(4, config.getInt("generation.size-classes.$name.y", 4)),
                    maxOf(3, config.getInt("generation.size-classes.$name.z", 3)))
            }
        }
        sizeClasses = classes.toMap()

        flow = readSlotList(config.getMapList("generation.template.flow"), "generation.template.flow")
        if (flow.isEmpty()) problems.add("generation.template.flow is missing or empty.")
        extraLink = readSlot(config.getConfigurationSection("generation.template.extra-combat.link"),
            "generation.template.extra-combat.link")
        extraRoom = readSlot(config.getConfigurationSection("generation.template.extra-combat.room"),
            "generation.template.extra-combat.room")
        extraMobs = config.getStringList("generation.template.extra-combat.mobs")
            .filter { it.isNotBlank() }.map { it.trim().lowercase(Locale.ROOT) }
        branchRooms = readSlotList(config.getMapList("generation.template.key-branch.rooms"),
            "generation.template.key-branch.rooms")
        branchConnection = if ("corridor".equals(
                config.getString("generation.template.key-branch.connection", "corridor"), ignoreCase = true))
            Connection.CORRIDOR else Connection.DIRECT
        attachToCombat = maxOf(1, config.getInt("generation.template.key-branch.attach-to-combat", 2))
        minibossMobs = (config.getString("generation.template.miniboss.mobs", "miniboss") ?: "miniboss")
            .trim().lowercase(Locale.ROOT)

        val numbers = HashMap<Int, Numbers>()
        for (difficulty in 1..9) {
            val base = "generation.template.difficulties.$difficulty"
            numbers[difficulty] = Numbers(
                maxOf(1, config.getInt("$base.combat-rooms", 3)),
                maxOf(0, config.getInt("$base.keys", 1)),
                maxOf(0, config.getInt("$base.minibosses", 0)))
        }
        difficulties = numbers.toMap()
    }

    /** Loud on enable and reload: a broken template is a config bug, not a seed problem. */
    fun report(): List<String> = problems.toList()

    /** The size class one pool's files must match, from every place the template uses it. */
    fun poolClasses(): Map<String, SizeClass> {
        val result = LinkedHashMap<String, SizeClass>()
        val all = ArrayList<RawSlot>()
        all.addAll(flow)
        extraLink?.let(all::add)
        extraRoom?.let(all::add)
        all.addAll(branchRooms)
        for (slot in all) {
            val sizeClass = sizeClasses[slot.className] ?: continue
            val previous = result.put(slot.pool, sizeClass)
            if (previous != null && previous.name != sizeClass.name) {
                problems.add("Pool '${slot.pool}' is used with two size classes " +
                    "(${previous.name} and ${sizeClass.name}); its files can only match one.")
            }
        }
        return result.toMap()
    }

    /** Every (role, pool, class) the template can ask for, for the coverage report. */
    fun requirements(): List<Triple<String, String, SizeClass>> {
        val result = LinkedHashMap<String, Triple<String, String, SizeClass>>()
        val all = ArrayList<RawSlot>()
        all.addAll(flow)
        extraLink?.let(all::add)
        extraRoom?.let(all::add)
        all.addAll(branchRooms)
        for (slot in all) {
            val sizeClass = sizeClasses[slot.className] ?: continue
            result["${slot.role}|${slot.pool}|${sizeClass.name}"] = Triple(slot.role, slot.pool, sizeClass)
        }
        return result.values.toList()
    }

    /**
     * Expands the template for one difficulty. The flow's combat rooms come
     * first; extra combat rooms insert before the locked-door slot, each
     * preceded by a link room. Minibosses claim the last combat rooms so the
     * escalation lands late in the run, and every key attaches its branch to
     * a different combat room, starting at the configured one.
     */
    @Throws(TemplateException::class)
    fun expand(difficulty: Int): Expansion {
        if (problems.isNotEmpty()) {
            throw TemplateException("The template configuration is invalid: " + problems.joinToString(" | "))
        }
        val numbers = difficulties.getValue(difficulty.coerceIn(1, 9))
        val flowCombat = flow.count { it.role == "combat" }
        if (flowCombat == 0) throw TemplateException("generation.template.flow contains no combat slot.")
        val combatRooms = maxOf(numbers.combatRooms, flowCombat)
        if (combatRooms != numbers.combatRooms) {
            logger.warning("Difficulty $difficulty asks for ${numbers.combatRooms} combat room(s) but the flow " +
                "already contains $flowCombat; using $flowCombat.")
        }
        val extra = combatRooms - flowCombat
        if (extra > 0 && (extraLink == null || extraRoom == null)) {
            throw TemplateException("Difficulty $difficulty needs $extra extra combat room(s) but " +
                "generation.template.extra-combat is not configured.")
        }

        val raw = ArrayList<RawSlot>()
        val lockedIndex = flow.indexOfFirst { it.lockedDoor }
        val insertAt = if (lockedIndex >= 0) lockedIndex else flow.size - 1
        for (index in flow.indices) {
            if (index == insertAt) {
                for (extraIndex in 0 until extra) {
                    raw.add(extraLink!!)
                    val mobs = if (extraMobs.isEmpty()) extraRoom!!.mobs
                        else extraMobs[extraIndex % extraMobs.size]
                    raw.add(extraRoom!!.copy(mobs = mobs))
                }
            }
            raw.add(flow[index])
        }

        // Minibosses claim combat rooms from the back, but never the first.
        val combatIndexes = raw.indices.filter { raw[it].role == "combat" }
        val minibossIndexes = HashSet<Int>()
        var remaining = minOf(numbers.minibosses, maxOf(0, combatIndexes.size - 1))
        if (remaining < numbers.minibosses) {
            logger.warning("Difficulty $difficulty asks for ${numbers.minibosses} miniboss(es) but only " +
                "$remaining combat room(s) can host one.")
        }
        for (index in combatIndexes.asReversed()) {
            if (remaining <= 0) break
            if (index == combatIndexes.first()) continue
            minibossIndexes.add(index)
            remaining--
        }

        val mainPath = raw.mapIndexed { index, slot ->
            val miniboss = index in minibossIndexes
            toSlot(slot, if (miniboss) minibossMobs else slot.mobs, Connection.DIRECT, miniboss)
        }

        if (numbers.keys > 0 && branchRooms.isEmpty()) {
            throw TemplateException("Difficulty $difficulty needs ${numbers.keys} key(s) but " +
                "generation.template.key-branch.rooms is empty.")
        }
        if (numbers.keys > combatIndexes.size) {
            throw TemplateException("Difficulty $difficulty needs ${numbers.keys} key branch(es) but only " +
                "${combatIndexes.size} combat room(s) exist to attach them to.")
        }
        val branches = ArrayList<KeyBranch>()
        val usedCombat = HashSet<Int>()
        for (key in 0 until numbers.keys) {
            var combatIndex = (attachToCombat - 1 + key) % combatIndexes.size
            while (combatIndex in usedCombat) combatIndex = (combatIndex + 1) % combatIndexes.size
            usedCombat.add(combatIndex)
            val rooms = branchRooms.mapIndexed { index, slot ->
                toSlot(slot, slot.mobs, if (index == 0) branchConnection else Connection.DIRECT, false)
            }
            branches.add(KeyBranch(combatIndex + 1, rooms))
        }
        // Deterministic order keeps seeds reproducible whatever the attach numbers.
        branches.sortBy { it.attachCombat }
        return Expansion(difficulty, mainPath, branches.toList())
    }

    private fun toSlot(raw: RawSlot, mobs: String?, connection: Connection, miniboss: Boolean): Slot {
        val sizeClass = sizeClasses[raw.className]
            ?: throw TemplateException("Slot '${raw.role}' names unknown size class '${raw.className}'; " +
                "known classes: " + sizeClasses.keys.joinToString(", "))
        return Slot(raw.role, raw.pool, sizeClass, mobs, connection, raw.stairsDown, raw.lockedDoor, miniboss,
            raw.deadEnd)
    }

    private fun readSlotList(rawList: List<Map<*, *>>, path: String): List<RawSlot> {
        val result = ArrayList<RawSlot>()
        for (index in rawList.indices) {
            val slot = readSlot(rawList[index], "$path[$index]")
            if (slot != null) result.add(slot)
        }
        return result.toList()
    }

    private fun readSlot(section: ConfigurationSection?, path: String): RawSlot? {
        if (section == null) return null
        return readSlot(section.getValues(false), path)
    }

    private fun readSlot(values: Map<*, *>, path: String): RawSlot? {
        val role = (values["role"] as? String)?.trim()?.lowercase(Locale.ROOT)
        if (role.isNullOrEmpty()) {
            problems.add("$path has no 'role'.")
            return null
        }
        val pool = ((values["pool"] as? String)?.trim()?.lowercase(Locale.ROOT))?.ifEmpty { null } ?: role
        val className = (values["class"] as? String)?.trim()?.lowercase(Locale.ROOT)
        if (className.isNullOrEmpty()) {
            problems.add("$path ('$role') has no 'class'.")
            return null
        }
        if (className !in sizeClasses) {
            problems.add("$path ('$role') names unknown size class '$className'.")
        }
        val mobs = ((values["mobs"] as? String)?.trim()?.lowercase(Locale.ROOT))?.ifEmpty { null }
        val stairs = (values["stairs"] as? String)?.trim()?.lowercase(Locale.ROOT)
        if (stairs != null && stairs != "down") {
            problems.add("$path ('$role') declares 'stairs: $stairs'; only 'down' is supported.")
        }
        return RawSlot(role, pool, className, mobs, stairs == "down",
            values["locked-door"] == true, values["dead-end"] == true)
    }

    class TemplateException(message: String) : Exception(message)
}
