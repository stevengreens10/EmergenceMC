package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.sim.elements.MsiChip;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Radioactivity and its detection (educational sealed-source quantities). */
public final class RadiationExhibits {
    private RadiationExhibits() {
    }

    /**
     * A Geiger counter from parts: a Cs-137 check source, a Geiger–Müller tube on a 510 V battery through 4.7 MΩ, its 1 kΩ
     * cathode resistor feeding a Schmitt trigger that clocks a 4-bit counter chip, and four LEDs showing the count
     * in binary.
     */
    public static Exhibit geigerCounter() {
        Exhibit e = new Exhibit("geiger", "Geiger counter",
                List.of("GEIGER COUNTER", "Cs-137 source, GM", "tube, counter chip:", "LEDs count in binary"),
                "The check source holds caesium-137 (370 kBq, a school source); its barium-137m daughter emits a 662 keV "
                        + "gamma ray in nine decays out of ten. A gamma ray that interacts in the Geiger–Müller tube frees "
                        + "an electron, and with 510 V on the anode (a battery of 240 small cells) it sets off an avalanche: the tube conducts until its "
                        + "charge is spent, a few nanocoulombs flow through the 10 kΩ cathode resistor — a pulse of a few volts — and "
                        + "the gas quenches it. The Schmitt trigger squares each pulse and the counter chip counts them; "
                        + "the LEDs show the count in binary. The clicks come at random (Poisson), about two a second here; "
                        + "a third of them are background from cosmic rays and the ground. Put a lead block between source "
                        + "and tube: 1 cm of lead stops 70 % of the gammas; move the source away: the rate falls as 1/r². "
                        + "Right after each pulse the tube is dead until the anode recharges — at high rates it misses hits.");
        e.add(BLOCK, "oak", 3, 0, 7, Dir.UP);
        e.name("source", e.add(SOURCE, "stainless_304", 3, 1, 7, Dir.UP, "nuclide", 0, "activity", 3.7e5));
        e.add(BLOCK, "oak", 6, 0, 7, Dir.UP);
        e.name("tube", e.add(GM_TUBE, "stainless_304", 6, 1, 7, Dir.EAST));
        e.add(RESISTOR, "alumina", 10, 1, 7, Dir.EAST, "resistance", 4.7e6);   // anode resistor
        e.add(CAPACITOR, "alumina", 10, 1, 4, Dir.EAST, "capacitance", 100e-12); // anode capacitor
        // the high tension: a battery of 240 small lead–acid cells, 510 V (survey meters ran on such stacks — the 510 V
        // batteries of the 1950s); a charged capacitor in its place leaked below the tube's starting voltage in minutes
        e.name("hv", e.add(BATTERY, "pvc", 14, 1, 7, Dir.EAST, "cells", 240, "capacity", 0.1));
        e.add(RESISTOR, "alumina", 4, 1, 11, Dir.SOUTH, "resistance", 10_000); // cathode resistor
        e.name("schmitt", e.add(LOGIC, "silicon", 10, 1, 10, Dir.EAST, "function", 7));
        e.name("counter", ComputerExhibit.chip(e, MsiChip.Type.COUNTER4, 14, 1, 10));
        int vdd = e.add(BATTERY, "pvc", 27, 1, 7, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 2); // 4 nickel–metal hydride cells: 5.2 V at half charge
        for (int k = 0; k < 4; k++) {
            e.name("led" + k, e.add(LED, "silicon", 14 + 2 * k, 1, 13, Dir.SOUTH, "wavelength", 590, "face", 1, "beam", 20));
            e.add(RESISTOR, "alumina", 14 + 2 * k, 1, 14, Dir.SOUTH, "resistance", 470);
        }
        e.plots = 2;
        Router r = new Router(e, 0, 0, 0, 31, 4, 15);
        r.pin("anode", 7, 1, 7).pin("anode", 9, 1, 7).pin("anode", 9, 1, 4);
        r.pin("hv", 11, 1, 7).pin("hv", 15, 1, 7);
        r.pin("cathode", 5, 1, 7).pin("cathode", 4, 1, 10).pin("cathode", 9, 1, 10);
        r.pin("clk", 11, 1, 10).pin("clk", 18, 1, 9);
        r.pin("gnd", 13, 1, 7).pin("gnd", 11, 1, 4).pin("gnd", 4, 1, 12).pin("gnd", 10, 1, 9).pin("gnd", 26, 1, 7)
                .pin("gnd", 22, 1, 9);
        for (int k = 0; k < 4; k++) r.pin("gnd", 14 + 2 * k, 1, 15); // LED returns (P0–P3 unused: LOAD̄ is held high)
        r.pin("vdd", 28, 1, 7).pin("vdd", 10, 1, 11).pin("vdd", 22, 1, 11)
                .pin("vdd", 19, 1, 9).pin("vdd", 20, 1, 9).pin("vdd", 21, 1, 9);               // CLR̄, LOAD̄ high; EN high
        for (int k = 0; k < 4; k++) r.pin("q" + k, 14 + k, 1, 11).pin("q" + k, 14 + 2 * k, 1, 12);
        r.route();
        return e.view(12, 6, 21, 25);
    }
}
