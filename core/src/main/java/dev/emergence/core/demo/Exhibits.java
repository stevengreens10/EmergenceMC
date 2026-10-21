package dev.emergence.core.demo;

import dev.emergence.core.assembly.Assembly;
import dev.emergence.core.assembly.AssemblyState;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.Part;
import dev.emergence.core.materials.MaterialDatabase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.emergence.core.assembly.PartKind.*;

/**
 * The demo-world exhibits. Every exhibit is built only from primitive parts; the behaviour on display
 * is whatever the physics produces. Each is covered by a core validation test (DemoExhibitsTest).
 * Exhibits are block-scale builds (every part a single cell, one block in the world), except the
 * miniature exhibit, built at true scale inside a single block.
 */
public final class Exhibits {
    private Exhibits() {}

    public static List<Exhibit> all() {
        return List.of(generator(), gearTrain(), capacitorGlow(), fuse(), conductionRace(), flywheels(), dcMotor(),
                daniellCell(), plating(), galvanicCorrosion(), airTurbine(), fanCoupling(), heatedVessel(), stirlingEngine(), steamEngine(), condensingEngine(),
                transistorSwitch(), nandGate(), binaryCounter(), thermostat(), ComputerExhibit.build(), shelves(), OpticsExhibits.lightBarrier(),
                OpticsExhibits.solarPanel(), ControlExhibits.servo(), ControlExhibits.governor(), ControlExhibits.contactGovernor(), ControlExhibits.flyball(), Exhibits.rectifier(), Exhibits.boost(), ControlExhibits.hBridge(), ControlExhibits.pwmMotor(), ControlExhibits.inverter(), ChemistryExhibits.brownGas(), ChemistryExhibits.thermalExplosion(), ChemistryExhibits.gasBurner(), ChemistryExhibits.still(), ChemistryExhibits.column(), ThermoExhibits.thermocouple(), ThermoExhibits.fanCooling(), RadiationExhibits.geigerCounter(), MechanicsExhibits.springMass(), MechanicsExhibits.unbalance(), MechanicsExhibits.spinningWeights(), MechanicsExhibits.fourBar(), AeroExhibits.windTurbine(), AeroExhibits.propellerCart(), MechanicsExhibits.harvester(), MechanicsExhibits.hydraulicPress(), MechanicsExhibits.hydraulicJack(), MechanicsExhibits.rackAndPinion(), MechanicsExhibits.screwJacks(), ThermoExhibits.burstTest(), Exhibits.faradayRing(), Exhibits.buckling(), Exhibits.fatigue(), Exhibits.eddyBrake(), Exhibits.magnetPull(), Exhibits.relay(), Exhibits.trembler(), EngineExhibits.gasEngine(), OpticsExhibits.periscope(), OpticsExhibits.lens(), OpticsExhibits.solarFurnace(), OpticsExhibits.lamp(), OpticsExhibits.prism(), OpticsExhibits.spectrometer(), OpticsExhibits.optocoupler(), OpticsExhibits.colourFilters(), ComputerExhibit.memory(), AcousticsExhibits.loudspeaker(),
                miniatureGenerator());
    }

    /** Hand-cranked axial-flux generator feeding a resistor through insulated wiring. */
    public static Exhibit generator() {
        Exhibit e = new Exhibit("generator", "Hand-cranked generator",
                List.of("HAND GENERATOR", "Hold right-click", "on the crank.", "Meter the resistor"),
                "Two NdFeB magnets on an aluminium rotor sweep under two copper coils. Their changing flux "
                        + "linkage induces an EMF (Faraday), current flows through the 20 Ω resistor and heats it, "
                        + "and the current's magnetic field brakes the rotor (Lenz). Use the multimeter on the "
                        + "resistor and on the rotor.");
        generatorBody(e, false);
        e.path(INSULATED_WIRE, "copper", new int[][]{{9, 6, 8}, {9, 6, 9}, {7, 6, 9}, {7, 6, 8}}, "diameter", 1.5e-3);
        e.name("load", e.add(RESISTOR, "alumina", 8, 6, 9, Dir.EAST, "resistance", 20));
        return e.view(8.5, 10.5, 21, 22);
    }

    /**
     * The hand generator's machine: frame, rotor (disk, two magnets, crank) and two coils in series. With
     * {@code outward} the coils' inner leads are joined across the middle and their outer leads are free (wire them at
     * 11, 6, 8 and 5, 6, 8); otherwise the outer leads are joined round the outside and the free ends are
     * the inner leads (9, 6, 8 and 7, 6, 8, beside the shaft).
     */
    static void generatorBody(Exhibit e, boolean outward) {
        String st = "steel_1045";
        // frame: base pillars holding the lower bearing, two posts and top bars holding the upper one
        e.fill(BLOCK, st, 9, 0, 8, 9, 2, 8, Dir.UP);
        e.fill(BLOCK, st, 7, 0, 8, 7, 2, 8, Dir.UP);
        e.fill(BLOCK, st, 11, 0, 12, 11, 7, 12, Dir.UP);
        e.fill(BLOCK, st, 5, 0, 12, 5, 7, 12, Dir.UP);
        e.fill(BLOCK, st, 9, 8, 8, 11, 8, 8, Dir.UP);
        e.fill(BLOCK, st, 11, 8, 9, 11, 8, 12, Dir.UP);
        e.fill(BLOCK, st, 5, 8, 8, 7, 8, 8, Dir.UP);
        e.fill(BLOCK, st, 5, 8, 9, 5, 8, 12, Dir.UP);
        // rotor: shaft in two bearings, a thin aluminium disk carrying the magnets, crank on top
        e.fill(SHAFT, st, 8, 2, 8, 8, 10, 8, Dir.UP, "diameter", 0.02);
        e.add(BEARING, st, 8, 2, 8, Dir.UP);
        e.add(BEARING, st, 8, 8, 8, Dir.UP);
        e.name("crank", e.add(CRANK, st, 8, 11, 8, Dir.UP, "radius", 0.15));
        e.fill(BLOCK, "aluminium_6061", 6, 4, 6, 10, 4, 10, Dir.UP, "fill", 0.08);
        e.add(MAGNET, "ndfeb_n42", 10, 5, 8, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 6, 5, 8, Dir.DOWN);
        // stationary coils just above the magnets' path; coil B faces the other way so the two EMFs (from
        // magnets of opposite polarity) add in series
        e.name("coil", e.add(COIL, "copper", 10, 6, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3));
        e.add(COIL, "copper", 6, 6, 8, Dir.DOWN, "turns", 300, "wire_diameter", 0.8e-3);
        if (outward) {
            // A− → B+ across the middle; A+ and B− out through the sides
            e.path(INSULATED_WIRE, "copper", new int[][]{{9, 6, 8}, {9, 6, 9}, {8, 6, 9}, {7, 6, 9}, {7, 6, 8}}, "diameter", 1.5e-3);
            return;
        }
        // A+ → B− around the outside; B+ → resistor → A− across the middle
        e.path(INSULATED_WIRE, "copper", new int[][]{{11, 6, 8}, {11, 6, 9}, {11, 6, 10}, {11, 6, 11}, {10, 6, 11},
                {9, 6, 11}, {8, 6, 11}, {7, 6, 11}, {6, 6, 11}, {5, 6, 11}, {5, 6, 10}, {5, 6, 9}, {5, 6, 8}}, "diameter", 1.5e-3);
    }

    /**
     * A bridge rectifier: the hand generator's alternating EMF through four silicon diodes onto a smoothing capacitor
     * that lights an LED steadily through its resistor — the first stage of every mains power supply.
     */
    public static Exhibit rectifier() {
        Exhibit e = new Exhibit("rectifier", "Bridge rectifier",
                List.of("RECTIFIER", "Crank the generator:", "four diodes turn its", "AC into steady DC"),
                "The hand generator again — its coils' EMF reverses each time a magnet passes — but its current goes "
                        + "through four silicon diodes arranged as a bridge. Whichever way the EMF points, two of the "
                        + "diodes conduct and two block, so current always leaves by the same terminal: the bridge turns "
                        + "the alternating voltage into one that only ever pushes one way (each diode costs about 0.6 V). "
                        + "A 10 mF capacitor fills on each peak and feeds the LED between them, so it glows steadily "
                        + "instead of flickering at the magnets' rate. Meter the capacitor: a steady voltage with a "
                        + "small sawtooth ripple — larger the harder the LED pulls and the slower you crank. Stop "
                        + "cranking and the capacitor alone keeps the LED lit for a few seconds while it drains.");
        e.plots = 2;
        generatorBody(e, true);
        int f = 1;
        // the bridge (current flows west → east through each diode): AC1 → D1 → DC+, AC2 → D2 → DC+, DC− → D3 → AC1,
        // DC− → D4 → AC2
        for (int k = 0; k < 4; k++) e.name("d" + (k + 1), e.add(DIODE, "silicon", 15, f, 4 + 2 * k, Dir.EAST));
        e.name("cap", e.add(CAPACITOR, "alumina", 19, f, 4, Dir.EAST, "capacitance", 0.01));
        e.name("resistor", e.add(RESISTOR, "alumina", 19, f, 6, Dir.EAST, "resistance", 470));
        e.name("led", e.add(LED, "silicon", 19, f, 8, Dir.EAST, "wavelength", 625, "face", 3, "beam", 20));
        Router r = new Router(e, 0, 0, 0, 23, 12, 15);
        r.pin("ac1", 11, 6, 8).pin("ac1", 14, f, 4).pin("ac1", 16, f, 8);
        r.pin("ac2", 5, 6, 8).pin("ac2", 14, f, 6).pin("ac2", 16, f, 10);
        r.pin("dc+", 16, f, 4).pin("dc+", 16, f, 6).pin("dc+", 18, f, 4).pin("dc+", 18, f, 6);
        r.pin("dc-", 14, f, 8).pin("dc-", 14, f, 10).pin("dc-", 20, f, 4).pin("dc-", 20, f, 8);
        r.pin("mid", 20, f, 6).pin("mid", 18, f, 8);
        // the rotor sweeps the cylinder round the shaft; the coils' wiring and the frame stay untouched
        r.keepClear(4, 2, 4, 12, 5, 12);
        r.keepClear(6, 6, 5, 10, 9, 11); // and nothing over the coils and their wiring
        r.route();
        return e.view(11, 8, 22, 34);
    }

    /**
     * A boost converter: a 5 V supply lights four red LEDs in series (≈ 7.6 V) that it could not light directly. A
     * Schmitt-trigger oscillator switches a MOSFET that shorts a coil across the supply; when it opens, the coil keeps its
     * current flowing and its voltage rises until the diode conducts into the output capacitor.
     */
    public static Exhibit boost() {
        return boost(true);
    }

    /** @param switching false: the MOSFET's gate tied low (the supply alone, through the coil and diode) */
    public static Exhibit boost(boolean switching) {
        Exhibit e = new Exhibit("boost", "Boost converter",
                List.of("BOOST CONVERTER", "5 V lights four LEDs", "in series (7.6 V):", "a coil steps it up"),
                "Four red LEDs in series need about 7.6 V before they light; the supply gives 5. A Schmitt-trigger "
                        + "oscillator (47 kΩ, 1 µF: some 26 times a second) switches a MOSFET that connects a "
                        + "2000-turn coil straight across the supply: the current in the coil grows (L·di/dt = V) and "
                        + "its magnetic field stores energy (½LI²). When the MOSFET opens the coil will not let its "
                        + "current stop — its voltage leaps to whatever it takes — and the current pours through the "
                        + "diode into the output capacitor, charging it above the supply until the LEDs light. Each "
                        + "cycle carries a parcel of energy from 5 V up to 7.6 V: power is conserved (minus the coil's "
                        + "resistance and the diode's drop), so the LEDs' current is smaller than what the supply "
                        + "gives. Meter the capacitor and the switch node: a square wave whose top stands above the "
                        + "supply. The same trick powers LED torches from a single cell.");
        e.plots = 2;
        int f = 1;
        int bat = e.add(CAPACITOR, "alumina", 2, f, 2, Dir.EAST, "capacitance", 50);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        // oscillator: Schmitt inverter, 47 kΩ feedback, 1 µF to ground (period 2RC·ln 1.5 ≈ 38 ms)
        e.name("schmitt", e.add(LOGIC, "silicon", 6, f, 10, Dir.EAST, "function", 7));
        e.add(RESISTOR, "alumina", 6, f, 13, Dir.EAST, "resistance", 47_000);
        e.add(CAPACITOR, "alumina", 3, f, 10, Dir.EAST, "capacitance", 1e-6);
        // the switch, the coil, the diode and the output capacitor
        e.name("switch", e.add(TRANSISTOR, "silicon", 10, f, 6, Dir.EAST, "type", 2));
        e.name("coil", e.add(COIL, "copper", 14, f, 6, Dir.UP, "turns", 2000, "wire_diameter", 0.5e-3));
        e.name("diode", e.add(DIODE, "silicon", 11, f, 9, Dir.EAST));
        e.name("cout", e.add(CAPACITOR, "alumina", 15, f, 9, Dir.EAST, "capacitance", 1000e-6));
        // a snubber across the switch: 0.1 µF through 1.5 kΩ (≈ √(L/C)) damps the ring between the coil and the
        // transistor's and diode's picofarads when both are off
        e.add(CAPACITOR, "alumina", 19, f, 6, Dir.EAST, "capacitance", 0.1e-6);
        e.add(RESISTOR, "alumina", 23, f, 6, Dir.EAST, "resistance", 1500);
        // the load: four red LEDs and 100 Ω in series
        for (int k = 0; k < 4; k++)
            e.name("led" + (k + 1), e.add(LED, "silicon", 13 + 3 * k, f, 13, Dir.EAST, "wavelength", 630, "face", 3, "beam", 25));
        e.name("load", e.add(RESISTOR, "alumina", 25, f, 13, Dir.EAST, "resistance", 100));
        Router r = new Router(e, 0, 0, 0, 30, 3, 15);
        r.pin("vin", 3, f, 2).pin("vin", 15, f, 6).pin("vin", 6, f, 11);
        r.pin("gnd", 1, f, 2).pin("gnd", 9, f, 6).pin("gnd", 6, f, 9).pin("gnd", 2, f, 10).pin("gnd", 14, f, 9).pin("gnd", 26, f, 13);
        r.pin("in", 5, f, 10).pin("in", 4, f, 10).pin("in", 5, f, 13);
        r.pin("osc", 7, f, 10).pin("osc", 7, f, 13);
        if (switching) r.pin("osc", 10, f, 5);
        else r.pin("gnd", 10, f, 5);
        r.pin("sw", 11, f, 6).pin("sw", 13, f, 6).pin("sw", 10, f, 9).pin("sw", 18, f, 6);
        r.pin("snub", 20, f, 6).pin("snub", 22, f, 6);
        r.pin("gnd", 24, f, 6);
        r.pin("vout", 12, f, 9).pin("vout", 16, f, 9).pin("vout", 12, f, 13);
        for (int k = 0; k < 3; k++) r.pin("s" + k, 14 + 3 * k, f, 13).pin("s" + k, 15 + 3 * k, f, 13);
        r.pin("s3", 23, f, 13).pin("s3", 24, f, 13);
        r.route();
        return e.view(14, 8, 21, 40);
    }

    /** Three meshing spur gears (8 : 24 : 40 teeth) — speed ratios and direction reversal. */
    public static Exhibit gearTrain() {
        Exhibit e = new Exhibit("gears", "Spur gear train",
                List.of("GEAR TRAIN", "8 : 24 : 40 teeth", "Crank the small", "gear. Compare rpm"),
                "Three spur gears with the same tooth module (7.8 mm) mesh because their pitch circles touch. "
                        + "Turning the 8-tooth pinion turns the 24-tooth gear backwards at 1/3 speed and the "
                        + "40-tooth gear forwards at 1/5 speed, with 5× the torque minus a little tooth friction.");
        String st = "steel_1045";
        e.name("input", e.fill(SHAFT, st, 4, 0, 8, 4, 5, 8, Dir.UP, "diameter", 0.015));
        e.fill(SHAFT, st, 6, 0, 8, 6, 5, 8, Dir.UP, "diameter", 0.015);
        e.name("output", e.fill(SHAFT, st, 10, 0, 8, 10, 5, 8, Dir.UP, "diameter", 0.015));
        for (int x : new int[]{4, 6, 10}) e.add(BEARING, st, x, 0, 8, Dir.UP);
        e.add(GEAR, st, 4, 3, 8, Dir.UP, "teeth", 8, "pitch_radius", 0.5 / 16, "thickness", 0.012);
        e.name("middle", e.add(GEAR, st, 6, 3, 8, Dir.UP, "teeth", 24, "pitch_radius", 1.5 / 16, "thickness", 0.012));
        e.add(GEAR, "brass", 10, 3, 8, Dir.UP, "teeth", 40, "pitch_radius", 2.5 / 16, "thickness", 0.012);
        e.name("crank", e.add(CRANK, st, 4, 6, 8, Dir.UP, "radius", 0.12));
        return e.view(7.5, 8.5, 15.5, 32);
    }

    /** A pre-charged capacitor dumped through a nichrome wire: incandescence from Joule heating. */
    public static Exhibit capacitorGlow() {
        Exhibit e = new Exhibit("glow", "Capacitor discharge",
                List.of("HOT WIRE", "Close the switch:", "28 J flash-heats", "the nichrome wire"),
                "A 10 mF capacitor charged to 75 V stores ½CV² ≈ 28 J. Closing the switch dumps it through "
                        + "two cells of 0.3 mm nichrome (≈2 Ω). The wire heats to ~1100 K and glows orange, then "
                        + "cools by radiation (Stefan–Boltzmann) and convection.");
        buildDischarge(e, 0.3e-3);
        return e.view(5.5, 4, 13.5, 30);
    }

    /** The same discharge through a much thinner wire: it melts, opening the circuit. */
    public static Exhibit fuse() {
        Exhibit e = new Exhibit("fuse", "Fuse",
                List.of("FUSE", "Close the switch:", "0.1 mm nichrome", "melts and opens"),
                "Identical capacitor and switch, but the nichrome is 0.1 mm thick: nine times the resistance "
                        + "and one ninth of the heat capacity. The same energy drives it past its melting point "
                        + "(1673 K) and it fails, interrupting the circuit — a fuse emerges from material properties.");
        buildDischarge(e, 0.1e-3);
        return e.view(5.5, 4, 13.5, 30);
    }

    private static void buildDischarge(Exhibit e, double wireDiameter) {
        int cap = e.add(CAPACITOR, "alumina", 4, 0, 8, Dir.EAST, "capacitance", 0.01);
        e.name("capacitor", cap);
        e.init(cap, new Exhibit.Initial(Double.NaN, 0, 75));
        e.name("switch", e.add(SWITCH, "brass", 5, 0, 8, Dir.EAST));
        e.name("wire", e.add(WIRE, "nichrome", 6, 0, 8, Dir.UP, "diameter", wireDiameter));
        e.add(WIRE, "nichrome", 7, 0, 8, Dir.UP, "diameter", wireDiameter);
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 0, 8}, {8, 0, 9}, {8, 0, 10}, {7, 0, 10}, {6, 0, 10}, {5, 0, 10},
                {4, 0, 10}, {3, 0, 10}, {3, 0, 9}, {3, 0, 8}}, "diameter", 1.5e-3);
    }

    /** A glowing-hot copper block feeding heat into a copper bar and a stainless-steel bar. */
    public static Exhibit conductionRace() {
        Exhibit e = new Exhibit("conduction", "Conduction race",
                List.of("CONDUCTION", "Copper vs stainless", "steel. Meter the", "far ends over time"),
                "A copper block starts at 1150 K (glowing orange). Heat diffuses along a copper bar (k = 391 W/m·K) and a "
                        + "stainless-steel bar (k = 16 W/m·K). Thermal diffusivity differs ~30×, so the copper bar's "
                        + "far end warms within minutes while the steel bar's barely changes.");
        int hot = e.add(BLOCK, "copper", 7, 0, 8, Dir.UP);
        e.name("source", hot);
        e.init(hot, new Exhibit.Initial(1150, 0, 0));
        for (int i = 1; i <= 4; i++) {
            int c = e.add(BLOCK, "copper", 7 + i, 0, 8, Dir.UP, "fill", 0.25);
            int s = e.add(BLOCK, "stainless_304", 7 - i, 0, 8, Dir.UP, "fill", 0.25);
            if (i == 4) {
                e.name("copperEnd", c);
                e.name("steelEnd", s);
            }
        }
        return e.view(7.5, 4, 13, 32);
    }

    /** Identical flywheels spun to 60 rad/s, one on ball bearings, one on a lubricated bronze bushing. */
    public static Exhibit flywheels() {
        Exhibit e = new Exhibit("flywheels", "Bearing friction",
                List.of("BEARINGS", "Left: ball bearing", "Right: plain bushing", "Both start 573 rpm"),
                "Two identical aluminium flywheels start at 60 rad/s. The ball bearing's rolling friction "
                        + "(SKF model, μ ≈ 0.0015) lets its flywheel run for a long time; the bronze bushing's "
                        + "boundary friction and oil shear (Petroff) stop the other within about a minute. "
                        + "The lost kinetic energy ends up as heat in the bearings.");
        String st = "steel_1045";
        int[] xs = {4, 11};
        for (int k = 0; k < 2; k++) {
            int x = xs[k];
            int shaft = e.fill(SHAFT, st, x, 0, 8, x, 4, 8, Dir.UP, "diameter", 0.02);
            int bearing = e.add(BEARING, k == 0 ? st : "bronze", x, 0, 8, Dir.UP, "type", k);
            e.name(k == 0 ? "ballBearing" : "plainBearing", bearing);
            e.fill(BLOCK, "aluminium_6061", x - 2, 2, 6, x + 2, 2, 10, Dir.UP, "fill", 0.25);
            e.name(k == 0 ? "ball" : "plain", shaft);
            e.init(shaft, new Exhibit.Initial(Double.NaN, 60, 0));
        }
        return e.view(7.5, 7, 17, 25);
    }

    /**
     * Brushed DC motor: rotor coils, a two-segment copper commutator on a nylon hub and two graphite
     * brushes, fed from a 1 F capacitor bank at 24 V through a switch. Commutation emerges from where the
     * segment gap passes the brushes.
     */
    public static Exhibit dcMotor() {
        Exhibit e = new Exhibit("dcmotor", "Brushed DC motor",
                List.of("DC MOTOR", "Close the switch.", "Commutator flips", "coil current"),
                "Two coils on a nylon rotor sit above two NdFeB magnets. Current reaches them through graphite "
                        + "brushes sliding on two copper commutator segments. Torque i·dΦ/dθ only pushes one way "
                        + "if the current reverses as each coil passes a magnet — which happens because the "
                        + "segment gap passes the brushes exactly then. A 1 F, 24 V capacitor bank powers it for a "
                        + "few seconds; rebuild the gallery (/emergence demo) to recharge.");
        String st = "steel_1045", ny = "nylon_66";
        // frame
        e.fill(BLOCK, st, 9, 0, 8, 9, 1, 8, Dir.UP);
        e.fill(BLOCK, st, 7, 0, 8, 7, 1, 8, Dir.UP);
        e.fill(BLOCK, st, 11, 0, 12, 11, 11, 12, Dir.UP);
        e.fill(BLOCK, st, 5, 0, 12, 5, 11, 12, Dir.UP);
        e.fill(BLOCK, st, 9, 12, 8, 11, 12, 8, Dir.UP);
        e.fill(BLOCK, st, 11, 12, 9, 11, 12, 12, Dir.UP);
        e.fill(BLOCK, st, 5, 12, 8, 7, 12, 8, Dir.UP);
        e.fill(BLOCK, st, 5, 12, 9, 5, 12, 12, Dir.UP);
        e.fill(BLOCK, st, 10, 3, 8, 10, 3, 12, Dir.UP);
        e.fill(BLOCK, st, 6, 3, 8, 6, 3, 12, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 10, 4, 8, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 6, 4, 8, Dir.DOWN);
        // rotor
        int shaft = e.fill(SHAFT, st, 8, 1, 8, 8, 12, 8, Dir.UP, "diameter", 0.02);
        e.name("rotor", shaft);
        e.init(shaft, new Exhibit.Initial(Double.NaN, 0, 0, 0.3)); // just past the dead point
        e.add(BEARING, st, 8, 1, 8, Dir.UP);
        e.add(BEARING, st, 8, 12, 8, Dir.UP);
        e.fill(BLOCK, ny, 6, 6, 6, 10, 6, 10, Dir.UP, "fill", 0.3);
        e.add(COIL, "copper", 10, 5, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3);
        // coil B brings its leads out along z (− towards the north, + towards the south)
        e.add(COIL, "copper", 6, 5, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3, "lead_axis", 2);
        // commutator: nylon hub with two copper segments, one each side of the gap
        e.add(BLOCK, ny, 8, 8, 8, Dir.UP);
        e.fill(BLOCK, "copper", 7, 8, 9, 9, 8, 9, Dir.UP, "fill", 0.6);
        e.fill(BLOCK, "copper", 7, 8, 7, 9, 8, 7, Dir.UP, "fill", 0.6);
        // rotor wiring: segment → coil A+; A− → B−; B+ → other segment
        e.add(WIRE, "copper", 8, 9, 9, Dir.UP);
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 10}, {8, 8, 10}, {8, 7, 10}, {9, 7, 10}, {10, 7, 10}, {11, 7, 10},
                {11, 6, 10}, {11, 5, 10}, {11, 5, 9}, {11, 5, 8}});
        e.path(INSULATED_WIRE, "copper", new int[][]{{9, 5, 8}, {9, 5, 7}, {8, 5, 7}, {7, 5, 7}, {6, 5, 7}});
        e.add(WIRE, "copper", 8, 9, 7, Dir.UP);
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 6}, {8, 8, 6}, {8, 7, 6}, {7, 7, 6}, {6, 7, 6}, {5, 7, 6},
                {5, 6, 6}, {5, 6, 7}, {5, 6, 8}, {5, 6, 9}, {5, 5, 9}, {6, 5, 9}});
        // brushes in nylon holders hung from the top bars
        e.add(BRUSH, "graphite", 10, 8, 8, Dir.WEST);
        e.fill(BLOCK, ny, 10, 9, 8, 10, 11, 8, Dir.UP);
        e.add(BRUSH, "graphite", 6, 8, 8, Dir.EAST);
        e.fill(BLOCK, ny, 6, 9, 8, 6, 11, 8, Dir.UP);
        // supply: capacitor bank and switch in a loop between the brushes
        e.path(INSULATED_WIRE, "copper", new int[][]{{11, 8, 8}, {12, 8, 8}, {12, 8, 9}, {12, 8, 10}, {12, 8, 11}, {12, 8, 12},
                {12, 8, 13}, {11, 8, 13}, {9, 8, 13}, {7, 8, 13}, {6, 8, 13}, {5, 8, 13}, {4, 8, 13},
                {4, 8, 12}, {4, 8, 11}, {4, 8, 10}, {4, 8, 9}, {4, 8, 8}, {5, 8, 8}});
        e.name("switch", e.add(SWITCH, "brass", 10, 8, 13, Dir.EAST));
        int cap = e.add(CAPACITOR, "alumina", 8, 8, 13, Dir.EAST, "capacitance", 1.0);
        e.name("capacitor", cap);
        e.init(cap, new Exhibit.Initial(Double.NaN, 0, 24));
        return e.view(8.5, 11, 22, 22);
    }

    /** Daniell cell: zinc in zinc sulfate, copper in copper sulfate, the two solutions in contact. */
    public static Exhibit daniellCell() {
        Exhibit e = new Exhibit("daniell", "Daniell cell",
                List.of("DANIELL CELL", "Zn|ZnSO4|CuSO4|Cu", "about 1 V. Meter", "resistor and jars"),
                "A zinc electrode in zinc sulfate and a copper electrode in copper sulfate, the two solutions "
                        + "touching. Zinc is oxidised (Zn → Zn²⁺ + 2e⁻) and copper ions are reduced (Cu²⁺ + 2e⁻ → Cu), "
                        + "so electrons flow through the 10 Ω resistor from zinc to copper. The cell voltage comes "
                        + "from the Nernst potentials minus activation and IR losses; over time the copper sulfate "
                        + "pales as Cu²⁺ is used up.");
        e.name("zinc", e.add(BLOCK, "zinc", 5, 0, 8, Dir.EAST, "fill", 0.3));
        e.name("znSol", e.add(ELECTROLYTE, "zinc_sulfate_1m", 6, 0, 8, Dir.UP));
        e.name("cuSol", e.add(ELECTROLYTE, "copper_sulfate_1m", 7, 0, 8, Dir.UP));
        e.name("copper", e.add(BLOCK, "copper", 8, 0, 8, Dir.EAST, "fill", 0.3));
        e.add(WIRE, "copper", 5, 1, 8, Dir.UP);
        e.add(WIRE, "copper", 8, 1, 8, Dir.UP);
        e.path(INSULATED_WIRE, "copper", new int[][]{{5, 2, 8}, {7, 2, 8}, {8, 2, 8}});
        e.name("load", e.add(RESISTOR, "alumina", 6, 2, 8, Dir.EAST, "resistance", 10));
        return e.view(6.5, 4, 12.5, 28);
    }

    /** Displacement plating: zinc in copper sulfate, copper in silver nitrate — no circuit at all. */
    public static Exhibit plating() {
        Exhibit e = new Exhibit("plating", "Displacement plating",
                List.of("PLATING", "Zinc in CuSO4,", "copper in AgNO3:", "no wires needed"),
                "A more reactive metal reduces the ions of a less reactive one at its own surface: zinc in "
                        + "copper sulfate becomes coated with copper (Zn + Cu²⁺ → Zn²⁺ + Cu) and copper in silver "
                        + "nitrate with silver (Cu + 2Ag⁺ → Cu²⁺ + 2Ag). Both happen without any external circuit; "
                        + "the multimeter shows the deposits and the ions appearing in solution.");
        e.name("zinc", e.add(BLOCK, "zinc", 5, 0, 8, Dir.EAST, "fill", 0.3));
        e.name("cuSol", e.add(ELECTROLYTE, "copper_sulfate_1m", 6, 0, 8, Dir.UP));
        e.name("copper", e.add(BLOCK, "copper", 9, 0, 8, Dir.EAST, "fill", 0.3));
        e.name("agSol", e.add(ELECTROLYTE, "silver_nitrate_01m", 10, 0, 8, Dir.UP));
        return e.view(7.5, 3.5, 12.5, 28);
    }

    /** Zinc and copper in acid: connecting them moves hydrogen evolution onto the copper (galvanic corrosion). */
    public static Exhibit galvanicCorrosion() {
        Exhibit e = new Exhibit("corrosion", "Galvanic corrosion",
                List.of("CORROSION", "Zn and Cu in acid.", "Close the switch:", "H2 bubbles on Cu"),
                "Zinc and copper stand in dilute sulfuric acid. Alone, zinc corrodes only slowly because hydrogen "
                        + "evolution on zinc is very sluggish (exchange current ~1e-7 A/m²). Closing the switch "
                        + "connects it to copper, where hydrogen evolves far more easily: the zinc now corrodes "
                        + "much faster and bubbles stream from the copper — the principle behind sacrificial anodes.");
        e.name("zinc", e.add(BLOCK, "zinc", 6, 0, 8, Dir.EAST, "fill", 0.3));
        e.name("acid", e.add(ELECTROLYTE, "sulfuric_acid_1m", 7, 0, 8, Dir.UP));
        e.name("copper", e.add(BLOCK, "copper", 8, 0, 8, Dir.EAST, "fill", 0.3));
        e.add(WIRE, "copper", 6, 1, 8, Dir.UP);
        e.add(WIRE, "copper", 8, 1, 8, Dir.UP);
        e.path(INSULATED_WIRE, "copper", new int[][]{{6, 2, 8}, {8, 2, 8}});
        e.name("switch", e.add(SWITCH, "brass", 7, 2, 8, Dir.EAST));
        return e.view(7.5, 3.5, 12, 28);
    }

    /**
     * Compressed-air turbine: a bank of tanks at 10 bar discharges through a valve into a duct with a bladed
     * rotor; the rotor spins a light disk on its shaft. The shaft leaves the duct through a gland.
     */
    public static Exhibit airTurbine() {
        Exhibit e = new Exhibit("turbine", "Compressed-air turbine",
                List.of("AIR TURBINE", "10 bar receiver.", "Open the valve:", "the rotor spins up"),
                "Thirty-six pressure vessels hold air at 10 bar. Opening the valve lets it out through a 10 mm "
                        + "seat, where the flow chokes at the speed of sound, into a duct with a bladed rotor. "
                        + "The air meets the staggered blades at an angle; their lift has a component around the "
                        + "shaft — torque — and the rotor and the disk on its shaft spin up until the blades slice "
                        + "the flow edge-on. Watch the gauges fall as the receiver empties.");
        String st = "steel_1045";
        // receiver on a steel stand
        e.fill(BLOCK, st, 0, 0, 7, 3, 0, 9, Dir.UP);
        e.name("receiver", e.fill(TANK, st, 0, 1, 7, 3, 3, 9, Dir.UP, "pressure", 1e6, "wall", 0.004, "gauge_range", 16));
        e.name("valve", e.add(VALVE, "brass", 4, 2, 8, Dir.EAST, "bore", 0.010));
        e.add(PIPE, st, 5, 2, 8, Dir.EAST, "bore", 0.04);
        e.name("rotor", e.add(ROTOR, "aluminium_6061", 6, 2, 8, Dir.EAST, "blade_angle", 60, "pitch", 1));
        e.add(PIPE, st, 7, 2, 8, Dir.EAST, "bore", 0.04);
        e.add(PIPE, st, 7, 3, 8, Dir.UP, "bore", 0.04);
        e.name("shaft", e.fill(SHAFT, st, 6, 2, 8, 13, 2, 8, Dir.EAST, "diameter", 0.012));
        e.add(BEARING, st, 9, 2, 8, Dir.EAST);
        e.add(BEARING, st, 13, 2, 8, Dir.EAST);
        e.fill(BLOCK, st, 9, 0, 8, 9, 1, 8, Dir.UP);
        e.fill(BLOCK, st, 13, 0, 8, 13, 1, 8, Dir.UP);
        // a light aluminium disk on the shaft shows the rotation
        e.name("disk", e.add(BLOCK, "aluminium_6061", 11, 2, 8, Dir.EAST, "fill", 0.15));
        return e.view(6.5, 6, 16, 22);
    }

    /**
     * A fan coupled to a windmill by nothing but air: a geared hand crank drives a fan in a duct; the air it
     * moves turns a second, free rotor on its own shaft further down the duct.
     */
    public static Exhibit fanCoupling() {
        Exhibit e = new Exhibit("fan", "Fan and windmill",
                List.of("FLUID COUPLING", "Crank the fan.", "Air turns the", "far rotor"),
                "Turning the crank drives two 5:1 gear stages, so the fan turns 25 times faster than your "
                        + "hand. The fan's blades push air along the duct (it enters through the chimney on the "
                        + "left and leaves through the one on the right). Downstream the moving air meets a second "
                        + "rotor on a separate shaft and turns it: power transmitted through the air, as in a "
                        + "torque converter or a wind turbine.");
        String st = "steel_1045";
        int y = 3;
        // fan shaft along x at z = 8: pinion, a bearing, gland through the inlet pipe, fan
        int fanShaft = e.fill(SHAFT, st, 2, y, 8, 6, y, 8, Dir.EAST, "diameter", 0.012);
        e.name("fanShaft", fanShaft);
        e.add(GEAR, st, 2, y, 8, Dir.EAST, "teeth", 8, "pitch_radius", 0.5 / 16);
        e.add(BEARING, st, 4, y, 8, Dir.EAST);
        e.fill(BLOCK, st, 4, 0, 8, 4, y - 1, 8, Dir.UP);
        e.add(PIPE, "pvc", 5, y, 8, Dir.EAST, "bore", 0.05, "wall", 0.003);
        e.add(PIPE, "pvc", 5, y + 1, 8, Dir.UP, "bore", 0.05, "wall", 0.003);
        e.name("fan", e.add(ROTOR, "aluminium_6061", 6, y, 8, Dir.EAST, "blade_angle", 60, "pitch", 1));
        for (int x = 7; x <= 9; x++) e.add(PIPE, "pvc", x, y, 8, Dir.EAST, "bore", 0.05, "wall", 0.003);
        // windmill rotor on its own shaft, gland through the outlet pipe, two bearings
        e.name("windmill", e.add(ROTOR, "aluminium_6061", 10, y, 8, Dir.EAST, "blade_angle", 40, "pitch", 1));
        e.add(PIPE, "pvc", 11, y, 8, Dir.EAST, "bore", 0.05, "wall", 0.003);
        e.add(PIPE, "pvc", 11, y + 1, 8, Dir.UP, "bore", 0.05, "wall", 0.003);
        e.name("millShaft", e.fill(SHAFT, st, 10, y, 8, 13, y, 8, Dir.EAST, "diameter", 0.012));
        e.add(BEARING, st, 12, y, 8, Dir.EAST);
        e.add(BEARING, st, 13, y, 8, Dir.EAST);
        e.fill(BLOCK, st, 12, 0, 8, 12, y - 1, 8, Dir.UP);
        e.fill(BLOCK, st, 13, 0, 8, 13, y - 1, 8, Dir.UP);
        // intermediate shaft at z = 11: pinion driven by the crank gear, 40-tooth gear driving the fan pinion
        int midShaft = e.fill(SHAFT, st, 1, y, 11, 4, y, 11, Dir.EAST, "diameter", 0.015);
        e.add(GEAR, st, 1, y, 11, Dir.EAST, "teeth", 8, "pitch_radius", 0.5 / 16);
        e.add(GEAR, st, 2, y, 11, Dir.EAST, "teeth", 40, "pitch_radius", 2.5 / 16);
        e.add(BEARING, st, 4, y, 11, Dir.EAST);
        e.fill(BLOCK, st, 4, 0, 11, 4, y - 1, 11, Dir.UP);
        // crank shaft at z = 14 with a 40-tooth gear (centre distances 3 cells = 0.5 + 2.5)
        int crankShaft = e.fill(SHAFT, st, 0, y, 14, 3, y, 14, Dir.EAST, "diameter", 0.015);
        e.add(GEAR, st, 1, y, 14, Dir.EAST, "teeth", 40, "pitch_radius", 2.5 / 16);
        e.add(BEARING, st, 3, y, 14, Dir.EAST);
        e.fill(BLOCK, st, 3, 0, 14, 3, y - 1, 14, Dir.UP);
        e.name("crank", e.add(CRANK, st, 0, y, 14, Dir.WEST, "radius", 0.15));
        // the gallery opens with the train already turning (speeds in the gear ratios, each mesh reversing)
        e.init(crankShaft, new Exhibit.Initial(Double.NaN, 16, 0));
        e.init(midShaft, new Exhibit.Initial(Double.NaN, -80, 0));
        e.init(fanShaft, new Exhibit.Initial(Double.NaN, 400, 0));
        return e.view(6.5, 8, 21, 25);
    }

    /**
     * Faraday's induction ring (1831): an electrical-steel ring with a coil on each side. Closing the switch on the
     * primary starts a current whose flux, carried round the ring, threads the secondary: while it grows, the
     * secondary's EMF lights one LED; opening the switch collapses it and the other LED flashes.
     */
    public static Exhibit faradayRing() {
        Exhibit e = new Exhibit("faraday", "Faraday's ring",
                List.of("FARADAY'S RING", "Close the switch,", "then open it: watch", "the two LEDs"),
                "Two coils on one iron ring, as Michael Faraday wound them in 1831. Close the switch: current from the "
                        + "battery bank starts to flow in the 200-turn coil on the left, and its magnetic flux, guided "
                        + "round the ring by the iron (5000 times more permeable than air), passes through the 1000-turn "
                        + "coil on the right. Only while that flux is changing does it induce a voltage there (Faraday's "
                        + "law): the green LED flashes, about five times the battery's voltage for five times the turns. "
                        + "With the current steady, nothing. Open the switch: the flux collapses, the voltage reverses, and "
                        + "the red LED flashes. Without the iron, the coils would barely notice each other. The iron has a "
                        + "limit: the 3 A the bank drives would push the ring's flux density past the steel's 2 tesla, so "
                        + "the core saturates — its flux stops growing early, and the make pulse is cut short. Saturated "
                        + "iron is little better than air; transformers are designed never to reach it. And the steel remembers: "
                        + "switched off, it keeps part of its flux (remanence) — its domains stay where the current left them "
                        + "until a reverse field of its coercivity moves them back.");
        String core = "electrical_steel";
        // the ring, flat at y = 1 on oak corner blocks (non-magnetic, so they do not short the ring)
        for (int i = 4; i <= 10; i++) {
            e.add(BLOCK, core, i, 1, 5, Dir.UP);
            e.add(BLOCK, core, i, 1, 11, Dir.UP);
        }
        for (int z = 6; z <= 10; z++) {
            if (z == 8) continue;
            e.add(BLOCK, core, 4, 1, z, Dir.UP);
            e.add(BLOCK, core, 10, 1, z, Dir.UP);
        }
        for (int[] c : new int[][]{{4, 5}, {10, 5}, {4, 11}, {10, 11}}) e.add(BLOCK, "oak", c[0], 0, c[1], Dir.UP);
        // the coils, wound on the ring's sides (iron cores), leads up and down
        e.name("primary", e.add(COIL, "copper", 4, 1, 8, Dir.SOUTH, "turns", 200, "wire_diameter", 0.8e-3, "core", 1,
                "bore_fraction", 0.6, "lead_axis", 1));
        e.name("secondary", e.add(COIL, "copper", 10, 1, 8, Dir.SOUTH, "turns", 1000, "wire_diameter", 0.3e-3, "core", 1,
                "bore_fraction", 0.6, "lead_axis", 1));
        // primary circuit: a 5 F, 12 V bank, a 4 ohm resistor and the switch
        int bank = e.add(CAPACITOR, "alumina", 1, 0, 4, Dir.EAST, "capacitance", 5.0); // big: its slow fade induces too little to light an LED
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 12));
        e.add(RESISTOR, "alumina", 1, 0, 8, Dir.EAST, "resistance", 4.0);
        e.name("switch", e.add(SWITCH, "brass", 1, 0, 13, Dir.EAST));
        // secondary: two LEDs back to back through 1 kilohm
        e.name("green", e.add(LED, "silicon", 13, 0, 6, Dir.EAST, "wavelength", 530));
        e.name("red", e.add(LED, "silicon", 13, 0, 10, Dir.WEST, "wavelength", 630));
        e.add(RESISTOR, "alumina", 13, 0, 13, Dir.EAST, "resistance", 1000);
        Router r = new Router(e, 0, 0, 0, 15, 3, 15);
        r.pin("p1", 2, 0, 4).pin("p1", 4, 2, 8);
        r.pin("p2", 4, 0, 8).pin("p2", 2, 0, 8);
        r.pin("p3", 0, 0, 8).pin("p3", 2, 0, 13);
        r.pin("p4", 0, 0, 13).pin("p4", 0, 0, 4);
        r.pin("s1", 10, 2, 8).pin("s1", 12, 0, 6).pin("s1", 12, 0, 10);
        r.pin("s2", 14, 0, 6).pin("s2", 14, 0, 10).pin("s2", 14, 0, 13);
        r.pin("s3", 12, 0, 13).pin("s3", 10, 0, 8);
        r.route();
        return e.view(7.5, 7, 17, 35);
    }

    /**
     * A relay from parts: an iron-cored coil on a bracket over a steel armature that rides on a short vertical slide. A
     * copper contact on the armature sits just under a fixed contact wire; a brush picks the current off it. Close the
     * switch: the coil's field pulls the armature up across its 3 mm gap, the contacts meet and the lamp circuit's LED
     * lights. Open it: the armature drops and the light goes out.
     */
    public static Exhibit relay() {
        Exhibit e = new Exhibit("relay", "Relay",
                List.of("RELAY", "A coil pulls its", "armature up: the", "contacts close"),
                "An electromagnet switching another circuit. Close the switch and current in the coil magnetises its iron "
                        + "core; the field reaches across the 3 mm air gap to the steel armature below and pulls it up "
                        + "(the force grows steeply as the gap closes: the gap's magnetic reluctance g/μ0A falls). The "
                        + "armature carries a copper contact up against a fixed one, closing the second circuit: the green "
                        + "LED lights. Open the switch: the field collapses and gravity drops the armature, parting the "
                        + "contacts. A small current switches a separate, larger one — the telegraph's repeater, the "
                        + "first logic gates, and every car's starter relay.");
        String st = "steel_1045";
        // frame: a post with a bracket carrying the coil; a mount for the slide's bearing
        e.fill(BLOCK, st, 3, 0, 8, 3, 7, 8, Dir.UP);
        e.fill(BLOCK, st, 4, 7, 8, 5, 7, 8, Dir.UP);
        e.name("coil", e.add(COIL, "copper", 5, 6, 8, Dir.DOWN, "turns", 2000, "wire_diameter", 0.5e-3, "core", 1,
                "bore_fraction", 0.6, "lead_axis", 2));
        // the armature on a vertical slide with 1.5 mm of travel either way (stop collars)
        e.name("armature", e.add(BLOCK, st, 5, 5, 8, Dir.UP, "fill", 0.4));
        e.name("slide", e.fill(SHAFT, st, 5, 1, 8, 5, 4, 8, Dir.UP, "diameter", 0.012));
        e.add(LINEAR_BEARING, st, 5, 2, 8, Dir.UP, "travel", 0.0015);
        e.fill(BLOCK, st, 4, 0, 8, 4, 2, 8, Dir.UP);
        // contacts: a copper arm from the armature, its end just under a fixed bare wire (clear of the coil); a brush
        // takes the current off the moving one
        e.add(BLOCK, "copper", 6, 5, 8, Dir.UP, "fill", 0.2);
        e.name("moving", e.add(BLOCK, "copper", 7, 5, 8, Dir.UP, "fill", 0.2));
        e.name("fixed", e.add(WIRE, "copper", 7, 6, 8, Dir.UP, "diameter", 0.002));
        e.add(BRUSH, "graphite", 8, 5, 8, Dir.WEST);
        e.add(BLOCK, "nylon_66", 8, 6, 8, Dir.UP);
        e.add(BLOCK, "nylon_66", 8, 6, 9, Dir.UP);
        e.fill(BLOCK, "nylon_66", 8, 0, 9, 8, 5, 9, Dir.UP);
        // the coil's circuit: 12 V and a switch; the lamp circuit: 5 V, 220 Ω and a green LED through the contacts
        int bc = e.add(CAPACITOR, "alumina", 2, 1, 12, Dir.EAST, "capacitance", 50);
        e.init(bc, new Exhibit.Initial(Double.NaN, 0, 24));
        e.name("switch", e.add(SWITCH, "brass", 6, 1, 12, Dir.EAST));
        int bl = e.add(CAPACITOR, "alumina", 2, 1, 15, Dir.EAST, "capacitance", 10);
        e.init(bl, new Exhibit.Initial(Double.NaN, 0, 5));
        e.name("lamp", e.add(LED, "silicon", 10, 1, 15, Dir.EAST, "wavelength", 525, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", 13, 1, 15, Dir.EAST, "resistance", 220); // between the LED and the brush
        // a pull-up: with the contacts open it holds the brush side at the supply, so the LED sees no voltage
        e.add(RESISTOR, "alumina", 13, 1, 13, Dir.EAST, "resistance", 10_000);
        Router r = new Router(e, 0, 0, 0, 15, 8, 16);
        r.keepClear(6, 4, 8, 7, 4, 8); // nothing under the moving arm (it would make a second, lower contact)
        r.pin("c+", 3, 1, 12).pin("c+", 5, 1, 12);
        r.pin("sw", 7, 1, 12).pin("sw", 5, 6, 9);
        r.pin("c-", 5, 6, 7).pin("c-", 1, 1, 12);
        r.pin("l+", 3, 1, 15).pin("l+", 9, 1, 15).pin("l+", 12, 1, 13);
        r.pin("lk", 11, 1, 15).pin("lk", 12, 1, 15);
        r.pin("lr", 14, 1, 15).pin("lr", 14, 1, 13).pin("lr", 9, 5, 8);
        r.pin("fx", 7, 7, 8).pin("fx", 1, 1, 15);
        r.route();
        return e.view(7, 6, 15, 15, 160);
    }

    /**
     * A trembler (an electric bell's interrupter): the relay's coil is fed through its own contacts, which the armature
     * opens as it is pulled up. With the circuit broken the coil's current decays through a flyback diode; the pull
     * fails, the armature drops, the contacts close and it all starts again — an oscillator from a magnet, a spring of
     * gravity and a switch. An LED across the coil blinks with each closing.
     */
    public static Exhibit trembler() {
        Exhibit e = new Exhibit("trembler", "Trembler",
                List.of("TREMBLER", "The relay switches", "itself off and on:", "a bell's buzzer"),
                "This relay's coil gets its current through its own contacts, which are closed while the armature rests "
                        + "and open when it is pulled up. Close the switch: the coil pulls the armature, which breaks the "
                        + "coil's own circuit. The coil's current cannot stop at once (its field holds energy, ½LI²); the "
                        + "diode across it lets the current run on, dying away over a tenth of a second as the winding's "
                        + "resistance turns it to heat. The pull fails, the armature drops, the contacts close and the "
                        + "cycle repeats, a few times a second: the interrupter of an electric bell or buzzer. The LED "
                        + "across the coil lights while the contacts feed it. Without the diode the contacts would arc.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 3, 0, 8, 3, 7, 8, Dir.UP);
        e.fill(BLOCK, st, 4, 7, 8, 5, 7, 8, Dir.UP);
        e.name("coil", e.add(COIL, "copper", 5, 6, 8, Dir.DOWN, "turns", 2000, "wire_diameter", 0.5e-3, "core", 1,
                "bore_fraction", 0.6, "lead_axis", 2));
        e.name("armature", e.add(BLOCK, st, 5, 5, 8, Dir.UP, "fill", 0.4));
        e.name("slide", e.fill(SHAFT, st, 5, 1, 8, 5, 4, 8, Dir.UP, "diameter", 0.012));
        e.add(LINEAR_BEARING, st, 5, 2, 8, Dir.UP, "travel", 0.0015);
        e.fill(BLOCK, st, 4, 0, 8, 4, 2, 8, Dir.UP);
        // contacts: the copper arm's end rests on a fixed wire below it (normally closed); a brush on the arm
        e.add(BLOCK, "copper", 6, 5, 8, Dir.UP, "fill", 0.2);
        e.name("moving", e.add(BLOCK, "copper", 7, 5, 8, Dir.UP, "fill", 0.2));
        e.name("fixed", e.add(WIRE, "copper", 7, 4, 8, Dir.UP, "diameter", 0.002));
        e.add(BRUSH, "graphite", 8, 5, 8, Dir.WEST);
        e.add(BLOCK, "nylon_66", 8, 6, 8, Dir.UP);
        e.add(BLOCK, "nylon_66", 8, 6, 9, Dir.UP);
        e.fill(BLOCK, "nylon_66", 8, 0, 9, 8, 5, 9, Dir.UP);
        // supply, switch, flyback diode across the coil, an indicator LED (with its resistor) across it too
        int bc = e.add(CAPACITOR, "alumina", 2, 1, 12, Dir.EAST, "capacitance", 50);
        e.init(bc, new Exhibit.Initial(Double.NaN, 0, 36));
        e.name("switch", e.add(SWITCH, "brass", 6, 1, 12, Dir.EAST));
        e.name("diode", e.add(DIODE, "silicon", 11, 1, 12, Dir.EAST));
        e.name("lamp", e.add(LED, "silicon", 11, 1, 15, Dir.EAST, "wavelength", 590, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", 14, 1, 15, Dir.EAST, "resistance", 2200);
        Router r = new Router(e, 0, 0, 0, 16, 8, 16);
        r.keepClear(6, 6, 8, 7, 6, 8); // nothing over the moving arm (it would make a second contact)
        r.pin("c+", 3, 1, 12).pin("c+", 5, 1, 12);
        r.pin("sw", 7, 1, 12).pin("sw", 5, 6, 9).pin("sw", 12, 1, 12).pin("sw", 10, 1, 15);   // coil +, diode K, LED A
        r.pin("cb", 5, 6, 7).pin("cb", 9, 5, 8).pin("cb", 10, 1, 12).pin("cb", 15, 1, 15);   // coil −, brush, diode A, LED R
        r.pin("lr", 12, 1, 15).pin("lr", 13, 1, 15);
        r.pin("fx", 7, 3, 8).pin("fx", 1, 1, 12);
        r.route();
        return e.view(7, 6, 15, 15, 160);
    }

    /**
     * A magnet's pull: two neodymium magnets on brackets, each with an iron armature hanging below it on a vertical slide,
     * loaded with a brass weight (3.2 kg all told). One armature was put up against its magnet; the other starts 5 cm below. Close to,
     * the magnet holds five hundred newtons; 5 cm away, under fifteen: one hangs on, the other falls to its stop.
     */
    public static Exhibit magnetPull() {
        Exhibit e = new Exhibit("magnetpull", "A magnet's pull",
                List.of("MAGNET AND IRON", "Close, it holds 50 kg;", "5 cm away it cannot", "lift 3 kg"),
                "Two identical neodymium magnets, each above an iron armature on a vertical slide carrying a brass "
                        + "weight (3.2 kg all told, 31 N). The left armature was set against its magnet; the right one 5 cm below. Iron facing a "
                        + "magnet is magnetised by it and acts like a mirror image of the magnet behind its face, so they "
                        + "attract as two magnets do: about 530 N with the few millimetres between them here, but the pull "
                        + "falls off steeply — 195 N at 16 mm, 14 N at 56 mm. So the left armature hangs on, weight and "
                        + "all, while the right one, never near enough to be caught, drops to the end of its slide. That "
                        + "is why magnetic catches and lifting magnets work only at contact.");
        String st = "steel_1045";
        for (int k = 0; k < 2; k++) {
            int x = 4 + 6 * k, z = 6;
            String side = k == 0 ? "near" : "far";
            e.add(LINEAR_BEARING, st, x, 1, z, Dir.UP);
            e.fill(BLOCK, st, x + 1, 0, z, x + 1, 1, z, Dir.UP);
            int rod = e.fill(SHAFT, st, x, 1, z, x, 3, z, Dir.UP, "diameter", 0.012);
            e.name(side, rod);
            e.init(rod, new Exhibit.Initial(Double.NaN, 0, 0, k == 0 ? 0 : -0.05));
            e.add(BLOCK, "brass", x, 3, z, Dir.UP); // the weight: a 2 kg brass block on the rod (3.2 kg all told), clear of the bearing
            e.name(side + "Armature", e.add(BLOCK, "iron", x, 4, z, Dir.UP, "fill", 0.5));
            e.name(side + "Magnet", e.add(MAGNET, "ndfeb_n42", x, 5, z, Dir.UP));
            e.fill(BLOCK, st, x, 6, z, x + 2, 6, z, Dir.UP);
            e.fill(BLOCK, st, x + 2, 0, z, x + 2, 5, z, Dir.UP);
        }
        return e.view(7.5, 5, 16, 15);
    }

    /**
     * Arago's disc: two discs on shafts, spun up, each passing between two strong magnets at its rim. The aluminium
     * one is braked by the eddy currents its motion through the field induces, and warms; the nylon one, which cannot
     * carry a current, coasts on.
     */
    public static Exhibit eddyBrake() {
        Exhibit e = new Exhibit("eddy", "Eddy-current brake",
                List.of("EDDY CURRENTS", "Magnets brake the", "aluminium disc,", "not the nylon one"),
                "Each disc spins past two magnets at its rim. Where the aluminium passes through their field it is "
                        + "cutting magnetic flux, and like a dynamo's wire it gets an EMF, v × B; aluminium conducts, so "
                        + "currents swirl in it (eddy currents), and a current crossing a magnetic field feels a force: "
                        + "against the motion. The disc slows, its kinetic energy turning to heat in the metal, and the "
                        + "faster it turns the harder the brake pulls (F = σ·t·B²·v per unit of disc in the field). "
                        + "Nothing touches it. The nylon disc, an insulator, carries no current and coasts on. Trains, "
                        + "roller coasters and exercise bikes brake like this; Arago saw it in 1824.");
        String st = "steel_1045";
        String[] disc = {"aluminium_6061", "nylon_66"};
        String[] name = {"aluminium", "nylon"};
        for (int k = 0; k < 2; k++) {
            int z = 3 + 8 * k;
            e.fill(BLOCK, st, 2, 0, z, 2, 2, z, Dir.UP);
            e.fill(BLOCK, st, 4, 0, z, 4, 2, z, Dir.UP);
            e.add(BEARING, st, 2, 3, z, Dir.EAST);
            e.add(BEARING, st, 4, 3, z, Dir.EAST);
            int s = e.fill(SHAFT, st, 1, 3, z, 6, 3, z, Dir.EAST, "diameter", 0.012);
            e.name(name[k] + "Shaft", s);
            e.init(s, new Exhibit.Initial(Double.NaN, 60, 0));
            e.add(CRANK, st, 0, 3, z, Dir.EAST, "radius", 0.1);
            // the disc: a 5 × 5 plate, 6 mm thick, on the shaft's end
            int d = -1;
            for (int y = 1; y <= 5; y++)
                for (int dz = -2; dz <= 2; dz++) {
                    int q = e.add(BLOCK, disc[k], 6, y, z + dz, Dir.EAST, "fill", 0.1);
                    if (d < 0) d = q;
                    if (y == 5 && dz == 0) e.name(name[k] + "Rim", q);
                }
            e.name(name[k] + "Disc", d);
            // two magnets just off the rim, either side of it, magnetised through it; each on an arm from behind
            for (int x : new int[]{5, 7}) {
                e.add(MAGNET, "ndfeb_n42", x, 6, z, Dir.EAST);
                e.fill(BLOCK, st, x, 6, z - 1, x, 6, z - 3, Dir.UP);
                e.fill(BLOCK, st, x, 0, z - 3, x, 5, z - 3, Dir.UP);
            }
        }
        return e.view(9, 6, 17, 14, 150);
    }

    /**
     * Wöhler's axles: two shafts the same size, each in two bearings with the same lead disc overhung beyond the outer
     * one, spun up and left to turn. The disc's weight bends each shaft at its outer bearing; as it turns, the bending
     * reverses every revolution (rotating bending, ~260 MPa). The steel shaft is below its endurance limit and turns
     * for ever; the aluminium one, at the same stress, cracks through after about two thousand turns and drops its disc.
     */
    public static Exhibit fatigue() {
        Exhibit e = new Exhibit("fatigue", "Metal fatigue",
                List.of("METAL FATIGUE", "Same load, same size:", "aluminium cracks,", "steel runs on"),
                "Each shaft turns in two bearings with a lead disc of 11 kg overhung beyond the outer one. The disc's "
                        + "weight bends the shaft there: its top fibres stretch and its bottom ones squeeze, about 260 MPa "
                        + "each way, well below what either metal would take once. But the shaft turns, so every fibre "
                        + "goes from tension to compression and back once a revolution. Wöhler found (testing railway "
                        + "axles, 1860s) that metal cracks under a stress repeated often enough, the more readily the "
                        + "higher it is. Steel has an endurance limit (here 280 MPa): below it, it lasts for ever. "
                        + "Aluminium has none: at this stress the left shaft cracks after about two thousand turns and its "
                        + "disc falls. This is why aircraft are inspected and retired by flight hours.");
        String st = "steel_1045";
        String[] shaft = {"aluminium_6061", st};
        String[] name = {"aluminium", "steel"};
        for (int k = 0; k < 2; k++) {
            int z = 4 + 6 * k;
            // the frame: two columns carrying the bearings
            e.fill(BLOCK, st, 2, 0, z, 2, 2, z, Dir.UP);
            e.fill(BLOCK, st, 4, 0, z, 4, 2, z, Dir.UP);
            e.add(BEARING, st, 2, 3, z, Dir.EAST);
            e.add(BEARING, st, 4, 3, z, Dir.EAST);
            int s = e.fill(SHAFT, shaft[k], 1, 3, z, 6, 3, z, Dir.EAST, "diameter", 0.0084);
            e.name(name[k] + "Shaft", s);
            e.name(name[k] + "Root", s + 3); // the shaft in its outer bearing, where it bends most
            e.init(s, new Exhibit.Initial(Double.NaN, 150, 0));
            e.add(CRANK, st, 0, 3, z, Dir.EAST, "radius", 0.1);
            // the disc: nine lead plates on the shaft's end (the middle one round the shaft itself)
            int d = -1;
            for (int y = 2; y <= 4; y++)
                for (int dz = -1; dz <= 1; dz++) {
                    int q = e.add(BLOCK, "lead", 6, y, z + dz, Dir.EAST, "fill", 0.5);
                    if (d < 0) d = q;
                }
            e.name(name[k] + "Disc", d);
        }
        return e.view(3.5, 5, 17, 18, -150);
    }

    /**
     * Euler buckling: two strips of thin steel (62.5 mm wide, 1.25 mm thick) standing on end, one twice as tall. A lead
     * block on the short one stands; the same block on the tall one buckles it — at a load its steel could carry
     * many times over in plain compression.
     */
    public static Exhibit buckling() {
        Exhibit e = new Exhibit("buckling", "Buckling",
                List.of("BUCKLING", "Same strip, same load:", "the tall one buckles", "(place a lead block)"),
                "Two strips of the same thin steel stand on end. The short one carries a lead block (27 N). Put the same "
                        + "block on top of the tall one and it buckles: a slender column fails not by crushing (the load is "
                        + "a small fraction of what the steel could take) but by bowing sideways. Euler found the critical "
                        + "load P = π²EI/(2L)² for a column fixed at the foot and free at the top: double the length and "
                        + "it drops to a quarter. The short strip could take 51 N, the tall one only 16 N.");
        String st = "steel_1045";
        e.add(BLOCK, st, 4, 0, 8, Dir.UP);
        e.add(BLOCK, st, 10, 0, 8, Dir.UP);
        e.name("short", e.fill(BLOCK, st, 4, 1, 8, 4, 4, 8, Dir.SOUTH, "fill", 0.02));
        e.name("shortLoad", e.add(BLOCK, "lead", 4, 5, 8, Dir.UP));
        int tall = e.fill(BLOCK, st, 10, 1, 8, 10, 8, 8, Dir.SOUTH, "fill", 0.02);
        e.name("tall", tall);
        e.name("tallTop", tall + 7); // where the block goes
        return e.view(7, 6, 23, 8);
    }

    /** Two identical sealed vessels; one sits against a red-hot copper block. Gay-Lussac's law on a gauge. */
    public static Exhibit heatedVessel() {
        Exhibit e = new Exhibit("pressure", "Heated pressure vessel",
                List.of("GAY-LUSSAC", "Heated vessel vs", "cold vessel.", "Watch the gauges"),
                "Two sealed steel vessels hold air at atmospheric pressure. The left one stands against a copper "
                        + "block at 1100 K: heat conducts through its wall and convects into the gas, whose "
                        + "pressure rises in proportion to its absolute temperature (p/T constant at fixed volume "
                        + "and mass). The gauges read pressure above ambient.");
        int hot = e.add(BLOCK, "copper", 4, 0, 8, Dir.UP);
        e.name("heater", hot);
        e.init(hot, new Exhibit.Initial(1100, 0, 0));
        e.add(BLOCK, "copper", 4, 1, 8, Dir.UP);
        e.init(e.parts.size() - 1, new Exhibit.Initial(1100, 0, 0));
        e.name("hotTank", e.add(TANK, "steel_1045", 5, 0, 8, Dir.UP));
        e.add(TANK, "steel_1045", 5, 1, 8, Dir.UP);
        e.name("coldTank", e.add(TANK, "steel_1045", 10, 0, 8, Dir.UP));
        e.add(TANK, "steel_1045", 10, 1, 8, Dir.UP);
        return e.view(7.5, 3.5, 13, 22);
    }

    /**
     * Alpha Stirling engine: a hot and a cold piston cylinder 90° apart around one crank, joined by a thin
     * transfer pipe and charged with air. The hot cylinder's head stands against a red-hot copper block.
     */
    public static Exhibit stirlingEngine() {
        Exhibit e = new Exhibit("stirling", "Stirling engine",
                List.of("STIRLING ENGINE", "Heat in, electricity", "out: meter the", "resistor"),
                "A heat engine from parts. The upper cylinder's head is heated by a copper block at 1100 K; the "
                        + "side cylinder stays near room temperature. Both pistons drive one crank throw, 90° "
                        + "apart, so the trapped air is mostly in the hot cylinder while it expands and mostly in "
                        + "the cold one while it is compressed. Expansion at high pressure does more work than "
                        + "compression at low pressure: the difference turns the flywheel (it was given a flick "
                        + "to start). Heat flows in from the block and out of the cold cylinder's fins. Magnets on "
                        + "the flywheel sweep past a coil: the engine drives a generator lighting up a resistor.");
        String st = "steel_1045";
        int y = 3;
        int shaft = e.fill(SHAFT, st, 0, y, 8, 7, y, 8, Dir.EAST, "diameter", 0.012);
        e.name("shaft", shaft);
        e.init(shaft, new Exhibit.Initial(Double.NaN, 20, 0));
        e.add(BEARING, st, 0, y, 8, Dir.EAST);
        e.add(BEARING, st, 7, y, 8, Dir.EAST);
        e.fill(BLOCK, st, 0, 0, 8, 0, y - 1, 8, Dir.UP);
        e.fill(BLOCK, st, 7, 0, 8, 7, y - 1, 8, Dir.UP);
        e.add(ECCENTRIC, st, 3, y, 8, Dir.EAST, "throw", 0.015);
        e.name("flywheel", e.fill(BLOCK, st, 5, y - 1, 7, 5, y + 1, 9, Dir.EAST, "fill", 0.3));
        // hot cylinder above the crank, cold cylinder beside it; air charged to 4 bar
        int hot = e.add(CYLINDER, "steel_1045", 3, y + 2, 8, Dir.DOWN, "bore", 0.04, "fins", 6,
                "ring_friction", 0.5);
        e.name("hot", hot);
        e.init(hot, new Exhibit.Initial(900, 0, 0)); // the gallery opens with the engine warmed up
        e.name("cold", e.add(CYLINDER, "aluminium_6061", 3, y, 10, Dir.NORTH, "bore", 0.04, "fins", 6,
                "ring_friction", 0.5));
        // aluminium cooling block closing the cold cylinder's head
        e.add(BLOCK, "aluminium_6061", 3, y, 11, Dir.SOUTH);
        e.add(PIPE, "copper", 3, y + 2, 9, Dir.SOUTH, "bore", 0.008);
        e.add(PIPE, "copper", 3, y + 2, 10, Dir.DOWN, "bore", 0.008);
        e.add(PIPE, "copper", 3, y + 1, 10, Dir.DOWN, "bore", 0.008);
        e.add(BLOCK, st, 3, y + 3, 10, Dir.UP, "fill", 0.2); // blind flange on the elbow
        int h1 = e.add(BLOCK, "copper", 3, y + 3, 8, Dir.UP);
        int h2 = e.add(BLOCK, "copper", 3, y + 4, 8, Dir.UP);
        e.name("heater", h1);
        e.init(h1, new Exhibit.Initial(1100, 0, 0));
        e.init(h2, new Exhibit.Initial(1100, 0, 0));
        // generator: two magnets on the flywheel sweep past a coil beside the bearing; a resistor is the load
        e.add(MAGNET, "ndfeb_n42", 6, y, 7, Dir.EAST);
        e.add(MAGNET, "ndfeb_n42", 6, y, 9, Dir.WEST);
        e.name("coil", e.add(COIL, "copper", 7, y, 9, Dir.EAST, "turns", 400, "wire_diameter", 0.5e-3, "lead_axis", 1));
        e.path(INSULATED_WIRE, "copper", new int[][]{{7, y + 1, 9}, {7, y + 1, 10}, {7, y - 1, 10}, {7, y - 1, 9}});
        e.name("load", e.add(RESISTOR, "alumina", 7, y, 10, Dir.UP, "resistance", 5));
        return e.view(6.5, 6.5, 16, 20);
    }

    /**
     * A single-acting steam engine from parts. A steel boiler, 60 % full of water at 145 °C, sits on a heating element
     * (on an insulating mica block); a safety valve on its dome lifts at 4.5 bar. Steam goes through a hand regulator to an
     * inlet valve worked by a cam on the crankshaft, which admits it to the cylinder just after top dead centre
     * and cuts it off part way down the stroke; the steam then expands. A second cam opens the exhaust valve for
     * the return stroke and the steam puffs out to the air.
     */
    public static Exhibit steamEngine() {
        return steamEngine(false, 8);
    }

    /**
     * The steam engine with its exhaust led into a sealed, cooled condenser instead of the air (Watt's separate
     * condenser): the spent steam meets 7 kPa instead of 101, and the same engine carries twice the load.
     */
    public static Exhibit condensingEngine() {
        return steamEngine(true, 16);
    }

    /**
     * @param condensing the exhaust into a sealed, cooled condenser instead of the air
     * @param brakeForce the Prony brake's spring force [N]
     */
    public static Exhibit steamEngine(boolean condensing, double brakeForce) {
        Exhibit e = condensing ? new Exhibit("condensing", "Condensing steam engine",
                List.of("CONDENSING ENGINE", "Exhaust into a", "vacuum: twice the", "load, same steam"),
                "The steam engine next door again, with one change: the exhaust valve opens into a sealed copper "
                        + "condenser instead of the air. The condenser holds only water and its own vapour, kept near 40 °C "
                        + "by the copper blocks clasping it, so its pressure is water's vapour pressure there: 7 kPa, not "
                        + "the atmosphere's 101. The spent steam rushes into it and condenses on the cold water, and the "
                        + "piston's upward stroke pushes against almost nothing. Each stroke's steam does more work — "
                        + "this engine carries a brake twice as hard as its neighbour's at about the same speed. That "
                        + "was James Watt's improvement (1769): Newcomen's engines condensed in the cylinder itself and "
                        + "wasted most of their heat re-warming it. Nothing here is told it is a condenser: steam "
                        + "flows by pressure, condenses where it is colder than its saturation temperature, and the "
                        + "condenser's pressure follows its temperature. Watch it warm, slowly — its blocks soak up the "
                        + "heat (a real condenser is water-cooled) — and the water it collects is the boiler's, which "
                        + "has no feed pump to return it.")
                : new Exhibit("steam", "Steam engine",
                List.of("STEAM ENGINE", "Boiler, cam valves,", "piston, flywheel.", "Shut the regulator"),
                "Water boils in the steel boiler, heated by a 600 W element under its floor; its pressure follows the "
                        + "saturation curve of water (4 bar at 145 °C) and a spring-loaded safety valve on top lifts "
                        + "if it climbs past 4.5 bar above the air. Through the open regulator steam flows to the inlet "
                        + "valve, which a cam on the crankshaft opens just after the piston passes the top of its "
                        + "stroke and closes part way down (cut-off); the trapped steam expands and keeps pushing. "
                        + "A second cam opens the exhaust valve for the upward stroke and the spent steam puffs out. "
                        + "The flywheel carries the crank through the exhaust stroke. An oak brake block pressed against "
                        + "the flywheel is the load: its friction turns the engine's work into heat (a Prony-brake "
                        + "dynamometer: torque μFr times speed). Shut the regulator and the engine "
                        + "coasts to a stop while the boiler pressure climbs until the safety valve blows; open it "
                        + "and give the flywheel a turn to restart. A second, smaller element against the cylinder is its "
                        + "jacket: without it the cylinder wall cools below the steam's saturation temperature, each "
                        + "admission condenses on it, and the engine slowly drowns and slows (Watt jacketed his). "
                        + "Nothing here is a scripted "
                        + "'engine': boiling, valve flow, expansion work and friction are each computed.");
        String st = "steel_1045";
        int y = 3;
        Object[] steam = {"fluid", 7};
        int shaft = e.fill(SHAFT, st, 0, y, 8, 9, y, 8, Dir.EAST, "diameter", 0.012);
        e.name("shaft", shaft);
        e.init(shaft, new Exhibit.Initial(Double.NaN, 15, 0, 0.3)); // running: given a turn to start
        e.add(BEARING, st, 0, y, 8, Dir.EAST);
        e.add(BEARING, st, 9, y, 8, Dir.EAST);
        e.fill(BLOCK, st, 0, 0, 8, 0, y - 1, 8, Dir.UP);
        e.fill(BLOCK, st, 9, 0, 8, 9, y - 1, 8, Dir.UP);
        // crank between two cams: exhaust cam (west) and inlet cam (east)
        e.add(ECCENTRIC, st, 2, y, 8, Dir.EAST, "throw", 0.012, "phase", 95);
        e.add(ECCENTRIC, st, 3, y, 8, Dir.EAST, "throw", 0.012);
        e.add(ECCENTRIC, st, 4, y, 8, Dir.EAST, "throw", 0.012, "phase", -40);
        e.name("flywheel", e.fill(BLOCK, st, 7, y - 1, 7, 7, y + 1, 9, Dir.EAST, "fill", 0.3));
        // cylinder above the crank, its head closed by a cap; valves on either side, their stems over the cams
        double warm = 412; // the engine is warmed through (lagged cylinder): steam does not flood it with condensate
        int cyl = e.add(CYLINDER, st, 3, y + 2, 8, Dir.DOWN, with(steam, "bore", 0.035, "ring_friction", 3.0));
        e.name("cylinder", cyl);
        e.add(BLOCK, st, 3, y + 3, 8, Dir.UP, "fill", 0.3);
        int exhaust = e.add(VALVE, "brass", 2, y + 2, 8, Dir.WEST, with(steam, "bore", 0.010));
        e.name("exhaust", exhaust);
        if (condensing) {
            // a sealed copper condenser on the exhaust seat, a quarter full of water at 40 °C (so 7.4 kPa), clasped
            // by copper blocks that soak up the heat the steam gives up
            int cnd = e.add(TANK, "copper", 1, y + 2, 8, Dir.UP, with(steam, "fill", 0.25, "wall", 0.002));
            e.name("condenser", cnd);
            e.init(cnd, new Exhibit.Initial(313, 0, 0));
            for (int[] b : new int[][]{{1, y + 2, 7}, {1, y + 2, 9}, {0, y + 2, 8}, {1, y + 3, 8}, {0, y + 2, 7}, {0, y + 2, 9}}) {
                e.init(e.add(BLOCK, "copper", b[0], b[1], b[2], Dir.UP), new Exhibit.Initial(300, 0, 0));
            }
        }
        int inlet = e.add(VALVE, "brass", 4, y + 2, 8, Dir.WEST, with(steam, "bore", 0.008, "lap", 0.7));
        e.name("inlet", inlet);
        // steam line: elbow, regulator, boiler riser
        int elbow = e.add(PIPE, "copper", 5, y + 2, 8, Dir.SOUTH, with(steam, "bore", 0.008));
        e.add(BLOCK, st, 5, y + 2, 7, Dir.UP, "fill", 0.2); // blind flange
        int l1 = e.add(PIPE, "copper", 5, y + 2, 9, Dir.SOUTH, with(steam, "bore", 0.008));
        int reg = e.add(VALVE, "brass", 5, y + 2, 10, Dir.NORTH, with(steam, "bore", 0.0012)); // the throttle
        e.name("regulator", reg);
        e.latch(reg); // open
        int l2 = e.add(PIPE, "copper", 5, y + 2, 11, Dir.SOUTH, with(steam, "bore", 0.008));
        int l3 = e.add(PIPE, "copper", 5, y + 2, 12, Dir.SOUTH, with(steam, "bore", 0.008));
        int riser = e.add(PIPE, "copper", 5, y + 2, 13, Dir.UP, with(steam, "bore", 0.012));
        int safety = e.add(VALVE, "brass", 5, y + 3, 13, Dir.UP, with(steam, "bore", 0.006, "relief", 4.5e5));
        e.name("safety", safety);
        int boiler = e.add(TANK, st, 5, y + 1, 13, Dir.UP, with(steam, "fill", 0.6, "wall", 0.003, "gauge_range", 6));
        e.name("boiler", boiler);
        // the boiler and its pipework up to the regulator are at steam temperature
        for (int i : new int[]{cyl, exhaust, inlet, elbow, l1}) e.init(i, new Exhibit.Initial(warm, 0, 0));
        for (int i : new int[]{reg, l2, l3, riser, safety, boiler}) e.init(i, new Exhibit.Initial(418, 0, 0));
        // load: a Prony brake, an oak block sprung against the underside of the flywheel (a dynamometer)
        int brake = e.add(BRAKE, "oak", 7, y - 2, 8, Dir.UP, "force", brakeForce);
        e.name("brake", brake);
        e.latch(brake); // applied
        e.add(BLOCK, st, 7, 0, 8, Dir.UP);
        // the fire: a heating element pressed under the boiler's floor, on a capacitor bank (600 W at first, 0.9 MJ in
        // all: the boiler stays pressed for many minutes, its safety valve lifting while the engine idles)
        e.fill(BLOCK, st, 5, 0, 13, 5, y - 2, 13, Dir.UP);
        e.add(BLOCK, "mica", 5, y - 1, 13, Dir.UP); // insulating: the element's heat goes up into the boiler, not to the ground
        int fire = e.add(RESISTOR, "alumina", 5, y, 13, Dir.EAST, "resistance", 6.0, "contact_area", 0.003,
                "surface_area", 0.002, "mass", 0.1, "max_temperature", 1500);
        e.name("fire", fire);
        e.init(fire, new Exhibit.Initial(470, 0, 0)); // alight already
        int bank = e.add(CAPACITOR, "alumina", 8, 0, 14, Dir.EAST, "capacitance", 500);
        e.name("bank", bank);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 60));
        // a jacket: a small element against the cylinder keeps its wall above the steam's saturation temperature, or each
        // admission of steam condenses on it and the cylinder slowly fills with water (as Watt found, and jacketed his)
        int jacket = e.add(RESISTOR, "alumina", 3, y + 2, 9, Dir.UP, "resistance", 30.0, "contact_area", 0.002,
                "surface_area", 0.002, "mass", 0.05);
        e.name("jacket", jacket);
        e.init(jacket, new Exhibit.Initial(430, 0, 0));
        Router r = new Router(e, 0, 0, 0, 15, y + 4, 15);
        r.pin("f+", 6, y, 13).pin("f+", 9, 0, 14).pin("f+", 3, y + 3, 9);
        r.pin("f-", 4, y, 13).pin("f-", 7, 0, 14).pin("f-", 3, y + 1, 9);
        r.route();
        return e.view(4.5, 11, 18, 42);
    }

    /** Plate thickness (as a fraction of the cell) and material of each shelf, north to south. */
    public static final Object[][] SHELVES = {{"aluminium_6061", 0.04}, {"steel_1045", 0.02}, {"oak", 0.2}, {"glass", 0.075}};

    /**
     * Strength of materials: four cantilever shelves of thin plate — aluminium, steel, oak, glass — stand out from a
     * steel wall, each carrying the same lead block at its tip. The frame analysis finds the bending stress at each
     * shelf root; each holds with a different margin. Add a second lead block and the thinnest yields.
     */
    public static Exhibit shelves() {
        Exhibit e = new Exhibit("shelves", "Cantilever shelves",
                List.of("STRENGTH OF", "MATERIALS: shelves", "under the same", "load. Add weight!"),
                "Each shelf is a cantilever four plates long, carrying a 2.8 kg lead block at its tip. The bending moment "
                        + "at the wall is the load times its reach (about 7 N·m), and the plate resists it with stress "
                        + "σ = 6M/(b t²): it rises with the square of thinness. 2.5 mm of aluminium, 1.25 mm of steel, "
                        + "12.5 mm of oak and 4.7 mm of glass all hold, the steel close to its yield strength and the "
                        + "glass well into its brittle fracture strength. Stack a second lead block on the steel or the "
                        + "glass shelf: past yield (or fracture) the plate breaks and everything beyond it falls.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 1, 0, 1, 1, 4, 12, Dir.UP);
        for (int k = 0; k < SHELVES.length; k++) {
            int z = 2 + 3 * k;
            String m = (String) SHELVES[k][0];
            int first = e.fill(BLOCK, m, 2, 3, z, 5, 3, z, Dir.UP, "fill", (Double) SHELVES[k][1]);
            e.name("shelf" + k, first);
            e.name("load" + k, e.add(BLOCK, "lead", 5, 4, z, Dir.UP));
        }
        return e.view(8, 7, 17, 25);
    }

    /** Parameter list {@code base} followed by {@code more}. */
    static Object[] with(Object[] base, Object... more) {
        Object[] o = java.util.Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, o, base.length, more.length);
        return o;
    }

    /**
     * An NPN transistor switching an LED: a small base current through a switch and 10 kΩ controls a
     * 20 mA collector current. Powered by a 10 F supercapacitor charged to 5 V.
     */
    public static Exhibit transistorSwitch() {
        Exhibit e = new Exhibit("transistor", "Transistor switch",
                List.of("TRANSISTOR", "0.4 mA into the", "base switches", "20 mA LED current"),
                "An NPN bipolar transistor: closing the switch drives about 0.4 mA through 10 kΩ into the base. "
                        + "Electrons injected from the emitter mostly diffuse across the thin base into the "
                        + "collector (current gain β ≈ 150), so the transistor saturates and the LED, in series "
                        + "with 150 Ω, lights with about 20 mA. Meter the base and collector currents. The 10 F "
                        + "supercapacitor at 5 V is the battery.");
        String cu = "copper";
        int bat = e.add(CAPACITOR, "alumina", 2, 0, 8, Dir.EAST, "capacitance", 10);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        e.path(INSULATED_WIRE, cu, new int[][]{{3, 0, 8}, {4, 0, 8}, {4, 0, 7}, {4, 0, 6}, {7, 0, 6}, {8, 0, 6}, {8, 0, 7},
                {7, 0, 8}, {9, 0, 8}, {9, 0, 9}, {9, 0, 10}, {8, 0, 10}, {7, 0, 10}, {6, 0, 10}, {5, 0, 10}, {4, 0, 10},
                {3, 0, 10}, {2, 0, 10}, {1, 0, 10}, {1, 0, 9}, {1, 0, 8}});
        e.name("led", e.add(LED, "silicon", 5, 0, 8, Dir.EAST, "wavelength", 630));
        e.name("ledResistor", e.add(RESISTOR, "alumina", 6, 0, 8, Dir.EAST, "resistance", 150));
        e.name("switch", e.add(SWITCH, "brass", 5, 0, 6, Dir.EAST));
        e.name("baseResistor", e.add(RESISTOR, "alumina", 6, 0, 6, Dir.EAST, "resistance", 10_000));
        e.name("transistor", e.add(TRANSISTOR, "silicon", 8, 0, 8, Dir.WEST, "type", 0));
        // 100 kΩ from base to emitter: holds the transistor firmly off while the switch is open
        e.add(RESISTOR, "alumina", 8, 0, 9, Dir.EAST, "resistance", 100_000);
        return e.view(5.5, 4, 14, 35);
    }

    /**
     * A CMOS NAND gate from four MOSFETs: two p-channel in parallel from the supply to the output, two
     * n-channel in series from the output to ground. Two switches are the inputs; the output lights an LED.
     */
    public static Exhibit nandGate() {
        Exhibit e = new Exhibit("nand", "CMOS NAND gate",
                List.of("NAND GATE", "4 MOSFETs. LED is", "off only when", "A and B are on"),
                "Logic from transistor physics. A p-channel MOSFET conducts when its gate is low, an n-channel one "
                        + "when its gate is high. Two p-channels in parallel pull the output up unless both inputs "
                        + "are high; two n-channels in series pull it down only when both are. The output LED is "
                        + "therefore off only for A = B = 1: NAND, from which any digital circuit can be built. "
                        + "Input B crosses the LED's return wire on a raised bridge.");
        String cu = "copper";
        int bat = e.add(CAPACITOR, "alumina", 1, 0, 8, Dir.NORTH, "capacitance", 10);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        // supply rails
        for (int z = 3; z <= 7; z++) e.add(INSULATED_WIRE, cu, 1, 0, z, Dir.UP);
        for (int z = 9; z <= 13; z++) e.add(INSULATED_WIRE, cu, 1, 0, z, Dir.UP);
        for (int x = 1; x <= 13; x++) {
            e.add(INSULATED_WIRE, cu, x, 0, 2, Dir.UP);
            e.add(INSULATED_WIRE, cu, x, 0, 14, Dir.UP);
        }
        // pull-up network: p-channel MOSFETs in parallel
        e.add(TRANSISTOR, "silicon", 5, 0, 4, Dir.SOUTH, "type", 3);
        e.add(TRANSISTOR, "silicon", 9, 0, 4, Dir.SOUTH, "type", 3);
        e.path(INSULATED_WIRE, cu, new int[][]{{5, 0, 3}, {9, 0, 3}});
        // output node
        e.path(INSULATED_WIRE, cu, new int[][]{{5, 0, 5}, {5, 0, 6}, {6, 0, 6}, {7, 0, 6}, {8, 0, 6}, {9, 0, 6}, {9, 0, 5}, {10, 0, 6}, {7, 0, 7}});
        // pull-down network: n-channel MOSFETs in series
        e.add(TRANSISTOR, "silicon", 7, 0, 8, Dir.NORTH, "type", 2);
        e.add(INSULATED_WIRE, cu, 7, 0, 9, Dir.UP);
        e.add(TRANSISTOR, "silicon", 7, 0, 10, Dir.NORTH, "type", 2);
        e.path(INSULATED_WIRE, cu, new int[][]{{7, 0, 11}, {7, 0, 12}, {7, 0, 13}});
        // input A: switch from the supply, 100 kΩ pull-down, to both gates
        e.name("a", e.add(SWITCH, "brass", 3, 0, 3, Dir.SOUTH));
        e.path(INSULATED_WIRE, cu, new int[][]{{3, 0, 4}, {4, 0, 4}, {3, 0, 5}, {3, 0, 6}, {3, 0, 7}, {3, 0, 8}, {4, 0, 8}, {5, 0, 8}, {6, 0, 8},
                {3, 0, 10}, {3, 0, 11}, {3, 0, 12}, {3, 0, 13}});
        e.add(RESISTOR, "alumina", 3, 0, 9, Dir.SOUTH, "resistance", 100_000);
        // input B: switch, pull-down, and a bridge over the LED return to the lower n-channel gate
        e.name("b", e.add(SWITCH, "brass", 13, 0, 3, Dir.SOUTH));
        e.path(INSULATED_WIRE, cu, new int[][]{{13, 0, 4}, {12, 0, 4}, {11, 0, 4}, {10, 0, 4}, {13, 0, 5}, {13, 0, 6}, {13, 0, 7}, {13, 0, 8},
                {13, 0, 9}, {13, 0, 10}, {13, 1, 10}, {13, 2, 10}, {12, 2, 10}, {11, 2, 10}, {10, 2, 10}, {9, 2, 10}, {8, 2, 10},
                {8, 1, 10}, {8, 0, 10}, {13, 0, 12}, {13, 0, 13}});
        e.add(RESISTOR, "alumina", 13, 0, 11, Dir.SOUTH, "resistance", 100_000);
        // output indicator: LED and 470 Ω to ground
        e.name("led", e.add(LED, "silicon", 10, 0, 7, Dir.SOUTH, "wavelength", 525));
        e.add(RESISTOR, "alumina", 10, 0, 8, Dir.SOUTH, "resistance", 470);
        e.path(INSULATED_WIRE, cu, new int[][]{{10, 0, 9}, {10, 0, 10}, {10, 0, 11}, {10, 0, 12}, {10, 0, 13}});
        return e.view(7, 9, 19, 50);
    }

    /**
     * A 4-bit ripple counter: a Schmitt-trigger RC oscillator clocks four D flip-flops, each wired to toggle
     * (D ← Q̄) and to clock the next from its Q̄. LEDs on the Q outputs show the count in binary.
     */
    public static Exhibit binaryCounter() {
        Exhibit e = new Exhibit("counter", "Binary counter",
                List.of("BINARY COUNTER", "Schmitt oscillator +", "4 flip-flops count", "0..15 on the LEDs"),
                "Digital logic at chip level. A Schmitt-trigger inverter with 100 kΩ feedback and a 10 µF capacitor "
                        + "oscillates (its threshold jumps between 0.4 and 0.6 of the supply, so the capacitor "
                        + "charges and discharges between them: period 2RC·ln 1.5 ≈ 0.8 s). Each D flip-flop feeds "
                        + "its inverted output back to its input, so it toggles on every rising clock edge and "
                        + "clocks the next stage at half the rate: the LEDs count in binary. The chips draw their "
                        + "current from the 5 V supercapacitor through real output resistances.");
        e.plots = 2;
        String cu = "copper";
        int y = 2;
        // supply: supercapacitor and the V_DD (z = 4) and GND (z = 12) buses
        int bat = e.add(CAPACITOR, "alumina", 0, y, 8, Dir.NORTH, "capacitance", 10);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        e.path(INSULATED_WIRE, cu, new int[][]{{0, y, 7}, {0, y, 6}, {0, y, 5}, {0, y, 9}, {0, y, 10}, {0, y, 11}});
        for (int x = 0; x <= 35; x++) {
            e.add(INSULATED_WIRE, cu, x, y, 4, Dir.UP);
            e.add(INSULATED_WIRE, cu, x, y, 12, Dir.UP);
        }
        // oscillator: Schmitt inverter at x = 4 with feedback resistor above and timing capacitor to V_DD
        chipSupply(e, 4, y);
        e.name("clock", e.add(LOGIC, "silicon", 4, y, 8, Dir.SOUTH, "function", 7));
        e.path(INSULATED_WIRE, cu, new int[][]{{4, y, 7}, {4, y, 9}, {4, y + 1, 9}, {4, y + 1, 7}, {4, y, 5}});
        e.add(RESISTOR, "alumina", 4, y + 1, 8, Dir.NORTH, "resistance", 100_000);
        e.add(CAPACITOR, "alumina", 4, y, 6, Dir.NORTH, "capacitance", 10e-6);
        // clock to the first flip-flop, along the floor
        e.path(INSULATED_WIRE, cu, new int[][]{{4, y - 1, 9}, {4, 0, 9}});
        for (int x = 5; x <= 12; x++) e.add(INSULATED_WIRE, cu, x, 0, 9, Dir.UP);
        e.path(INSULATED_WIRE, cu, new int[][]{{12, 0, 8}, {12, 1, 8}});
        for (int k = 0; k < 4; k++) {
            int x = 12 + 7 * k;
            chipSupply(e, x, y);
            e.name("bit" + k, e.add(LOGIC, "silicon", x, y, 8, Dir.SOUTH, "function", 8));
            // D ← Q̄ (toggle), Q → LED → 470 Ω → GND
            e.path(INSULATED_WIRE, cu, new int[][]{{x, y, 7}, {x, y + 1, 8}, {x, y + 1, 7}, {x, y, 9}});
            e.name("led" + k, e.add(LED, "silicon", x, y, 10, Dir.SOUTH, "wavelength", k % 2 == 0 ? 630 : 590));
            e.add(RESISTOR, "alumina", x, y, 11, Dir.SOUTH, "resistance", 470);
            if (k < 3) {
                // Q̄ clocks the next stage: up, along y + 2, down a free column, along the floor, up into its clock pin
                int nx = x + 7;
                e.add(INSULATED_WIRE, cu, x, y + 2, 7, Dir.UP);
                for (int xx = x + 1; xx <= nx - 3; xx++) e.add(INSULATED_WIRE, cu, xx, y + 2, 7, Dir.UP);
                for (int yy = y + 1; yy >= 0; yy--) e.add(INSULATED_WIRE, cu, nx - 3, yy, 7, Dir.UP);
                for (int xx = nx - 2; xx <= nx; xx++) e.add(INSULATED_WIRE, cu, xx, 0, 7, Dir.UP);
                e.path(INSULATED_WIRE, cu, new int[][]{{nx, 0, 8}, {nx, 1, 8}});
            }
        }
        return e.view(20, 9, 20, 40);
    }

    /**
     * A thermostat — a closed control loop from parts. An NTC thermistor clamped to a small aluminium plate
     * forms a divider with 5.1 kΩ; a Schmitt-trigger chip compares it with its thresholds and drives a power
     * MOSFET that switches a 6 Ω heater under the plate. The loop holds the plate between two temperatures
     * set by the Schmitt hysteresis; an LED shows when the heater is on.
     */
    public static Exhibit thermostat() {
        Exhibit e = new Exhibit("thermostat", "Thermostat",
                List.of("THERMOSTAT", "Sensor -> logic ->", "MOSFET -> heater.", "Meter the plate"),
                "Feedback control from physical parts. The thermistor's resistance falls ~4 % per kelvin (β = 3950 K); "
                        + "with 5.1 kΩ it makes a voltage that rises as the plate warms. Above 60 % of the supply the "
                        + "Schmitt trigger's output drops, the MOSFET turns off and the heater (and LED) go dark; the "
                        + "plate cools by convection and radiation until the voltage falls below 40 %, and the "
                        + "heater comes back on. The temperature cycles between roughly 30 and 50 °C. Watch it with "
                        + "the multimeter on the plate or the thermistor. Powered by a 3000 F supercapacitor bank at 5 V (a few hours; as it runs down the heater weakens until it can no longer reach the upper threshold).");
        int bat = e.add(CAPACITOR, "alumina", 1, 0, 8, Dir.NORTH, "capacitance", 3000);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        int[][] vdd = new int[14 + 5 + 5 + 7 + 1][];
        int k = 0;
        for (int x = 1; x <= 14; x++) vdd[k++] = new int[]{x, 0, 2};
        for (int z = 3; z <= 7; z++) vdd[k++] = new int[]{1, 0, z};
        for (int z = 3; z <= 7; z++) vdd[k++] = new int[]{4, 0, z};
        for (int z = 3; z <= 9; z++) vdd[k++] = new int[]{9, 4, z};
        vdd[k] = new int[]{9, 3, 9};
        e.net("vdd", vdd);
        e.net("vdd", new int[][]{{9, 0, 3}, {9, 1, 3}, {9, 2, 3}, {9, 3, 3}, {4, 1, 7}, {4, 2, 7}});
        int[][] gnd = new int[14 + 5][];
        k = 0;
        for (int x = 1; x <= 14; x++) gnd[k++] = new int[]{x, 0, 14};
        for (int z = 9; z <= 13; z++) gnd[k++] = new int[]{1, 0, z};
        e.net("gnd", gnd);
        e.net("gnd", new int[][]{{6, 2, 11}, {6, 1, 11}, {6, 0, 11}, {6, 0, 12}, {6, 0, 13},
                {9, 1, 9}, {9, 0, 9}, {9, 0, 10}, {9, 0, 11}, {9, 0, 12}, {9, 0, 13},
                {13, 2, 9}, {13, 1, 9}, {13, 0, 9}, {13, 0, 10}, {13, 0, 11}, {13, 0, 12}, {13, 0, 13}});
        // heater under a thin aluminium plate, thermistor clamped on top
        e.name("heater", e.add(RESISTOR, "alumina", 4, 0, 8, Dir.SOUTH, "resistance", 6));
        int plate = e.add(BLOCK, "aluminium_6061", 4, 1, 8, Dir.UP, "fill", 0.05);
        e.name("plate", plate);
        int ntc = e.add(RESISTOR, "alumina", 4, 2, 8, Dir.SOUTH, "resistance", 10_000, "ntc_beta", 3950);
        e.name("thermistor", ntc);
        for (int idx : new int[]{plate, ntc, e.named.get("heater")}) e.init(idx, new Exhibit.Initial(318, 0, 0));
        e.net("heaterLow", new int[][]{{4, 0, 9}, {5, 0, 9}, {6, 0, 9}, {7, 0, 9}});
        e.name("mosfet", e.add(TRANSISTOR, "silicon", 8, 0, 9, Dir.WEST, "type", 2, "transconductance", 2.0));
        // sensing divider and the Schmitt trigger (supply on its lower and upper faces)
        e.net("sense", new int[][]{{4, 2, 9}, {5, 2, 9}, {6, 2, 9}, {7, 2, 9}, {8, 2, 9}});
        e.add(RESISTOR, "alumina", 6, 2, 10, Dir.SOUTH, "resistance", 5_100);
        e.name("schmitt", e.add(LOGIC, "silicon", 9, 2, 9, Dir.EAST, "function", 7, "lead_axis", 1));
        // gate drive: output → around the north → MOSFET gate; and the heater-on LED
        e.net("gate", new int[][]{{10, 2, 9}, {10, 2, 8}, {10, 2, 7}, {10, 2, 6}, {9, 2, 6}, {8, 2, 6}, {8, 1, 6}, {8, 0, 6},
                {8, 0, 7}, {8, 0, 8}});
        e.name("led", e.add(LED, "silicon", 11, 2, 9, Dir.EAST, "wavelength", 610));
        e.add(RESISTOR, "alumina", 12, 2, 9, Dir.EAST, "resistance", 470);
        return e.view(7.5, 7, 19, 35);
    }

    /** Supply connections of a chip at (x, y, 8): GND face → column x−2 down to the GND bus, V_DD face → column x+2 up to V_DD. */
    private static void chipSupply(Exhibit e, int x, int y) {
        String cu = "copper";
        e.path(INSULATED_WIRE, cu, new int[][]{{x - 1, y, 8}, {x - 2, y, 8}, {x - 2, y, 9}, {x - 2, y, 10}, {x - 2, y, 11},
                {x + 1, y, 8}, {x + 2, y, 8}, {x + 2, y, 7}, {x + 2, y, 6}, {x + 2, y, 5}});
    }

    /**
     * The hand generator again, as a true-scale miniature inside one block (6.25 mm cells): magnets,
     * coils and wiring a sixteenth of the size. Same physics, much smaller EMF and torques.
     */
    public static Exhibit miniatureGenerator() {
        Exhibit e = new Exhibit("miniature", "Miniature generator",
                List.of("MINIATURE", "True-scale hand", "generator in one", "block (tweezers)"),
                "Everything in the gallery can also be built at true scale: with precision tweezers in the "
                        + "off-hand, parts go into a 16×16×16 grid of 6.25 mm cells inside a single block. This "
                        + "is the hand generator at that scale — 6 cm across. Crank it and meter the resistor.");
        e.miniature = true;
        String st = "steel_1045";
        e.box(BLOCK, st, 9, 0, 8, 9, 2, 8, Dir.UP);
        e.box(BLOCK, st, 7, 0, 8, 7, 2, 8, Dir.UP);
        e.box(SHAFT, st, 8, 2, 8, 8, 10, 8, Dir.UP, "diameter", 0.004);
        e.add(BEARING, st, 8, 2, 8, Dir.UP);
        e.add(BEARING, st, 8, 8, 8, Dir.UP);
        e.box(BLOCK, st, 9, 8, 7, 11, 8, 12, Dir.UP);
        e.box(BLOCK, st, 5, 8, 7, 7, 8, 12, Dir.UP);
        e.box(BLOCK, st, 11, 0, 12, 11, 7, 12, Dir.UP);
        e.box(BLOCK, st, 5, 0, 12, 5, 7, 12, Dir.UP);
        e.name("crank", e.add(CRANK, st, 8, 11, 8, Dir.UP, "radius", 0.02));
        e.box(BLOCK, "aluminium_6061", 6, 4, 6, 10, 4, 10, Dir.UP, "fill", 0.25);
        e.add(MAGNET, "ndfeb_n42", 10, 5, 8, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 6, 5, 8, Dir.DOWN);
        e.name("coil", e.box(COIL, "copper", 10, 6, 8, 10, 7, 8, Dir.UP, "turns", 300, "wire_diameter", 0.1e-3));
        e.box(COIL, "copper", 6, 6, 8, 6, 7, 8, Dir.UP, "turns", 300, "wire_diameter", 0.1e-3);
        int[][] path = {{11, 6, 8}, {11, 6, 9}, {11, 6, 10}, {10, 6, 10}, {9, 6, 10}, {7, 6, 10}, {6, 6, 10}, {5, 6, 10}, {5, 6, 9}, {5, 6, 8}};
        for (int[] c : path) e.add(INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP, "diameter", 0.3e-3);
        e.name("load", e.add(RESISTOR, "alumina", 8, 6, 10, Dir.EAST, "resistance", 20));
        return e.view(8.5, 1.05, 9.55, 32);
    }

    // ------------------------------------------------------------------ conversion for tests/tools

    /** A built assembly plus its initial state and the part-id mapping. */
    public record Built(Assembly assembly, AssemblyState state, Map<Integer, Long> ids) {
        public long id(Exhibit e, String name) {
            return ids.get(e.named.get(name));
        }
    }

    /** Builds an exhibit as a core assembly; parts in layer y = 0 rest on the ground (anchored). */
    public static Built build(Exhibit e, double ambient) {
        return build(e, ambient, null);
    }

    /**
     * As {@link #build(Exhibit, double)}, its parts added in a shuffled order with scattered ids when {@code shuffle} is
     * given — as a world holds them, in whatever order a player placed them (what is built must not depend on it).
     */
    public static Built build(Exhibit e, double ambient, java.util.Random shuffle) {
        Assembly a = new Assembly().ambientTemperature(ambient);
        AssemblyState st = AssemblyState.empty();
        Map<Integer, Long> ids = new HashMap<>();
        List<Exhibit.PartDef> order = new java.util.ArrayList<>(e.parts);
        if (shuffle != null) java.util.Collections.shuffle(order, shuffle);
        java.util.Set<Long> used = new java.util.HashSet<>();
        for (Exhibit.PartDef d : order) {
            long id = d.index() + 1;
            if (shuffle != null) {
                do id = 1 + (shuffle.nextLong() >>> 2); while (!used.add(id));
            }
            ids.put(d.index(), id);
            Part part = new Part(id, d.kind(), MaterialDatabase.standard().get(d.material()), d.x0(), d.y0(), d.z0(),
                    d.x1(), d.y1(), d.z1(), d.axis(), d.params());
            if (e.latched.contains(d.index())) part.setSwitchClosed(true);
            a.add(part);
        }
        a.anchoredWhen(p -> p.minY() == 0 && p.kind().structural && p.kind() != dev.emergence.core.assembly.PartKind.SHAFT);
        for (var en : e.initial.entrySet()) {
            long id = ids.get(en.getKey());
            Exhibit.Initial in = en.getValue();
            if (!Double.isNaN(in.temperature())) st.temperatures().put(id, in.temperature());
            if (in.omega() != 0 || in.angle() != 0) st.bodyMotion().put(id, new double[]{in.omega(), in.angle()});
            if (in.capacitorVoltage() != 0) st.capacitorVoltages().put(id, in.capacitorVoltage());
        }
        return new Built(a, st, ids);
    }
}
