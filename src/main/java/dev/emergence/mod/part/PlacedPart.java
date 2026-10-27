package dev.emergence.mod.part;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.Part;
import dev.emergence.core.assembly.PartKind;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.phys.AABB;

/**
 * A part placed inside an assembly block: local cell box (0..15 on each axis), orientation, spec and
 * a world-unique id. Switch state is the only mutable operational state.
 */
public final class PlacedPart {
    public final long id;
    public final PartKind kind;
    public final PartSpec spec;
    public final int x0, y0, z0, x1, y1, z1;
    public final Dir axis;
    public boolean closed;

    public PlacedPart(long id, PartKind kind, PartSpec spec, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, boolean closed) {
        this.id = id;
        this.kind = kind;
        this.spec = spec;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.axis = axis;
        this.closed = closed;
    }

    public boolean contains(int x, int y, int z) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }

    /** Collision/selection box in block-local coordinates (full cells) for a block of the given resolution. */
    public AABB localBox(int resolution) {
        double s = 1.0 / resolution;
        return new AABB(x0 * s, y0 * s, z0 * s, (x1 + 1) * s, (y1 + 1) * s, (z1 + 1) * s);
    }

    /**
     * Converts to a core part in global cell coordinates for the block at {@code pos}. With resolution 16
     * (miniature) a block holds 16³ cells; with resolution 1 (block scale) the block is one cell. Either
     * way a cell is 1/16 m physically, so block-scale builds are 16× magnified scale models.
     */
    /** As {@link #toCore(BlockPos, int)}, moved by {@code shift} cells (a joined island's full-size parts, see the manager). */
    public Part toCore(BlockPos pos, int resolution, int[] shift) {
        Part p = toCore(pos, resolution);
        if (shift == null) return p;
        Part q = new Part(id, kind, p.material(), p.minX() + shift[0], p.minY() + shift[1], p.minZ() + shift[2],
                p.maxX() + shift[0], p.maxY() + shift[1], p.maxZ() + shift[2], axis, spec.params(), spec.text().isEmpty() ? null : spec.text());
        q.setSwitchClosed(closed);
        return q;
    }

    public Part toCore(BlockPos pos, int resolution) {
        int ox = pos.getX() * resolution, oy = pos.getY() * resolution, oz = pos.getZ() * resolution;
        Part p = new Part(id, kind, spec.resolveMaterial(), ox + x0, oy + y0, oz + z0, ox + x1, oy + y1, oz + z1, axis, spec.params(),
                spec.text().isEmpty() ? null : spec.text());
        p.setSwitchClosed(closed);
        return p;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("id", id);
        t.putString("kind", kind.name());
        t.put("spec", PartSpec.CODEC.encodeStart(NbtOps.INSTANCE, spec).getOrThrow());
        t.putIntArray("box", new int[]{x0, y0, z0, x1, y1, z1});
        t.putByte("axis", (byte) axis.ordinal());
        t.putBoolean("closed", closed);
        return t;
    }

    public static PlacedPart load(CompoundTag t) {
        int[] b = t.getIntArray("box");
        PartSpec spec = PartSpec.CODEC.parse(NbtOps.INSTANCE, t.get("spec")).result().orElse(PartSpec.of("steel_1045", 1, 1));
        PartKind kind;
        try {
            kind = PartKind.valueOf(t.getString("kind"));
        } catch (IllegalArgumentException e) {
            kind = PartKind.BLOCK;
        }
        return new PlacedPart(t.getLong("id"), kind, spec, b[0], b[1], b[2], b[3], b[4], b[5],
                Dir.values()[t.getByte("axis")], t.getBoolean("closed"));
    }
}
