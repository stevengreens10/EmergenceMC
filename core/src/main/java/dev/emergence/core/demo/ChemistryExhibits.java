package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of gas chemistry: equilibrium that shifts with temperature, and a thermal explosion. */
public final class ChemistryExhibits {
    private ChemistryExhibits() {
    }

    /**
     * Two sealed glass flasks charged with the same amount of dinitrogen tetroxide. N₂O₄ ⇌ 2 NO₂ is endothermic
     * (+57 kJ/mol), so the equilibrium shifts towards brown NO₂ as the gas warms (Le Chatelier; K_p grows ~50× from
     * 20 to 90 °C). One flask sits on a hot plate kept warm by a heater; the other stands at room temperature.
     */
    public static Exhibit brownGas() {
        Exhibit e = new Exhibit("equilibrium", "Brown gas",
                List.of("BROWN GAS", "N2O4 <=> 2 NO2", "Warmth shifts the", "equilibrium"),
                "Both flasks were charged with the same amount of colourless N₂O₄ (0.05 bar). Some of it splits into "
                        + "brown NO₂ until the rate of splitting equals the rate of recombining — chemical equilibrium, "
                        + "K_p = p(NO₂)²/p(N₂O₄) = e^(−ΔG°/RT). Splitting absorbs 57 kJ/mol, so warmth favours it: the "
                        + "flask on the hot plate (about 90 °C) holds nearly twice the NO₂ and looks much darker. Turn "
                        + "the heater off with the switch and watch it fade as it cools; the multimeter shows the "
                        + "pressure and the mole fractions.");
        String gl = "glass";
        // cold flask on a stand
        e.add(BLOCK, "oak", 4, 0, 7, Dir.UP);
        int cold = e.add(TANK, gl, 4, 1, 7, Dir.UP, "x_N2O4", 1, "pressure", 5000, "wall", 0.002);
        e.name("cold", cold);
        // warm flask on an aluminium hot plate over a 4 Ω heater
        e.name("heater", e.add(RESISTOR, "alumina", 10, 0, 7, Dir.EAST, "resistance", 4));
        int plate = e.add(BLOCK, "aluminium_6061", 10, 1, 7, Dir.UP, "fill", 0.3);
        int hot = e.add(TANK, gl, 10, 2, 7, Dir.UP, "x_N2O4", 1, "pressure", 5000, "wall", 0.002);
        e.name("hot", hot);
        for (int i : new int[]{plate, hot, e.named.get("heater")}) e.init(i, new Exhibit.Initial(363, 0, 0));
        // the hot plate's legs (wood: steel would touch the heater's leads)
        e.add(BLOCK, "oak", 10, 0, 6, Dir.UP);
        e.add(BLOCK, "oak", 10, 0, 8, Dir.UP);
        // 12 V supercapacitor bank and switch
        int bank = e.add(CAPACITOR, "alumina", 10, 0, 12, Dir.EAST, "capacitance", 3000);
        e.name("battery", bank);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 12));
        int sw = e.add(SWITCH, "brass", 6, 0, 10, Dir.EAST);
        e.name("switch", sw);
        e.latch(sw);
        Router r = new Router(e, 0, 0, 0, 15, 3, 15);
        r.pin("live", 11, 0, 12).pin("live", 11, 0, 7);
        r.pin("ret", 9, 0, 12).pin("ret", 7, 0, 10);
        r.pin("sw", 5, 0, 10).pin("sw", 9, 0, 7);
        r.route();
        return e.view(7.5, 4, 14, 18);
    }

    /**
     * Semenov thermal explosion: stoichiometric methane–air sealed in a thin steel vessel on a furnace hot plate.
     * Closing the switch heats the vessel; the reaction's heat release
     * grows exponentially with temperature while the loss to the wall grows only linearly, so at a critical
     * temperature the gas runs away — ignition without a spark.
     */
    public static Exhibit thermalExplosion() {
        Exhibit e = new Exhibit("explosion", "Thermal explosion",
                List.of("THERMAL EXPLOSION", "Close the switch:", "methane + air in a", "heated can ignites"),
                "The can holds methane and air in the proportions that burn completely (9.5 % CH₄), at atmospheric pressure. Close the switch: the 2.3 kW heater under the can warms it. Even at 800 K "
                        + "they react, slowly: the rate grows as e^(−E/RT), roughly doubling every 25 K here, while the "
                        + "heat the can's wall takes away grows only in proportion to the temperature difference. The "
                        + "heater keeps heating the can; at a critical temperature (Semenov's, about 590 °C, after two minutes or so) the heat released "
                        + "outruns the loss and the gas runs away — it ignites by itself, the temperature jumping to "
                        + "~3000 K and the pressure to several bar in a fraction of a second, leaving CO₂ and water "
                        + "vapour. Meter the can for its pressure, temperature and composition, or clip the data logger "
                        + "on it and watch the spike.");
        String st = "steel_1045";
        // a 2.3 kW cartridge heater (4 Ω on 96 V) pressed against the can's base over 20 cm²
        e.name("heater", e.add(RESISTOR, "alumina", 8, 0, 7, Dir.EAST, "resistance", 4.0, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.1));
        int can = e.add(TANK, st, 8, 1, 7, Dir.UP, "x_CH4", 0.095, "x_O2", 0.19, "x_N2", 0.715, "pressure", 101325, "wall", 0.0015);
        e.name("can", can);
        // legs of mica, a refractory insulator (alumina conducts the heat away and radiates it; wood would char;
        // steel would short the heater's leads)
        e.add(BLOCK, "mica", 8, 0, 6, Dir.UP);
        e.add(BLOCK, "mica", 8, 0, 8, Dir.UP);
        int bank = e.add(CAPACITOR, "alumina", 8, 0, 12, Dir.EAST, "capacitance", 150);
        e.name("battery", bank);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 96)); // its charge fades over minutes (RC = 10 min)
        int sw = e.add(SWITCH, "brass", 4, 0, 10, Dir.EAST);
        e.name("switch", sw);
        Router r = new Router(e, 0, 0, 0, 15, 3, 15);
        r.pin("live", 9, 0, 12).pin("live", 9, 0, 7);
        r.pin("ret", 7, 0, 12).pin("ret", 5, 0, 10);
        r.pin("sw", 3, 0, 10).pin("sw", 7, 0, 7);
        r.route();
        return e.view(8, 3.5, 13, 18);
    }
}
