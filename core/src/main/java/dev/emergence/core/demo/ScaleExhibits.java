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

    /**
     * Air and heat across scales: a full-size tank at 3 bar, its pipe against the east face of a miniature block, where
     * a miniature pipe from a small tank at 1 bar meets it; and a full-size copper block at 450 K resting on the block's
     * top face, over a miniature copper cell. The tanks come to one pressure; the cell warms.
     */
    public static Exhibit pipesAndHeatAcrossScales() {
        Exhibit e = new Exhibit("acrossscales2", "Pipes and heat across scales",
                List.of("ACROSS SCALES", "Air and heat pass", "between full size", "and miniature"),
                "The big tank holds air at 3 bar; its pipe ends against the side of a block that holds, at true size "
                        + "(6.25 cm cells), a small tank at 1 bar and a pipe run to that same face. Pipes meeting across a "
                        + "block's face are joined, whatever the scale: air flows until both tanks are at one pressure "
                        + "(about 1.7 bar: the small side has its pipe to fill too). On top, a copper block that started at 450 K rests on the block's top face, "
                        + "over a copper cell standing on a nylon post inside it: heat crosses the contact, and the two "
                        + "coppers come to one temperature, then cool together. Meter the small tank and the cell.");
        e.miniature = true;
        String st = "steel_1045";
        // air: the small tank on the block's floor, its pipe run east to the face
        e.name("minitank", e.add(TANK, st, 9, 0, 8, Dir.UP, "pressure", 1e5));
        // (a pipe a cell at a time, as the tweezers lay it)
        for (int x = 10; x <= 15; x++) {
            int pp = e.add(PIPE, "copper", x, 0, 8, Dir.EAST);
            if (x == 15) e.name("minipipe", pp);
        }
        // heat: a copper cell just under the top face, on a nylon post (nylon: it keeps the heat)
        e.box(BLOCK, "nylon_66", 8, 0, 4, 8, 14, 4, Dir.UP);
        e.name("cell", e.add(BLOCK, "copper", 8, 15, 4, Dir.UP));
        // full size: the big tank's pipe against the east face
        e.namedBlock.put("bigpipe", e.blockPart(PIPE, "copper", 1, 0, 0, Dir.EAST));
        e.namedBlock.put("bigtank", e.blockPart(TANK, st, 2, 0, 0, Dir.UP, "pressure", 3e5));
        // on the top face a copper block at 450 K, held by a nylon pillar to the west
        int hot = e.blockPart(BLOCK, "copper", 0, 1, 0, Dir.UP);
        e.namedBlock.put("hot", hot);
        e.blockInitial.put(hot, new Exhibit.Initial(450, 0, 0, 0));
        e.blockPart(BLOCK, "nylon_66", -1, 0, 0, Dir.UP);
        e.blockPart(BLOCK, "nylon_66", -1, 1, 0, Dir.UP);
        return e.view(8.5, 2.2, 12.5, 22);
    }
    /**
     * Gears and structure across scales: a full-size crank turns a 32-tooth gear on a shaft one block up; beside it a
     * miniature block stands on a full-size block (which holds it up: structural parts touching across a face are one
     * piece), and in it a 16-tooth gear of the same tooth size, against the face, meshes with the big one and turns a
     * miniature shaft with a disc on it, twice as fast the other way.
     */
    public static Exhibit gearsAcrossScales() {
        Exhibit e = new Exhibit("acrossscales3", "Gears across scales",
                List.of("ACROSS SCALES", "A full-size gear", "drives a miniature", "one, 2 : 1"),
                "Turn the crank: its 32-tooth gear meshes, across the face of the block beside it, with a 16-tooth gear "
                        + "inside that block, at its true size (6.25 cm cells): gears side by side across a block's face mesh, "
                        + "whatever the scale, if their teeth are the same size. The small gear turns its shaft and the "
                        + "disc on it twice as fast, the other way. The miniature block stands on a full-size block: "
                        + "structural parts touching across a face are one piece, so the full-size block holds the "
                        + "miniature machine up (on a turning platform it would ride round with it).");
        String st = "steel_1045";
        // full size: the crank's shaft in a bearing, its post led round the gear (a block beside the gear would weld it)
        e.name("bigshaft", e.fill(SHAFT, st, 6, 1, 8, 6, 3, 8, Dir.UP, "diameter", 0.012));
        e.add(BEARING, st, 6, 2, 8, Dir.UP);
        e.fill(BLOCK, st, 5, 0, 9, 5, 2, 9, Dir.UP);
        e.add(BLOCK, st, 5, 2, 8, Dir.UP);
        e.name("biggear", e.add(GEAR, st, 6, 1, 8, Dir.UP, "teeth", 32, "pitch_radius", 2.0 / 16, "thickness", 0.01));
        e.name("crank", e.add(CRANK, st, 6, 4, 8, Dir.UP, "radius", 0.1));
        // the block the miniature one stands on
        e.name("stand", e.add(BLOCK, st, 7, 0, 8, Dir.UP));
        // the miniature: its gear against the west face on a shaft in a bearing on its floor, a disc on the shaft's top
        Exhibit m = new Exhibit("gears", "Miniature gear", List.of(), "");
        m.miniature = true;
        m.name("shaft", m.fill(SHAFT, st, 0, 0, 8, 0, 12, 8, Dir.UP, "diameter", 0.006));
        m.name("bearing", m.add(BEARING, st, 0, 0, 8, Dir.UP));
        m.name("gear", m.add(GEAR, st, 0, 8, 8, Dir.UP, "teeth", 16, "pitch_radius", 1.0 / 16, "thickness", 0.01));
        m.name("disc", m.box(BLOCK, "aluminium_6061", 0, 13, 7, 2, 13, 9, Dir.UP, "fill", 0.2));
        e.embed(7, 1, 8, m);
        return e.view(6.5, 3.5, 14, 25);
    }
}
