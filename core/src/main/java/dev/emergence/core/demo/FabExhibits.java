package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of making things from materials: a wafer processed into chips. */
public final class FabExhibits {
    private FabExhibits() {
    }

    /** The heater's resistance [Ω]: on 24 lead–acid cells it holds the wafer near 1200 °C. */
    static final double HEATER_OHMS = 1.2;

    /**
     * A wafer furnace: a silicon wafer (a 6.25 cm plate with a phosphorus film) on a ceramic heating element run from a
     * battery bank. At about 1200 °C its surface oxidises (the transistors' gate oxide) and the phosphorus diffuses in
     * (their sources and drains). Each by its own law and at its own rate, so how long it is heated decides the chips.
     */
    public static Exhibit waferFurnace() {
        Exhibit e = new Exhibit("waferfurnace", "Wafer furnace",
                List.of("WAFER FURNACE", "Hot silicon grows", "its gate oxide;", "phosphorus diffuses in"),
                "A silicon wafer with a phosphorus film lies on a ceramic heating element (1.4 kW from 24 lead–acid cells; "
                        + "open the switch to stop). At about 1200 °C two things happen to it, each at its own rate. The air oxidises "
                        + "its surface: the oxide grows fast for the first nanometres, then slower as oxygen has to diffuse "
                        + "through what has grown (Deal–Grove). That oxide will be the gate of every transistor. And the "
                        + "phosphorus diffuses in (D = D₀·e^(−Ea/kT)), making the n-type sources and drains; they spread "
                        + "sideways too. Too short, and the oxide is too thin for the transistors to turn off; too long, and "
                        + "the sources and drains meet under the gates. Right-click the wafer (empty hand) to see its oxide, "
                        + "its junctions and the chips it would make; sneak and right-click to cut it into chips.");
        e.miniature = true;
        // the wafer on its heater
        e.name("heater", e.add(RESISTOR, "alumina", 8, 0, 8, Dir.EAST, "resistance", HEATER_OHMS, "max_temperature", 2000,
                "contact_area", 0.004, "mass", 0.02));
        e.name("wafer", e.add(BLOCK, "silicon", 8, 1, 8, Dir.UP, "fill", 0.05, "dopant", 2, "fab_gate", 1e-5));
        // 24 lead–acid cells, 50 V, and the switch, wired in 2.5 mm copper
        e.name("battery", e.add(BATTERY, "pvc", 4, 0, 8, Dir.EAST, "cells", 24, "capacity", 100));
        int sw = e.add(SWITCH, "brass", 6, 0, 10, Dir.UP);
        e.name("switch", sw);
        e.latch(sw);
        e.path(INSULATED_WIRE, "copper", new int[][]{{4, 0, 7}, {5, 0, 7}, {6, 0, 7}, {7, 0, 7}, {8, 0, 7}}, "diameter", 2.5e-3);
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 0, 9}, {8, 0, 10}, {7, 0, 10}}, "diameter", 2.5e-3);
        e.path(INSULATED_WIRE, "copper", new int[][]{{5, 0, 10}, {4, 0, 10}, {4, 0, 9}}, "diameter", 2.5e-3);
        return e.view(7.5, 0.6, 9.2, 40);
    }
}
