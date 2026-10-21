package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Internal-combustion engines assembled from cylinders, cam-worked valves, gears and a gas supply. */
public final class EngineExhibits {
    private EngineExhibits() {}

    /**
     * A four-stroke gas engine (as Otto's of 1876, here with a hot head for ignition): one cylinder over the crank, an
     * overhead camshaft turned at half speed by a 16:32 gear pair, cam-worked intake and exhaust valves. The intake
     * draws air through a mixing box into which methane leaks from a 200 bar bottle through a choked needle valve.
     */
    public static Exhibit gasEngine() {
        // valve timing from the geometry: phases are measured from +y about the shaft, the valves hang below the
        // camshaft (open when cam angle + phase = 180°), the crank is at top dead centre at 0 and the camshaft turns at
        // −½ crank speed; intake centred at crank 90° (mid first stroke) → 225°, exhaust at crank 630° (mid fourth) → 135°
        return gasEngine(225, 135);
    }

    /** With the intake and exhaust cams' phases [°] (for exploring the valve timing). */
    public static Exhibit gasEngine(double intakePhase, double exhaustPhase) {
        Exhibit e = new Exhibit("engine", "Gas engine",
                List.of("GAS ENGINE", "Four strokes: suck,", "squeeze, bang, blow", "(hot-head ignition)"),
                "A four-stroke engine from parts. The crank turns a camshaft at half speed through a pair of gears (16 and "
                        + "32 teeth); its two cams open the valves in turn. Down stroke one: the intake valve is open and "
                        + "the piston draws in air mixed with methane leaking from the bottle into the mixing box. Up stroke "
                        + "two: both valves shut, the charge is squeezed and heated. At the top it meets the hot cylinder "
                        + "head and ignites: down stroke three is the power stroke. Up stroke four: the exhaust valve opens "
                        + "and the burnt gas is pushed out. The flywheel carries the engine through the three strokes that "
                        + "take work instead of giving it. Meter the cylinder for its pressure and composition.");
        String st = "steel_1045";
        // frame: two columns carrying the crankshaft (y 3) and the camshaft (y 7) bearings
        for (int x : new int[]{0, 11}) {
            e.fill(BLOCK, st, x, 0, 8, x, 2, 8, Dir.UP);
            e.fill(BLOCK, st, x, 4, 8, x, 6, 8, Dir.UP);
        }
        int crank = e.fill(SHAFT, st, 0, 3, 8, 11, 3, 8, Dir.EAST, "diameter", 0.014);
        e.name("crankshaft", crank);
        e.init(crank, new Exhibit.Initial(Double.NaN, 30, 0)); // given a spin to start
        e.add(BEARING, st, 0, 3, 8, Dir.EAST);
        e.add(BEARING, st, 11, 3, 8, Dir.EAST);
        int cam = e.fill(SHAFT, st, 0, 7, 8, 11, 7, 8, Dir.EAST, "diameter", 0.012);
        e.name("camshaft", cam);
        e.init(cam, new Exhibit.Initial(Double.NaN, -15, 0)); // half the crank's speed, the other way (external gears)
        e.add(BEARING, st, 0, 7, 8, Dir.EAST);
        e.add(BEARING, st, 11, 7, 8, Dir.EAST);
        // crank, flywheel, the timing gears (2:1)
        e.add(ECCENTRIC, st, 3, 3, 8, Dir.EAST, "throw", 0.015);
        e.name("flywheel", e.fill(BLOCK, st, 7, 2, 7, 7, 4, 9, Dir.EAST, "fill", 0.4));
        // load: a Prony brake, an oak block sprung against the underside of the flywheel (a dynamometer)
        int brake = e.add(BRAKE, "oak", 7, 1, 8, Dir.UP, "force", 12);
        e.name("brake", brake);
        e.latch(brake); // applied
        e.add(BLOCK, st, 7, 0, 8, Dir.UP);
        e.add(GEAR, st, 9, 3, 8, Dir.EAST, "pitch_radius", 4.0 / 3 / 16, "teeth", 16, "thickness", 0.012);
        e.add(GEAR, st, 9, 7, 8, Dir.EAST, "pitch_radius", 8.0 / 3 / 16, "teeth", 32, "thickness", 0.012);
        // the cylinder over the crank (35 mm bore, 30 mm stroke), its head hot
        // compression ratio 9: 3.75 mm of clearance over the 30 mm stroke
        int cyl = e.add(CYLINDER, st, 3, 5, 8, Dir.DOWN, "bore", 0.035, "ring_friction", 2.0, "clearance", 0.00375);
        e.name("cylinder", cyl);
        e.init(cyl, new Exhibit.Initial(550, 0, 0)); // warm from running
        int head = e.add(BLOCK, st, 3, 6, 8, Dir.UP, "fill", 0.3);
        e.init(head, new Exhibit.Initial(900, 0, 0));
        // valves beside the cylinder, under the camshaft's cams
        e.name("exhaust", e.add(VALVE, "brass", 2, 5, 8, Dir.WEST, "bore", 0.010, "lap", 0.7));
        e.name("intake", e.add(VALVE, "brass", 4, 5, 8, Dir.WEST, "bore", 0.010, "lap", 0.7));
        e.add(ECCENTRIC, st, 2, 7, 8, Dir.EAST, "throw", 0.005, "phase", exhaustPhase);
        e.add(ECCENTRIC, st, 4, 7, 8, Dir.EAST, "throw", 0.005, "phase", intakePhase);
        // intake: a pipe to the mixing box, which breathes air through a side pipe and takes methane from the bottle
        e.add(PIPE, st, 5, 5, 8, Dir.EAST, "bore", 0.012);
        // primed: the mixing box starts with a burnable charge (as a choke gives a cold engine a rich one)
        e.name("mixer", e.add(TANK, st, 6, 5, 8, Dir.UP, "wall", 0.002, "x_CH4", 0.095, "x_O2", 0.19, "x_N2", 0.715));
        e.add(PIPE, st, 6, 5, 9, Dir.SOUTH, "bore", 0.012);                 // air inlet, open at its far end
        e.name("needle", e.add(VALVE, "brass", 6, 5, 7, Dir.SOUTH, "bore", 2.5e-5));
        e.latch(e.parts.size() - 1);
        // three 200 bar bottles in a column (half an hour of running), on a stand
        e.name("fuel", e.add(TANK, st, 6, 5, 6, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006));
        e.add(TANK, st, 6, 4, 6, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.add(TANK, st, 6, 3, 6, Dir.UP, "x_CH4", 1, "pressure", 200e5, "wall", 0.006);
        e.fill(BLOCK, st, 6, 0, 6, 6, 2, 6, Dir.UP);
        // the glow plug: a silicon-carbide element set into the cylinder wall on a slender mount (128 W: 72 ohm on 96 V), switched from a
        // battery — it keeps the wall hot enough to light each charge as it is squeezed
        int glow = e.add(RESISTOR, "alumina", 3, 5, 9, Dir.EAST, "resistance", 72.0, "contact_area", 3e-5,
                "surface_area", 0.0002, "mass", 0.01, "max_temperature", 1800, "exposed", 1);
        e.name("glow", glow);
        e.init(glow, new Exhibit.Initial(1200, 0, 0));
        int bank = e.add(BATTERY, "pvc", 2, 1, 13, Dir.EAST, "cells", 45, "capacity", 40); // 45 lead–acid cells: 95.6 V full
        int sw = e.add(SWITCH, "brass", 6, 1, 13, Dir.EAST);
        e.name("switch", sw);
        e.latch(sw);
        Router r = new Router(e, 0, 0, 9, 11, 7, 15);
        r.pin("a", 2, 5, 9).pin("a", 1, 1, 13);
        r.pin("b", 4, 5, 9).pin("b", 5, 1, 13);
        r.pin("c", 3, 1, 13).pin("c", 7, 1, 13);
        r.insulation("a", 800).insulation("b", 800); // glass-fibre: the element's leads run hot
        r.keepClear(6, 5, 10, 6, 5, 10);            // the air inlet's mouth
        r.route();
        return e.view(5.5, 7, 17, 20);
    }
}
