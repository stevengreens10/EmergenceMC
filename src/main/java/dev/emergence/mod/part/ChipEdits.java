package dev.emergence.mod.part;

import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.digital.DesignComponent;
import dev.emergence.core.digital.DigitalChip;
import dev.emergence.core.digital.MsiComponent;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.net.ChipProbePayload;
import dev.emergence.mod.net.ChipTextPayload;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server side of the chip designer and the ROM editor on placed chips: writing a design or a ROM's contents onto a
 * chip in the world, and reading a running chip's state.
 */
public final class ChipEdits {
    private ChipEdits() {
    }

    /** How far from a chip a player may edit or probe it [blocks]. */
    static final double REACH = 24;

    /**
     * Writes {@code text} onto every segment of the chip. If its pins changed, its row grows or shrinks at its far end
     * (growing into free blocks only). A design that does not compile is written as it is (the player's draft), its
     * row kept. Returns null on success, else why not.
     */
    public static String writeText(ServerPlayer player, BlockPos pos, long partId, String text) {
        ServerLevel level = player.serverLevel();
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) return "Too far from the chip.";
        if (text.length() > ChipTextPayload.MAX) return "Too long for a chip.";
        if (!(level.getBlockEntity(pos) instanceof AssemblyBlockEntity be) || be.byId(partId) == null) return "The chip is gone.";
        PlacedPart part = be.byId(partId);
        if (part.kind != PartKind.CHIP) return "That is not a chip.";
        List<ChipRows.Seg> row = ChipRows.row(level, pos, part);
        PartSpec spec = part.spec.withText(text);
        var pins = ChipRows.pins(spec);
        int want = pins.error() == null ? pins.segments() : row.size();
        boolean blockScale = be.resolution() == AssemblyBlockEntity.BLOCK_SCALE;
        if (want != row.size() && !blockScale) return "A miniature chip keeps its length: place it again to change its pins.";
        Direction along = Direction.values()[part.axis.ordinal()];
        BlockPos end = row.get(row.size() - 1).pos();
        for (int k = row.size(); k < want; k++) {
            BlockPos p = end.relative(along, k - row.size() + 1);
            if (!level.getBlockState(p).canBeReplaced() || level.getBlockEntity(p) != null) {
                return "Its pins now need " + want + " blocks; make room for " + (want - row.size()) + " more beyond its end.";
            }
        }
        for (int k = 0; k < Math.min(want, row.size()); k++) {
            ChipRows.Seg s = row.get(k);
            if (level.getBlockEntity(s.pos()) instanceof AssemblyBlockEntity sbe) sbe.replaceSpec(s.part().id, spec);
        }
        for (int k = want; k < row.size(); k++) {
            ChipRows.Seg s = row.get(k);
            if (!(level.getBlockEntity(s.pos()) instanceof AssemblyBlockEntity sbe)) continue;
            sbe.removePart(s.part().id);
            if (sbe.parts().isEmpty()) level.removeBlock(s.pos(), false);
        }
        for (int k = row.size(); k < want; k++) {
            BlockPos p = end.relative(along, k - row.size() + 1);
            level.setBlock(p, dev.emergence.mod.block.AssemblyBlock.stateAt(level, p), 3);
            if (!(level.getBlockEntity(p) instanceof AssemblyBlockEntity nbe)) break;
            nbe.setResolution(AssemblyBlockEntity.BLOCK_SCALE);
            nbe.addPart(new PlacedPart(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), PartKind.CHIP, spec, 0, 0, 0, 0, 0, 0, part.axis, false));
        }
        return null;
    }

    /** What the chip the part belongs to is doing, as the simulation has it. */
    public static ChipProbePayload probe(ServerPlayer player, BlockPos pos, long partId) {
        ServerLevel level = player.serverLevel();
        ChipProbePayload none = new ChipProbePayload(partId, false, false, 0, 0, 0, new boolean[0], new boolean[0], List.of(), new long[0], new int[0]);
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) return none;
        CompiledAssembly c = SimulationManager.get(level).compiledAt(pos);
        if (c == null || !(c.digitalChip(partId) instanceof DigitalChip chip)) {
            // the part may be a later segment: the chip is kept under its first segment's id
            if (c != null && level.getBlockEntity(pos) instanceof AssemblyBlockEntity be && be.byId(partId) != null) {
                var row = ChipRows.row(level, pos, be.byId(partId));
                if (!row.isEmpty() && c.digitalChip(row.get(0).part().id) instanceof DigitalChip head) return probe(partId, head);
            }
            return none;
        }
        return probe(partId, chip);
    }

    private static ChipProbePayload probe(long partId, DigitalChip chip) {
        var comp = chip.component();
        boolean[] in = new boolean[comp.inputs().size()], out = new boolean[comp.outputs().size()];
        for (int k = 0; k < in.length; k++) in[k] = comp.inputLevel(k);
        for (int k = 0; k < out.length; k++) out[k] = comp.output(k);
        List<String> names = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        long edges = 0, violations = 0;
        if (comp instanceof DesignComponent d) {
            var inst = d.instance();
            for (String r : inst.design().registers()) {
                names.add(r);
                values.add(inst.value(r));
                widths.add(inst.design().width(r));
            }
            edges = inst.edges();
            violations = inst.violations();
        } else if (comp instanceof MsiComponent m) {
            switch (m.type()) {
                case COUNTER4, REGISTER4 -> {
                    double[] st = m.state();
                    long q = 0;
                    for (int i = 0; i < st.length; i++) if (st[i] > 0.5) q |= 1L << i;
                    names.add("Q");
                    values.add(q);
                    widths.add(4);
                }
                case RAM16X4 -> {
                    for (int a = 0; a < 16; a++) {
                        names.add(String.format("[%X]", a));
                        values.add((long) m.word(a));
                        widths.add(4);
                    }
                }
                default -> { }
            }
        }
        long[] v = values.stream().mapToLong(Long::longValue).toArray();
        int[] w = widths.stream().mapToInt(Integer::intValue).toArray();
        return new ChipProbePayload(partId, true, chip.powered(), chip.supply(), edges, violations, in, out, names, v, w);
    }
}
