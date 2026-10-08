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
 */
public final class Exhibits {
    private Exhibits() {}

    public static List<Exhibit> all() {
        return List.of(generator(), gearTrain(), capacitorGlow(), fuse(), conductionRace(), flywheels());
    }

    /** Hand-cranked axial-flux generator feeding a resistor through insulated wiring. */
    public static Exhibit generator() {
        Exhibit e = new Exhibit("generator", "Hand-cranked generator",
                List.of("HAND GENERATOR", "Hold right-click", "on the crank.", "Meter the resistor"),
                "Two NdFeB magnets on an aluminium rotor sweep past two copper coils. Their changing flux "
                        + "linkage induces an EMF (Faraday), current flows through the 20 Ω resistor and heats it, "
                        + "and the current's magnetic field brakes the rotor (Lenz). Use the multimeter on the "
                        + "resistor and on the rotor.");
        String st = "steel_1045";
        e.add(BLOCK, st, 9, 0, 8, 9, 2, 8, Dir.UP);
        e.add(BLOCK, st, 7, 0, 8, 7, 2, 8, Dir.UP);
        e.add(SHAFT, st, 8, 2, 8, 8, 10, 8, Dir.UP, "diameter", 0.02);
        e.add(BEARING, st, 8, 2, 8, Dir.UP);
        e.add(BEARING, st, 8, 8, 8, Dir.UP);
        e.add(BLOCK, st, 9, 8, 7, 11, 8, 12, Dir.UP);
        e.add(BLOCK, st, 5, 8, 7, 7, 8, 12, Dir.UP);
        e.add(BLOCK, st, 11, 0, 12, 11, 7, 12, Dir.UP);
        e.add(BLOCK, st, 5, 0, 12, 5, 7, 12, Dir.UP);
        e.name("crank", e.add(CRANK, st, 8, 11, 8, Dir.UP, "radius", 0.15));
        e.add(BLOCK, "aluminium_6061", 6, 4, 6, 10, 4, 10, Dir.UP, "fill", 0.04);
        e.add(MAGNET, "ndfeb_n42", 10, 5, 8, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 6, 5, 8, Dir.DOWN);
        e.name("coil", e.add(COIL, "copper", 10, 6, 8, 10, 7, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3));
        e.add(COIL, "copper", 6, 6, 8, 6, 7, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3);
        int[][] path = {{11, 6, 8}, {11, 6, 9}, {11, 6, 10}, {10, 6, 10}, {9, 6, 10}, {7, 6, 10}, {6, 6, 10}, {5, 6, 10}, {5, 6, 9}, {5, 6, 8}};
        for (int[] c : path) e.add(INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP, "diameter", 1.5e-3);
        e.name("load", e.add(RESISTOR, "alumina", 8, 6, 10, Dir.EAST, "resistance", 20));
        return e.view(0.42, 1.05, 14);
    }

    /** Three meshing spur gears (8 : 24 : 40 teeth) — speed ratios and direction reversal. */
    public static Exhibit gearTrain() {
        Exhibit e = new Exhibit("gears", "Spur gear train",
                List.of("GEAR TRAIN", "8 : 24 : 40 teeth", "Crank the small", "gear. Compare rpm"),
                "Three spur gears with the same tooth module mesh because their pitch circles touch. Turning "
                        + "the 8-tooth pinion turns the 24-tooth gear backwards at 1/3 speed and the 40-tooth gear "
                        + "forwards at 1/5 speed, with 5× the torque minus a little tooth friction.");
        String st = "steel_1045";
        e.name("input", e.add(SHAFT, st, 4, 0, 8, 4, 5, 8, Dir.UP, "diameter", 0.015));
        e.add(SHAFT, st, 6, 0, 8, 6, 5, 8, Dir.UP, "diameter", 0.015);
        e.name("output", e.add(SHAFT, st, 10, 0, 8, 10, 5, 8, Dir.UP, "diameter", 0.015));
        for (int x : new int[]{4, 6, 10}) e.add(BEARING, st, x, 0, 8, Dir.UP);
        e.add(GEAR, st, 4, 3, 8, Dir.UP, "teeth", 8, "thickness", 0.012);
        e.name("middle", e.add(GEAR, st, 5, 3, 7, 7, 3, 9, Dir.UP, "teeth", 24, "thickness", 0.012));
        e.add(GEAR, st, 8, 3, 6, 12, 3, 10, Dir.UP, "teeth", 40, "thickness", 0.012);
        e.name("crank", e.add(CRANK, st, 4, 6, 8, Dir.UP, "radius", 0.12));
        return e.view(0.5, 0.85, 28);
    }

    /** A pre-charged capacitor dumped through a nichrome wire: incandescence from Joule heating. */
    public static Exhibit capacitorGlow() {
        Exhibit e = new Exhibit("glow", "Capacitor discharge",
                List.of("HOT WIRE", "Close the switch:", "28 J flash-heats", "the nichrome wire"),
                "A 10 mF capacitor charged to 75 V stores ½CV² ≈ 28 J. Closing the switch dumps it through "
                        + "two cells of 0.3 mm nichrome (≈2 Ω). The wire heats to ~1100 K and glows orange, then "
                        + "cools by radiation (Stefan–Boltzmann) and convection.");
        buildDischarge(e, 0.3e-3);
        return e.view(0.32, 0.62, 42);
    }

    /** The same discharge through a much thinner wire: it melts, opening the circuit. */
    public static Exhibit fuse() {
        Exhibit e = new Exhibit("fuse", "Fuse",
                List.of("FUSE", "Close the switch:", "0.1 mm nichrome", "melts and opens"),
                "Identical capacitor and switch, but the nichrome is 0.1 mm thick: nine times the resistance "
                        + "and one ninth of the heat capacity. The same energy drives it past its melting point "
                        + "(1673 K) and it fails, interrupting the circuit — a fuse emerges from material properties.");
        buildDischarge(e, 0.1e-3);
        return e.view(0.32, 0.62, 42);
    }

    private static void buildDischarge(Exhibit e, double wireDiameter) {
        int cap = e.add(CAPACITOR, "alumina", 4, 0, 8, Dir.EAST, "capacitance", 0.01);
        e.name("capacitor", cap);
        e.init(cap, new Exhibit.Initial(Double.NaN, 0, 75));
        e.name("switch", e.add(SWITCH, "brass", 5, 0, 8, Dir.EAST));
        e.name("wire", e.add(WIRE, "nichrome", 6, 0, 8, Dir.UP, "diameter", wireDiameter));
        e.add(WIRE, "nichrome", 7, 0, 8, Dir.UP, "diameter", wireDiameter);
        int[][] ret = {{8, 0, 8}, {8, 0, 9}, {8, 0, 10}, {7, 0, 10}, {6, 0, 10}, {5, 0, 10}, {4, 0, 10}, {3, 0, 10}, {3, 0, 9}, {3, 0, 8}};
        for (int[] c : ret) e.add(INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP, "diameter", 1.5e-3);
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
        return e.view(0.3, 0.7, 35);
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
            int shaft = e.add(SHAFT, st, x, 0, 8, x, 4, 8, Dir.UP, "diameter", 0.02);
            int bearing = e.add(BEARING, k == 0 ? st : "bronze", x, 0, 8, Dir.UP, "type", k);
            e.name(k == 0 ? "ballBearing" : "plainBearing", bearing);
            e.add(BLOCK, "aluminium_6061", x - 2, 2, 6, x + 2, 2, 10, Dir.UP, "fill", 0.25);
            e.name(k == 0 ? "ball" : "plain", shaft);
            e.init(shaft, new Exhibit.Initial(Double.NaN, 60, 0));
        }
        return e;
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
        Assembly a = new Assembly().ambientTemperature(ambient);
        AssemblyState st = AssemblyState.empty();
        Map<Integer, Long> ids = new HashMap<>();
        for (Exhibit.PartDef d : e.parts) {
            long id = d.index() + 1;
            ids.put(d.index(), id);
            a.add(new Part(id, d.kind(), MaterialDatabase.standard().get(d.material()), d.x0(), d.y0(), d.z0(),
                    d.x1(), d.y1(), d.z1(), d.axis(), d.params()));
        }
        a.anchoredWhen(p -> p.minY() == 0 && p.kind().structural && p.kind() != dev.emergence.core.assembly.PartKind.SHAFT);
        for (var en : e.initial.entrySet()) {
            long id = ids.get(en.getKey());
            Exhibit.Initial in = en.getValue();
            if (!Double.isNaN(in.temperature())) st.temperatures().put(id, in.temperature());
            if (in.omega() != 0) st.bodyMotion().put(id, new double[]{in.omega(), 0});
            if (in.capacitorVoltage() != 0) st.capacitorVoltages().put(id, in.capacitorVoltage());
        }
        return new Built(a, st, ids);
    }
}
