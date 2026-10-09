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
        return e.view(7.5, 3.5, 12.5, 14);
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
        // a 2.3 kW silicon-carbide heating element (4 Ω on 96 V, good to ~1800 K, as in furnaces) pressed against the
        // can's base over 20 cm²
        e.name("heater", e.add(RESISTOR, "alumina", 8, 0, 7, Dir.EAST, "resistance", 4.0, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.1, "max_temperature", 1800));
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
        // the heater's leads run hot: glass-fibre insulated wire (PVC would char at 160 °C)
        r.insulation("live", 800).insulation("sw", 800);
        r.route();
        return e.view(8, 3.5, 13, 18);
    }

    /**
     * A gas burner that draws its own air: methane from a 200 bar cylinder through a needle valve (a choked orifice)
     * into a steel combustion chamber, which also opens to the air through an inlet pipe at its side and to a chimney
     * above. Lit by a heating element, the flame keeps the chamber hot; the column of hot gas in the chimney is lighter
     * than the air outside, so it rises and pulls fresh air in through the inlet — natural draft.
     */
    public static Exhibit gasBurner() {
        Exhibit e = new Exhibit("burner", "Gas burner",
                List.of("GAS BURNER", "1. heater switch on", "2. open fuel valve", "3. heater off"),
                "Light it the way a gas furnace does: switch the heating element on and let the chamber glow (about two "
                        + "minutes), then open the fuel valve — the methane lights on the hot walls — then switch the heater "
                        + "off: the flame keeps itself hot and burns on. The methane comes from a 200 bar cylinder through "
                        + "a needle valve so fine (40 µm) that the flow is choked: it depends only on the cylinder pressure. "
                        + "Nothing pumps the air: the hot gas in the chimney weighs less than the cold air outside, so it "
                        + "rises and pulls fresh air in through the side pipe (natural draft), nearly the right amount to "
                        + "burn the fuel. Meter the chamber or the chimney for temperature and composition: CO₂ and water "
                        + "vapour leave, nitrogen passes through. Too small a flame in a cold box goes out — the walls take "
                        + "its heat faster than it makes it.");
        String st = "steel_1045";
        e.add(BLOCK, st, 3, 0, 8, Dir.UP);
        int fuel = e.add(TANK, st, 3, 1, 8, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.name("fuel", fuel);
        int valve = e.add(VALVE, "brass", 4, 1, 8, Dir.EAST, "bore", 4e-5);
        e.name("valve", valve);
        e.add(PIPE, st, 5, 1, 8, Dir.EAST, "bore", 0.004);
        int chamber = e.add(TANK, st, 6, 1, 8, Dir.UP, "wall", 0.002);
        e.name("chamber", chamber);
        e.add(PIPE, st, 6, 1, 9, Dir.SOUTH, "bore", 0.016);                // air inlet, open at its far end
        int chimney = e.fill(PIPE, st, 6, 2, 8, 6, 6, 8, Dir.UP, "bore", 0.04, "wall", 0.001); // chimney, open at the top
        e.name("chimney", chimney + 4);
        // a silicon-carbide element under the chamber to light it, on glass-fibre-insulated leads
        e.name("heater", e.add(RESISTOR, "alumina", 6, 0, 8, Dir.EAST, "resistance", 4.0, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.1, "max_temperature", 1800));
        int bank = e.add(CAPACITOR, "alumina", 8, 0, 12, Dir.EAST, "capacitance", 150);
        e.name("battery", bank);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 96));
        e.name("switch", e.add(SWITCH, "brass", 4, 0, 12, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 15, 3, 15);
        r.pin("live", 9, 0, 12).pin("live", 7, 0, 8);
        r.pin("ret", 7, 0, 12).pin("ret", 5, 0, 12);
        r.pin("sw", 3, 0, 12).pin("sw", 5, 0, 8);
        r.insulation("live", 800).insulation("sw", 800);
        r.keepClear(6, 1, 10, 6, 1, 10); // the air inlet's mouth stays open
        r.route();
        return e.view(6, 5, 15, 15);
    }
}
