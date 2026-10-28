package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static dev.emergence.core.demo.Exhibits.with;

/**
 * The power station: one machine of many disciplines, every part of it doing a job, built as a player builds — in
 * its own world, apart from the gallery. Methane burns in a firebox under a boiler; the steam drives an engine held to
 * its speed by Watt's flyball governor; the engine turns a generator whose rectified output charges the station's
 * bank of 288 lead–acid cells; and the bank keeps the station's own battery charged, which runs the electronics that
 * hold their charges (regulators dumping the surplus) and count the engine's turns.
 *
 * <p>Built in sections, each in its own coordinates ({@link Exhibit#origin}):
 * <ul>
 *     <li>the plant (origin {@link #PLANT}): fuel, firebox, boiler, engine, governor, brake;</li>
 * </ul>
 */
public final class StationExhibit {
    private StationExhibit() {}

    /** Where the plant's own coordinates start in the station's. */
    static final int[] PLANT = {10, 0, 0};

    public static Exhibit station() {
        return station(true);
    }

    /** @param electrics the generator and the electronics too (without: the plant alone, for testing it) */
    public static Exhibit station(boolean electrics) {
        Exhibit e = new Exhibit("station", "Power station",
                List.of("POWER STATION", "Fire, steam, a", "governed engine and", "a bank of batteries"),
                "A steam power station that runs itself, every part of it a part a player could place. Methane from "
                        + "six 200 bar bottles burns in a three-cell firebox under a three-tank boiler, drawing its air by "
                        + "the chimney's draft; through a copper crown the flame gives the water some 750 W, and the rest "
                        + "of the fire's heat goes up the chimney. The steam drives a single-cylinder engine with "
                        + "cam-worked valves, its speed held by a flyball governor geared off the crankshaft, working the "
                        + "throttle's plug: put the brake on and the throttle opens. The water goes round and round: the "
                        + "engine exhausts, and the safety valve blows, into a finned copper condenser at about half an "
                        + "atmosphere (water's vapour pressure there, near 80 °C); the condensate falls into the hot well, "
                        + "and a feed pump on the crankshaft (a piston between two check valves, below the well so the "
                        + "near-boiling water does not flash in it) pumps it back into the boiler, an overflow at the "
                        + "water line keeping its level. Geared 4 : 1 off the crankshaft, an axial-flux generator — four "
                        + "magnets in a disc between eight coils — charges the station's bank through a diode bridge: 288 "
                        + "lead–acid cells in blocks, twelve strings of 24 in two walls, 48 V and 4.6 kWh, some 37 W going "
                        + "in. Op-amp regulators dump what a full bank or battery cannot take; the bank keeps the station's "
                        + "own 5 V battery charged through a dropping resistor; and an infrared beam cut by a vane on the "
                        + "crankshaft counts its turns in binary on three LEDs. In front, a dashboard of seven screens, each "
                        + "driven by a gauge built of miniature electronics in the block behind it, shows the fuel left, the "
                        + "boiler's pressure, the engine's speed, the condenser's pressure, the generator's current, the "
                        + "bank's charge and the power going into it, live, with their trends.");
        e.plots = 4;
        e.origin(PLANT[0], PLANT[1], PLANT[2]);
        plant(e, electrics);
        e.origin(0, 0, 0);
        if (electrics && !Boolean.getBoolean("diag.noDashboard")) dashboard(e);
        return e.view(30, 16, 34, 28);
    }

    /**
     * The plant, in its own coordinates (the governed steam engine's: crankshaft along x at y = 3, z = 8; boiler tanks at
     * (5, 4, 13…15)). The fire is a methane firebox of three cells under the boiler, behind a copper crown: six bottles at
     * x = 3 feed a needle valve into its south cell, where the air comes in; the flue leaves its north cell east and rises
     * in a chimney at (7, ·, 13).
     */
    static void plant(Exhibit e, boolean electrics) {
        String st = "steel_1045";
        int y = 3;
        Object[] steam = {"fluid", 7};
        final double LINE = Double.parseDouble(System.getProperty("diag.line", "0.008"));
        // ---- crankshaft, bearings, cams, crank, flywheel (as the governed engine)
        int shaft = e.fill(SHAFT, st, 0, y, 8, 11, y, 8, Dir.EAST, "diameter", 0.012);
        e.name("shaft", shaft);
        e.init(shaft, new Exhibit.Initial(Double.NaN, 14, 0, 0.3)); // running, as the station is found
        e.add(BEARING, st, 0, y, 8, Dir.EAST);
        e.add(BEARING, st, 9, y, 8, Dir.EAST);
        e.fill(BLOCK, st, 0, 0, 8, 0, y - 1, 8, Dir.UP);
        e.fill(BLOCK, st, 9, 0, 8, 9, y - 1, 8, Dir.UP);
        e.add(ECCENTRIC, st, 2, y, 8, Dir.EAST, "throw", 0.012, "phase", 95);
        e.add(ECCENTRIC, st, 3, y, 8, Dir.EAST, "throw", Double.parseDouble(System.getProperty("diag.throw", "0.025")));
        e.add(ECCENTRIC, st, 4, y, 8, Dir.EAST, "throw", 0.012, "phase", -40);
        e.name("flywheel", e.fill(BLOCK, st, 7, y - 1, 7, 7, y + 1, 9, Dir.EAST, "fill", 0.3));
        // ---- cylinder, valves, steam line, throttle on the governor's rod, boiler and its safety valve
        int cyl = e.add(CYLINDER, st, 3, y + 2, 8, Dir.DOWN, with(steam, "bore", Double.parseDouble(System.getProperty("diag.bore", "0.053")), "ring_friction",
                Double.parseDouble(System.getProperty("diag.ring", "4.5"))));
        e.name("cylinder", cyl);
        e.add(BLOCK, st, 3, y + 3, 8, Dir.UP, "fill", 0.3);
        int exhaust = e.add(VALVE, "brass", 2, y + 2, 8, Dir.WEST, with(steam, "bore", 0.010));
        e.name("exhaust", exhaust);
        int inlet = e.add(VALVE, "brass", 4, y + 2, 8, Dir.WEST, with(steam, "bore", LINE, "lap", Double.parseDouble(System.getProperty("diag.lap", "0.78"))));
        e.name("inlet", inlet);
        int elbow = e.add(PIPE, "copper", 5, y + 2, 8, Dir.SOUTH, with(steam, "bore", LINE));
        e.add(BLOCK, st, 5, y + 2, 7, Dir.UP, "fill", 0.2); // blind flange
        int l1 = e.add(PIPE, "copper", 5, y + 2, 9, Dir.SOUTH, with(steam, "bore", LINE));
        int reg = e.add(VALVE, "brass", 5, y + 2, 10, Dir.NORTH, with(steam, "bore", Double.parseDouble(System.getProperty("diag.throttle", "0.004")),
                "plug_travel", Exhibits.GOVERNED_PLUG_TRAVEL));
        e.name("throttle", reg);
        int l2 = e.add(PIPE, "copper", 5, y + 2, 11, Dir.SOUTH, with(steam, "bore", LINE));
        int l3 = e.add(PIPE, "copper", 5, y + 2, 12, Dir.SOUTH, with(steam, "bore", LINE));
        int riser = e.add(PIPE, "copper", 5, y + 2, 13, Dir.UP, with(steam, "bore", 0.012));
        int safety = e.add(VALVE, "brass", 5, y + 3, 13, Dir.UP, with(steam, "bore", 0.006, "relief", 4.5e5));
        e.name("safety", safety);
        // the boiler: three tanks in a row (south from under the steam riser), over a firebox and crown as long
        int boiler = e.fill(TANK, st, 5, y + 1, 13, 5, y + 1, 15, Dir.UP, with(steam, "fill", 0.6, "wall", 0.003, "gauge_range", 6));
        e.name("boiler", boiler);
        // (no steam jacket: one fed from the main, against a block clasping the cylinder, kept it no warmer — what chills
        // its wall is the condenser's vacuum, the water left on it boiling off at 50 °C each exhaust stroke — and its fine
        // pipe held every step of the plant's solution short)
        // everything that holds steam starts at its temperature
        for (int i : new int[]{cyl, exhaust, inlet, elbow, l1}) e.init(i, new Exhibit.Initial(412, 0, 0));
        for (int i : new int[]{reg, l2, l3, riser, safety, boiler, boiler + 1, boiler + 2}) e.init(i, new Exhibit.Initial(418, 0, 0));
        // ---- load: a Prony brake under the flywheel, off (the generator is the station's load; the brake is there to try it)
        e.name("brake", e.add(BRAKE, "oak", 7, y - 2, 8, Dir.UP, "force", Double.parseDouble(System.getProperty("diag.brakeForce", "8"))));
        e.add(BLOCK, st, 7, 0, 8, Dir.UP);
        // ---- the firebox under the boiler: a copper crown, the chamber, the igniter on a mica block
        // What reaches the water is what the flame gives the firebox's walls — convection and the hot gas's radiation,
        // some 500 W from a one-cell chamber of a 2 kW fire, the rest gone up the chimney at ~1500 K: on that the boiler
        // ran down in three minutes, on the heat its crown had stored. Three cells long, the chamber has 2.3 times the
        // wall; the crown is copper (as a locomotive's inner firebox: steel's seventh of copper's conductivity held the
        // walls hot, an alumina tile or firebrick far less). Most of the fire's heat still leaves up the chimney: a crude
        // boiler's efficiency.
        // The fuel and air come in at the south end; the flame stands in the middle cell (each cell is stirred through:
        // the cold mixture coming in at the end is no flame's, as a burner's mixing tube), its gas passing north under
        // the steam riser's end of the boiler and out to the chimney. (The other way round, the boiler's end over the
        // cold mixture took the others' steam back as water, filled, and sent it up the riser into the engine.)
        e.name("crown", e.fill(BLOCK, "copper", 5, y, 13, 5, y, 15, Dir.UP));
        e.name("crownMid", e.named.get("crown") + 1);
        e.name("crownEnd", e.named.get("crown") + 2);
        int box = e.fill(TANK, st, 5, y - 1, 13, 5, y - 1, 15, Dir.UP, "wall", 0.0005);
        e.name("firebox", box);
        e.name("fireboxMid", box + 1);
        e.name("fireboxInlet", box + 2);
        e.name("igniter", e.add(RESISTOR, "alumina", 5, y - 2, 15, Dir.EAST, "resistance", 2.0, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.1, "max_temperature", 1800));
        e.add(BLOCK, "mica", 5, 0, 15, Dir.UP);
        for (int k = 0; k < 3; k++) e.init(box + k, new Exhibit.Initial(1500, 0, 0)); // alight: the igniter has done its work
        for (int k = 0; k < 3; k++) e.init(e.named.get("crown") + k, new Exhibit.Initial(480, 0, 0));
        // air inlet, open at its south end: the gallery burner's, whose draft draws nearly the air its 40 µm needle's
        // methane needs (narrower, a cooling firebox starved itself rich; with less fuel, too lean to burn: below 5 %)
        e.add(PIPE, st, 5, y - 1, 16, Dir.SOUTH, "bore", Double.parseDouble(System.getProperty("diag.airBore", "0.024")));
        // the flue: east out of the chamber's north end, up the chimney (open at the top; a longer flue's cold gas
        // weakened the draft as the fuel first came, and the firebox went out too rich)
        e.add(PIPE, st, 6, y - 1, 13, Dir.EAST, "bore", 0.04);
        int chimney = e.fill(PIPE, st, 7, y - 1, 13, 7, y + 7, 13, Dir.UP, "bore", 0.04, "wall", 0.001);
        e.name("chimney", chimney + 8);
        e.fill(BLOCK, st, 7, 0, 13, 7, y - 2, 13, Dir.UP); // its foot: capped below (open, it would draw air in there)
        // ---- fuel: six bottles in two columns at x = 3, a needle valve straight into the firebox (opened to start the
        // station, shut to stop it; a pipe between them, a cubic centimetre of pure methane squeezed between the choked
        // needle and the firebox, would not restart from a saved world)
        int fuel = e.add(TANK, st, 3, y - 1, 15, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.name("fuel", fuel);
        e.add(TANK, st, 3, y - 2, 15, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.add(TANK, st, 3, 0, 15, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.fill(TANK, st, 3, 0, 14, 3, y - 1, 14, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        int needle = e.add(VALVE, "brass", 4, y - 1, 15, Dir.EAST, "bore", Double.parseDouble(System.getProperty("diag.needleBore", "4e-5"))); // choked: some 40 mg/s, 2 kW of fire
        e.name("needle", needle);
        e.latch(needle); // burning, as the station is found
        // ---- the water circuit is closed: the engine exhausts, and the safety valve blows, into an air-cooled condenser
        // west of the engine; its well feeds a pump on the crankshaft's west end, which returns the water to the boiler
        condenser(e, y, steam);
        // exhaust: level, straight west from the exhaust valve into the condenser's side, above its water (it was led up
        // and over, from a chest on the valve: the water the exhaust brings out of the cylinder fell back into the chest,
        // below the well — some 0.07 g/s, the well's whole reserve in twenty minutes — and nothing could lift it out)
        e.fill(PIPE, "copper", -2, y + 2, 8, 1, y + 2, 8, Dir.EAST, with(steam, "bore", 0.012));
        // the safety valve's outlet: up, west along y + 9 and north into the condenser's top
        e.fill(PIPE, "copper", 5, y + 4, 13, 5, y + 8, 13, Dir.UP, with(steam, "bore", 0.008));
        e.fill(PIPE, "copper", -2, y + 9, 13, 5, y + 9, 13, Dir.EAST, with(steam, "bore", 0.008));
        e.add(BLOCK, st, 6, y + 9, 13, Dir.UP, "fill", 0.2);
        // the overflow: a 0.3 mm orifice in the boiler's south face, at its water line, up into the safety line — the
        // boiler keeps its own level: above the line the pump's surplus water spills back to the condenser (it flashes
        // there), below it a little steam goes the same way (some 0.05 g/s)
        e.name("overflow", e.add(VALVE, "brass", 5, y + 1, 16, Dir.UP, with(steam, "bore", Double.parseDouble(System.getProperty("diag.overflowBore", "0.0003")))));
        e.latch(e.named.get("overflow"));
        e.add(BLOCK, "mica", 5, y, 16, Dir.UP, "fill", 0.2); // its blind foot (mica: beside the crown, steel drew its heat)
        e.fill(PIPE, "copper", 5, y + 2, 16, 5, y + 8, 16, Dir.UP, with(steam, "bore", 0.006));
        e.fill(PIPE, "copper", 5, y + 9, 14, 5, y + 9, 16, Dir.SOUTH, with(steam, "bore", 0.006));
        e.add(BLOCK, st, 5, y + 9, 17, Dir.UP, "fill", 0.2);
        e.fill(PIPE, "copper", -3, y + 9, 9, -3, y + 9, 13, Dir.SOUTH, with(steam, "bore", 0.008));
        e.add(BLOCK, st, -3, y + 9, 14, Dir.UP, "fill", 0.2);
        // and down into the condenser's top (y + 4)
        e.fill(PIPE, "copper", -3, y + 5, 8, -3, y + 9, 8, Dir.UP, with(steam, "bore", 0.008));
        e.add(BLOCK, st, -3, y + 10, 8, Dir.UP, "fill", 0.2);
        feedPump(e, y, steam);
        // ---- lighting up: the igniter on its own bank (45 lead–acid cells, 96 V: 4.6 kW through 2 Ω, the walls glowing
        // in under a minute) through a switch; once the fire burns it keeps itself hot, and the igniter is switched off
        e.name("igniterBank", e.add(BATTERY, "pvc", 10, 0, 14, Dir.EAST, "cells", 45, "capacity", 40));
        e.name("lightUp", e.add(SWITCH, "brass", 13, 0, 14, Dir.EAST));
        // ---- the governor (the governed engine's, bevel-geared off the crankshaft's east end) on the throttle
        e.plots = Math.max(e.plots, 2);
        Exhibits.governor(e, y);
        Router r = new Router(e, 0, 0, 11, 19, 2, 15);
        r.pin("ig+", 11, 0, 14).pin("ig+", 6, y - 2, 15);   // bank + → igniter's east lead
        r.pin("ig-", 4, y - 2, 15).pin("ig-", 12, 0, 14);   // igniter's west lead → switch
        r.pin("igr", 14, 0, 14).pin("igr", 9, 0, 14);       // switch → bank −
        r.insulation("ig+", 800).insulation("ig-", 800);    // glass-fibre: the element's leads run hot
        r.route();
        if (electrics) {
            generator(e, y);
            bank(e);
            electrical(e, y);
        }
    }

    /** The rotor's four pole positions (y, z) about the generator's axis (·, y, 5), a quarter turn apart. */
    static int[][] poles(int y) {
        return new int[][]{{y + 2, 5}, {y, 7}, {y - 2, 5}, {y, 3}};
    }

    /** Generator coil i's (east 0–3 at x 14, west 4–7 at x 12, at pole i % 4) two leads' cells: the lower first. */
    static int[][] coilLeads(int y, int i) {
        int x = i < 4 ? 14 : 12, yy = poles(y)[i % 4][0], z = poles(y)[i % 4][1];
        return i % 2 == 0 ? new int[][]{{x, yy, z - 1}, {x, yy, z + 1}} : new int[][]{{x, yy - 1, z}, {x, yy + 1, z}};
    }

    /**
     * The generator (plant coordinates): a 48-tooth gear on the crankshaft at x = 10 drives a 12-tooth pinion three cells
     * north (4 : 1), on a shaft along x at (·, y, 5) in two bearings. An axial-flux machine: an aluminium disc at x = 13
     * carries four NdFeB magnets set into it a quarter turn apart, two cells out, alternately north and south east; on
     * each side of it, a cell away, a coil faces each magnet's path (x = 12 and x = 14) — eight coils of 510 turns of
     * 1.19 mm wire, which fill 55 % of their windings' space (the most a winding takes). Each magnet's field passes
     * through the coil on either side alike, and every coil's flux reverses at once each quarter turn: their EMFs in phase,
     * the eight in series (those at the side poles, leads along y, the other way round) make some 70 V at the peak, 1.25
     * times the bank's charging voltage with the bridge's two drops. Through a bridge of four diodes it charges the
     * station's bank (see bank). On a bench at its 56 rad/s it gave the bank 37 W of the 53 W its shaft took: 7.5 Ω of
     * copper and the diodes take the rest. (As first built — two magnets and two coils on the disc's face, geared 2 : 1
     * into a 5 V battery — it made 0.4 W: its EMF barely passed the battery's voltage.)
     */
    static void generator(Exhibit e, int y) {
        String st = "steel_1045", al = "aluminium_6061";
        // the gear pair, three cells apart (their pitch radii add to 3/16 m): -Ddiag.genTeeth=<gear>:<pinion> to try others
        String[] teeth = System.getProperty("diag.genTeeth", "48:12").split(":");
        int zg = Integer.parseInt(teeth[0]), zp = Integer.parseInt(teeth[1]);
        double rg = 3.0 / 16 * zg / (zg + zp), rp = 3.0 / 16 * zp / (zg + zp);
        e.add(GEAR, st, 10, y, 8, Dir.EAST, "teeth", zg, "pitch_radius", rg, "thickness", 0.01);
        int gs = e.fill(SHAFT, st, 10, y, 5, 16, y, 5, Dir.EAST, "diameter", 0.012);
        e.name("genShaft", gs);
        e.add(GEAR, st, 10, y, 5, Dir.EAST, "teeth", zp, "pitch_radius", rp, "thickness", 0.01);
        e.add(BEARING, st, 11, y, 5, Dir.EAST);
        e.fill(BLOCK, st, 11, 0, 5, 11, y - 1, 5, Dir.UP);
        e.add(BEARING, st, 16, y, 5, Dir.EAST);
        e.fill(BLOCK, st, 16, 0, 5, 16, y - 1, 5, Dir.UP);
        e.init(gs, new Exhibit.Initial(Double.NaN, -14.0 * zg / zp, 0, 0)); // turning with the engine (the other way: a spur pair)
        // the rotor: a disc of plates in the y–z plane, the magnets set into it (the plate at its middle holds it on the
        // shaft: a thin shaft is fixed only to what it passes through)
        int[][] poles = poles(y);
        for (int yy = y - 2; yy <= y + 2; yy++)
            for (int z = 3; z <= 7; z++) {
                boolean pole = false;
                for (int[] p : poles) pole |= p[0] == yy && p[1] == z;
                if (!pole) e.add(BLOCK, al, 13, yy, z, Dir.EAST, "fill", 0.3);
            }
        for (int k = 0; k < 4; k++) e.add(MAGNET, "ndfeb_n42", 13, poles[k][0], poles[k][1], k % 2 == 0 ? Dir.EAST : Dir.WEST);
        // the coils (those at the side poles with their leads along y: along z they would lie against the shaft)
        int turns = Integer.getInteger("diag.genTurns", 510);
        double wire = Double.parseDouble(System.getProperty("diag.genWire", "1.19e-3"));
        for (int i = 0; i < 8; i++)
            e.name("coil" + i, e.add(COIL, "copper", i < 4 ? 14 : 12, poles[i % 4][0], poles[i % 4][1], Dir.EAST, "turns", turns,
                    "wire_diameter", wire, "lead_axis", i % 2 == 0 ? 2 : 1));
        e.name("coilA", e.named.get("coil0"));
        // the bridge (current west → east through each diode)
        for (int k = 0; k < 4; k++) e.name("d" + (k + 1), e.add(DIODE, "silicon", 17, 1, 9 + 2 * k, Dir.EAST));
        // the station's own battery, for its electronics: four nickel–metal hydride cells, 5 V, kept charged from the bank
        // (-Ddiag.batteryCharge=<fraction>: it starts at that charge, past 1 overcharged — the regulator's case)
        e.name("battery", e.add(BATTERY, "pvc", 19, 1, 6, Dir.SOUTH, "chemistry", 1, "cells", 4, "capacity", 5,
                "charge", Double.parseDouble(System.getProperty("diag.batteryCharge", "1"))));
        // (wired with the electronics: see electrical)
    }

    /** The bank's strings: how many, each of BANK_CELLS cells end to end (two walls of six, one above another). */
    public static final int BANK_STRINGS = 12, BANK_CELLS = 24;

    /**
     * The bank (plant coordinates): the station's store, 288 lead–acid cells in blocks, north of the electronics. Each
     * block is one 2 V cell of 8 A·h — 16 Wh in its 6.25 cm cube, 66 Wh a litre, a flooded stationary cell's — and 24
     * end to end along x (20…43) make a string of 48 V; six strings stacked up make a wall (a battery's leads are on its
     * end faces and its north and south faces: stacked, they do not touch), and two walls a cell apart (z −3 and −5) the
     * bank: 12 strings, 96 A·h at 48 V, 4.6 kWh — two days of the generator's output. Copper buses of 4 mm wire down
     * each end join the strings' ends. It starts at -Ddiag.bankCharge (0.6).
     * <p>
     * Its 288 batteries are solved as twelve: a string of alike batteries end to end, nothing else at its joints, is one
     * element of all their cells (exact: they carry one current; see AssemblyCompiler's battery strings).
     */
    static void bank(Exhibit e) {
        double charge = Double.parseDouble(System.getProperty("diag.bankCharge", "0.6"));
        for (int wall = 0; wall < 2; wall++)
            for (int s = 0; s < BANK_STRINGS / 2; s++)
                for (int k = 0; k < BANK_CELLS; k++) {
                    int id = e.add(BATTERY, "pvc", 20 + k, s, -3 - 2 * wall, Dir.EAST, "cells", 1, "capacity", 8, "charge", charge);
                    if (wall == 0 && s == 0 && k == 0) e.name("bank", id);
                    if (wall == 0 && s == 0 && k == BANK_CELLS - 1) e.name("bankLast", id);
                }
        for (int x : new int[]{19, 20 + BANK_CELLS}) {
            e.fill(WIRE, "copper", x, 0, -3, x, BANK_STRINGS / 2 - 1, -3, Dir.UP, "diameter", 0.004);
            e.fill(WIRE, "copper", x, 0, -5, x, BANK_STRINGS / 2 - 1, -5, Dir.UP, "diameter", 0.004);
            e.add(WIRE, "copper", x, 0, -4, Dir.UP, "diameter", 0.004);
        }
    }

    /**
     * The condenser (plant coordinates): a column of copper tanks at (−3, y − 1…y + 4, 8) with copper fins on three
     * sides, two deep; its foot is the hot well, above the feed pump (a feed pump sits below its well, its suction
     * flooded: sucking the well's water up to it, water at its boiling point flashed to steam in the pump — cavitation).
     * The column stands low, its well under the engine's exhaust valve, so that everything that comes out of the
     * cylinder runs down into it: the exhaust enters level at y + 2, just above the water; the safety valve's steam and
     * the overflow come down into the top. It starts cool, so its pressure is water's vapour pressure there: the engine
     * exhausts into a partial vacuum.
     */
    static void condenser(Exhibit e, int y, Object[] steam) {
        String cu = "copper";
        // (the circuit's water beyond the boiler's: what stands in the overflow's riser and the pipes, and some to spare)
        int well = e.add(TANK, cu, -3, y - 1, 8, Dir.UP, with(steam, "fill", 0.7, "wall", 0.002));
        e.name("hotwell", well);
        e.init(well, new Exhibit.Initial(330, 0, 0));
        int top = 0;
        // the cells over it hold water too: the circuit's reserve (condensate stands in the steam pipes' low points, behind
        // the safety valve and in the overflow's riser before each spills on — some 100 g), its level below the exhaust's
        for (int k = y; k <= y + 4; k++) {
            int t = e.add(TANK, cu, -3, k, 8, Dir.UP, with(steam, "wall", 0.002, "fill", k <= y + 1 ? 0.7 : 0));
            e.init(t, new Exhibit.Initial(330, 0, 0));
            top = t;
        }
        e.name("condenser", top);
        // its legs: a steel post under the well's west fins
        e.fill(BLOCK, "steel_1045", -4, 0, 8, -4, y - 2, 8, Dir.UP);
        // fins: thin copper plates on the west, north and south faces, two deep, beside all but the top cell (with the
        // three-cell firebox's steam it holds some 0.45 bar, half an atmosphere's vacuum; a third row out added
        // little: what it rejects is set by the air on its fins)
        for (int k = y - 1; k <= y + 3; k++) {
            for (int[] f : new int[][]{{-4, k, 8}, {-5, k, 8}, {-3, k, 7}, {-3, k, 6}, {-3, k, 9}, {-3, k, 10}}) {
                e.init(e.add(BLOCK, cu, f[0], f[1], f[2], Dir.UP, "fill", 0.2), new Exhibit.Initial(320, 0, 0));
            }
        }
    }

    /**
     * The feed pump (plant coordinates): a 12 mm cylinder hung under an eccentric on the crankshaft's west end, between
     * two check valves — water in from the hot well through the west one as the piston rises, out through the north one
     * as it falls, round under the engine and into the boiler's west face. It moves some 2.5 cm³ a second, several
     * times what the boiler boils away, so the boiler fills while the well has water: the water divides itself
     * between them, and the circuit keeps its own level.
     */
    static void feedPump(Exhibit e, int y, Object[] steam) {
        String st = "steel_1045";
        // (as a player lays them: a part editor fills no pipe — the lines start empty, and the pump primes them)
        Object[] water = with(steam, "bore", 0.010);
        // (the shortest throw a crank takes, 1 mm: some 0.5 g of water a second, a little more than the boiler boils away;
        // the overflow returns the rest — with more, the boiler spent its heat warming water only to spill it)
        e.name("feedCrank", e.add(ECCENTRIC, st, -1, y, 8, Dir.EAST, "throw", 0.001));
        // (a deep clearance over the piston, 3 cm: water barely yields, and a chamber of 2 cm³ at the top of the stroke held
        // the whole plant's solution to fine steps; it changes nothing the pump delivers)
        // It hangs under the crank, head down, so that the well can stand over it and still below the engine's exhaust.
        int pump = e.add(CYLINDER, st, -1, y - 2, 8, Dir.UP, with(steam, "bore", 0.012, "ring_friction", 0.5));
        e.name("feedPump", pump);
        e.add(BLOCK, st, -1, y - 3, 8, Dir.UP, "fill", 0.3); // its head
        // suction: down from under the well into a pipe cell and east into a check valve on the pump's west side, the
        // well's water standing over the valve (round through a small tank, the water there flashed and refilled at every
        // change of the well's level, a jolt the solution could not take); the pipe's west end is closed by the
        // condenser's leg
        // (at the well's temperature, so at its pressure: started cold, the full pipes stood at water's vapour pressure at
        // 20 °C, a fifth of the well's, and the well's water rushed into them)
        e.init(e.add(PIPE, st, -3, y - 2, 8, Dir.EAST, water), new Exhibit.Initial(330, 0, 0));
        e.name("suction", e.add(VALVE, "brass", -2, y - 2, 8, Dir.EAST, with(steam, "bore", 0.010, "relief", 500)));
        e.init(e.named.get("suction"), new Exhibit.Initial(330, 0, 0));
        // delivery: the north check valve → north, east under the engine, south, east and up into the boiler
        e.name("delivery", e.add(VALVE, "brass", -1, y - 2, 7, Dir.NORTH, with(steam, "bore", 0.010, "relief", 3000)));
        e.add(PIPE, st, -1, y - 2, 6, Dir.NORTH, water);
        e.add(PIPE, st, -1, 1, 5, Dir.EAST, water);
        e.add(BLOCK, st, -2, 1, 5, Dir.UP, "fill", 0.2);
        e.add(PIPE, st, 0, 1, 5, Dir.EAST, water);
        e.fill(PIPE, st, 1, 1, 5, 1, 1, 11, Dir.SOUTH, water);
        e.add(BLOCK, st, 1, 1, 4, Dir.UP, "fill", 0.2);
        e.add(BLOCK, st, 1, 1, 12, Dir.UP, "fill", 0.2);
        e.fill(PIPE, st, 2, 1, 11, 3, 1, 11, Dir.EAST, water);
        e.fill(PIPE, st, 4, 1, 11, 4, y, 11, Dir.UP, water);
        e.add(BLOCK, st, 4, 0, 11, Dir.UP, "fill", 0.2);
        e.fill(PIPE, st, 4, y + 1, 11, 4, y + 1, 12, Dir.SOUTH, water);
        e.add(BLOCK, st, 4, y + 1, 10, Dir.UP, "fill", 0.2);
        // the boiler's own check valve, right at its face (a water-filled pipe there, its far end warmed by the boiler,
        // flashed and refilled between strokes)
        e.name("feedCheck", e.add(VALVE, "brass", 4, y + 1, 13, Dir.EAST, with(steam, "bore", 0.010, "relief", 3000)));
        e.add(BLOCK, st, 3, y + 1, 13, Dir.UP, "fill", 0.2);
    }

    /**
     * The station's electronics (plant coordinates, on the floor east of the generator, all on its battery): a charge
     * regulator and a revolution counter; and the bank's (see bank): its charge regulator, the dropper that keeps the
     * station's own battery charged from it, and the divider the dashboard reads it by.
     * <p>
     * The station's battery is charged from the bank's 49 V through 560 Ω: some 78 mA, against the 63 mA its
     * electronics draw (the counter's infrared LED 25 mA, the screens 5 mA, the reference 3 mA, the chips and LEDs the
     * rest). A dropping resistor: 3.4 W lost in it, a tenth of what the generator brings — a switching converter would
     * take far less — and the shunt regulator after it holds the voltage. With the engine stopped the dashboard still
     * runs, on the bank.
     * <p>
     * The bank's regulator, as the battery's: an op-amp compares 10/253 of the bank's voltage (243 kΩ over 10 kΩ, less
     * the hysteresis' pull) with the same three diodes' 2.10 V (0.70 V each at 3.5 mA). Past about 53.4 V (2.23 V a
     * cell, about a lead–acid cell's float voltage: the bank some 12 % overcharged, gassing) it switches on a MOSFET
     * that puts a 56 Ω dump load across the bank, 1 A — more than the generator brings — until the bank has fallen some
     * 0.14 V (its 10 MΩ of hysteresis: 1 MΩ, 1.4 V, kept it dumping for hours). Overcharged to 114 %, it dumps 0.97 A,
     * the load warming 7 K in its first minute. (Over 270 kΩ it tripped at 2.4 V a cell, the charging voltage under a
     * strong current: on this generator's trickle, the bank overcharged by a third.)
     * <p>
     * The regulator: an op-amp compares 12/34 of the battery's voltage (22 kΩ over 12 kΩ) with three silicon diodes'
     * forward drop (through 1 kΩ: some 4 mA, 0.69 V each, 2.06 V); past about 5.85 V (1.46 V a cell, where a nickel–metal
     * hydride cell's charge ends: over 10 kΩ, as first built, 6.7 V — overcharged by more than a tenth, which the
     * generator's 4 mA surplus of old never reached and its 220 mA reaches in hours) its output switches on a MOSFET
     * that puts the station's lamp and a 10 Ω dump load across the battery, some 0.6 A, more than the generator brings,
     * so the battery runs back down (1 MΩ from the output back to its input holds it on until the battery has fallen a
     * tenth of a volt: no chatter).
     * <p>
     * The counter: a steel vane on the crankshaft cuts an infrared beam once a turn; the photodiode's current through
     * 2.2 MΩ holds a Schmitt trigger's input up while the beam is clear, and each cut clocks the first of three
     * toggling flip-flops, each of which clocks the next: their green LEDs count the engine's turns in binary.
     */
    static void electrical(Exhibit e, int y) {
        String st = "steel_1045";
        // ---- the regulator
        e.name("regulator", e.add(OPAMP, "silicon", 23, 1, 8, Dir.EAST));
        e.add(RESISTOR, "alumina", 21, 1, 10, Dir.EAST, "resistance", 22_000);   // battery + → sense
        e.add(RESISTOR, "alumina", 21, 1, 12, Dir.EAST, "resistance", 12_000);   // sense → ground
        e.add(RESISTOR, "alumina", 25, 1, 6, Dir.EAST, "resistance", 1e6);       // output → sense (hysteresis)
        e.add(RESISTOR, "alumina", 21, 1, 14, Dir.EAST, "resistance", 1_000);    // battery + → reference
        e.name("ref1", e.add(DIODE, "silicon", 28, 1, 12, Dir.EAST));            // the reference: three diodes down
        e.add(DIODE, "silicon", 28, 1, 14, Dir.EAST);
        e.add(DIODE, "silicon", 31, 1, 14, Dir.EAST);
        e.name("shunt", e.add(TRANSISTOR, "silicon", 27, 1, 9, Dir.WEST, "type", 2, "transconductance", 2.0));
        e.name("lamp", e.add(LED, "silicon", 24, 1, 4, Dir.EAST, "wavelength", 590, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", 27, 1, 4, Dir.EAST, "resistance", 100);       // the lamp's resistor
        e.name("dump", e.add(RESISTOR, "alumina", 27, 1, 2, Dir.EAST, "resistance", 10, "surface_area", 0.002, "mass", 0.05));
        // ---- the counter: the vane on the crankshaft (between the flywheel and the bearing), the beam across its path
        e.fill(BLOCK, st, 8, y + 1, 8, 8, y + 2, 8, Dir.UP, "fill", 0.3);
        e.name("irled", e.add(LED, "silicon", 8, 1, 10, Dir.EAST, "wavelength", 850, "efficiency", 0.3, "beam", 20));
        e.name("pd", e.add(PHOTODIODE, "silicon", 8, 5, 10, Dir.EAST, "face", 0));
        e.add(RESISTOR, "alumina", 21, 1, 1, Dir.EAST, "resistance", 150);       // the IR LED's current
        e.add(RESISTOR, "alumina", 24, 1, 0, Dir.EAST, "resistance", 2.2e6);     // the photodiode's load
        e.name("schmitt", e.add(LOGIC, "silicon", 34, 1, 2, Dir.EAST, "function", 7));
        for (int k = 0; k < 3; k++) {
            e.name("bit" + k, e.add(LOGIC, "silicon", 38 + 4 * k, 1, 2, Dir.EAST, "function", 8));
            e.name("bitLed" + k, e.add(LED, "silicon", 38 + 4 * k, 1, 6, Dir.EAST, "wavelength", 565, "face", 3, "beam", 20));
            e.add(RESISTOR, "alumina", 38 + 4 * k, 1, 8, Dir.EAST, "resistance", 470);
        }
        // ---- the bank's: its regulator, the dropper to the station's battery, the dashboard's divider
        e.add(RESISTOR, "alumina", 51, 1, 2, Dir.EAST, "resistance", 243_000);   // bank + → its sense
        e.name("bankSense", e.add(RESISTOR, "alumina", 51, 1, 4, Dir.EAST, "resistance", 10_000));    // its sense → ground
        e.add(RESISTOR, "alumina", 51, 1, 6, Dir.EAST, "resistance", 10e6);      // output → sense (hysteresis)
        e.name("bankRegulator", e.add(OPAMP, "silicon", 54, 1, 4, Dir.EAST));
        e.name("bankShunt", e.add(TRANSISTOR, "silicon", 57, 1, 8, Dir.WEST, "type", 2, "transconductance", 2.0));
        e.name("bankDump", e.add(RESISTOR, "alumina", 57, 1, 11, Dir.EAST, "resistance", 56, "surface_area", 0.05, "mass", 0.5));
        e.name("dropper", e.add(RESISTOR, "alumina", 51, 1, 9, Dir.EAST, "resistance", 560, "surface_area", 0.01, "mass", 0.05));
        e.add(RESISTOR, "alumina", 51, 1, 12, Dir.EAST, "resistance", 90_000);   // bank + → the dashboard's line
        e.add(RESISTOR, "alumina", 51, 1, 14, Dir.EAST, "resistance", 10_000);   // that line → ground
        Router r = new Router(e, 6, 0, -2, 60, y + 4, 15);
        // what turns: the flywheel and vane, the 40-tooth gear and the crankshaft's bevel, the generator's rotor
        r.keepClear(7, 2, 7, 7, 4, 9);   // the flywheel (its corners reach 1.4 cells from the axis)
        r.keepClear(8, 1, 7, 8, 5, 9);   // the vane (its tip 2 cells out)
        r.keepClear(8, 2, 6, 8, 4, 10);
        r.keepClear(10, 0, 5, 11, y + 3, 11);
        r.keepClear(13, y - 3, 2, 13, y + 3, 8);     // the rotor (its corners reach 2.8 cells from the axis)
        r.keepClear(20, 0, -2, 43, 5, -2);           // the bank's face: its batteries' + leads
        // the generator: its eight coils in series (each the way round that adds its EMF), the ends to the bridge, the
        // bridge to the bank
        String prev = "ac1";
        for (int i = 0; i < 8; i++) {
            int[][] l = coilLeads(y, i);
            int[] in = i % 2 == 0 ? l[0] : l[1], out = i % 2 == 0 ? l[1] : l[0];
            String next = i == 7 ? "ac2" : "s" + i;
            r.pin(prev, in[0], in[1], in[2]).pin(next, out[0], out[1], out[2]);
            prev = next;
        }
        r.pin("ac1", 16, 1, 9).pin("ac1", 18, 1, 13);       // → D1 anode, D3 cathode
        r.pin("ac2", 16, 1, 11).pin("ac2", 18, 1, 15);      // → D2 anode, D4 cathode
        r.pin("bpos", 18, 1, 9).pin("bpos", 18, 1, 11).pin("bpos", 44, 0, -2);   // D1, D2 cathodes → the bank's +
        r.pin("gnd", 19, 0, -2);                                                  // the bank's −
        r.pin("vdd", 19, 1, 7);                                                   // the battery's +
        r.pin("bpos", 50, 1, 2).pin("bsense", 52, 1, 2).pin("bsense", 50, 1, 4).pin("gnd", 52, 1, 4);
        r.pin("bout", 50, 1, 6).pin("bsense", 52, 1, 6);
        r.pin("bsense", 53, 1, 4).pin("bout", 55, 1, 4).pin("vdd", 54, 1, 5).pin("gnd", 54, 1, 3).pin("ref", 54, 0, 4);
        r.pin("bdrain", 56, 1, 8).pin("gnd", 58, 1, 8).pin("bout", 57, 1, 7);
        r.pin("bdrain", 56, 1, 11).pin("bpos", 58, 1, 11);
        r.pin("bpos", 50, 1, 9).pin("vdd", 52, 1, 9);
        r.pin("bpos", 50, 1, 12).pin("bline", 52, 1, 12).pin("bline", 50, 1, 14).pin("gnd", 52, 1, 14);
        r.insulation("bpos", 500).insulation("bdrain", 500); // (PTFE: the dump load's leads run hot)
        // (D3, D4 anodes: the bridge's return, through a 0.1 Ω shunt beside them to ground — the generator gauge on the
        // dashboard senses its two ends; the current itself stays here, its path as short as before. Its ground end is
        // a bare copper junction, which joins the ground's wire and the gauge's sense wire of another colour)
        e.name("genShunt", e.add(RESISTOR, "alumina", 14, 1, 15, Dir.EAST, "resistance", 0.1, "surface_area", 0.002));
        e.add(WIRE, "copper", 13, 1, 15, Dir.UP);
        r.keepClear(12, 1, 15, 12, 1, 15).keepClear(13, 0, 15, 13, 0, 15);
        r.pin("ret", 16, 1, 13).pin("ret", 16, 1, 15).pin("ret", 15, 1, 15).pin("gnd", 19, 1, 5);   // D3, D4 anodes, the shunt; battery −
        r.pin("gnd", 13, 1, 14).pin("rref", 13, 2, 15);                                               // at the junction
        r.pin("vdd", 20, 1, 10).pin("vdd", 20, 1, 14).pin("vdd", 23, 1, 9).pin("vdd", 23, 1, 4).pin("vdd", 26, 1, 2)
                .pin("vdd", 20, 1, 1).pin("vdd", 9, 5, 10).pin("vdd", 34, 1, 3);
        r.pin("gnd", 22, 1, 12).pin("gnd", 23, 1, 7).pin("gnd", 28, 1, 9).pin("gnd", 32, 1, 14)
                .pin("gnd", 9, 1, 10).pin("gnd", 25, 1, 0).pin("gnd", 34, 1, 1);
        r.pin("sense", 22, 1, 10).pin("sense", 20, 1, 12).pin("sense", 22, 1, 8).pin("sense", 24, 1, 6);
        r.pin("ref", 22, 1, 14).pin("ref", 27, 1, 12).pin("ref", 23, 0, 8);
        r.pin("r12", 29, 1, 12).pin("r12", 27, 1, 14);
        r.pin("r23", 29, 1, 14).pin("r23", 30, 1, 14);
        r.pin("regOut", 24, 1, 8).pin("regOut", 26, 1, 6).pin("regOut", 27, 1, 8);
        r.pin("lampK", 25, 1, 4).pin("lampK", 26, 1, 4);
        r.pin("drain", 28, 1, 4).pin("drain", 28, 1, 2).pin("drain", 26, 1, 9);
        r.pin("irA", 22, 1, 1).pin("irA", 7, 1, 10);
        r.pin("beam", 7, 5, 10).pin("beam", 23, 1, 0).pin("beam", 33, 1, 2);
        // the Schmitt's output clocks the first stage; each stage's Q̄ goes back to its own D (it toggles) and on to
        // the next stage's clock (a ripple counter)
        r.pin("clk0", 35, 1, 2).pin("clk0", 38, 0, 2);
        for (int k = 0; k < 3; k++) {
            int x = 38 + 4 * k;
            r.pin("vdd", x, 1, 3).pin("gnd", x, 1, 1);
            r.pin("qb" + k, x, 2, 2).pin("qb" + k, x - 1, 1, 2);
            if (k < 2) r.pin("qb" + k, x + 4, 0, 2);
            r.pin("q" + k, x + 1, 1, 2).pin("q" + k, x - 1, 1, 6);
            r.pin("ledK" + k, x + 1, 1, 6).pin("ledK" + k, x - 1, 1, 8);
            r.pin("gnd", x + 1, 1, 8);
        }
        // the dashboard's taps (see dashboard): the supply, ground, the bridge's return and the counter's pulses, each
        // to the south edge on its ribbon colour, then down onto the trunk
        r.colour("vdd", DashboardExhibits.VDD).colour("gnd", DashboardExhibits.GND).colour("ret", DashboardExhibits.RETURN)
                .colour("rref", DashboardExhibits.RETURN_REF).colour("clk0", DashboardExhibits.PULSE).colour("bline", DashboardExhibits.BANK);
        // (each at a cell beside no component: single-cell parts bring their leads out on their sides too, and a tap at
        // x 28 touched the regulator's second reference diode, shorting its reference to the bridge's return)
        int[][] taps = {{20, DashboardExhibits.VDD}, {24, DashboardExhibits.GND}, {26, DashboardExhibits.RETURN_REF}, {30, DashboardExhibits.RETURN},
                {32, DashboardExhibits.PULSE}, {36, DashboardExhibits.BANK}};
        String[] tapNets = {"vdd", "gnd", "rref", "ret", "clk0", "bline"};
        for (int k = 0; k < taps.length; k++) r.freePin(tapNets[k], taps[k][0], 1, 15);
        r.route();
        // (over the top, clear of the routed wiring: insulated wires of one colour side by side splice, and a net the
        // router coloured alike lay at the south edge — a drop beside it shorted it to the tap, the regulator's sense to
        // the supply, and the regulator dumped)
        for (int[] t : taps) {
            e.add(INSULATED_WIRE, "copper", t[0], 1, 16, Dir.UP, "color", t[1]);
            e.add(INSULATED_WIRE, "copper", t[0], 1, 17, Dir.UP, "color", t[1]);
            e.add(INSULATED_WIRE, "copper", t[0], 0, 17, Dir.UP, "color", t[1]);
        }
    }

    /** The storage gauge's constant: 1000 × its diode's drop, set from its settled readings (see DashboardTest). */
    static final int STORAGE_K = 645;
    /** The power gauge's constant: 0.5 · 1023² / (10 V_dd²), set from its settled readings (see DashboardTest). */
    static final int POWER_K = 1_652;
    /** Gauge k's label, as its card shows it (for reading the screens back). */
    public static String gaugeLabel(int k) {
        return GAUGES[k].label();
    }

    /** Gauge k's decimal places (its card's value is its chip's value over 10 to that). */
    public static int gaugeDecimals(int k) {
        return GAUGES[k].dp();
    }

    /** The dashboard's gauges, west to east (see {@link #dashboard}). */
    static final DashboardExhibits.Gauge[] GAUGES = {
            // the bottles' gauge pressure: 0–250 bar on 10–90 % of the supply
            // (its trend a reading a minute — 30 of them, at one each 2 s: two hours across; an alarm below 50 bar)
            new DashboardExhibits.Gauge("fuel", "FUEL BOTTLES", "BAR", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.FUEL, 0,
                    0, 102, 782, 0, 0, 1, 0, 12, 500, 0, 30),
            // the boiler's gauge pressure: 0–6 bar
            // (an alarm above 4.4 bar: near the safety valve's lift)
            new DashboardExhibits.Gauge("boiler", "BOILER PRESSURE", "BAR", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.BOILER, 0,
                    0, 102, 188, 0, 0, 2, 0, 51, 0, 440, 1),
            // turns a minute from the counter's pulses, one a turn: 60 × 10 kHz / clocks between them
            // (an alarm below 90 rpm: the engine slowing to a stall)
            new DashboardExhibits.Gauge("engine", "ENGINE SPEED", "RPM", DashboardExhibits.FrontEnd.PULSE, DashboardExhibits.PULSE, 0,
                    2, 0, 0, 0, 600_000, 0, 0, 154, 90, 0, 1),
            // the condenser's absolute pressure: gauge −100–0 kPa, plus the world's air pressure above 100 kPa (2.8 kPa)
            // (an alarm above 80 kPa: its vacuum lost)
            new DashboardExhibits.Gauge("condenser", "CONDENSER", "KPA", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.CONDENSER, 0,
                    0, 102, 313, 28, 0, 1, 0, 31, 0, 800, 1),
            // the generator's current: 0.1 Ω, gain 50 (200 mA a volt), on a 5.6 V supply: 1.095 mA a code (1.12 A full
            // scale); its bar full at 1 A
            new DashboardExhibits.Gauge("generator", "GENERATOR", "MA", DashboardExhibits.FrontEnd.CURRENT, DashboardExhibits.RETURN, DashboardExhibits.RETURN_REF,
                    0, 0, 280, 0, 0, 0, 0, 31),
            // the bank's state of charge, from its voltage: its line (a tenth of it) read against a diode, k = 1000 V_d
            // (hundredths of a volt), and from 46.68 V flat (24 cells at 1.945 V, Vinal's rule at the acid's SG 1.10) to
            // 51.00 V full (2.125 V): 100 % in 4.32 V; its bar full at 100 %, an alarm below 20 %
            new DashboardExhibits.Gauge("storage", "STORAGE", "%", DashboardExhibits.FrontEnd.RATIO, DashboardExhibits.BANK, 0,
                    5, 4668, 59, 0, STORAGE_K, 0, 0, 307, 20, 0, 1),
            // the power into the bank: the generator's current (the shunt, as its gauge's) times the bank's voltage (its
            // line): k = 0.5 · 1023² / (10 V_dd²) in tenths of a watt; its bar full at 50 W
            new DashboardExhibits.Gauge("power", "GENERATOR POWER", "W", DashboardExhibits.FrontEnd.POWER_BANK, DashboardExhibits.RETURN,
                    DashboardExhibits.RETURN_REF, 4, 0, 0, 0, POWER_K, 1, 0, 61, 0, 0, 1, DashboardExhibits.BANK)};
    /**
     * The gauge blocks' x (each at z 19, its screen at x − 2…x + 1, z 20): a block apart, so that each screen is its own
     * (panels side by side make one screen).
     */
    static final int[] GAUGE_X = {18, 23, 28, 33, 38, 43, 48};

    /**
     * The dashboard (station coordinates), south of the plant: six full-size screens of 4 × 2 blocks facing south, and
     * behind each, in one block, a gauge of miniature electronics (DashboardExhibits.gaugeBlock) driving it across the
     * block's face. Behind the gauges a full-size ribbon cable runs east–west at z 18, and each gauge takes from its
     * north face the conductors it needs, by colour: the station's supply and ground, the counter's pulses and the
     * generator's return (tapped from the electronics), and three pressure transducers' outputs — on the boiler, on
     * the condenser and on a fuel bottle — wired to it here, each transducer powered from the ribbon.
     */
    static void dashboard(Exhibit e) {
        for (int x = 16; x <= 50; x++) {
            int rb = e.add(RIBBON, "copper", x, 0, 18, Dir.EAST);
            if (x == 16) e.name("trunk", rb);
        }
        String ss = "stainless_304";
        e.name("boilerSensor", e.add(PRESSURE_SENSOR, ss, 16, 4, 15, Dir.EAST, "pmin", 0, "pmax", 6e5, "lead_axis", 1));
        e.name("condenserSensor", e.add(PRESSURE_SENSOR, ss, 7, 7, 9, Dir.SOUTH, "pmin", -1e5, "pmax", 0, "lead_axis", 0));
        e.name("fuelSensor", e.add(PRESSURE_SENSOR, ss, 12, 2, 15, Dir.WEST, "pmin", 0, "pmax", 250e5, "lead_axis", 2));
        Router r = new Router(e, 0, 0, 7, 45, 9, 17);
        r.keepClear(17, 0, 0, 30, 9, 13);   // the engine, governor and generator: what turns
        r.keepClear(8, 0, 16, 8, 1, 16);    // the station's sign
        r.pin("vdd2", 16, 5, 15).pin("gnd2", 16, 3, 15).pin("pB", 17, 4, 15);   // the boiler's transducer
        r.pin("vdd2", 8, 7, 9).pin("gnd2", 6, 7, 9).pin("pC", 7, 7, 10);         // the condenser's
        r.pin("vdd2", 12, 2, 16).pin("gnd2", 12, 2, 14).pin("pF", 11, 2, 15);    // the fuel bottle's
        // onto the trunk, each against it from the north on its colour
        r.freePin("vdd2", 19, 0, 17).freePin("gnd2", 21, 0, 17).freePin("pB", 23, 0, 17).freePin("pC", 25, 0, 17).freePin("pF", 27, 0, 17);
        r.colour("vdd2", DashboardExhibits.VDD).colour("gnd2", DashboardExhibits.GND).colour("pB", DashboardExhibits.BOILER)
                .colour("pC", DashboardExhibits.CONDENSER).colour("pF", DashboardExhibits.FUEL);
        r.route();
        // the gauges and their screens
        for (int k = 0; k < GAUGES.length; k++) {
            int x = GAUGE_X[k];
            e.embed(x, 0, 19, DashboardExhibits.gaugeBlock(GAUGES[k]));
            for (int y = 0; y <= 1; y++) {
                for (int dx = -2; dx <= 1; dx++) {
                    int sc = e.add(DISPLAY, "glass", x + dx, y, 20, Dir.SOUTH);
                    if (y == 0 && dx == 0) e.name("screen" + k, sc);
                }
            }
        }
    }
}
