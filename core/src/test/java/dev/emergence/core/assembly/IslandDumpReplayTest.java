package dev.emergence.core.assembly;

import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Replays an island the game could not simulate, from the dump it writes with -Demergence.dumpFailures=true
 * ({@code island-failure-*.txt}), or one the tour wrote out with -Demergence.dumpIslands=<exhibit>
 * ({@code island-dump-*.txt}): run with {@code -Ddiag.dump=<file>} to compile it from the dumped state and step it,
 * {@code -Ddiag.dumpSeconds} long (default 1), printing each second the probes of the parts in {@code -Ddiag.dumpProbe}
 * (comma-separated ids).
 */
class IslandDumpReplayTest {
    @Test
    @EnabledIfSystemProperty(named = "diag.dump", matches = ".+")
    void replay() throws Exception {
        var mats = MaterialDatabase.standard();
        Assembly a = new Assembly();
        AssemblyState st = AssemblyState.empty();
        // -Ddiag.dropState=<id>,…: those parts' saved state left out (they start as built)
        java.util.Set<String> drop = new java.util.HashSet<>(java.util.Arrays.asList(System.getProperty("diag.dropState", "").split(",")));
        for (String line : Files.readAllLines(Path.of(System.getProperty("diag.dump")))) {
            String[] f = line.trim().split(" ");
            if (f.length > 1 && !f[0].equals("P") && !f[0].equals("S")) {
                String id = f[0].equals("C") ? f[1].substring(1, f[1].indexOf(':') > 0 ? f[1].indexOf(':') : 1) : f[1];
                if (drop.contains(id)) continue;
            }
            switch (f[0]) {
                case "ambient" -> a.ambientTemperature(Double.parseDouble(f[1])).ambientPressure(Double.parseDouble(f[2]));
                case "P" -> {
                    Map<String, Double> prm = new HashMap<>();
                    for (int i = 12; i < f.length; i++) {
                        int eq = f[i].indexOf('=');
                        if (eq > 0) prm.put(f[i].substring(0, eq), Double.parseDouble(f[i].substring(eq + 1)));
                    }
                    a.add(new Part(Long.parseLong(f[1]), PartKind.valueOf(f[2]), mats.get(f[3]), Integer.parseInt(f[4]),
                            Integer.parseInt(f[5]), Integer.parseInt(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]),
                            Integer.parseInt(f[9]), Dir.valueOf(f[10]), prm));
                }
                case "T" -> st.temperatures().put(Long.parseLong(f[1]), Double.parseDouble(f[2]));
                case "I" -> st.coilCurrents().put(Long.parseLong(f[1]), Double.parseDouble(f[2]));
                case "V" -> st.capacitorVoltages().put(Long.parseLong(f[1]), Double.parseDouble(f[2]));
                case "M" -> st.bodyMotion().put(Long.parseLong(f[1]), new double[]{Double.parseDouble(f[2]), Double.parseDouble(f[3])});
                case "F" -> st.fluids().put(Long.parseLong(f[1]), new double[]{Double.parseDouble(f[2]), Double.parseDouble(f[3])});
                case "C" -> {
                    double v = Double.parseDouble(f[2]);
                    // -Ddiag.scaleSpecies=<key>:<factor>,…: a saved amount scaled (e.g. G<id>:N2:1e5, air into a pure gas)
                    for (String sc : System.getProperty("diag.scaleSpecies", "").split(",")) {
                        int k = sc.lastIndexOf(':');
                        if (k > 0 && sc.substring(0, k).equals(f[1])) v *= Double.parseDouble(sc.substring(k + 1));
                    }
                    st.chemistry().put(f[1], v);
                }
                default -> {
                }
            }
        }
        java.util.Set<Long> anchored = new java.util.HashSet<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("diag.dump")))) {
            String[] f = line.trim().split(" ");
            if (f[0].equals("P") && f[11].equals("1")) anchored.add(Long.parseLong(f[1]));
            // a part's own state (a switch's position, a knob's setting)
            if (f[0].equals("S")) a.get(Long.parseLong(f[1])).setState(f[2], Double.parseDouble(f[3]));
        }
        a.anchoredWhen(p -> anchored.contains(p.id()));
        if (Boolean.getBoolean("diag.rest")) st = st.recovery();
        CompiledAssembly c = AssemblyCompiler.compile(a, st);
        for (var b : c.bodies()) {
            StringBuilder sb = new StringBuilder();
            for (long id : b.parts()) sb.append(a.get(id).kind()).append('@').append(a.get(id).minX()).append(',').append(a.get(id).minY()).append(' ');
            System.out.printf("body %d static=%s slider=%s: %s%n", b.index(), b.isStatic(), b.slider, sb);
        }
        c.warnings().forEach(w -> System.out.println("warning: " + w));
        double seconds = Double.parseDouble(System.getProperty("diag.dumpSeconds", "1"));
        String[] probes = System.getProperty("diag.dumpProbe", "").isBlank() ? new String[0] : System.getProperty("diag.dumpProbe").split(",");
        // -Ddiag.dumpCrank=<crank part id>:<s>: a hand on that crank for the first s seconds (as the tour's hand)
        String[] crank = System.getProperty("diag.dumpCrank", "").isBlank() ? null : System.getProperty("diag.dumpCrank").split(":");
        for (int i = 0; i < Math.round(seconds / 0.05); i++) {
            if (Boolean.getBoolean("diag.newtonLaggards") && i == 0) {
                try {
                    c.step(0.05);
                } catch (RuntimeException ex) {
                    var lag = new java.util.ArrayList<>(c.solver().newtonLaggards().entrySet());
                    lag.sort((p1, p2) -> p2.getValue() - p1.getValue());
                    System.out.println("laggards: " + lag.subList(0, Math.min(15, lag.size())));
                    throw ex;
                }
                continue;
            }
            if (crank != null) c.setCrankEffort(Long.parseLong(crank[0]), (i + 1) * 0.05 <= Double.parseDouble(crank[1]) ? 1 : 0, false);
            c.step(0.05);
            if (probes.length == 0) System.out.printf("t=%.2f%n", c.time());
            else if ((i + 1) % 20 == 0) {
                StringBuilder sb = new StringBuilder(String.format("t=%.2f", c.time()));
                for (String id : probes) {
                    var pr = c.probe(Long.parseLong(id.trim()));
                    sb.append(String.format("  %s: ω %.3f, I %.4f A, V %.4f V, T %.2f K", id.trim(), pr.angularVelocity(), pr.current(),
                            pr.voltage(), pr.temperature()));
                }
                System.out.println(sb);
            }
        }
        // -Ddiag.dumpFriction=<W>: every Coulomb friction dissipating more than that at the end
        if (System.getProperty("diag.dumpFriction") != null) {
            double min = Double.parseDouble(System.getProperty("diag.dumpFriction"));
            for (var el : c.network().elements())
                if (el instanceof dev.emergence.core.sim.elements.CoulombFriction f) {
                    double[] tp = f.torqueAndPower(c.solver());
                    if (Math.abs(tp[1]) > min)
                        System.out.printf("friction %s: limit %.4g, torque %.4g, %.4f W%n", f, f.frictionTorque(), tp[0], tp[1]);
                }
        }
        // -Ddiag.dumpCurrents=<A>: every part carrying more than that at the end, where the current goes
        if (System.getProperty("diag.dumpCurrents") != null) {
            double min = Double.parseDouble(System.getProperty("diag.dumpCurrents"));
            for (Part p : a.parts()) {
                var pr = c.probe(p.id());
                if (pr != null && (Math.abs(pr.current()) > min || Math.abs(pr.power()) > 10 * min))
                    System.out.printf("current %s %s at %d,%d,%d–%d,%d,%d: %.4f A, %.4f V, %.4f W%n", p.kind(), p.material().id(), p.minX(), p.minY(),
                            p.minZ(), p.maxX(), p.maxY(), p.maxZ(), pr.current(), pr.voltage(), pr.power());
            }
        }
    }
}
