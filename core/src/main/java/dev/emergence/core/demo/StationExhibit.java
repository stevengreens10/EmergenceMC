package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static dev.emergence.core.demo.Exhibits.with;

/**
 * The power station: one machine of many disciplines, every part of it doing a job, built as a player builds — in
 * its own world, apart from the gallery. Methane burns in a firebox under a boiler; the steam drives an engine held to
 * its speed by Watt's flyball governor; the engine turns a generator whose rectified output charges the station's
 * battery; and that battery runs the electronics that hold its own charge (a regulator dumping the surplus into a
 * lamp) and count the engine's turns.
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
                List.of("POWER STATION", "Fire, steam, a", "governed engine and", "its own electricity"),
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
                        + "water line keeping its level. A generator geared off the crankshaft charges a 5 V battery "
                        + "through a diode bridge; an op-amp regulator dumps what a full battery cannot take into a lamp; "
                        + "and an infrared beam cut by a vane on the crankshaft counts its turns in binary on three LEDs. "
                        + "In front, a dashboard of six screens, each driven by a gauge built of miniature electronics in the "
                        + "block behind it, shows the fuel left, the boiler's pressure, the engine's speed, the condenser's "
                        + "pressure, the generator's current and the battery's voltage, live, with their trends.");
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
        e.name("brake", e.add(BRAKE, "oak", 7, y - 2, 8, Dir.UP, "force", 8));
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
            electrical(e, y);
        }
    }

    /**
     * The generator (plant coordinates): a 32-tooth gear on the crankshaft at x = 10 drives a 16-tooth pinion three cells
     * north (2 : 1, the governor's tooth size; geared faster, each magnet's pass a sharp pulse of charging current, it
     * set the whole plant's solution a thousand steps a second), on a shaft along x at (·, y, 5) in two bearings; coils of
     * 600 turns give the slower rotor EMF enough to charge. On it an aluminium disc
     * at x = 13 carries two magnets (x = 14, two cells above and below the axis, opposite ways) past two coils fixed at
     * x = 15 — the hand generator on its side, turned by the engine. Its alternating EMF goes through a bridge of four
     * diodes into the station's battery (four nickel–metal hydride cells, 5 V).
     */
    static void generator(Exhibit e, int y) {
        String st = "steel_1045", al = "aluminium_6061";
        e.add(GEAR, st, 10, y, 8, Dir.EAST, "teeth", 32, "pitch_radius", 2.0 / 16, "thickness", 0.01);
        int gs = e.fill(SHAFT, st, 10, y, 5, 16, y, 5, Dir.EAST, "diameter", 0.012);
        e.name("genShaft", gs);
        e.add(GEAR, st, 10, y, 5, Dir.EAST, "teeth", 16, "pitch_radius", 1.0 / 16, "thickness", 0.01);
        e.add(BEARING, st, 11, y, 5, Dir.EAST);
        e.fill(BLOCK, st, 11, 0, 5, 11, y - 1, 5, Dir.UP);
        e.add(BEARING, st, 16, y, 5, Dir.EAST);
        e.fill(BLOCK, st, 16, 0, 5, 16, y - 1, 5, Dir.UP);
        e.init(gs, new Exhibit.Initial(Double.NaN, -28, 0, 0)); // turning with the engine (the other way: a spur pair)
        // the rotor: a thin disc in the y–z plane, the magnets on its east face
        e.fill(BLOCK, al, 13, y - 2, 3, 13, y + 2, 7, Dir.EAST, "fill", 0.08);
        e.add(MAGNET, "ndfeb_n42", 14, y + 2, 5, Dir.EAST);
        e.add(MAGNET, "ndfeb_n42", 14, y - 2, 5, Dir.WEST);
        // the coils, facing the magnets' path (wound alike: the magnets are opposite, so with the series link joining the
        // lower ends their EMFs add — wound opposite ways, they cancelled)
        e.name("coilA", e.add(COIL, "copper", 15, y + 2, 5, Dir.EAST, "turns", 600, "wire_diameter", 0.6e-3, "lead_axis", 2));
        e.name("coilB", e.add(COIL, "copper", 15, y - 2, 5, Dir.EAST, "turns", 600, "wire_diameter", 0.6e-3, "lead_axis", 2));
        // the bridge (current west → east through each diode) and the battery
        for (int k = 0; k < 4; k++) e.name("d" + (k + 1), e.add(DIODE, "silicon", 17, 1, 9 + 2 * k, Dir.EAST));
        e.name("battery", e.add(BATTERY, "pvc", 19, 1, 6, Dir.SOUTH, "chemistry", 1, "cells", 4, "capacity", 5));
        // (wired with the electronics: see electrical)
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
     * regulator and a revolution counter.
     * <p>
     * The regulator: an op-amp compares a third of the battery's voltage (22 kΩ over 10 kΩ) with three silicon diodes'
     * forward drop (through 1 kΩ: some 1.8 V, nearly whatever the battery does); past about 5.7 V, a full battery, its
     * output switches on a MOSFET that puts the station's lamp and a 10 Ω dump load across the battery, taking what the
     * generator brings beyond what the battery can store (1 MΩ from the output back to its input holds it on until the
     * battery has fallen a tenth of a volt: no chatter).
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
        e.add(RESISTOR, "alumina", 21, 1, 12, Dir.EAST, "resistance", 10_000);   // sense → ground
        e.add(RESISTOR, "alumina", 25, 1, 6, Dir.EAST, "resistance", 1e6);       // output → sense (hysteresis)
        e.add(RESISTOR, "alumina", 21, 1, 14, Dir.EAST, "resistance", 1_000);    // battery + → reference
        e.add(DIODE, "silicon", 28, 1, 12, Dir.EAST);                            // the reference: three diodes down
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
        Router r = new Router(e, 6, 0, 0, 50, y + 3, 15);
        // what turns: the flywheel and vane, the 40-tooth gear and the crankshaft's bevel, the generator's rotor
        r.keepClear(7, 2, 7, 7, 4, 9);   // the flywheel (its corners reach 1.4 cells from the axis)
        r.keepClear(8, 1, 7, 8, 5, 9);   // the vane (its tip 2 cells out)
        r.keepClear(8, 2, 6, 8, 4, 10);
        r.keepClear(10, 0, 5, 11, y + 3, 11);
        r.keepClear(12, 0, 2, 14, y + 3, 8);
        // the generator: its coils in series, their ends to the bridge, the bridge to the battery (+ at 19, 1, 7)
        r.pin("mid", 15, y + 2, 6).pin("mid", 15, y - 2, 6);
        r.pin("ac1", 15, y + 2, 4).pin("ac1", 16, 1, 9).pin("ac1", 18, 1, 13);       // → D1 anode, D3 cathode
        r.pin("ac2", 15, y - 2, 4).pin("ac2", 16, 1, 11).pin("ac2", 18, 1, 15);      // → D2 anode, D4 cathode
        r.pin("vdd", 18, 1, 9).pin("vdd", 18, 1, 11).pin("vdd", 19, 1, 7);           // D1, D2 cathodes → battery +
        // (D3, D4 anodes: the bridge's return, through the generator gauge's shunt on the dashboard back to ground)
        r.pin("ret", 16, 1, 13).pin("ret", 16, 1, 15).pin("gnd", 19, 1, 5);          // D3, D4 anodes; battery −
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
                .colour("clk0", DashboardExhibits.PULSE);
        int[][] taps = {{20, DashboardExhibits.VDD}, {24, DashboardExhibits.GND}, {28, DashboardExhibits.RETURN}, {32, DashboardExhibits.PULSE}};
        String[] tapNets = {"vdd", "gnd", "ret", "clk0"};
        for (int k = 0; k < taps.length; k++) r.pin(tapNets[k], taps[k][0], 1, 15);
        r.route();
        for (int[] t : taps) {
            e.add(INSULATED_WIRE, "copper", t[0], 1, 16, Dir.UP, "color", t[1]);
            e.add(INSULATED_WIRE, "copper", t[0], 0, 16, Dir.UP, "color", t[1]);
            e.add(INSULATED_WIRE, "copper", t[0], 0, 17, Dir.UP, "color", t[1]);
        }
    }

    /** The battery gauge's constant: its diode reference's voltage × 1023 × 100 (see DashboardTest). */
    static final int BATTERY_K = 144_400;
    /** The dashboard's gauges, west to east (see {@link #dashboard}). */
    static final DashboardExhibits.Gauge[] GAUGES = {
            // the bottles' gauge pressure: 0–250 bar on 10–90 % of the supply
            new DashboardExhibits.Gauge("fuel", "FUEL BOTTLES", "BAR", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.FUEL, 0,
                    0, 102, 782, 0, 0, 1, 0, 12),
            // the boiler's gauge pressure: 0–6 bar
            new DashboardExhibits.Gauge("boiler", "BOILER PRESSURE", "BAR", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.BOILER, 0,
                    0, 102, 188, 0, 0, 2, 0, 51),
            // turns a minute from the counter's pulses, one a turn: 60 × 10 kHz / clocks between them
            new DashboardExhibits.Gauge("engine", "ENGINE SPEED", "RPM", DashboardExhibits.FrontEnd.PULSE, DashboardExhibits.PULSE, 0,
                    2, 0, 0, 0, 600_000, 0, 0, 154),
            // the condenser's absolute pressure: gauge −100–0 kPa, plus the world's air pressure above 100 kPa (2.8 kPa)
            new DashboardExhibits.Gauge("condenser", "CONDENSER", "KPA", DashboardExhibits.FrontEnd.DIRECT, DashboardExhibits.CONDENSER, 0,
                    0, 102, 313, 28, 0, 1, 0, 31),
            // the generator's current: 0.5 Ω, gain 50 (40 mA a volt), on a 5.6 V supply: 0.219 mA a code
            new DashboardExhibits.Gauge("generator", "GENERATOR", "MA", DashboardExhibits.FrontEnd.CURRENT, DashboardExhibits.RETURN, 0,
                    0, 0, 56, 0, 0, 0, 0, 307),
            // the battery's voltage: two diodes' 1.24 V read against it, k = 1.24 V × 1023 × 100
            new DashboardExhibits.Gauge("battery", "BATTERY", "V", DashboardExhibits.FrontEnd.REFERENCE, DashboardExhibits.VDD, 0,
                    1, 0, 0, 0, BATTERY_K, 2, 400, 154)};
    /**
     * The gauge blocks' x (each at z 19, its screen at x − 2…x + 1, z 20): a block apart, so that each screen is its own
     * (panels side by side make one screen).
     */
    static final int[] GAUGE_X = {18, 23, 28, 33, 38, 43};

    /**
     * The dashboard (station coordinates), south of the plant: six full-size screens of 4 × 2 blocks facing south, and
     * behind each, in one block, a gauge of miniature electronics (DashboardExhibits.gaugeBlock) driving it across the
     * block's face. Behind the gauges a full-size ribbon cable runs east–west at z 18, and each gauge takes from its
     * north face the conductors it needs, by colour: the station's supply and ground, the counter's pulses and the
     * generator's return (tapped from the electronics), and three pressure transducers' outputs — on the boiler, on
     * the condenser and on a fuel bottle — wired to it here, each transducer powered from the ribbon.
     */
    static void dashboard(Exhibit e) {
        for (int x = 16; x <= 46; x++) {
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
        r.pin("vdd2", 19, 0, 17).pin("gnd2", 21, 0, 17).pin("pB", 23, 0, 17).pin("pC", 25, 0, 17).pin("pF", 27, 0, 17);
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
