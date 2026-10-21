package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Thermoelectric exhibits. */
public final class ThermoExhibits {
    private ThermoExhibits() {
    }

    /**
     * A thermocouple thermometer: a copper block and a constantan block touching on a heated ceramic plate (the hot
     * junction), a constantan lead running to a cold junction with copper at room temperature, and an op-amp
     * amplifying the few millivolts ×201.
     */
    public static Exhibit thermocouple() {
        Exhibit e = new Exhibit("thermocouple", "Thermocouple",
                List.of("THERMOCOUPLE", "Two metals, two", "temperatures: a", "voltage (Seebeck)"),
                "Where copper meets constantan, a temperature difference between the two junctions drives a voltage "
                        + "round the circuit: 40.7 µV for every kelvin (a type-T thermocouple). Nothing in it is a "
                        + "'sensor' — any two different metals do this, because heat drives their electrons along at "
                        + "different rates (the Seebeck coefficient: +1.8 µV/K for copper, −38.9 for constantan). The hot "
                        + "junction sits on a heated ceramic plate, the cold one is at room temperature on the bench; "
                        + "the op-amp multiplies the millivolts by 201, so the meter on its output reads about 8 mV per "
                        + "kelvin of difference. Switch the heater off and watch the voltage fall as the junction cools. "
                        + "Run a current the other way and the same junctions pump heat instead (Peltier).");
        String cu = "copper";
        // heater, ceramic plate and the hot junction on it
        e.name("heater", e.add(RESISTOR, "alumina", 4, 0, 7, Dir.EAST, "resistance", 4));
        int plate = e.add(BLOCK, "alumina", 4, 1, 7, Dir.UP);
        int jcu = e.add(BLOCK, cu, 4, 2, 7, Dir.UP, "fill", 0.25);
        int jcon = e.add(BLOCK, "constantan", 4, 2, 8, Dir.UP, "fill", 0.25);
        e.name("junction", jcu);
        for (int i : new int[]{plate, jcu, jcon, e.named.get("heater")}) e.init(i, new Exhibit.Initial(373, 0, 0));
        // copper lead off the copper block, constantan lead off the constantan block to the cold junction at x = 10
        e.add(WIRE, cu, 4, 2, 6, Dir.UP);
        e.add(WIRE, "constantan", 4, 3, 8, Dir.UP);
        e.path(INSULATED_WIRE, "constantan", new int[][]{{4, 4, 8}, {4, 4, 9}, {4, 4, 10}, {5, 4, 10}, {6, 4, 10}, {7, 4, 10},
                {8, 4, 10}, {9, 4, 10}}, "color", 15);
        e.name("cold", e.add(WIRE, cu, 10, 4, 10, Dir.UP)); // the cold junction: constantan meets copper
        // amplifier: non-inverting, gain 1 + 20k/100 = 201, on ±12 V
        e.name("opamp", e.add(OPAMP, "silicon", 14, 1, 6, Dir.EAST));
        e.add(RESISTOR, "alumina", 14, 1, 3, Dir.EAST, "resistance", 100);       // in− to 0 V
        e.add(RESISTOR, "alumina", 18, 1, 3, Dir.EAST, "resistance", 20_000);    // out to in−
        e.name("load", e.add(RESISTOR, "alumina", 18, 1, 9, Dir.EAST, "resistance", 10_000)); // the meter's load
        int bp = e.add(BATTERY, "pvc", 22, 1, 6, Dir.EAST, "cells", 6, "capacity", 20); // 6 lead–acid cells: 12.8 V full
        int bn = e.add(BATTERY, "pvc", 26, 1, 6, Dir.EAST, "cells", 6, "capacity", 20); // 6 lead–acid cells: 12.8 V full
        // heater supply
        int hb = e.add(BATTERY, "pvc", 4, 0, 13, Dir.EAST, "cells", 6, "capacity", 20); // 6 lead–acid cells: 12.8 V full
        e.name("battery", hb);
        int sw = e.add(SWITCH, "brass", 8, 0, 13, Dir.EAST);
        e.name("switch", sw);
        e.latch(sw);
        e.plots = 2;
        Router r = new Router(e, 0, 0, 0, 31, 6, 15);
        r.pin("hA", 3, 0, 7).pin("hA", 3, 0, 13);
        r.pin("hB", 5, 0, 7).pin("hB", 7, 0, 13);
        r.pin("hC", 9, 0, 13).pin("hC", 5, 0, 13);
        // the cold junction's copper to in+, the hot junction's copper lead to 0 V: positive when the junction is hot
        r.pin("tc", 11, 4, 10).pin("tc", 13, 1, 6);
        r.pin("gnd", 4, 2, 5).pin("gnd", 13, 1, 3).pin("gnd", 19, 1, 9)      // hot copper lead, R1, load
                .pin("gnd", 21, 1, 6).pin("gnd", 27, 1, 6);                  // the batteries' 0 V
        r.pin("inN", 15, 1, 3).pin("inN", 17, 1, 3).pin("inN", 14, 0, 6);
        r.pin("out", 15, 1, 6).pin("out", 19, 1, 3).pin("out", 17, 1, 9);
        r.pin("vp", 23, 1, 6).pin("vp", 14, 1, 7);
        r.pin("vn", 25, 1, 6).pin("vn", 14, 1, 5);
        // bare wire touches any wire beside it: keep the routed wiring off the bare leads and the cold junction
        for (int[] k : new int[][]{{3, 2, 6}, {5, 2, 6}, {4, 3, 6}, {4, 1, 6}, {3, 3, 8}, {5, 3, 8}, {4, 3, 7}, {4, 3, 9},
                {10, 3, 10}, {10, 5, 10}, {10, 4, 9}, {10, 4, 11}}) r.keepClear(k[0], k[1], k[2], k[0], k[1], k[2]);
        r.route();
        return e.view(12, 7, 20, 25);
    }

    /**
     * Burst test: two sealed vessels half full of water, each on a 2.3 kW heating element switched from one capacitor
     * bank. The water boils and its pressure climbs the saturation curve; the thin copper box's walls bend until they
     * tear, while the thick steel box holds — why boilers carry safety valves.
     */
    public static Exhibit burstTest() {
        Exhibit e = new Exhibit("burst", "Burst test",
                List.of("BURST TEST", "Close the switch:", "thin copper bursts,", "thick steel holds"),
                "Two sealed boxes, each half full of water, sit on heating elements. Close the switch: the water warms "
                        + "and boils, and with nowhere for the steam to go the pressure follows the boiling curve up: "
                        + "about 2 bar above atmospheric at 134 °C. The pressure pushes on every wall; a flat wall bends, "
                        + "and the stress at its edges grows with the square of its width over its thickness. The left box "
                        + "is copper 1 mm thick: at about 2 bar its walls reach copper's tensile strength and it bursts, "
                        + "venting its steam. The right one is steel 3 mm thick and would need nearly 60 bar. Meter the "
                        + "boxes to watch the pressure and the water temperature climb together.");
        for (int k = 0; k < 2; k++) {
            int x = k == 0 ? 4 : 11;
            // a 2.3 kW silicon-carbide element (4 ohm on 96 V) under each box, on mica legs
            e.add(RESISTOR, "alumina", x, 0, 7, Dir.EAST, "resistance", 4.0, "contact_area", 0.002, "surface_area", 0.002,
                    "mass", 0.1, "max_temperature", 1800);
            e.name(k == 0 ? "thin" : "thick", e.add(TANK, k == 0 ? "copper" : "steel_1045", x, 1, 7, Dir.UP,
                    "fluid", 7, "fill", 0.5, "wall", k == 0 ? 0.001 : 0.003));
            e.add(BLOCK, "mica", x, 0, 6, Dir.UP);
            e.add(BLOCK, "mica", x, 0, 8, Dir.UP);
        }
        int bank = e.add(BATTERY, "pvc", 8, 0, 12, Dir.EAST, "cells", 45, "capacity", 40); // 45 lead–acid cells: 95.6 V full
        e.name("switch", e.add(SWITCH, "brass", 8, 0, 10, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 15, 3, 15);
        r.pin("live", 9, 0, 12).pin("live", 5, 0, 7).pin("live", 12, 0, 7);
        r.pin("ret", 7, 0, 12).pin("ret", 7, 0, 10);
        r.pin("sw", 9, 0, 10).pin("sw", 3, 0, 7).pin("sw", 10, 0, 7);
        r.insulation("live", 800).insulation("sw", 800); // glass-fibre: the elements' leads run hot
        r.route();
        return e.view(7.5, 4, 17, 25);
    }

    /**
     * Fan cooling: two identical aluminium plates, each warmed by a 5 W resistor beneath it. A cranked fan blows air
     * out of a duct onto one of them; the other sits out of the jet. Forced convection — the moving air scrubbing the
     * plate's boundary layer thin — carries off the heat several times faster than the still air round the other.
     */
    public static Exhibit fanCooling() {
        Exhibit e = new Exhibit("fancooling", "Fan cooling",
                List.of("FAN COOLING", "Crank the fan:", "the plate in its jet", "runs much cooler"),
                "Two aluminium plates, each heated by a 5 W resistor. Still air carries heat off a warm surface only "
                        + "slowly (natural convection, about 10 W per square metre per kelvin): left alone, both "
                        + "settle some 40 °C above the room. Crank the fan: it drives air down the duct and out in a jet "
                        + "onto the right-hand plate. Moving air scrubs the warm layer of air off the surface (forced "
                        + "convection: the heat-transfer coefficient grows as the square root of the speed), so that plate "
                        + "runs much cooler than its twin. The jet slows as it spreads; move a plate further away and it "
                        + "helps less. This is how a computer's heat sink and a car's radiator work.");
        String st = "steel_1045";
        int y = 3;
        int fanShaft = e.fill(SHAFT, st, 2, y, 8, 6, y, 8, Dir.EAST, "diameter", 0.012);
        e.name("fanShaft", fanShaft);
        e.add(GEAR, st, 2, y, 8, Dir.EAST, "teeth", 8, "pitch_radius", 0.5 / 16);
        e.add(BEARING, st, 4, y, 8, Dir.EAST);
        e.fill(BLOCK, st, 4, 0, 8, 4, y - 1, 8, Dir.UP);
        e.add(PIPE, "pvc", 5, y, 8, Dir.EAST, "bore", 0.05, "wall", 0.003);
        e.add(PIPE, "pvc", 5, y + 1, 8, Dir.UP, "bore", 0.05, "wall", 0.003);
        e.name("fan", e.add(ROTOR, "aluminium_6061", 6, y, 8, Dir.EAST, "blade_angle", 60, "pitch", 1));
        e.fill(PIPE, "pvc", 7, y, 8, 8, y, 8, Dir.EAST, "bore", 0.05, "wall", 0.003); // its outlet, open to the east
        int midShaft = e.fill(SHAFT, st, 1, y, 11, 4, y, 11, Dir.EAST, "diameter", 0.015);
        e.add(GEAR, st, 1, y, 11, Dir.EAST, "teeth", 8, "pitch_radius", 0.5 / 16);
        e.add(GEAR, st, 2, y, 11, Dir.EAST, "teeth", 40, "pitch_radius", 2.5 / 16);
        e.add(BEARING, st, 4, y, 11, Dir.EAST);
        e.fill(BLOCK, st, 4, 0, 11, 4, y - 1, 11, Dir.UP);
        int crankShaft = e.fill(SHAFT, st, 0, y, 14, 3, y, 14, Dir.EAST, "diameter", 0.015);
        e.add(GEAR, st, 1, y, 14, Dir.EAST, "teeth", 40, "pitch_radius", 2.5 / 16);
        e.add(BEARING, st, 3, y, 14, Dir.EAST);
        e.fill(BLOCK, st, 3, 0, 14, 3, y - 1, 14, Dir.UP);
        e.name("crank", e.add(CRANK, st, 0, y, 14, Dir.WEST, "radius", 0.15));
        // the plates: one in the jet (two cells past the duct's mouth), one aside; a heater under each
        for (int k = 0; k < 2; k++) {
            int z = k == 0 ? 8 : 4;
            String side = k == 0 ? "cooled" : "still";
            int plate = e.add(BLOCK, "aluminium_6061", 10, y, z, Dir.EAST, "fill", 0.2);
            int heater = e.add(RESISTOR, "alumina", 10, y - 1, z, Dir.EAST, "resistance", 20.0, "contact_area", 0.002);
            e.name(side, plate);
            e.name(side + "Heater", heater);
            double warm = 333.15; // about where still air holds them with the heaters on
            e.init(plate, new Exhibit.Initial(warm, 0, 0));
            e.init(heater, new Exhibit.Initial(warm, 0, 0));
            e.fill(BLOCK, "pvc", 10, 0, z, 10, y - 2, z, Dir.UP);
        }
        int bank = e.add(BATTERY, "pvc", 13, 1, 6, Dir.SOUTH, "cells", 5, "capacity", 20);
        int sw = e.add(SWITCH, "brass", 13, 1, 10, Dir.SOUTH);
        e.name("switch", sw);
        e.latch(sw);
        Router r = new Router(e, 0, 0, 0, 15, 4, 15);
        r.keepClear(9, y, 8, 9, y, 8); // the jet's path
        r.pin("pos", 13, 1, 7).pin("pos", 13, 1, 9);
        r.pin("sw", 13, 1, 11).pin("sw", 11, y - 1, 8).pin("sw", 11, y - 1, 4);
        r.pin("neg", 13, 1, 5).pin("neg", 9, y - 1, 8).pin("neg", 9, y - 1, 4);
        r.route();
        return e.view(7, 7, 18, 25);
    }
}
