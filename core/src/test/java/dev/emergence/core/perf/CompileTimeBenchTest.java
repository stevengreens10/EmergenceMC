package dev.emergence.core.perf;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * How long each exhibit takes to compile (the server thread's hitch when a player changes a machine): printed, the
 * slowest first, not asserted.
 */
@org.junit.jupiter.api.parallel.Isolated("wall-clock timings: run alone")
class CompileTimeBenchTest {
    @Test
    void compileTimes() {
        List<String> rows = new ArrayList<>();
        List<Double> ms = new ArrayList<>();
        double total = 0;
        String only = System.getProperty("diag.compileOnly");
        for (Exhibit e : Exhibits.all()) {
            if (only != null && !only.equals(e.id)) continue;
            Exhibits.Built b = Exhibits.build(e, 293.15);
            AssemblyCompiler.compile(b.assembly(), b.state()); // warm
            long t0 = System.nanoTime();
            AssemblyCompiler.compile(b.assembly(), b.state());
            double t = (System.nanoTime() - t0) / 1e6;
            total += t;
            ms.add(t);
            rows.add(String.format("COMPILE %-12s %4d parts %8.1f ms", e.id, b.assembly().parts().size(), t));
        }
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(ms.get(y), ms.get(x)));
        for (int k = 0; k < Math.min(15, order.length); k++) System.out.println(rows.get(order[k]));
        System.out.printf("COMPILE total %.0f ms over %d exhibits%n", total, rows.size());
    }
}
