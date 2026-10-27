package dev.emergence.mod.part;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.digital.ChipTypes;
import dev.emergence.mod.block.AssemblyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.List;

/**
 * A chip in the world is a row of CHIP segments (one per block at block scale, one per cell in miniature), as the
 * compiler joins them: the same function and axis, side by side along it. This finds a chip's row from any of its
 * segments, for placing, removing and editing it as one thing and for labelling its pins.
 */
public final class ChipRows {
    private ChipRows() {
    }

    /** One segment: the block it is in and the part. */
    public record Seg(BlockPos pos, PlacedPart part) {
    }

    /** The chip's function (its "function" parameter). */
    public static int function(PartSpec s) {
        return (int) Math.round(s.params().getOrDefault("function", 0.0));
    }

    /** The pins of a chip with these settings (its design or ROM image is its text). */
    public static ChipTypes.Pins pins(PartSpec s) {
        return ChipTypes.pins(function(s), s.text());
    }

    static boolean sameChip(PlacedPart a, PlacedPart b) {
        return b.kind == PartKind.CHIP && b.axis == a.axis && function(b.spec) == function(a.spec)
                && b.spec.params().getOrDefault("lead_axis", -1.0).equals(a.spec.params().getOrDefault("lead_axis", -1.0));
    }

    /** The segment next to {@code s} one cell along its axis ({@code dir} = ±1) belonging to the same chip, or null. */
    public static Seg neighbour(BlockGetter level, Seg s, int dir) {
        if (!(level.getBlockEntity(s.pos()) instanceof AssemblyBlockEntity be)) return null;
        int res = be.resolution();
        Dir ax = s.part().axis;
        int x = s.part().x0 + ax.dx * dir, y = s.part().y0 + ax.dy * dir, z = s.part().z0 + ax.dz * dir;
        BlockPos pos = s.pos();
        AssemblyBlockEntity in = be;
        if (x < 0 || y < 0 || z < 0 || x >= res || y >= res || z >= res) {
            pos = pos.offset(Math.floorDiv(x, res), Math.floorDiv(y, res), Math.floorDiv(z, res));
            x = Math.floorMod(x, res);
            y = Math.floorMod(y, res);
            z = Math.floorMod(z, res);
            if (!(level.getBlockEntity(pos) instanceof AssemblyBlockEntity o) || o.resolution() != res) return null;
            in = o;
        }
        for (PlacedPart q : in.parts()) {
            if (q.x0 == x && q.y0 == y && q.z0 == z && sameChip(s.part(), q)) return new Seg(pos, q);
        }
        return null;
    }

    /** The whole row the segment belongs to, first segment (the −axis end) first. */
    public static List<Seg> row(BlockGetter level, BlockPos pos, PlacedPart part) {
        Seg s = new Seg(pos, part);
        for (int i = 0; i < 128; i++) {
            Seg prev = neighbour(level, s, -1);
            if (prev == null) break;
            s = prev;
        }
        List<Seg> row = new ArrayList<>();
        for (Seg q = s; q != null && row.size() < 128; q = neighbour(level, q, 1)) row.add(q);
        return row;
    }

    /** Takes the whole chip the part belongs to out of the world (every segment; emptied blocks removed). */
    public static void removeWhole(net.minecraft.world.level.Level level, BlockPos pos, PlacedPart part) {
        for (Seg seg : row(level, pos, part)) {
            if (!(level.getBlockEntity(seg.pos()) instanceof AssemblyBlockEntity sbe)) continue;
            sbe.removePart(seg.part().id);
            if (sbe.parts().isEmpty()) level.removeBlock(seg.pos(), false);
        }
    }

    /** Which segment of its row the part is (0 the first). */
    public static int index(List<Seg> row, PlacedPart part) {
        for (int i = 0; i < row.size(); i++) if (row.get(i).part().id == part.id) return i;
        return -1;
    }
}
