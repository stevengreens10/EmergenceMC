package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static dev.emergence.core.demo.DemoExhibitsTest.AMBIENT;

/** The power station ({@link StationExhibit}), section by section. */
class StationTest {
    /**
     * Saved and reloaded, as a world does: run {@code -Ddiag.reload=<s>}, export the state, compile the station afresh
     * from it and run on (a restart from a running station's state).
     */
    @Test
    @EnabledIfSystemProperty(named = "diag.reload", matches = ".+")
    void stationRestartsFromItsSavedState() {
        Exhibit e = StationExhibit.station(!Boolean.getBoolean("diag.plantOnly"));
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        c.toggleSwitch(b.id(e, "lightUp"));
        DemoExhibitsTest.run(c, Double.parseDouble(System.getProperty("diag.reload")));
        var saved = c.exportState();
        // -Ddiag.dropPhase=true: the water/steam volumes' phases left out, as a world's save once did (pressures and
        // temperatures kept, every level lost)
        if (Boolean.getBoolean("diag.dropPhase")) saved.fluids().replaceAll((k, v) -> v.length > 2 ? new double[]{v[0], v[1]} : v);
        System.out.printf("before: t=%.1f s, engine %.2f rad/s%n", c.time(), Math.abs(c.probe(b.id(e, "shaft")).angularVelocity()));
        CompiledAssembly r = AssemblyCompiler.compile(b.assembly(), saved);
        // -Ddiag.reloadRun=<s>: how long it runs on after the reload (1 s by default), reporting each quarter of it with
        // the counter's state (it should go on counting)
        int steps = (int) Math.round(Double.parseDouble(System.getProperty("diag.reloadRun", "1")) / 0.05);
        // -Ddiag.sun=<W/m²>: the sun high in the sky, every part in it (as a world by day)
        double sun = Double.parseDouble(System.getProperty("diag.sun", "0"));
        if (sun > 0) r.setDaylight(new dev.emergence.core.math.Vec3(0.3, -1, 0.2), sun, id -> true);
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (int i = 0; i < steps; i++) {
            r.step(0.05);
            StringBuilder bits = new StringBuilder();
            if (e.named.containsKey("bitLed0")) for (int k = 2; k >= 0; k--) bits.append(r.lightOutput(b.id(e, "bitLed" + k)) > 1e-4 ? '1' : '0');
            seen.add(bits.toString());
            if ((i + 1) % Math.max(1, steps / 4) == 0)
                System.out.printf("after reload: t=%.2f s, engine %.2f rad/s, counter %s (states seen %s)%n", r.time(),
                        Math.abs(r.probe(b.id(e, "shaft")).angularVelocity()), bits, seen);
        }
    }

    /** The plant with the main fuel held open by hand: does the firebox keep the boiler pressed and the engine running? */
    @Test
    @EnabledIfSystemProperty(named = "diag.station", matches = ".+")
    void plantRunsOnItsFire() {
        // -Ddiag.plantOnly=true: without the generator and electronics
        Exhibit e = StationExhibit.station(!Boolean.getBoolean("diag.plantOnly"));
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        c.warnings().forEach(w -> System.out.println("warning: " + w));
        System.out.printf("station: %d parts, %d bodies%n", b.assembly().parts().size(), c.bodies().size());
        // -Ddiag.volume=<m³>: the parts holding about that volume (a step limiter names only its volume)
        if (System.getProperty("diag.volume") != null) {
            double want = Double.parseDouble(System.getProperty("diag.volume"));
            for (var p : b.assembly().parts())
                if (p.kind().holdsFluid() && Math.abs(dev.emergence.core.assembly.PartGeometry.fluidVolume(p) - want) < 0.01 * want)
                    System.out.printf("volume %.4g: %s at %d,%d,%d %s%n", want, p.kind(), p.minX(), p.minY(), p.minZ(), p.params());
        }
        // -Ddiag.element=<m³>: the fluid volumes of about that volume (a step limiter names one by its volume), and their parts
        if (System.getProperty("diag.element") != null) {
            double want = Double.parseDouble(System.getProperty("diag.element"));
            for (var el : c.network().elements()) {
                if (!(el instanceof dev.emergence.core.sim.elements.FluidVolume fv)) continue;
                double vol = fv.volume(c.solverView());
                if (Math.abs(vol - want) > 0.02 * want) continue;
                String nm = fv.pressureNode().name();
                var p = b.assembly().get(Long.parseLong(nm.substring(1)));
                System.out.printf("element: %.4g m³, node %s: %s at %d,%d,%d %s%n", vol, nm, p.kind(), p.minX(), p.minY(), p.minZ(), p.params());
            }
        }
        // -Ddiag.parts=<id>,…: where those parts are (a failure names them by id)
        for (String id : System.getProperty("diag.parts", "").split(",")) {
            if (id.isBlank()) continue;
            var p = b.assembly().get(Long.parseLong(id.trim()));
            System.out.printf("part %s: %s %s at %d,%d,%d axis %s %s%n", id, p.kind(), p.material().id(), p.minX(), p.minY(), p.minZ(), p.axis(), p.params());
        }
        for (var v : c.vents()) {
            var p = b.assembly().get(v.partId());
            System.out.printf("vent: %s at %d,%d,%d (%s) facing %s%n", p.kind(), p.minX(), p.minY(), p.minZ(), c.fluidName(v.partId()), v.face());
        }
        var ch = dev.emergence.core.chem.Chemistry.standard();
        long shaft = b.id(e, "shaft"), boiler = b.id(e, "boiler"), box = b.id(e, "firebox"),
                cyl = b.id(e, "cylinder"), fuel = b.id(e, "fuel");
        double len = Double.parseDouble(System.getProperty("diag.stationRun", "120"));
        // -Ddiag.lightSeconds=<s>: the igniter switched on for that long first, as a player lights it
        double light = Double.parseDouble(System.getProperty("diag.lightSeconds", "0"));
        if (light > 0) c.toggleSwitch(b.id(e, "lightUp"));
        boolean lit = light > 0;
        double m0 = c.fluid(boiler)[2], f0 = c.pressure(fuel);
        // -Ddiag.every=<s>: how often it reports (10 s by default)
        double every = Double.parseDouble(System.getProperty("diag.every", "10"));
        int per = (int) Math.round(every / 0.05);
        java.util.Map<String, Double> masses = new java.util.HashMap<>();
        for (double t = 0; t < len - 1e-9; t += every) {
            // -Ddiag.brakeAt=<s>: the brake put on then, as the tour's hand puts it on
            if (t == Double.parseDouble(System.getProperty("diag.brakeAt", "-1"))) c.toggleSwitch(b.id(e, "brake"));
            if (lit && t >= light) {
                c.toggleSwitch(b.id(e, "lightUp"));
                lit = false;
            }
            double w = 0;
            long wall0 = System.nanoTime(), acc0 = c.solver().acceptedSteps(), rej0 = c.solver().rejectedSteps(), it0 = c.solver().newtonIterations();
            var lim0 = new java.util.HashMap<>(c.solver().stepLimiters());
            // -Ddiag.flows=<part name>,…: the mean mass flow through each of those parts' passages over the report [g/s]
            String[] flowNames = System.getProperty("diag.flows", "").isBlank() ? new String[0] : System.getProperty("diag.flows").split(",");
            double[] flowSum = new double[flowNames.length];
            for (int i = 0; i < per; i++) {
                c.step(0.05);
                w += Math.abs(c.probe(shaft).angularVelocity()) / per;
                for (int k = 0; k < flowNames.length; k++) {
                    var br = c.branchesOf(b.id(e, flowNames[k].trim()));
                    if (!br.isEmpty()) flowSum[k] += br.get(0).massFlow(c.solverView()) / per;
                }
            }
            if (flowNames.length > 0) {
                StringBuilder sb = new StringBuilder("   mean flows:");
                for (int k = 0; k < flowNames.length; k++) sb.append(String.format(" %s %.3f g/s;", flowNames[k].trim(), 1e3 * flowSum[k]));
                System.out.println(sb);
            }
            // -Ddiag.profile=true: what the last 10 s cost, and what held its steps short
            if (Boolean.getBoolean("diag.profile")) {
                var lim = new java.util.TreeMap<Integer, String>(java.util.Comparator.reverseOrder());
                c.solver().stepLimiters().forEach((k, v) -> {
                    int d = v - lim0.getOrDefault(k, 0);
                    if (d > 0) lim.merge(d, k, (a, b2) -> a + ", " + b2);
                });
                StringBuilder top = new StringBuilder();
                int n = 0;
                for (var en : lim.entrySet()) {
                    if (n++ >= 6) break;
                    top.append(en.getValue()).append('=').append(en.getKey()).append("; ");
                }
                if (Boolean.getBoolean("diag.newtonLaggards")) {
                    var lag = new java.util.ArrayList<>(c.solver().newtonLaggards().entrySet());
                    lag.sort((p1, p2) -> p2.getValue() - p1.getValue());
                    System.out.println("   laggards: " + lag.subList(0, Math.min(10, lag.size())));
                    c.solver().newtonLaggards().clear();
                }
                if (dev.emergence.core.math.SparseLinearSystem.COLLAPSES != null) {
                    var col = new java.util.ArrayList<>(dev.emergence.core.math.SparseLinearSystem.COLLAPSES.entrySet());
                    col.sort((p1, p2) -> p2.getValue() - p1.getValue());
                    StringBuilder sb = new StringBuilder("   collapsed pivots:");
                    for (var en : col.subList(0, Math.min(8, col.size())))
                        sb.append(' ').append(c.network().unknownName(en.getKey())).append('=').append(en.getValue());
                    System.out.println(sb);
                    dev.emergence.core.math.SparseLinearSystem.COLLAPSES.clear();
                }
                System.out.printf("   factorisations: %d fresh (pattern), %d fresh (unstable pivot), %d reused%n",
                        dev.emergence.core.math.SparseLinearSystem.FULL_PATTERN.sumThenReset(),
                        dev.emergence.core.math.SparseLinearSystem.FULL_UNSTABLE.sumThenReset(),
                        dev.emergence.core.math.SparseLinearSystem.REFACTORS.sumThenReset());
                System.out.printf("   profile: %.1f s of wall time for 10 s, %d steps accepted, %d rejected, %d Newton iterations, %d unknowns; limiters: %s%n",
                        (System.nanoTime() - wall0) / 1e9, c.solver().acceptedSteps() - acc0, c.solver().rejectedSteps() - rej0,
                        c.solver().newtonIterations() - it0, c.solver().unknowns(), top);
            }
            // -Ddiag.masses=true: every water volume whose mass moved by more than a gram since the last report
            if (Boolean.getBoolean("diag.masses")) {
                StringBuilder sb = new StringBuilder("   water moved:");
                for (var el : c.network().elements()) {
                    if (!(el instanceof dev.emergence.core.sim.elements.FluidVolume fv) || !fv.twoPhase()) continue;
                    double m = fv.mass(c.solverView());
                    String nm = fv.pressureNode().name();
                    Double was = masses.put(nm, m);
                    if (was != null && Math.abs(m - was) > 1e-3) {
                        var p = b.assembly().get(Long.parseLong(nm.substring(1)));
                        sb.append(String.format(" %s@%d,%d,%d %+.1f g (now %.1f, vapour %.2f);", p.kind(), p.minX(), p.minY(), p.minZ(),
                                1e3 * (m - was), 1e3 * m, fv.vapourFraction(c.solverView())));
                    }
                }
                System.out.println(sb);
            }
            var g = c.gas(box);
            System.out.printf("t=%.0f s: engine %.2f rad/s, boiler %.2f bar %.1f K, firebox %.0f K wall %.0f K (CH4 %.2f %%, O2 %.1f %%, CO2 %.1f %%), "
                            + "crown %.0f K, igniter %.0f K, cylinder wall %.1f K, fuel %.1f bar%n",
                    c.time(), w, 1e-5 * c.pressure(boiler), c.fluid(boiler)[1], g.temperature(c.solverView()), c.temperature(box),
                    100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("CH4"))), 100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("O2"))),
                    100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("CO2"))), c.temperature(b.id(e, "crown")), c.temperature(b.id(e, "igniter")), c.temperature(cyl),
                    1e-5 * c.pressure(fuel));
            // -Ddiag.heat=<part name>[:gas],…: every conduction, convection and radiation path at those parts' thermal nodes
            // (the part's own, or with ":gas" the gas it holds), heat out of them positive [W]
            for (String nm : System.getProperty("diag.heat", "").split(",")) {
                if (nm.isBlank()) continue;
                boolean ofGas = nm.trim().endsWith(":gas");
                long id = b.id(e, nm.trim().replace(":gas", ""));
                java.util.Set<String> mine = java.util.Set.of((ofGas ? "Tg" : "T") + id);
                StringBuilder sb = new StringBuilder("   heat out of " + nm + ":");
                double sum = 0;
                for (var el : c.network().elements()) {
                    dev.emergence.core.sim.Node x, y;
                    double q;
                    if (el instanceof dev.emergence.core.sim.elements.ThermalConductance tc) {
                        x = tc.a();
                        y = tc.b();
                        q = tc.heatFlow(c.solverView());
                    } else if (el instanceof dev.emergence.core.sim.elements.Radiation rd) {
                        x = rd.surface();
                        y = rd.surroundings();
                        q = rd.heatFlow(c.solverView());
                    } else if (el instanceof dev.emergence.core.sim.elements.NaturalConvection nc) {
                        x = nc.surface();
                        y = null;
                        q = nc.heatFlow(c.solverView());
                    } else continue;
                    boolean fromX = mine.contains(x.name()), fromY = y != null && mine.contains(y.name());
                    if (fromX == fromY) continue; // (not its own)
                    double out = fromX ? q : -q;
                    String other = fromX ? (y == null ? "ambient (convection)" : y.name()) : x.name();
                    String what = other;
                    try {
                        var op = b.assembly().get(Long.parseLong(other.replaceAll("^Tg?", "")));
                        if (op != null) what = op.kind() + "@" + op.minX() + "," + op.minY() + "," + op.minZ() + (other.startsWith("Tg") ? " gas" : "");
                    } catch (NumberFormatException ignored) {
                    }
                    if (Math.abs(out) > 0.5) sb.append(String.format(" %s %s %.0f;", what, el.getClass().getSimpleName().replace("ThermalConductance", "G"), out));
                    sum += out;
                }
                if (ofGas) {
                    var gg = c.gas(id);
                    sb.append(String.format(" (gas %.0f K, CH4 %.2f %%, O2 %.1f %%)", gg.temperature(c.solverView()),
                            100 * gg.moleFraction(c.solverView(), gg.indexOf(ch.species("CH4"))), 100 * gg.moleFraction(c.solverView(), gg.indexOf(ch.species("O2")))));
                }
                System.out.println(sb.append(String.format(" (total %.0f W)", sum)));
            }
            var seat = c.branchesOf(b.id(e, "throttle")).stream().filter(dev.emergence.core.sim.elements.FluidBranch::hasCam).findFirst();
            if (seat.isPresent()) System.out.printf("   throttle %.3f open, rod %.2f mm%n", seat.get().opening(c.solverView()),
                    1e3 * c.probe(b.id(e, "throttleRod")).angle());
            long well = b.id(e, "hotwell"), cnd = b.id(e, "condenser"), pump = b.id(e, "feedPump");
            if (e.named.containsKey("battery")) {
                var bat = c.probe(b.id(e, "battery"));
                System.out.printf("   battery: %.4f A·h taken in since the start (%.3f A on average)%n",
                        -c.batteryChargeDelivered(b.id(e, "battery")) / 3600, -c.batteryChargeDelivered(b.id(e, "battery")) / c.time());
                System.out.printf("   generator %.1f rad/s, battery %.3f V, charging %.3f A, coil A %.3f A%n", Math.abs(c.probe(b.id(e, "genShaft")).angularVelocity()),
                        bat.voltage(), bat.current(), c.probe(b.id(e, "coilA")).current());
                System.out.printf("   coil A %.3f V, coil B %.3f V; diodes %.3f %.3f %.3f %.3f A%n", c.probe(b.id(e, "coilA")).voltage(),
                        c.probe(b.id(e, "coilB")).voltage(), c.probe(b.id(e, "d1")).current(), c.probe(b.id(e, "d2")).current(),
                        c.probe(b.id(e, "d3")).current(), c.probe(b.id(e, "d4")).current());
            }
            if (e.named.containsKey("lamp")) {
                StringBuilder bits = new StringBuilder();
                for (int k = 2; k >= 0; k--) bits.append(c.lightOutput(b.id(e, "bitLed" + k)) > 1e-4 ? '1' : '0');
                System.out.printf("   lamp %.2f mW, counter %s, IR LED %.1f mW, photodiode %.3g W in%n", 1e3 * c.lightOutput(b.id(e, "lamp")),
                        bits, 1e3 * c.lightOutput(b.id(e, "irled")), c.lightInput(b.id(e, "pd")));
            }
            System.out.printf("   condenser %.3f bar %.1f K, hot well %.1f g of water, feed pump flow %.3f g/s, delivery %.3f g/s%n",
                    1e-5 * c.pressure(cnd), c.fluid(cnd)[1], 1e3 * c.fluid(well)[2] * (1 - c.fluid(well)[4]), 1e3 * c.fluid(pump)[3],
                    1e3 * Math.abs(c.branchesOf(b.id(e, "delivery")).get(0).massFlow(c.solverView())));
            System.out.printf("   boiler water %.1f g (%.3f g/s since the start), fuel used %.2f %% of the bottles%n",
                    1e3 * c.fluid(boiler)[2], 1e3 * (m0 - c.fluid(boiler)[2]) / c.time(), 100 * (1 - c.pressure(fuel) / f0));
        }
    }
}
