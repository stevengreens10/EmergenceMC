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

    /**
     * A propeller cart: a two-bladed propeller with a flywheel, on a bracket on a light cart that slides along a rail.
     * Spun, the propeller throws air one way and the cart is pushed the other (the air is the "something to push
     * against"); its thrust falls as the cart picks up speed, and as the flywheel runs down.
     */
    public static Exhibit propellerCart() {
        return propellerCart(8);
    }

    static Exhibit propellerCart(double pitch) {
        Exhibit e = new Exhibit("propcart", "Propeller cart",
                List.of("PROPELLER CART", "The spinning propeller", "throws air back:", "the cart goes forward"),
                "A light aluminium cart runs on a two-metre rail through two bushings. On it, a flywheel and a two-bladed "
                        + "propeller turn on one shaft, along the rail. Each blade, set at an angle to its path, deflects "
                        + "the air it meets (lift and drag from its angle of attack, element by element), and together "
                        + "they drive a stream of air through the disc they sweep — momentum theory sets how fast. The "
                        + "air pushed one way, the propeller is pushed the other with the same force, and the cart with "
                        + "it: nothing else touches the cart. The faster the cart goes, the less its blades bite (they "
                        + "meet the air already moving towards them) and the thrust falls; the flywheel runs down as "
                        + "the air takes its energy. Crank the flywheel to spin it up again. Aircraft propellers, "
                        + "airboats and the fans of a hovercraft all push this way.");
        e.plots = 2;
        String st = "steel_1045", al = "aluminium_6061";
        int y = 3, ya = y + 8; // the propeller high enough for long blades to clear the rail
        // the rail: a rod through two bushings on posts, nearly two metres long
        e.fill(BLOCK, st, 16, 0, 8, 17, y - 1, 8, Dir.UP);
        e.add(LINEAR_BEARING, st, 16, y, 8, Dir.EAST, "viscosity", 0.05);
        e.add(LINEAR_BEARING, st, 17, y, 8, Dir.EAST, "viscosity", 0.05);
        e.name("rail", e.fill(SHAFT, st, 2, y, 8, 31, y, 8, Dir.EAST, "diameter", 0.012));
        // the cart: an aluminium deck on the rod and a bracket up to the propeller's bearing
        e.name("cart", e.fill(BLOCK, al, 4, y, 8, 6, y, 8, Dir.EAST, "fill", 0.3));
        e.fill(BLOCK, al, 5, y + 1, 8, 5, ya - 1, 8, Dir.EAST, "fill", 0.3); // upright plates: one strip
        e.add(BEARING, st, 5, ya, 8, Dir.EAST);
        int shaft = e.fill(SHAFT, st, 3, ya, 8, 8, ya, 8, Dir.EAST, "diameter", 0.01);
        e.name("shaft", shaft);
        // the propeller: two blades across the shaft's end, pitched into the turn
        e.add(BLOCK, al, 3, ya, 8, Dir.UP, "fill", 0.5);
        // long blades: a hand can turn the propeller only so fast, and thrust grows with the cube of their length
        e.fill(BLADE, al, 3, ya, 1, 3, ya, 7, Dir.NORTH, "face", Dir.UP.ordinal(), "pitch", pitch);
        e.fill(BLADE, al, 3, ya, 9, 3, ya, 15, Dir.SOUTH, "face", Dir.UP.ordinal(), "pitch", pitch);
        // the flywheel, and a crank to spin it up
        e.name("flywheel", e.fill(BLOCK, "brass", 7, ya - 1, 7, 7, ya + 1, 9, Dir.EAST, "fill", 0.3));
        e.name("crank", e.add(CRANK, st, 8, ya, 8, Dir.EAST, "radius", 0.1));
        return e.view(17, 9, 24, 25);
    }
}
