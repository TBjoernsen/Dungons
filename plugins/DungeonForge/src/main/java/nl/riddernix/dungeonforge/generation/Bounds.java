package nl.riddernix.dungeonforge.generation;

/** An inclusive, axis-aligned block box. */
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Bounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Bounds minimum must not exceed its maximum.");
        }
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public int centreX() {
        return minX + (sizeX() - 1) / 2;
    }

    public int centreZ() {
        return minZ + (sizeZ() - 1) / 2;
    }

    public boolean intersects(Bounds other) {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /** Returns whether a block coordinate is inside this inclusive block box. */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public Bounds expand(int amount) {
        return new Bounds(minX - amount, minY - amount, minZ - amount,
                maxX + amount, maxY + amount, maxZ + amount);
    }

    public Bounds translate(int x, int y, int z) {
        return new Bounds(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    public static Bounds union(Bounds first, Bounds second) {
        return new Bounds(
                Math.min(first.minX, second.minX), Math.min(first.minY, second.minY), Math.min(first.minZ, second.minZ),
                Math.max(first.maxX, second.maxX), Math.max(first.maxY, second.maxY), Math.max(first.maxZ, second.maxZ)
        );
    }
}
