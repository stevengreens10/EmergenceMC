package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of light: an optical light barrier on a turning vane, and a solar panel. */
public final class OpticsExhibits {
    private OpticsExhibits() {
    }

    /**
     * A light barrier: an infrared LED shines up to a photodiode; a vane on a hand-cranked rotor cuts the beam once
     * per turn. The photocurrent through 2.2 MΩ makes a voltage that a Schmitt trigger squares up; its output drives
     * an indicator LED and clocks a D flip-flop wired to toggle — the counting stage of an optical encoder.
     */
    public static Exhibit lightBarrier() {
        Exhibit e = new Exhibit("lightbarrier", "Light barrier",
                List.of("LIGHT BARRIER", "IR LED -> photodiode.", "Crank: the vane", "cuts the beam"),
                "An infrared LED (850 nm) shines straight up at a silicon photodiode four blocks above. Light that "
                        + "arrives frees electrons in the junction: about 0.55 A of photocurrent per watt. A few microamps "
                        + "through 2.2 MΩ hold the Schmitt trigger's input high; turn the crank and the steel vane on the "
                        + "rotor cuts the beam once per turn, the input drops, and the trigger's output flips — lighting "
                        + "the yellow LED and clocking a flip-flop that toggles the green one: an optical encoder from "
                        + "parts. Glass would not stop the beam; any opaque part does.");
        String st = "steel_1045";
        int sx = 4, sz = 6;
        // rotor: vertical shaft on a bearing, crank on top, a nylon hub with a steel vane reaching over the beam
        int shaft = e.fill(SHAFT, st, sx, 1, sz, sx, 4, sz, Dir.UP, "diameter", 0.012);
        e.name("rotor", shaft);
        e.add(BEARING, st, sx, 1, sz, Dir.UP);
        e.fill(BLOCK, st, sx, 0, sz - 1, sx, 1, sz - 1, Dir.UP);
        e.fill(BLOCK, st, sx, 0, sz + 1, sx, 1, sz + 1, Dir.UP);
        e.add(BLOCK, "nylon_66", sx, 3, sz, Dir.UP, "fill", 0.5);
        e.fill(BLOCK, st, sx + 1, 3, sz, sx + 2, 3, sz, Dir.UP, "fill", 0.2);
        e.name("crank", e.add(CRANK, st, sx, 4, sz, Dir.UP, "radius", 0.12));
        // the beam: IR LED below the vane's path, photodiode above it facing down
        e.name("irled", e.add(LED, "silicon", sx + 2, 1, sz, Dir.EAST, "wavelength", 850, "efficiency", 0.3, "beam", 20));
        e.name("pd", e.add(PHOTODIODE, "silicon", sx + 2, 5, sz, Dir.EAST, "face", 0));
        // electronics on the floor to the east
        int bat = e.add(CAPACITOR, "alumina", 12, 1, 2, Dir.EAST, "capacitance", 10);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        e.add(RESISTOR, "alumina", 9, 1, 2, Dir.EAST, "resistance", 150);       // IR LED current
        e.add(RESISTOR, "alumina", 9, 1, 10, Dir.EAST, "resistance", 2.2e6);    // photodiode load
        e.name("schmitt", e.add(LOGIC, "silicon", 13, 1, 10, Dir.EAST, "function", 7));
        e.name("toggle", e.add(LOGIC, "silicon", 17, 1, 10, Dir.EAST, "function", 8));
        e.name("beamLed", e.add(LED, "silicon", 13, 1, 13, Dir.EAST, "wavelength", 590, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", 16, 1, 13, Dir.EAST, "resistance", 470);
        e.name("countLed", e.add(LED, "silicon", 17, 1, 6, Dir.EAST, "wavelength", 565, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", 14, 1, 6, Dir.EAST, "resistance", 470);
        Router r = new Router(e, 0, 0, 0, 19, 7, 14);
        r.pin("vdd", 13, 1, 2).pin("vdd", 8, 1, 2).pin("vdd", sx + 3, 5, sz)       // supply, IR resistor, PD cathode
                .pin("vdd", 13, 1, 11).pin("vdd", 17, 1, 11);                       // chip supplies
        r.pin("irA", 10, 1, 2).pin("irA", sx + 1, 1, sz);                            // resistor → IR LED anode
        r.pin("gnd", 11, 1, 2).pin("gnd", sx + 3, 1, sz).pin("gnd", 10, 1, 10)       // supply −, IR LED cathode, load
                .pin("gnd", 13, 1, 9).pin("gnd", 17, 1, 9).pin("gnd", 17, 1, 13).pin("gnd", 13, 1, 6);
        r.pin("sense", sx + 1, 5, sz).pin("sense", 8, 1, 10).pin("sense", 12, 1, 10); // PD anode → load, Schmitt in
        r.pin("dark", 14, 1, 10).pin("dark", 17, 0, 10).pin("dark", 12, 1, 13);     // Schmitt out → DFF clock, yellow LED
        r.pin("beamLedK", 14, 1, 13).pin("beamLedK", 15, 1, 13);                    // yellow LED → 470 Ω
        r.pin("q", 18, 1, 10).pin("q", 16, 1, 6);                                    // Q → green LED anode
        r.pin("qbar", 17, 2, 10).pin("qbar", 16, 1, 10);                             // Q̄ → D (toggle)
        r.pin("countK", 18, 1, 6).pin("countK", 15, 1, 6);                           // green LED → 470 Ω
        r.route();
        return e.view(10, 9, 22, 35);
    }

    /**
     * A solar panel: eight large silicon cells face the sky, each one's cathode face against the next one's anode, so they
     * form a series string (≈ 0.6 V per cell open-circuit). In daylight the string lights an LED through 100 Ω; at
     * night, or shaded, it goes dark.
     */
    public static Exhibit solarPanel() {
        Exhibit e = new Exhibit("solar", "Solar panel",
                List.of("SOLAR PANEL", "8 silicon cells", "in series light", "an LED by day"),
                "Each block is one solar cell: a large silicon PN junction facing the sky. Sunlight (up to about 1000 W/m² "
                        + "with the sun overhead, less when it is low or it rains) drives about 0.5 A per watt of light "
                        + "across the junction; open-circuited a cell settles near 0.6 V, where its own diode current "
                        + "balances the photocurrent. The cells touch end to end, + to −, so their voltages add: the "
                        + "string lights the LED through 100 Ω. Cover a cell and the whole string falls — a series "
                        + "string is only as strong as its darkest cell. At night everything is dark.");
        for (int x = 2; x <= 9; x++) {
            int k = e.add(PHOTODIODE, "silicon", x, 1, 6, Dir.EAST, "area", 0.0035, "face", 1);
            if (x == 2) e.name("cell", k);
        }
        e.fill(BLOCK, "aluminium_6061", 2, 0, 6, 9, 0, 6, Dir.UP, "fill", 0.5);
        e.name("led", e.add(LED, "silicon", 6, 1, 10, Dir.EAST, "wavelength", 590, "face", 3, "beam", 6));
        e.add(RESISTOR, "alumina", 10, 1, 10, Dir.EAST, "resistance", 100);
        Router r = new Router(e, 0, 0, 0, 13, 5, 13);
        // a lit cell drives current out of its anode: the string's + end is the first cell's anode side
        r.pin("plus", 1, 1, 6).pin("plus", 5, 1, 10);
        r.pin("ledK", 7, 1, 10).pin("ledK", 9, 1, 10);
        r.pin("minus", 10, 1, 6).pin("minus", 11, 1, 10);
        r.route();
        return e.view(6, 6, 16, 35);
    }
}
