package dev.emergence.core.assembly;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a full-size part meets a miniature block across one of its faces, what joins them (Assembly.Link). The same
 * rules for the game's machines joined across scales and for the core's test assemblies of such exhibits.
 * <ul>
 *   <li>Heat: each miniature part with a cell on the face to the full-size part, the contact one cell's face shared
 *       among them (a full-size part is one 6.25 cm cell, however many miniature parts touch it).</li>
 *   <li>A shaft: a full-size shaft running towards the face to a miniature shaft running to it — they turn together.</li>
 *   <li>A pipe: a full-size pipe running towards the face (or a tank) to a miniature pipe running to it (or a tank on
 *       it) — a passage, neither end venting.</li>
 *   <li>Structure: structural parts touching across the face are one rigid piece.</li>
 *   <li>Gears side by side across the face, on parallel axes: they mesh.</li>
 * </ul>
 */
public final class CrossScale {
    private CrossScale() {
    }

    /**
     * Adds the links between a full-size part and a miniature block's parts. {@code toMini}: the direction from the
     * full-size part to the block; {@code fullCell}: the full-size part's cell in the assembly's grid; {@code origin}:
     * the block's first cell in that grid; {@code res}: its cells a side; {@code mini}: the parts in the block.
     */
    public static void link(Assembly asm, Part full, int[] fullCell, Dir toMini, List<Part> mini, int[] origin, int res) {
        Dir face = toMini.opposite(); // the block's face towards the full-size part
        List<Part> onIt = new ArrayList<>();
        for (Part p : mini) if (onFace(p, face, origin, res)) onIt.add(p);
        if (onIt.isEmpty()) return;
        int ax = toMini.axis();
        for (Part p : onIt) {
            int[] cell = faceCell(p, face);
            asm.link(new Assembly.Link(Assembly.LinkKind.THERMAL, p.id(), cell, face, full.id(), fullCell, toMini, 1.0 / onIt.size()));
            boolean along = p.axis().axis() == ax && full.axis().axis() == ax;
            if (p.kind() == PartKind.SHAFT && full.kind() == PartKind.SHAFT && along) {
                asm.link(new Assembly.Link(Assembly.LinkKind.SHAFT, p.id(), cell, face, full.id(), fullCell, toMini, 1));
            }
            // structural parts against one another: one rigid piece (a gear meshes instead; a shaft's end turns)
            if (p.kind().structural && full.kind().structural && p.kind() != PartKind.GEAR && full.kind() != PartKind.GEAR
                    && p.kind() != PartKind.SHAFT && full.kind() != PartKind.SHAFT) {
                asm.link(new Assembly.Link(Assembly.LinkKind.STRUCTURAL, p.id(), cell, face, full.id(), fullCell, toMini, 1));
            }
            // gears side by side across the face (the face across both axes): they mesh
            if (p.kind() == PartKind.GEAR && full.kind() == PartKind.GEAR && p.axis().axis() == full.axis().axis() && p.axis().axis() != ax) {
                asm.link(new Assembly.Link(Assembly.LinkKind.GEAR, p.id(), cell, face, full.id(), fullCell, toMini, 1));
            }
            boolean pPipe = p.kind() == PartKind.PIPE && p.axis().axis() == ax || p.kind() == PartKind.TANK;
            boolean qPipe = full.kind() == PartKind.PIPE && full.axis().axis() == ax || full.kind() == PartKind.TANK;
            if (pPipe && qPipe) {
                asm.link(new Assembly.Link(Assembly.LinkKind.FLUID, p.id(), cell, face, full.id(), fullCell, toMini, 1));
            }
        }
    }

    /** Whether part p (in a block whose first cell is {@code origin}) has a cell on the block's face {@code f}. */
    public static boolean onFace(Part p, Dir f, int[] origin, int res) {
        int last = res - 1;
        return switch (f) {
            case EAST -> p.maxX() - origin[0] == last;
            case WEST -> p.minX() - origin[0] == 0;
            case UP -> p.maxY() - origin[1] == last;
            case DOWN -> p.minY() - origin[1] == 0;
            case SOUTH -> p.maxZ() - origin[2] == last;
            case NORTH -> p.minZ() - origin[2] == 0;
        };
    }

    /** Part p's cell on its face {@code f} (its middle one there). */
    static int[] faceCell(Part p, Dir f) {
        int x = f == Dir.EAST ? p.maxX() : f == Dir.WEST ? p.minX() : (p.minX() + p.maxX()) / 2;
        int y = f == Dir.UP ? p.maxY() : f == Dir.DOWN ? p.minY() : (p.minY() + p.maxY()) / 2;
        int z = f == Dir.SOUTH ? p.maxZ() : f == Dir.NORTH ? p.minZ() : (p.minZ() + p.maxZ()) / 2;
        return new int[]{x, y, z};
    }
}
