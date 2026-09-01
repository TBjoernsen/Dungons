package nl.riddernix.dungeonplugin.generation

/** An inclusive, axis-aligned block box. */
data class Bounds(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {

    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) {
            "Bounds minimum must not exceed its maximum."
        }
    }

    fun sizeX(): Int = maxX - minX + 1

    fun sizeY(): Int = maxY - minY + 1

    fun sizeZ(): Int = maxZ - minZ + 1

    fun volume(): Long = sizeX().toLong() * sizeY() * sizeZ()

    fun centreX(): Int = minX + (sizeX() - 1) / 2

    fun centreZ(): Int = minZ + (sizeZ() - 1) / 2

    fun intersects(other: Bounds): Boolean =
        minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ

    /** Returns whether a block coordinate is inside this inclusive block box. */
    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun expand(amount: Int): Bounds = Bounds(minX - amount, minY - amount, minZ - amount,
        maxX + amount, maxY + amount, maxZ + amount)

    fun translate(x: Int, y: Int, z: Int): Bounds =
        Bounds(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z)

    companion object {
        fun union(first: Bounds, second: Bounds): Bounds = Bounds(
            minOf(first.minX, second.minX), minOf(first.minY, second.minY), minOf(first.minZ, second.minZ),
            maxOf(first.maxX, second.maxX), maxOf(first.maxY, second.maxY), maxOf(first.maxZ, second.maxZ)
        )
    }
}
