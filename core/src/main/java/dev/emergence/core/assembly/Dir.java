package dev.emergence.core.assembly;

import dev.emergence.core.math.Vec3;

/** The six axis directions of the construction grid (ordinal order matches Minecraft's Direction). */
public enum Dir {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    public final int dx, dy, dz;

    Dir(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public Dir opposite() {
        return values()[ordinal() ^ 1];
    }

    /** 0 = x, 1 = y, 2 = z. */
    public int axis() {
        return dx != 0 ? 0 : dy != 0 ? 1 : 2;
    }

    public int sign() {
        return dx + dy + dz;
    }

    public Vec3 vec() {
        return new Vec3(dx, dy, dz);
    }

    public static Dir fromAxis(int axis, boolean positive) {
        return switch (axis) {
            case 0 -> positive ? EAST : WEST;
            case 1 -> positive ? UP : DOWN;
            default -> positive ? SOUTH : NORTH;
        };
    }
}
