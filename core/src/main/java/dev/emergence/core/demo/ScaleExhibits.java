package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of machines across scales: a miniature block and full-size parts against it, one machine. */
public final class ScaleExhibits {
    private ScaleExhibits() {
    }

    /**
     * A full-size crank turns a miniature generator: the miniature hand generator, its shaft carried up to the top face of
     * its block, and on that face a full-size shaft in a bearing, braced by a steel pillar, with a crank on top. The two
     * shafts are coupled across the face and turn together; the miniature generator lights its load.
     */
    public static Exhibit crankAcrossScales() {
        Exhibit e = new Exhibit("acrossscales", "Across scales",
                List.of("ACROSS SCALES", "A full-size crank", "turns a miniature", "generator"),
                "Turn the big crank: its shaft stands on the top face of a block that holds the hand generator at its true size "
                        + "(6.25 cm cells), and the generator's shaft runs up to that face. Shafts end to end across a block's "
                        + "face are coupled: they turn together, the one driving the other, whatever the scale. So do pipes "
                        + "(a passage between them) and heat (through the contact), and a ribbon cable carries wires across. "
                        + "Meter the generator's load resistor: the miniature generator makes the current.");
        e.miniature = true;
        String st = "steel_1045";
        // the miniature hand generator (see miniatureGenerator), its shaft run up to the block's top face, no crank
        e.box(BLOCK, st, 9, 0, 8, 9, 2, 8, Dir.UP);
        e.box(BLOCK, st, 7, 0, 8, 7, 2, 8, Dir.UP);
        e.name("shaft", e.box(SHAFT, st, 8, 2, 8, 8, 15, 8, Dir.UP, "diameter", 0.004));
        e.add(BEARING, st, 8, 2, 8, Dir.UP);
        e.add(BEARING, st, 8, 8, 8, Dir.UP);
        e.box(BLOCK, st, 9, 8, 7, 11, 8, 9, Dir.UP);
        e.box(BLOCK, st, 9, 8, 10, 11, 8, 12, Dir.UP);
        e.box(BLOCK, st, 5, 8, 7, 7, 8, 9, Dir.UP);
        e.box(BLOCK, st, 5, 8, 10, 7, 8, 12, Dir.UP);
        e.box(BLOCK, st, 11, 0, 12, 11, 7, 12, Dir.UP);
        e.box(BLOCK, st, 5, 0, 12, 5, 7, 12, Dir.UP);
        e.box(BLOCK, "aluminium_6061", 6, 4, 6, 10, 4, 10, Dir.UP, "fill", 0.25);
        e.add(MAGNET, "ndfeb_n42", 10, 5, 8, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 6, 5, 8, Dir.DOWN);
        e.box(COIL, "copper", 10, 6, 8, 10, 7, 8, Dir.UP, "turns", 300, "wire_diameter", 0.1e-3);
        e.box(COIL, "copper", 6, 6, 8, 6, 7, 8, Dir.UP, "turns", 300, "wire_diameter", 0.1e-3);
        int[][] path = {{11, 6, 8}, {11, 6, 9}, {11, 6, 10}, {10, 6, 10}, {9, 6, 10}, {7, 6, 10}, {6, 6, 10}, {5, 6, 10}, {5, 6, 9}, {5, 6, 8}};
        for (int[] c : path) e.add(INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP, "diameter", 0.3e-3);
        e.name("load", e.add(RESISTOR, "alumina", 8, 6, 10, Dir.EAST, "resistance", 20));
        // full size, on the block's top face: a shaft in a bearing braced by a steel pillar, and the crank on top
        e.namedBlock.put("bigshaft", e.blockPart(SHAFT, st, 0, 1, 0, Dir.UP, "diameter", 0.015));
        e.blockPart(BEARING, st, 0, 1, 0, Dir.UP);
        e.namedBlock.put("crank", e.blockPart(CRANK, st, 0, 2, 0, Dir.UP, "radius", 0.12));
        e.blockPart(BLOCK, st, 1, 0, 0, Dir.UP);
        e.blockPart(BLOCK, st, 1, 1, 0, Dir.UP);
        return e.view(8.5, 2.6, 11.5, 22);
    }
}
