package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static dev.emergence.core.demo.Exhibits.with;

/**
 * The power station: one machine of many disciplines, every part of it doing a job, built as a player builds — in
 * its own world, apart from the gallery. Methane burns in a firebox under a boiler; the steam drives an engine held to
 * its speed by Watt's flyball governor; the engine turns a generator whose rectified output charges the station's
 * battery; and that battery runs the electronics that keep the boiler at its pressure, opening and shutting the fuel
 * through a solenoid valve, and count the engine's turns.
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
        Exhibit e = new Exhibit("station", "Power station",
                List.of("POWER STATION", "Fire, steam, a", "governed engine and", "its own electricity"),
                "A steam power station that runs itself, every part of it a part a player could place. Methane from "
                        + "six 200 bar bottles burns in a firebox under the boiler, drawing its air by the chimney's draft; "
                        + "an alumina tile between the flame and the water passes the boiler some 500 W, and the rest of "
                        + "the fire's heat goes up the chimney. The steam drives a single-cylinder engine with cam-worked "
                        + "valves, its speed held by a flyball governor geared off the crankshaft, working the throttle's "
                        + "plug. The water goes round and round: the engine exhausts, and the safety valve blows, into a "
                        + "finned copper condenser, whose vacuum (water's vapour pressure at 50 °C, a tenth of the air's) "
                        + "the engine exhausts against; the condensate falls into the hot well, and a feed pump on the "
                        + "crankshaft — a piston between two check valves, below the well so the near-boiling water "
                        + "does not flash in it — pumps it back into the boiler.");
        e.plots = 4;
        e.origin(PLANT[0], PLANT[1], PLANT[2]);
        plant(e);
        e.origin(0, 0, 0);
        return e.view(30, 16, 34, 28);
    }

    /**
     * The plant, in its own coordinates (the governed steam engine's: crankshaft along x at y = 3, z = 8; boiler at
     * (5, 4, 13)). The fire is a methane firebox under the boiler, behind a steel crown sheet: six bottles at x = 2 feed
     * the main valve (its plug on the solenoid's rod) and a pilot needle beside it; air comes in from the north, the flue
     * leaves east and rises in a chimney at (7, ·, 13). The cylinder's jacket is a small steam vessel fed from the main.
     */
    static void plant(Exhibit e) {
        String st = "steel_1045";
        int y = 3;
        Object[] steam = {"fluid", 7};
        // ---- crankshaft, bearings, cams, crank, flywheel (as the governed engine)
        int shaft = e.fill(SHAFT, st, 0, y, 8, 11, y, 8, Dir.EAST, "diameter", 0.012);
        e.name("shaft", shaft);
        e.init(shaft, new Exhibit.Initial(Double.NaN, 14, 0, 0.3)); // running, as the station is found
        e.add(BEARING, st, 0, y, 8, Dir.EAST);
        e.add(BEARING, st, 9, y, 8, Dir.EAST);
        e.fill(BLOCK, st, 0, 0, 8, 0, y - 1, 8, Dir.UP);
        e.fill(BLOCK, st, 9, 0, 8, 9, y - 1, 8, Dir.UP);
        e.add(ECCENTRIC, st, 2, y, 8, Dir.EAST, "throw", 0.012, "phase", 95);
        e.add(ECCENTRIC, st, 3, y, 8, Dir.EAST, "throw", 0.012);
        e.add(ECCENTRIC, st, 4, y, 8, Dir.EAST, "throw", 0.012, "phase", -40);
        e.name("flywheel", e.fill(BLOCK, st, 7, y - 1, 7, 7, y + 1, 9, Dir.EAST, "fill", 0.3));
        // ---- cylinder, valves, steam line, throttle on the governor's rod, boiler and its safety valve
        int cyl = e.add(CYLINDER, st, 3, y + 2, 8, Dir.DOWN, with(steam, "bore", 0.035, "ring_friction", 3.0));
        e.name("cylinder", cyl);
        e.add(BLOCK, st, 3, y + 3, 8, Dir.UP, "fill", 0.3);
        int exhaust = e.add(VALVE, "brass", 2, y + 2, 8, Dir.WEST, with(steam, "bore", 0.010));
        e.name("exhaust", exhaust);
        int inlet = e.add(VALVE, "brass", 4, y + 2, 8, Dir.WEST, with(steam, "bore", 0.008, "lap", 0.7));
        e.name("inlet", inlet);
        int elbow = e.add(PIPE, "copper", 5, y + 2, 8, Dir.SOUTH, with(steam, "bore", 0.008));
        e.add(BLOCK, st, 5, y + 2, 7, Dir.UP, "fill", 0.2); // blind flange
        int l1 = e.add(PIPE, "copper", 5, y + 2, 9, Dir.SOUTH, with(steam, "bore", 0.008));
        int reg = e.add(VALVE, "brass", 5, y + 2, 10, Dir.NORTH, with(steam, "bore", Exhibits.GOVERNED_THROTTLE_BORE,
                "plug_travel", Exhibits.GOVERNED_PLUG_TRAVEL));
        e.name("throttle", reg);
        int l2 = e.add(PIPE, "copper", 5, y + 2, 11, Dir.SOUTH, with(steam, "bore", 0.008));
        int l3 = e.add(PIPE, "copper", 5, y + 2, 12, Dir.SOUTH, with(steam, "bore", 0.008));
        int riser = e.add(PIPE, "copper", 5, y + 2, 13, Dir.UP, with(steam, "bore", 0.012));
        int safety = e.add(VALVE, "brass", 5, y + 3, 13, Dir.UP, with(steam, "bore", 0.006, "relief", 4.5e5));
        e.name("safety", safety);
        int boiler = e.add(TANK, st, 5, y + 1, 13, Dir.UP, with(steam, "fill", 0.6, "wall", 0.003, "gauge_range", 6));
        e.name("boiler", boiler);
        // (no steam jacket: one fed from the main, against a block clasping the cylinder, kept it no warmer — what chills
        // its wall is the condenser's vacuum, the water left on it boiling off at 50 °C each exhaust stroke — and its fine
        // pipe held every step of the plant's solution short)
        // everything that holds steam starts at its temperature
        for (int i : new int[]{cyl, exhaust, inlet, elbow, l1}) e.init(i, new Exhibit.Initial(412, 0, 0));
        for (int i : new int[]{reg, l2, l3, riser, safety, boiler}) e.init(i, new Exhibit.Initial(418, 0, 0));
        // ---- load: a Prony brake under the flywheel, off (the generator is the station's load; the brake is there to try it)
        e.name("brake", e.add(BRAKE, "oak", 7, y - 2, 8, Dir.UP, "force", 8));
        e.add(BLOCK, st, 7, 0, 8, Dir.UP);
        // ---- the firebox under the boiler: a steel crown sheet, the chamber, the igniter on a mica block
        // the crown: an alumina tile (k·A/L ≈ 1.9 W/K: some 500 W at the firebox's heat, what the engine uses; a steel
        // sheet passes nearly twice that, which only blows off at the safety valve, and firebrick a twentieth — the rest of
        // the fire leaves up the chimney, a crude boiler's efficiency)
        e.name("crown", e.add(BLOCK, "alumina", 5, y, 13, Dir.UP));
        int box = e.add(TANK, st, 5, y - 1, 13, Dir.UP, "wall", 0.0005);
        e.name("firebox", box);
        e.name("igniter", e.add(RESISTOR, "alumina", 5, y - 2, 13, Dir.EAST, "resistance", 2.0, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.1, "max_temperature", 1800));
        e.add(BLOCK, "mica", 5, 0, 13, Dir.UP);
        e.init(box, new Exhibit.Initial(1500, 0, 0)); // alight: the igniter has done its work
        e.init(e.named.get("crown"), new Exhibit.Initial(700, 0, 0));
        // air inlet, open at its north end: the gallery burner's, whose draft draws nearly the air its 40 µm needle's
        // methane needs (narrower, a cooling firebox starved itself rich; with less fuel, too lean to burn: below 5 %)
        e.add(PIPE, st, 5, y - 1, 12, Dir.NORTH, "bore", 0.024);
        // the flue: east out of the chamber, up the chimney (open at the top)
        e.add(PIPE, st, 6, y - 1, 13, Dir.EAST, "bore", 0.04);
        int chimney = e.fill(PIPE, st, 7, y - 1, 13, 7, y + 7, 13, Dir.UP, "bore", 0.04, "wall", 0.001);
        e.name("chimney", chimney + 8);
        e.fill(BLOCK, st, 7, 0, 13, 7, y - 2, 13, Dir.UP); // its foot: capped below (open, it would draw air in there)
        // ---- fuel: six bottles in two columns at x = 2, a needle valve (opened to start the station, shut to stop it)
        int fuel = e.add(TANK, st, 2, y - 1, 13, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.name("fuel", fuel);
        e.add(TANK, st, 2, y - 2, 13, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.add(TANK, st, 2, 0, 13, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.fill(TANK, st, 2, 0, 14, 2, y - 1, 14, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        int needle = e.add(VALVE, "brass", 3, y - 1, 13, Dir.EAST, "bore", 4e-5); // choked: some 40 mg/s, 2 kW of fire
        e.name("needle", needle);
        e.latch(needle); // burning, as the station is found
        e.add(PIPE, st, 4, y - 1, 13, Dir.EAST, "bore", 0.004);
        // ---- the water circuit is closed: the engine exhausts, and the safety valve blows, into an air-cooled condenser
        // west of the engine; its well feeds a pump on the crankshaft's west end, which returns the water to the boiler
        condenser(e, y, steam);
        // exhaust: into a small chest on the exhaust valve's outlet (a tank: a pipe's foot there would breathe the air, and
        // a cap under it would sit on the crankshaft), up and west into the condenser
        e.init(e.add(TANK, "copper", 1, y + 2, 8, Dir.UP, with(steam, "wall", 0.002)), new Exhibit.Initial(373, 0, 0));
        e.fill(PIPE, "copper", 1, y + 3, 8, 1, y + 7, 8, Dir.UP, with(steam, "bore", 0.012));
        e.add(BLOCK, st, 1, y + 8, 8, Dir.UP, "fill", 0.2); // blind end
        e.fill(PIPE, "copper", -2, y + 7, 8, 0, y + 7, 8, Dir.EAST, with(steam, "bore", 0.012));
        // the safety valve's outlet: up, west along y + 9 and north into the condenser's top
        e.fill(PIPE, "copper", 5, y + 4, 13, 5, y + 8, 13, Dir.UP, with(steam, "bore", 0.008));
        e.fill(PIPE, "copper", -2, y + 9, 13, 5, y + 9, 13, Dir.EAST, with(steam, "bore", 0.008));
        e.add(BLOCK, st, 6, y + 9, 13, Dir.UP, "fill", 0.2);
        e.fill(PIPE, "copper", -3, y + 9, 9, -3, y + 9, 13, Dir.SOUTH, with(steam, "bore", 0.008));
        e.add(BLOCK, st, -3, y + 9, 14, Dir.UP, "fill", 0.2);
        feedPump(e, y, steam);
        // ---- lighting up: the igniter on its own bank (45 lead–acid cells, 96 V: 4.6 kW through 2 Ω, the walls glowing
        // in under a minute) through a switch; once the fire burns it keeps itself hot, and the igniter is switched off
        e.name("igniterBank", e.add(BATTERY, "pvc", 10, 0, 14, Dir.EAST, "cells", 45, "capacity", 40));
        e.name("lightUp", e.add(SWITCH, "brass", 13, 0, 14, Dir.EAST));
        // ---- the governor (the governed engine's, bevel-geared off the crankshaft's east end) on the throttle
        e.plots = Math.max(e.plots, 2);
        Exhibits.governor(e, y);
        Router r = new Router(e, 0, 0, 11, 19, 2, 15);
        r.pin("ig+", 11, 0, 14).pin("ig+", 6, y - 2, 13);   // bank + → igniter's east lead
        r.pin("ig-", 4, y - 2, 13).pin("ig-", 12, 0, 14);   // igniter's west lead → switch
        r.pin("igr", 14, 0, 14).pin("igr", 9, 0, 14);       // switch → bank −
        r.insulation("ig+", 800).insulation("ig-", 800);    // glass-fibre: the element's leads run hot
        r.route();
    }

    /**
     * The condenser (plant coordinates): a column of copper tanks at (−3, y + 4…y + 9, 8) with copper fins on three
     * sides, two deep; its foot is the hot well, above the feed pump (sucking the well's water up to it, water at its
     * boiling point flashed to steam in the pump — cavitation: a feed pump sits below its well, its suction flooded).
     * The exhaust enters at y + 7, the safety valve's steam at the top; the condensate runs down into the well. It starts
     * cool and nearly empty, so its pressure is water's vapour pressure there: the engine exhausts into a partial vacuum.
     */
    static void condenser(Exhibit e, int y, Object[] steam) {
        String cu = "copper";
        int well = e.add(TANK, cu, -3, y + 4, 8, Dir.UP, with(steam, "fill", 0.8, "wall", 0.002));
        e.name("hotwell", well);
        e.init(well, new Exhibit.Initial(330, 0, 0));
        int top = 0;
        for (int k = y + 5; k <= y + 9; k++) {
            int t = e.add(TANK, cu, -3, k, 8, Dir.UP, with(steam, "wall", 0.002));
            e.init(t, new Exhibit.Initial(330, 0, 0));
            top = t;
        }
        e.name("condenser", top);
        // its legs: a steel post under the well's west fins
        e.fill(BLOCK, "steel_1045", -4, 0, 8, -4, y + 3, 8, Dir.UP);
        // fins: thin copper plates on the west, north and south faces, two deep, beside all but the top cell
        for (int k = y + 4; k <= y + 8; k++) {
            for (int[] f : new int[][]{{-4, k, 8}, {-5, k, 8}, {-3, k, 7}, {-3, k, 6}, {-3, k, 9}, {-3, k, 10}}) {
                e.init(e.add(BLOCK, cu, f[0], f[1], f[2], Dir.UP, "fill", 0.2), new Exhibit.Initial(320, 0, 0));
            }
        }
    }

    /**
     * The feed pump (plant coordinates): a 12 mm cylinder over an eccentric on the crankshaft's west end, between two
     * check valves — water in from the hot well through the south one as the piston rises, out through the north one
     * as it falls, round under the engine and into the boiler's west face. It moves some 2.5 cm³ a second, several
     * times what the boiler boils away, so the boiler fills while the well has water: the water divides itself
     * between them, and the circuit keeps its own level.
     */
    static void feedPump(Exhibit e, int y, Object[] steam) {
        String st = "steel_1045";
        Object[] water = with(steam, "bore", 0.010, "fill", 1);
        e.name("feedCrank", e.add(ECCENTRIC, st, -1, y, 8, Dir.EAST, "throw", 0.005));
        int pump = e.add(CYLINDER, st, -1, y + 2, 8, Dir.DOWN, with(steam, "bore", 0.012, "ring_friction", 0.5, "fill", 1));
        e.name("feedPump", pump);
        e.add(BLOCK, st, -1, y + 3, 8, Dir.UP, "fill", 0.3); // its head
        // suction: from under the well (from its side it drew steam once the water fell below the face's middle), round to
        // a small tank over the pump's south side and down into the south check valve — a light one (500 Pa: the well
        // stands only a block or two above the pump)
        e.add(PIPE, st, -3, y + 3, 8, Dir.SOUTH, water);
        e.add(BLOCK, st, -3, y + 3, 7, Dir.UP, "fill", 0.2);
        e.fill(PIPE, st, -3, y + 3, 9, -3, y + 3, 10, Dir.SOUTH, water);
        e.add(BLOCK, st, -3, y + 3, 11, Dir.UP, "fill", 0.2);
        e.add(PIPE, st, -2, y + 3, 10, Dir.EAST, water);
        e.init(e.add(TANK, st, -1, y + 3, 10, Dir.UP, with(steam, "fill", 1, "wall", 0.002)), new Exhibit.Initial(330, 0, 0));
        e.add(PIPE, st, -1, y + 2, 10, Dir.NORTH, water);
        e.add(BLOCK, st, -1, y + 2, 11, Dir.UP, "fill", 0.2);
        e.name("suction", e.add(VALVE, "brass", -1, y + 2, 9, Dir.NORTH, with(steam, "bore", 0.006, "relief", 500, "fill", 1)));
        // delivery: the north check valve → down, east under the engine, south, east and up into the boiler
        e.name("delivery", e.add(VALVE, "brass", -1, y + 2, 7, Dir.NORTH, with(steam, "bore", 0.006, "relief", 3000, "fill", 1)));
        e.add(PIPE, st, -1, y + 2, 6, Dir.NORTH, water);
        e.fill(PIPE, st, -1, 1, 5, -1, y + 2, 5, Dir.UP, water);
        e.add(BLOCK, st, -1, y + 3, 5, Dir.UP, "fill", 0.2);
        e.add(BLOCK, st, -1, 0, 5, Dir.UP, "fill", 0.2);
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
        e.name("feedCheck", e.add(VALVE, "brass", 4, y + 1, 13, Dir.EAST, with(steam, "bore", 0.006, "relief", 3000, "fill", 1)));
        e.add(BLOCK, st, 3, y + 1, 13, Dir.UP, "fill", 0.2);
    }
}
