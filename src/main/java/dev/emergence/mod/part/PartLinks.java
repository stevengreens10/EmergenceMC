package dev.emergence.mod.part;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.block.AssemblyBlockEntity;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * How placed parts meet their neighbours, across block boundaries: shared by the renderer (what to draw joined) and
 * the block shapes (hitboxes follow the drawn geometry), so it runs on both sides.
 */
public final class PartLinks {
    private PartLinks() {}

    public static boolean isWire(PlacedPart q) {
        return q.kind == PartKind.WIRE || q.kind == PartKind.INSULATED_WIRE;
    }

    /** The assembly block at pos with be's resolution (its grid continues be's), or null. */
    static AssemblyBlockEntity other(AssemblyBlockEntity be, BlockPos pos) {
        if (be.getLevel() == null) return null;
        return be.getLevel().getBlockEntity(pos) instanceof AssemblyBlockEntity o && o.resolution() == be.resolution() ? o : null;
    }

    /** Parts occupying the cell just beyond face d of part p (possibly in the neighbouring block). */
    public static List<PlacedPart> across(AssemblyBlockEntity be, PlacedPart p, Dir d) {
        int res = be.resolution();
        int a = d.axis();
        int[] lo = {p.x0, p.y0, p.z0}, hi = {p.x1, p.y1, p.z1};
        int[] cell = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        cell[a] = d.sign() > 0 ? hi[a] + 1 : lo[a] - 1;
        AssemblyBlockEntity target = be;
        if (cell[a] < 0 || cell[a] >= res) {
            BlockPos np = be.getBlockPos().relative(net.minecraft.core.Direction.values()[d.ordinal()]);
            AssemblyBlockEntity o = other(be, np);
            if (o == null) {
                // a world block (or another assembly) beyond: report a non-fluid occupant if solid
                return be.getLevel() == null || be.getLevel().getBlockState(np).isAir() ? List.of() : List.of(SOLID);
            }
            target = o;
            cell[a] = Math.floorMod(cell[a], res);
        }
        List<PlacedPart> out = new java.util.ArrayList<>(2);
        for (PlacedPart q : target.parts()) if (q.contains(cell[0], cell[1], cell[2])) out.add(q);
        return out;
    }

    /** Parts in cell (x, y, z) of be's grid, which may lie in a neighbouring assembly block. */
    public static List<PlacedPart> partsAt(AssemblyBlockEntity be, int x, int y, int z) {
        int res = be.resolution();
        int bx = Math.floorDiv(x, res), by = Math.floorDiv(y, res), bz = Math.floorDiv(z, res);
        AssemblyBlockEntity t = be;
        if (bx != 0 || by != 0 || bz != 0) {
            AssemblyBlockEntity o = other(be, be.getBlockPos().offset(bx, by, bz));
            if (o == null) return List.of();
            t = o;
        }
        int cx = Math.floorMod(x, res), cy = Math.floorMod(y, res), cz = Math.floorMod(z, res);
        List<PlacedPart> out = new java.util.ArrayList<>(2);
        for (PlacedPart q : t.parts()) if (q.contains(cx, cy, cz)) out.add(q);
        return out;
    }

    /** How many cells of p's face d have a part (not a wire) right beyond them. */
    public static int faceCover(AssemblyBlockEntity be, PlacedPart p, Dir d) {
        int a = d.axis(), n = 0;
        int[] lo = {p.x0, p.y0, p.z0}, hi = {p.x1, p.y1, p.z1};
        int[] l = lo.clone(), h = hi.clone();
        l[a] = h[a] = d.sign() > 0 ? hi[a] + 1 : lo[a] - 1;
        for (int x = l[0]; x <= h[0]; x++)
            for (int y = l[1]; y <= h[1]; y++)
                for (int z = l[2]; z <= h[2]; z++)
                    for (PlacedPart q : partsAt(be, x, y, z)) {
                        if (q != p && !isWire(q)) {
                            n++;
                            break;
                        }
                    }
        return n;
    }

    /** Placeholder occupant for a world block beyond a face. */
    static final PlacedPart SOLID = new PlacedPart(0, PartKind.BLOCK, dev.emergence.mod.part.PartSpec.of("granite", 1, 1),
            0, 0, 0, 0, 0, 0, Dir.UP, false);

    /** The part a wire (or leaded part) p meets through face d — the next cell's wire, lead or conductor — or null. */
    public static PlacedPart neighbour(AssemblyBlockEntity be, PlacedPart p, Dir d) {
        int res = be.resolution();
        int a = d.axis();
        int[] lo = {p.x0, p.y0, p.z0}, hi = {p.x1, p.y1, p.z1};
        int[] cell = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        cell[a] = d.sign() > 0 ? hi[a] + 1 : lo[a] - 1;
        AssemblyBlockEntity target = be;
        if (cell[a] < 0 || cell[a] >= res) {
            BlockPos np = be.getBlockPos().relative(net.minecraft.core.Direction.values()[d.ordinal()]);
            AssemblyBlockEntity o = other(be, np);
            if (o == null) {
                // a miniature insulated wire on its block's face against a full-size ribbon: joined to its conductor
                if (p.kind == PartKind.INSULATED_WIRE && be.getLevel() != null && be.getLevel().getBlockEntity(np) instanceof AssemblyBlockEntity big
                        && big.resolution() == AssemblyBlockEntity.BLOCK_SCALE && res == AssemblyBlockEntity.MINIATURE)
                    for (PlacedPart q : big.parts()) if (q.kind == PartKind.RIBBON) return q;
                return null;
            }
            target = o;
            cell[a] = Math.floorMod(cell[a], res);
        }
        for (PlacedPart q : target.parts()) {
            if (!q.contains(cell[0], cell[1], cell[2])) continue;
            // an insulated wire against a ribbon joins the conductor of its colour (and a ribbon its wires)
            if (p.kind == PartKind.INSULATED_WIRE && q.kind == PartKind.RIBBON || p.kind == PartKind.RIBBON && q.kind == PartKind.INSULATED_WIRE) return q;
            if (p.kind == PartKind.INSULATED_WIRE && q.kind == PartKind.INSULATED_WIRE
                    && Math.round(p.spec.params().getOrDefault("color", 0.0)) != Math.round(q.spec.params().getOrDefault("color", 0.0))) continue;
            if (!isWire(p) && !isWire(q)) continue; // two components side by side: no lead between them
            // a component joins a wire only through a face where it has a terminal (as the compiler wires it)
            if (!isWire(p) && p.kind.leaded && !hasPort(be, p, d)) continue;
            if (!isWire(q) && q.kind.leaded && !hasPort(target, q, d.opposite())) continue;
            if (isWire(q) || q.kind.leaded || q.kind == PartKind.ELECTROLYTE) return q;
            if (p.kind == PartKind.WIRE && q.spec.resolveMaterial().isConductor()) return q;
        }
        return null;
    }

    /** True if leaded part p has an electrical terminal on its face d (its middle cell on that side). */
    public static boolean hasPort(AssemblyBlockEntity be, PlacedPart p, Dir d) {
        dev.emergence.core.assembly.Part core = p.toCore(be.getBlockPos(), be.resolution());
        int a = d.axis();
        int[] lo = {core.minX(), core.minY(), core.minZ()}, hi = {core.maxX(), core.maxY(), core.maxZ()};
        int[] cell = {lo[0] + (hi[0] - lo[0] + 1) / 2, lo[1] + (hi[1] - lo[1] + 1) / 2, lo[2] + (hi[2] - lo[2] + 1) / 2};
        cell[a] = d.sign() > 0 ? hi[a] : lo[a];
        return dev.emergence.core.assembly.AssemblyCompiler.hasElectricalPort(core, cell[0], cell[1], cell[2], d);
    }

}
