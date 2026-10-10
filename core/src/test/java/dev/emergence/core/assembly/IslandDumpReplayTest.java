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
 * ({@code island-failure-*.txt}): run with {@code -Ddiag.dump=<file>} to compile it from the dumped state and step it.
 */
class IslandDumpReplayTest {
    @Test
    @EnabledIfSystemProperty(named = "diag.dump", matches = ".+")
    void replay() throws Exception {
        var mats = MaterialDatabase.standard();
        Assembly a = new Assembly();
        AssemblyState st = AssemblyState.empty();
        for (String line : Files.readAllLines(Path.of(System.getProperty("diag.dump")))) {
            String[] f = line.trim().split(" ");
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
                case "C" -> st.chemistry().put(f[1], Double.parseDouble(f[2]));
                default -> {
                }
            }
        }
        java.util.Set<Long> anchored = new java.util.HashSet<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("diag.dump")))) {
            String[] f = line.trim().split(" ");
            if (f[0].equals("P") && f[11].equals("1")) anchored.add(Long.parseLong(f[1]));
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
        for (int i = 0; i < 20; i++) {
            c.step(0.05);
            System.out.printf("t=%.2f%n", c.time());
        }
    }
}
