package dev.emergence.core.demo;

import dev.emergence.core.assembly.Assembly;
import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.AssemblyState;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Part;
import dev.emergence.core.assembly.PartKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;



import java.util.Set;
import java.util.stream.Collectors;

import static dev.emergence.core.demo.DemoExhibitsTest.run;
import static dev.emergence.core.demo.DemoExhibitsTest.startByHand;

/**
 * The flyball exhibit built as the world holds it — every part one cell, as a player places them — for the kinds in
 * {@code -Ddiag.split} (comma-separated, or ALL): what of the world's behaviour comes from that alone.
 */
class SplitExhibitDiagTest {
    /** The exhibit with each multi-cell part of a kind in {@code kinds} cut into one part per cell (ids: first cell keeps it). */
    static Exhibits.Built split(Exhibits.Built b, Set<PartKind> kinds) {
        Assembly a = new Assembly().ambientTemperature(b.assembly().ambientTemperature());
        long next = 1L << 40;
        for (Part p : b.assembly().parts()) {
            if (!kinds.contains(p.kind())) {
                a.add(p);
                continue;
            }
            boolean first = true;
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int y = p.minY(); y <= p.maxY(); y++)
                    for (int z = p.minZ(); z <= p.maxZ(); z++) {
                        long id = first ? p.id() : next++;
                        Part q = new Part(id, p.kind(), p.material(), x, y, z, x, y, z, p.axis(), p.params());
                        p.state().forEach(q::setState);
                        a.add(q);
                        first = false;
                    }
        }
        a.anchoredWhen(p -> p.minY() == 0 && p.kind().structural && p.kind() != PartKind.SHAFT);
        AssemblyState st = b.state();
        return new Exhibits.Built(a, st, b.ids());
    }

    /**
     * The parts of a world island's dump, lined up with the exhibit by their lowest corner; each named part of the
     * exhibit mapped to the dump's part in its first cell (of the same kind); switches open; the exhibit's initial state
     * moved over to the dump's ids, and {@code -Ddiag.dumpState=true} the dump's state as well (overriding).
     */
    static Exhibits.Built fromDump(Exhibit e, Exhibits.Built core, java.nio.file.Path file) {
        var mats = dev.emergence.core.materials.MaterialDatabase.standard();
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        try {
            for (String l : java.nio.file.Files.readAllLines(file)) rows.add(l.trim().split(" "));
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
        int[] wmin = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, cmin = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (String[] f : rows) if (f[0].equals("P")) for (int k = 0; k < 3; k++) wmin[k] = Math.min(wmin[k], Integer.parseInt(f[4 + k]));
        for (Part p : core.assembly().parts()) {
            cmin[0] = Math.min(cmin[0], p.minX());
            cmin[1] = Math.min(cmin[1], p.minY());
            cmin[2] = Math.min(cmin[2], p.minZ());
        }
        Assembly a = new Assembly().ambientTemperature(core.assembly().ambientTemperature());
        java.util.Set<Long> anchored = new java.util.HashSet<>();
        for (String[] f : rows) {
            if (!f[0].equals("P")) continue;
            java.util.Map<String, Double> prm = new java.util.HashMap<>();
            for (int i = 12; i < f.length; i++) {
                int eq = f[i].indexOf('=');
                if (eq > 0) prm.put(f[i].substring(0, eq), Double.parseDouble(f[i].substring(eq + 1)));
            }
            int[] o = new int[3];
            for (int k = 0; k < 3; k++) o[k] = cmin[k] - wmin[k];
            a.add(new Part(Long.parseLong(f[1]), PartKind.valueOf(f[2]), mats.get(f[3]), Integer.parseInt(f[4]) + o[0], Integer.parseInt(f[5]) + o[1],
                    Integer.parseInt(f[6]) + o[2], Integer.parseInt(f[7]) + o[0], Integer.parseInt(f[8]) + o[1], Integer.parseInt(f[9]) + o[2],
                    dev.emergence.core.assembly.Dir.valueOf(f[10]), prm));
            if (f[11].equals("1")) anchored.add(Long.parseLong(f[1]));
        }
        a.anchoredWhen(p -> anchored.contains(p.id()));
        // the exhibit's ids to the dump's: the dump's part of the same kind in the core part's first cell
        java.util.Map<Long, Long> idOf = new java.util.HashMap<>();
        for (Part p : core.assembly().parts())
            for (Part q : a.parts())
                if (q.kind() == p.kind() && q.minX() == p.minX() && q.minY() == p.minY() && q.minZ() == p.minZ()) idOf.put(p.id(), q.id());
        java.util.Map<Integer, Long> ids = new java.util.HashMap<>();
        core.ids().forEach((k, v) -> { if (idOf.containsKey(v)) ids.put(k, idOf.get(v)); });
        AssemblyState st = AssemblyState.empty();
        core.state().temperatures().forEach((k, v) -> { if (idOf.containsKey(k)) st.temperatures().put(idOf.get(k), v); });
        core.state().bodyMotion().forEach((k, v) -> { if (idOf.containsKey(k)) st.bodyMotion().put(idOf.get(k), v.clone()); });
        if (Boolean.getBoolean("diag.dumpState")) {
            for (String[] f : rows) {
                if (!System.getProperty("diag.dumpStateKinds", "TMIV").contains(f[0])) continue;
                switch (f[0]) {
                    case "T" -> st.temperatures().put(Long.parseLong(f[1]), Double.parseDouble(f[2]));
                    case "M" -> {
                        double w = Double.parseDouble(f[2]), ang = Double.parseDouble(f[3]);
                        // -Ddiag.wrapAngle=true: a turning body's angle within one turn
                        if (Boolean.getBoolean("diag.wrapAngle")) ang = Math.IEEEremainder(ang, 2 * Math.PI);
                        // -Ddiag.fastAngle=<rad>: the fast-turning bodies (the rotor) put at that angle instead
                        if (System.getProperty("diag.fastAngle") != null && Math.abs(w) > 1) ang = Double.parseDouble(System.getProperty("diag.fastAngle"));
                        // -Ddiag.onlyFast=true: only the fast-turning bodies' state kept (the governor's linkage from rest)
                        if (Boolean.getBoolean("diag.onlyFast") && Math.abs(w) <= 1) continue;
                        // -Ddiag.noSpeed=true: the bodies' speeds dropped (positions kept)
                        if (Boolean.getBoolean("diag.noSpeed")) w = 0;
                        st.bodyMotion().put(Long.parseLong(f[1]), new double[]{w, ang});
                    }
                    case "I" -> st.coilCurrents().put(Long.parseLong(f[1]), Double.parseDouble(f[2]));
                    case "V" -> st.capacitorVoltages().put(Long.parseLong(f[1]), Double.parseDouble(f[2]));
                    default -> {
                    }
                }
            }
        }
        return new Exhibits.Built(a, st, ids);
    }

    /**
     * The governed steam engine's speed over a long run ({@code -Ddiag.governedRun=<s>} each, braked then free), every
     * 10 s: whether the core test's windows read a steady state.
     */
    @Test
    @EnabledIfSystemProperty(named = "diag.governedRun", matches = ".+")
    void governedLongRun() {
        Exhibit e = Exhibits.governedEngine();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        long shaft = b.id(e, "shaft"), rod = b.id(e, "throttleRod"), weight = b.id(e, "weightA");
        double len = Double.parseDouble(System.getProperty("diag.governedRun"));
        for (int m = 0; m < 2; m++) {
            if (m == 1) c.toggleSwitch(b.id(e, "brake"));
            for (double t = 0; t < len - 1e-9; t += 10) {
                double w = 0;
                for (int i = 0; i < 200; i++) {
                    c.step(0.05);
                    w += Math.abs(c.probe(shaft).angularVelocity()) / 200;
                }
                System.out.printf("governed %s t=%.0f s: mean %.2f rad/s over 10 s, weight out %.1f mm, rod %.2f mm, brake %.2f W, boiler %.2f bar%n",
                        m == 0 ? "braked" : "free", c.time(), w, 1e3 * c.probe(weight).angle(), 1e3 * c.probe(rod).angle(),
                        c.brake(b.id(e, "brake"))[1], 1e-5 * c.pressure(b.id(e, "boiler")));
                var sv = c.branchesOf(b.id(e, "safety"));
                double vent = 0;
                for (var br : sv) vent = Math.max(vent, Math.abs(br.massFlow(c.solverView())));
                double[] inl = c.fluid(b.id(e, "inlet"));
                System.out.printf("   boiler water %.2f g, safety valve %.4f g/s, inlet valve flow %.4f g/s%n", 1e3 * c.fluid(b.id(e, "boiler"))[2],
                        1e3 * vent, 1e3 * inl[3]);
            }
        }
    }

    /**
     * The gas engine left standing {@code -Ddiag.engineStand=<s>} (in the noon sun with {@code -Ddiag.sun=true}) before
     * it is cranked as the core test cranks it: does a long wait, as deep in a full tour, keep it from starting?
     */
    @Test
    @EnabledIfSystemProperty(named = "diag.engineStand", matches = ".+")
    void engineAfterStanding() {
        Exhibit e = EngineExhibits.gasEngine();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        if (Boolean.getBoolean("diag.sun")) c.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> true);
        var ch = dev.emergence.core.chem.Chemistry.standard();
        long crank = b.id(e, "crankshaft"), handle = b.id(e, "handle");
        double stand = Double.parseDouble(System.getProperty("diag.engineStand"));
        for (double t = 0; t < stand - 1e-9; t += 300) {
            run(c, Math.min(300, stand - t));
            var mg = c.gas(b.id(e, "mixer"));
            System.out.printf("engine standing t=%.0f s: head %.0f K, glow plug %.0f K, mixer %.2f %% methane, crank %.3f rad/s%n", c.time(),
                    c.temperature(b.id(e, "head")), c.temperature(b.id(e, "glow")),
                    100 * mg.moleFraction(c.solverView(), mg.indexOf(ch.species("CH4"))), c.probe(crank).angularVelocity());
        }
        c.toggleSwitch(b.id(e, "needle"));
        for (int s = 0; s < 60; s++) {
            double wMax = 0;
            for (int i = 0; i < 20; i++) {
                c.setCrankEffort(handle, 1, false);
                c.step(0.05);
                wMax = Math.max(wMax, c.probe(crank).angularVelocity());
            }
            var g = c.gas(b.id(e, "cylinder"));
            System.out.printf("engine cranked %d s: up to %.1f rad/s, cylinder %.2f %% methane, %.2f %% CO2, head %.0f K%n", s + 1, wMax,
                    100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("CH4"))), 100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("CO2"))),
                    c.temperature(b.id(e, "head")));
            if (wMax > 80) break;
        }
    }

    private static double coilT(CompiledAssembly c, Exhibits.Built b) {
        double t = 0;
        for (Part p : b.assembly().parts()) if (p.kind() == PartKind.COIL) t = Math.max(t, c.probe(p.id()).temperature());
        return t;
    }

    @Test
    @EnabledIfSystemProperty(named = "diag.split", matches = ".+")
    void flyballSplit() {
        String which = System.getProperty("diag.split");
        Set<PartKind> kinds = which.equals("ALL") ? Set.of(PartKind.values())
                : java.util.Arrays.stream(which.split(",")).map(String::trim).map(PartKind::valueOf).collect(Collectors.toSet());
        Exhibit e = ControlExhibits.flyball();
        Exhibits.Built b = split(Exhibits.build(e, DemoExhibitsTest.AMBIENT), kinds);
        // -Ddiag.fromDump=<island-dump file>: the world's own parts instead, matched to the exhibit's by cell, from rest
        if (System.getProperty("diag.fromDump") != null) b = fromDump(e, b, java.nio.file.Path.of(System.getProperty("diag.fromDump")));
        // -Ddiag.param=KIND:key=value;…: a parameter of every part of that kind changed (a design under trial)
        if (System.getProperty("diag.param") != null) {
            for (String s : System.getProperty("diag.param").split(";")) {
                String[] kv = s.split("[:=]");
                for (int i = 0; i < e.parts.size(); i++) {
                    Exhibit.PartDef d = e.parts.get(i);
                    if (d.kind() != PartKind.valueOf(kv[0])) continue;
                    java.util.Map<String, Double> prm = new java.util.HashMap<>(d.params());
                    prm.put(kv[1], Double.parseDouble(kv[2]));
                    e.parts.set(i, new Exhibit.PartDef(d.index(), d.kind(), d.material(), d.x0(), d.y0(), d.z0(), d.x1(), d.y1(), d.z1(), d.axis(), prm));
                }
            }
            b = split(Exhibits.build(e, DemoExhibitsTest.AMBIENT), kinds);
        }
        // -Ddiag.initOmega=<rad/s>: the rotor started at that speed instead of the exhibit's
        if (System.getProperty("diag.initOmega") != null) {
            double[] m = b.state().bodyMotion().get(b.id(e, "rotor"));
            m[0] = Double.parseDouble(System.getProperty("diag.initOmega"));
        }
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        c.warnings().forEach(w -> System.out.println("warning: " + w));
        long rotor = b.id(e, "rotor"), bat = b.id(e, "battery"), crank = b.id(e, "crankShaft"), rod = b.id(e, "rodA");
        // -Ddiag.maxStep=<s>: the solver's step capped (is an operating point the integrator's?)
        if (System.getProperty("diag.maxStep") != null) c.solver().maxStep(Double.parseDouble(System.getProperty("diag.maxStep")));
        c.toggleSwitch(b.id(e, "switch"));
        startByHand(c, rotor);
        for (int m = 0; m < 2; m++) {
            if (m == 1) c.toggleSwitch(b.id(e, "brake"));
            // -Ddiag.longRun=<s>: that long before each reading, the trajectory printed every 20 s
            double len = Double.parseDouble(System.getProperty("diag.longRun", m == 0 ? "40" : "20"));
            for (double t = 0; t < len - 1e-9; t += 20) {
                run(c, Math.min(20, len - t));
                if (System.getProperty("diag.longRun") != null)
                    System.out.printf("  t=%.0f s: %.2f rad/s, battery %.3f A, weights out %.1f mm, rheostat %.1f°, rheostat at %.1f K, coil at %.1f K%n",
                            c.time(), c.probe(rotor).angularVelocity(), Math.abs(c.probe(bat).current()), -1e3 * c.probe(rod).angle(),
                            Math.toDegrees(c.probe(crank).angle()), c.probe(b.id(e, "rheostat")).temperature(), coilT(c, b));
            }
            double w = 0, amps = 0;
            int n = 40;
            double[] rx = {Double.MAX_VALUE, -Double.MAX_VALUE}, wx = {Double.MAX_VALUE, -Double.MAX_VALUE};
            java.util.function.Consumer<dev.emergence.core.sim.TransientSolver> l = s -> {
                double x = c.probe(rod).angle(), v = c.probe(rotor).angularVelocity();
                rx[0] = Math.min(rx[0], x);
                rx[1] = Math.max(rx[1], x);
                wx[0] = Math.min(wx[0], v);
                wx[1] = Math.max(wx[1], v);
            };
            c.solver().addStepListener(l);
            for (int k = 0; k < n; k++) {
                c.step(0.05);
                w += c.probe(rotor).angularVelocity() / n;
                amps += Math.abs(c.probe(bat).current()) / n;
            }
            c.solver().removeStepListener(l);
            System.out.printf("  the weight's swing %.3f mm (%.2f–%.2f), the rotor's %.2f–%.2f rad/s%n", 1e3 * (rx[1] - rx[0]), -1e3 * rx[1], -1e3 * rx[0], wx[0], wx[1]);
            System.out.printf("split %s, %s: %.2f rad/s at %.3f A, weights out %.1f mm, rheostat %.1f°%n", which, m == 0 ? "free" : "braked",
                    w, amps, -1e3 * c.probe(rod).angle(), Math.toDegrees(c.probe(crank).angle()));
            System.out.printf("  energy error %.4g J, %d steps accepted, %d rejected, last %.3g s, limiters %s%n", c.energyError(),
                    c.solver().acceptedSteps(), c.solver().rejectedSteps(), c.solver().lastStepSize(), c.solver().stepLimiters());
        }
    }
}
