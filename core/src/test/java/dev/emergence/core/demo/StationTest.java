package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static dev.emergence.core.demo.DemoExhibitsTest.AMBIENT;

/** The power station ({@link StationExhibit}), section by section. */
class StationTest {
    /** The plant with the main fuel held open by hand: does the firebox keep the boiler pressed and the engine running? */
    @Test
    @EnabledIfSystemProperty(named = "diag.station", matches = ".+")
    void plantRunsOnItsFire() {
        Exhibit e = StationExhibit.station();
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
        for (double t = 0; t < len - 1e-9; t += every) {
            if (lit && t >= light) {
                c.toggleSwitch(b.id(e, "lightUp"));
                lit = false;
            }
            double w = 0;
            long wall0 = System.nanoTime(), acc0 = c.solver().acceptedSteps(), rej0 = c.solver().rejectedSteps(), it0 = c.solver().newtonIterations();
            var lim0 = new java.util.HashMap<>(c.solver().stepLimiters());
            for (int i = 0; i < per; i++) {
                c.step(0.05);
                w += Math.abs(c.probe(shaft).angularVelocity()) / per;
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
            var g = c.gas(box);
            System.out.printf("t=%.0f s: engine %.2f rad/s, boiler %.2f bar %.1f K, firebox %.0f K wall %.0f K (CH4 %.2f %%, O2 %.1f %%, CO2 %.1f %%), "
                            + "crown %.0f K, igniter %.0f K, cylinder wall %.1f K, fuel %.1f bar%n",
                    c.time(), w, 1e-5 * c.pressure(boiler), c.fluid(boiler)[1], g.temperature(c.solverView()), c.temperature(box),
                    100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("CH4"))), 100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("O2"))),
                    100 * g.moleFraction(c.solverView(), g.indexOf(ch.species("CO2"))), c.temperature(b.id(e, "crown")), c.temperature(b.id(e, "igniter")), c.temperature(cyl),
                    1e-5 * c.pressure(fuel));
            long well = b.id(e, "hotwell"), cnd = b.id(e, "condenser"), pump = b.id(e, "feedPump");
            System.out.printf("   condenser %.3f bar %.1f K, hot well %.1f g of water, feed pump flow %.3f g/s, delivery %.3f g/s%n",
                    1e-5 * c.pressure(cnd), c.fluid(cnd)[1], 1e3 * c.fluid(well)[2] * (1 - c.fluid(well)[4]), 1e3 * c.fluid(pump)[3],
                    1e3 * Math.abs(c.branchesOf(b.id(e, "delivery")).get(0).massFlow(c.solverView())));
            System.out.printf("   boiler water %.1f g (%.3f g/s since the start), fuel used %.2f %% of the bottles%n",
                    1e3 * c.fluid(boiler)[2], 1e3 * (m0 - c.fluid(boiler)[2]) / c.time(), 100 * (1 - c.pressure(fuel) / f0));
        }
    }
}
