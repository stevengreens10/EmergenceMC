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
        int bp = e.add(CAPACITOR, "alumina", 22, 1, 6, Dir.EAST, "capacitance", 3000);
        int bn = e.add(CAPACITOR, "alumina", 26, 1, 6, Dir.EAST, "capacitance", 3000);
        e.init(bp, new Exhibit.Initial(Double.NaN, 0, 12));
        e.init(bn, new Exhibit.Initial(Double.NaN, 0, 12));
        // heater supply
        int hb = e.add(CAPACITOR, "alumina", 4, 0, 13, Dir.EAST, "capacitance", 3000);
        e.name("battery", hb);
        e.init(hb, new Exhibit.Initial(Double.NaN, 0, 12));
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
                .pin("gnd", 21, 1, 6).pin("gnd", 27, 1, 6);                  // the banks' 0 V
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
        int bank = e.add(CAPACITOR, "alumina", 8, 0, 12, Dir.EAST, "capacitance", 150);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 96));
        e.name("switch", e.add(SWITCH, "brass", 8, 0, 10, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 15, 3, 15);
        r.pin("live", 9, 0, 12).pin("live", 5, 0, 7).pin("live", 12, 0, 7);
        r.pin("ret", 7, 0, 12).pin("ret", 7, 0, 10);
        r.pin("sw", 9, 0, 10).pin("sw", 3, 0, 7).pin("sw", 10, 0, 7);
        r.insulation("live", 800).insulation("sw", 800); // glass-fibre: the elements' leads run hot
        r.route();
        return e.view(7.5, 4, 17, 25);
    }
}
