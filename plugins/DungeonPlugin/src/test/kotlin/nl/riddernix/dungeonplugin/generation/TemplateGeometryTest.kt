package nl.riddernix.dungeonplugin.generation

import nl.riddernix.dungeonplugin.generation.TemplateGeometry.Dir
import nl.riddernix.dungeonplugin.generation.TemplateGeometry.LocalDoor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves the door-alignment rules off-server. The floor-transition case gets
 * the most attention because it is the one most likely to be off by one: a
 * stairs room's two doors sit on different floors, and everything after the
 * stairs must land exactly one drop lower.
 */
class TemplateGeometryTest {

    /** A 15x12x15 stairs link: north door walks at local y7, south door at local y1. */
    private val stairsUpper = LocalDoor(Dir.NORTH, 7, 10, 7, 3, 3, 3)
    private val stairsLower = LocalDoor(Dir.SOUTH, 7, 4, 1, 3, 3, 3)

    @Test
    fun `flush placement puts the wall planes adjacent`() {
        // A 67-wide combat room at origin 0,64,0 with its south door at
        // cross 33, walking floor y65.
        val exit = TemplateGeometry.WorldDoor(Dir.SOUTH, 66, 33, 94, 65, 3, 3, 3)
        val entry = LocalDoor(Dir.NORTH, 7, 10, 1, 3, 3, 3)
        val origin = TemplateGeometry.alignFlush(exit, entry, 15, 15, 0)
        // The new room's north wall (z = origin.z) sits one block past z=66.
        assertEquals(67, origin.z)
        // Marker cross carries over: local x7 lands on world x33.
        assertEquals(33 - 7, origin.x)
        // Vertical: the entry walks at local y1, the exit floor is y65.
        assertEquals(64, origin.y)
    }

    @Test
    fun `a stairs room hands the chain down by exactly its own drop`() {
        // Entered from the north through its upper door, floor y65.
        val fromCombat = TemplateGeometry.WorldDoor(Dir.SOUTH, 100, 40, 94, 65, 3, 3, 3)
        val stairsOrigin = TemplateGeometry.alignFlush(fromCombat, stairsUpper, 15, 15, 0)
        // Upper door walks at local 7, so the shell sits 65 - 7 = 58.
        assertEquals(58, stairsOrigin.y)

        // The lower door, resolved to world space, must walk at 65 - (7-1) = 59.
        val lowerWorld = TemplateGeometry.worldDoor(stairsLower, 15, 15, 0, stairsOrigin)
        assertEquals(59, lowerWorld.floor)
        assertEquals(Dir.SOUTH, lowerWorld.face)

        // The next room aligns to the lower door: its own floor continues at 59.
        val nextEntry = LocalDoor(Dir.NORTH, 33, 30, 1, 3, 3, 3)
        val nextOrigin = TemplateGeometry.alignFlush(lowerWorld, nextEntry, 67, 67, 0)
        assertEquals(58, nextOrigin.y)
        val nextEntryWorld = TemplateGeometry.worldDoor(nextEntry, 67, 67, 0, nextOrigin)
        // The two passages share their walking floor - the off-by-one trap.
        assertEquals(lowerWorld.floor, nextEntryWorld.floor)
        // And their wall planes are adjacent with matching cross centres.
        assertEquals(lowerWorld.wallPlane + 1, nextEntryWorld.wallPlane)
        assertEquals(lowerWorld.cross, nextEntryWorld.cross)
    }

    @Test
    fun `rotation keeps the alignment contract`() {
        // The stairs room rotated 90 degrees: its north upper door now faces
        // east, so it can hang off a west-facing exit.
        val exit = TemplateGeometry.WorldDoor(Dir.WEST, 10, 50, 80, 65, 3, 3, 3)
        val origin = TemplateGeometry.alignFlush(exit, stairsUpper, 15, 15, 90)
        val upperWorld = TemplateGeometry.worldDoor(stairsUpper, 15, 15, 90, origin)
        assertEquals(Dir.EAST, upperWorld.face)
        assertEquals(exit.wallPlane - 1, upperWorld.wallPlane)
        assertEquals(exit.cross, upperWorld.cross)
        assertEquals(exit.floor, upperWorld.floor)
        // The lower door leaves to the west after rotation, one drop down.
        val lowerWorld = TemplateGeometry.worldDoor(stairsLower, 15, 15, 90, origin)
        assertEquals(Dir.WEST, lowerWorld.face)
        assertEquals(exit.floor - 6, lowerWorld.floor)
    }

    @Test
    fun `flush neighbours touch without overlapping`() {
        val exit = TemplateGeometry.WorldDoor(Dir.SOUTH, 66, 33, 94, 65, 3, 3, 3)
        val entry = LocalDoor(Dir.NORTH, 7, 10, 1, 3, 3, 3)
        val origin = TemplateGeometry.alignFlush(exit, entry, 15, 15, 0)
        val first = Bounds(0, 64, 0, 66, 97, 66)
        val second = Bounds(origin.x, origin.y, origin.z, origin.x + 14, origin.y + 11, origin.z + 14)
        assertNull(TemplateGeometry.collides(second, listOf(first)))
        // One block closer and the overlap check must catch it.
        val tooClose = second.translate(0, 0, -1)
        assertNotNull(TemplateGeometry.collides(tooClose, listOf(first)))
    }

    @Test
    fun `a corridor gap moves the room by exactly the gap`() {
        val exit = TemplateGeometry.WorldDoor(Dir.EAST, 66, 33, 94, 65, 3, 3, 3)
        val entry = LocalDoor(Dir.WEST, 7, 10, 1, 3, 3, 3)
        val flush = TemplateGeometry.alignFlush(exit, entry, 15, 15, 0)
        val gapped = TemplateGeometry.alignAcrossGap(exit, entry, 15, 15, 0, 18)
        assertEquals(flush.x + 18, gapped.x)
        assertEquals(flush.y, gapped.y)
        assertEquals(flush.z, gapped.z)
    }

    @Test
    fun `door openings surround the marker cross on the wall plane`() {
        val door = TemplateGeometry.WorldDoor(Dir.SOUTH, 66, 33, 94, 65, 3, 3, 3)
        val opening = TemplateGeometry.doorOpening(door)
        assertEquals(Bounds(32, 65, 66, 34, 67, 66), opening)
    }
}
