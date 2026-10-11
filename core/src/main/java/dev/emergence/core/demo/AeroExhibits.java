package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of air moving past blades: lift, drag, windmills and propellers. */
public final class AeroExhibits {
    private AeroExhibits() {}

    /**
     * A wind turbine: four flat blades on a horizontal shaft facing the wind, driving a magnet disc past two coils into a
     * load. The blades' lift turns the rotor; the generator's current brakes it; momentum theory sets how much the
     * blades slow the wind passing through them.
     */
    public static Exhibit windTurbine() {
        Exhibit e = new Exhibit("windturbine", "Wind turbine",
                List.of("WIND TURBINE", "Wind lifts the blades,", "the rotor turns the", "magnets past coils"),
                "Four flat blades on a shaft facing the wind (it blows from the west, harder in rain and storms and "
                        + "higher up). Each blade is set at an angle: the wind meeting it, combined with the blade's own "
                        + "motion, arrives at a small angle of attack, and the blade's lift — mostly sideways — turns the "
                        + "rotor. Behind it, two magnets on a disc sweep past two coils: the EMF drives current through "
                        + "the load resistor, and that current's field brakes the rotor (Lenz) — the wind's work becomes "
                        + "heat in the resistor. No rotor can take more than 16/27 of the wind's power through its disc "
                        + "(the Betz limit): to take it all it would have to stop the air, and stopped air lets no more "
                        + "through. These small, untwisted flat blades manage a fraction of that. Meter the resistor and "
                        + "the shaft; the power grows as the cube of the wind speed.");
        String st = "steel_1045", al = "aluminium_6061";
        int y = 6, z = 8;
        double pitch = -10; // the blades' angle to the plane of rotation (best of −5 … −25° for these flat blades)
        // the rotor: a hub on the shaft's upwind end, four blades in the plane across the wind
        int shaft = e.fill(SHAFT, st, 2, y, z, 9, y, z, Dir.EAST, "diameter", 0.02);
        e.name("shaft", shaft);
        e.name("hub", e.add(BLOCK, al, 2, y, z, Dir.EAST, "fill", 0.5));
        // each blade's chord runs along its direction of travel (ω about +x), turned "pitch" into the wind
        e.name("blade", e.fill(BLADE, al, 2, y + 1, z, 2, y + 4, z, Dir.UP, "face", Dir.SOUTH.ordinal(), "pitch", pitch));
        e.fill(BLADE, al, 2, y - 4, z, 2, y - 1, z, Dir.DOWN, "face", Dir.NORTH.ordinal(), "pitch", pitch);
        e.fill(BLADE, al, 2, y, z + 1, 2, y, z + 4, Dir.SOUTH, "face", Dir.DOWN.ordinal(), "pitch", pitch);
        e.fill(BLADE, al, 2, y, z - 4, 2, y, z - 1, Dir.NORTH, "face", Dir.UP.ordinal(), "pitch", pitch);
        // the bearings on posts
        for (int x : new int[]{4, 7}) {
            e.add(BEARING, st, x, y, z, Dir.EAST);
            e.fill(BLOCK, st, x, 0, z, x, y - 1, z, Dir.UP);
        }
        // the generator: an aluminium arm on the shaft's end, magnets of opposite polarity on its downwind face, coils
        // just past them (wound so the two EMFs add)
        e.fill(BLOCK, al, 9, y - 2, z, 9, y + 2, z, Dir.EAST, "fill", 0.3);
        e.add(MAGNET, "ndfeb_n42", 10, y + 2, z, Dir.EAST);
        e.add(MAGNET, "ndfeb_n42", 10, y - 2, z, Dir.WEST);
        e.name("coil", e.add(COIL, "copper", 11, y + 2, z, Dir.EAST, "turns", 300, "wire_diameter", 0.8e-3));
        e.add(COIL, "copper", 11, y - 2, z, Dir.WEST, "turns", 300, "wire_diameter", 0.8e-3);
        e.name("load", e.add(RESISTOR, "alumina", 13, y, z, Dir.SOUTH, "resistance", 20));
        Router r = new Router(e, 10, 0, z - 4, 14, 12, z + 4);
        r.keepClear(10, y - 3, z - 3, 10, y + 3, z + 3); // where the magnets sweep
        r.pin("series", 11, y + 2, z + 1).pin("series", 11, y - 2, z - 1);
        r.pin("a", 11, y + 2, z - 1).pin("a", 13, y, z - 1);
        r.pin("b", 11, y - 2, z + 1).pin("b", 13, y, z + 1);
        r.route();
        return e.view(6, 6, 20, 24);
    }
}
