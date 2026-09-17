package nl.riddernix.dungeonplugin.generation

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the whole planner off-server against the bundled config.yml with
 * empty room pools, so every slot becomes a placeholder shell: the state a
 * server is in before a single new-format room has been built. If this
 * passes, a fresh install can generate every difficulty end to end.
 */
class TemplateLayoutGeneratorTest {

    private val logger = Logger.getLogger("template-test")

    private fun generator(): Pair<TemplateLayoutGenerator, YamlConfiguration> {
        val file = File("src/main/resources/config.yml")
        assertTrue(file.isFile, "bundled config.yml not found at ${file.absolutePath}")
        val config = YamlConfiguration.loadConfiguration(file)
        val templates = TemplateConfig(config, logger)
        assertEquals(emptyList(), templates.report(), "the bundled template must parse cleanly")
        return TemplateLayoutGenerator(config, templates, { emptyList() }, logger) to config
    }

    @Test
    fun `every difficulty generates on an empty room folder`() {
        val (generator, _) = generator()
        for (difficulty in 1..9) {
            val plan = generator.generate(difficulty, 12345L + difficulty)
            assertTrue(plan.placements.isEmpty(), "no real rooms exist, so no placements")
            assertEquals(plan.layout.rooms.size, plan.placeholders.size,
                "difficulty $difficulty: every room must have a placeholder shell")
            assertNoOverlaps(plan)
            assertFlushConnections(plan)
        }
    }

    @Test
    fun `difficulty one follows the authored flow`() {
        val (generator, _) = generator()
        val plan = generator.generate(1, 42L)
        val layout = plan.layout
        // spawn, hall, combat, stairs, combat, rest, combat, great hall,
        // boss, plus the key branch's parkour and key room.
        assertEquals(11, layout.rooms.size)
        assertEquals(10, layout.tunnels.size)
        // Exactly one corridor connection: the branch to the parkour.
        val corridors = layout.tunnels.filter { it.floors.isNotEmpty() }
        assertEquals(1, corridors.size)
        // The locked door seals the great hall's entrance, keyed to the key room.
        val gate = assertNotNull(layout.keyGate)
        assertEquals(1, gate.guardianRoomIds.size)
        val lockedTunnel = layout.tunnels.first { it.id() == gate.lockedTunnelId }
        val greatHall = layout.rooms.first { it.sizeClass == "great_hall" }
        assertEquals(greatHall.id, lockedTunnel.secondRoomId)
    }

    @Test
    fun `the stairs slot moves the second floor down`() {
        val (generator, _) = generator()
        val plan = generator.generate(1, 7L)
        val rooms = plan.layout.rooms
        // Slots 1..9 in walking order carry ids "1".."9".
        val beforeStairs = rooms.first { it.id == "3" }
        val stairs = rooms.first { it.id == "4" }
        val afterStairs = rooms.first { it.id == "5" }
        val floorBefore = beforeStairs.floorY
        val floorAfter = afterStairs.floorY
        assertNotNull(floorBefore)
        assertNotNull(floorAfter)
        assertTrue(floorAfter < floorBefore,
            "the room after the stairs must sit lower (before=$floorBefore after=$floorAfter)")
        // The stairs shell itself is entered on the upper floor.
        assertEquals(floorBefore, stairs.floorY)
    }

    @Test
    fun `higher difficulties stretch combat rooms keys and minibosses`() {
        val (generator, config) = generator()
        val plan = generator.generate(9, 99L)
        val combatRooms = config.getInt("generation.template.difficulties.9.combat-rooms")
        val keys = config.getInt("generation.template.difficulties.9.keys")
        val minibosses = config.getInt("generation.template.difficulties.9.minibosses")
        val gate = assertNotNull(plan.layout.keyGate)
        assertEquals(keys, gate.guardianRoomIds.size)
        assertEquals(minibosses, plan.layout.rooms.count { it.miniboss })
        assertEquals(combatRooms, plan.placeholders.values.count { it.role == "combat" })
        assertNoOverlaps(plan)
    }

    @Test
    fun `the same seed plans the same dungeon`() {
        val (generator, _) = generator()
        val first = generator.generate(5, 1234L)
        val second = generator.generate(5, 1234L)
        assertEquals(first.summary, second.summary)
    }

    private fun assertNoOverlaps(plan: TemplatePlan) {
        val rooms = plan.layout.rooms
        for (first in rooms.indices) {
            for (second in first + 1 until rooms.size) {
                assertTrue(!rooms[first].bounds.intersects(rooms[second].bounds),
                    "rooms ${rooms[first].id} and ${rooms[second].id} overlap: " +
                        "${rooms[first].bounds} vs ${rooms[second].bounds}")
            }
        }
    }

    /** Every zero-length connection must be exactly two adjacent wall planes on one floor line. */
    private fun assertFlushConnections(plan: TemplatePlan) {
        for (tunnel in plan.layout.tunnels.filter { it.floors.isEmpty() }) {
            val first = tunnel.firstDoorway
            val second = tunnel.secondDoorway
            val gapX = if (first.minX == first.maxX && second.minX == second.maxX)
                Math.abs(second.minX - first.minX) else 0
            val gapZ = if (first.minZ == first.maxZ && second.minZ == second.maxZ)
                Math.abs(second.minZ - first.minZ) else 0
            assertEquals(1, maxOf(gapX, gapZ),
                "flush connection ${tunnel.id()} is not wall-against-wall: $first vs $second")
            assertEquals(first.minY, second.minY,
                "flush connection ${tunnel.id()} misaligns its passage floors")
        }
    }
}
