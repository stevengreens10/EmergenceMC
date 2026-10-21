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
                "The can holds methane and air in the proportions that burn completely (9.5 % CH₄), at atmospheric pressure. Close the switch: the 3.1 kW heater under the can warms it. Even at 800 K "
                        + "they react, slowly: the rate grows as e^(−E/RT), roughly doubling every 25 K here, while the "
                        + "heat the can's wall takes away grows only in proportion to the temperature difference. The "
                        + "heater keeps heating the can; at a critical temperature (Semenov's, about 590 °C, after a minute or so) the heat released "
                        + "outruns the loss and the gas runs away — it ignites by itself, the temperature jumping to "
                        + "~3000 K and the pressure to several bar in a fraction of a second, leaving CO₂ and water "
                        + "vapour. Meter the can for its pressure, temperature and composition, or clip the data logger "
                        + "on it and watch the spike.");
        String st = "steel_1045";
        // a 3.1 kW silicon-carbide heating element (3 Ω on 96 V, good to ~1800 K, as in furnaces) pressed against the
        // can's base over 20 cm²
        e.name("heater", e.add(RESISTOR, "alumina", 8, 0, 7, Dir.EAST, "resistance", 3.0, "contact_area", 0.002,
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
                "Light it the way a gas furnace does: switch the heating element on and open the fuel valve — the "
                        + "methane lights on the walls once they glow, in under a minute — then switch the heater off: the "
                        + "flame keeps itself hot and burns on. The methane comes from a 200 bar cylinder through "
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
        int chamber = e.add(TANK, st, 6, 1, 8, Dir.UP, "wall", 0.0005); // thin: the igniter brings it to light-off quickly
        e.name("chamber", chamber);
        e.add(PIPE, st, 6, 1, 9, Dir.SOUTH, "bore", 0.016);                // air inlet, open at its far end
        int chimney = e.fill(PIPE, st, 6, 2, 8, 6, 6, 8, Dir.UP, "bore", 0.04, "wall", 0.001); // chimney, open at the top
        e.name("chimney", chimney + 4);
        // a silicon-carbide element under the chamber to light it, on glass-fibre-insulated leads: 4.6 kW from the
        // 96 V bank (it peaks near 1500 K; much below 2 Ω the 60 A and more burns out its leads)
        e.name("heater", e.add(RESISTOR, "alumina", 6, 0, 8, Dir.EAST, "resistance", 2.0, "contact_area", 0.002,
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

    /**
     * A still: a steel pot of dilute ethanol (10 mol %) over a heating element; its vapour rises through a pipe to a
     * finned copper-cooled receiver that vents to the air. Boiling, the liquid gives off a vapour far richer in ethanol
     * than itself (vapour–liquid equilibrium); it condenses in the cool receiver as a distillate of about half ethanol.
     */
    public static Exhibit still() {
        Exhibit e = new Exhibit("still", "Still",
                List.of("DISTILLATION", "Switch the heater on:", "the distillate is", "4× richer in ethanol"),
                "The pot holds 50 mL of dilute ethanol — one molecule in ten — already warm. Switch the heater on: the "
                        + "liquid begins to boil near 88 °C. Its vapour is not like it: ethanol is the more volatile, and the "
                        + "vapour over a 10 % solution is about 44 % ethanol (Raoult's law, corrected for how ethanol and "
                        + "water dislike each other — the activity coefficients). The vapour flows to the receiver, whose "
                        + "copper fins keep it cool, and condenses there: the distillate, about half ethanol (the cool receiver "
                        + "keeps more of the water than of the ethanol: a second stage). Meter the pot and the receiver for "
                        + "their liquids' compositions. As the pot loses ethanol its boiling point climbs towards 100 °C and "
                        + "the distillate weakens; collecting it and distilling again (or a column of many stages) "
                        + "concentrates it further — up to the azeotrope near 90 %, beyond which boiling cannot separate.");
        String st = "steel_1045";
        // warm already (80 °C), its headspace in equilibrium with the liquid: the vapours' partial pressures x·γ·p_sat
        // (42 and 46 kPa) and the air that is left
        int pot = e.add(TANK, st, 3, 1, 8, Dir.UP, "wall", 0.002, "liquid_C2H5OH", 0.23, "liquid_H2O", 2.07,
                "x_N2", 0.095, "x_O2", 0.025, "x_H2O", 0.42, "x_C2H5OH", 0.455);
        e.name("pot", pot);
        e.init(pot, new Exhibit.Initial(353.15, 0, 0)); // warmed already: it boils soon after the heater goes on
        // the vapour line: up from the pot's lid, across, down into the receiver
        e.fill(PIPE, st, 3, 2, 8, 3, 3, 8, Dir.UP, "bore", 0.01);
        e.fill(PIPE, st, 4, 3, 8, 7, 3, 8, Dir.EAST, "bore", 0.01);
        e.add(PIPE, st, 8, 3, 8, Dir.DOWN, "bore", 0.01);
        e.add(PIPE, st, 8, 2, 8, Dir.DOWN, "bore", 0.01);
        // caps on the corners' upper ends (an open pipe end vents to the air)
        e.add(BLOCK, st, 3, 4, 8, Dir.UP);
        e.add(BLOCK, st, 8, 4, 8, Dir.UP);
        int receiver = e.add(TANK, "copper", 8, 1, 8, Dir.UP, "wall", 0.002, "condenser", 1);
        e.name("receiver", receiver);
        e.add(BLOCK, st, 8, 0, 8, Dir.UP);
        // cooling fins: thin copper plates against the receiver's sides
        for (int k = 0; k < 3; k++) {
            e.add(BLOCK, "copper", 8, 1, 9 + k, Dir.SOUTH, "fill", 0.15);
            e.add(BLOCK, "copper", 8, 1, 7 - k, Dir.SOUTH, "fill", 0.15);
        }
        // its vent: a pipe east, open at its end
        e.fill(PIPE, st, 9, 1, 8, 10, 1, 8, Dir.EAST, "bore", 0.006);
        // the heater under the pot
        e.name("heater", e.add(RESISTOR, "alumina", 3, 0, 8, Dir.EAST, "resistance", 9.0, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.05));
        int bank = e.add(CAPACITOR, "alumina", 3, 0, 13, Dir.EAST, "capacitance", 200);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 30));
        e.name("switch", e.add(SWITCH, "brass", 6, 0, 13, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 15, 2, 15);
        r.pin("bat", 4, 0, 13).pin("bat", 5, 0, 13);
        r.pin("sw", 7, 0, 13).pin("sw", 4, 0, 8);
        r.pin("ret", 2, 0, 8).pin("ret", 2, 0, 13);
        r.keepClear(11, 1, 8, 11, 1, 8); // the vent's mouth stays open
        r.route();
        return e.view(6, 5, 15, 20);
    }

    /**
     * A reflux column: the still's pot under three trays — stacked tanks whose liquid leaves over a weir — and a finned
     * copper condenser on top that returns all it condenses (total reflux). Each tray's pool meets the vapour rising
     * through it and approaches equilibrium with it; the condensate running back down strips the water from the vapour
     * on its way up, so each tray up is richer in ethanol than the one below.
     */
    public static Exhibit column() {
        Exhibit e = new Exhibit("column", "Reflux column",
                List.of("REFLUX COLUMN", "Switch the heater on:", "each tray up holds", "richer ethanol"),
                "The still's pot of dilute ethanol again, but its vapour must climb through three trays before it "
                        + "reaches the condenser. Each tray is a vessel holding a shallow pool behind a 4 mm weir; the vapour "
                        + "from below passes over it and the two exchange — water condenses into the pool, ethanol "
                        + "evaporates from it — towards equilibrium (Raoult's law with ethanol–water activity coefficients). "
                        + "The finned copper condenser at the top returns everything it condenses: the liquid spills back "
                        + "down from tray to tray (total reflux), stripping the water from the vapour on its way up. "
                        + "Switch the heater on and wait a few minutes: meter the pools — each tray up is richer in "
                        + "ethanol than the one below, and the condensate reaches half ethanol and more while the pot "
                        + "falls to a few percent. One still stage enriches about tenfold (in the ratio of ethanol to "
                        + "water, distillate over pot); the column, some fifteen to fifty. Nothing here knows it is a "
                        + "column: it is vessels, weirs, gravity and phase equilibrium.");
        String st = "steel_1045";
        Object[] mix = {"x_N2", 0.095, "x_O2", 0.025, "x_H2O", 0.42, "x_C2H5OH", 0.455};
        int pot = e.add(TANK, st, 3, 1, 8, Dir.UP, cat(new Object[]{"wall", 0.002, "liquid_C2H5OH", 0.23, "liquid_H2O", 2.07}, mix));
        e.name("pot", pot);
        e.init(pot, new Exhibit.Initial(353.15, 0, 0));
        for (int k = 0; k < 3; k++) {
            int tray = e.add(TANK, st, 3, 2 + k, 8, Dir.UP, cat(new Object[]{"wall", 0.002, "weir", 0.004, "liquid_C2H5OH", 0.07,
                    "liquid_H2O", 0.63}, mix));
            e.name("tray" + (k + 1), tray);
            e.init(tray, new Exhibit.Initial(353.15, 0, 0));
        }
        // the vapour line up to the condenser (a short pipe: the condenser's fins would otherwise chill the trays through
        // the walls they share)
        e.add(PIPE, st, 3, 5, 8, Dir.UP, "bore", 0.012);
        // the condenser: two finned copper tanks; the lower one's lip keeps a little distillate to sample
        int cond = e.add(TANK, "copper", 3, 6, 8, Dir.UP, cat(new Object[]{"wall", 0.002, "condenser", 1, "weir", 0.002}, mix));
        e.name("condenser", cond);
        e.init(cond, new Exhibit.Initial(330, 0, 0));
        int cond2 = e.add(TANK, "copper", 3, 7, 8, Dir.UP, cat(new Object[]{"wall", 0.002, "condenser", 1}, mix));
        e.init(cond2, new Exhibit.Initial(310, 0, 0));
        for (int y = 6; y <= 7; y++)
            for (int[] f : new int[][]{{2, 8}, {4, 8}, {1, 8}, {5, 8}}) e.add(BLOCK, "copper", f[0], y, f[1], Dir.EAST, "fill", 0.15);
        for (int y = 6; y <= 7; y++)
            for (int[] f : new int[][]{{3, 7}, {3, 9}, {3, 6}, {3, 10}}) e.add(BLOCK, "copper", f[0], y, f[1], Dir.SOUTH, "fill", 0.15);
        e.add(PIPE, st, 3, 8, 8, Dir.UP, "bore", 0.006); // the vent, open at its top
        e.name("heater", e.add(RESISTOR, "alumina", 3, 0, 8, Dir.EAST, "resistance", 4.5, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.05));
        int bank = e.add(CAPACITOR, "alumina", 3, 0, 13, Dir.EAST, "capacitance", 400);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 30));
        e.name("switch", e.add(SWITCH, "brass", 6, 0, 13, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 15, 2, 15);
        r.pin("bat", 4, 0, 13).pin("bat", 5, 0, 13);
        r.pin("sw", 7, 0, 13).pin("sw", 4, 0, 8);
        r.pin("ret", 2, 0, 8).pin("ret", 2, 0, 13);
        r.route();
        return e.view(6, 7, 19, 18);
    }

    private static Object[] cat(Object[] a, Object[] b) {
        Object[] o = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
