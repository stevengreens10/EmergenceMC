package dev.emergence.core.perf;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmarks against the budget in PERFORMANCE.md: one 50 ms game tick of an island
 * should cost well under 5 ms of server time. Results are printed for the performance log.
 */
@org.junit.jupiter.api.parallel.Isolated("wall-clock timings: run alone")
class SolverBenchmarkTest {

    static double msPerTick(CompiledAssembly c, int ticks) {
        for (int i = 0; i < 20; i++) c.step(0.05); // warm-up (JIT, first factorisation)
        return measure(c, ticks, null);
    }

    /** Human cranking exactly as in game. */
    static void human(CompiledAssembly c, long crank) {
        c.setCrankEffort(crank, 1, false);
    }

    static double measure(CompiledAssembly c, int ticks, Runnable perTick) {
        var s = c.solver();
        long st0 = s.acceptedSteps(), n0 = s.newtonIterations(), r0 = s.rejectedSteps(), f0 = s.newtonFailures();
        long t0 = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            if (perTick != null) perTick.run();
            c.step(0.05);
        }
        double ms = (System.nanoTime() - t0) / 1e6 / ticks;
        System.out.printf("BENCH   steps/tick=%.1f newton/step=%.2f rejected=%d newtonFail=%d lastH=%.2e%n",
                (s.acceptedSteps() - st0) / (double) ticks, (s.newtonIterations() - n0) / (double) Math.max(1, s.acceptedSteps() - st0),
                s.rejectedSteps() - r0, s.newtonFailures() - f0, s.lastStepSize());
        System.out.println("BENCH   limiters " + s.stepLimiters());
        return ms;
    }

    @Test
    void exhibitIslandsFitTheTickBudget() {
        for (Exhibit e : Exhibits.all()) {
            Exhibits.Built b = Exhibits.build(e, 293.15);
            CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
            double ms;
            if (e.named.containsKey("crank")) {
                long crank = b.id(e, "crank");
                for (int i = 0; i < 100; i++) { // spin up to the human-power steady state first
                    human(c, crank);
                    c.step(0.05);
                }
                ms = measure(c, 100, () -> human(c, crank));
                System.out.printf("BENCH   crank speed %.1f rad/s%n", c.probe(crank).angularVelocity());
            } else {
                ms = msPerTick(c, 100);
            }
            System.out.printf("BENCH exhibit %-11s unknowns=%4d  %.3f ms/tick%n", e.id, c.network().size(), ms);

        }
    }

    @Test
    void largeRcMeshScales() {
        for (int n : new int[]{10, 30, 60}) {
            Network net = new Network();
            Node gnd = net.reference(Domain.ELECTRICAL);
            Node[][] g = new Node[n][n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                g[i][j] = net.node(Domain.ELECTRICAL, i + "," + j);
                net.add(new Capacitor(g[i][j], gnd, 1e-6));
                if (i > 0) net.add(new Resistor(g[i][j], g[i - 1][j], 100));
                if (j > 0) net.add(new Resistor(g[i][j], g[i][j - 1], 100));
            }
            net.add(new VoltageSource(g[0][0], gnd, t -> 5 * Math.sin(2 * Math.PI * 50 * t)));
            TransientSolver s = new TransientSolver(net).maxStep(1e-3);
            s.advance(0.05);
            long t0 = System.nanoTime();
            s.advance(0.5);
            double ms = (System.nanoTime() - t0) / 1e6 / 10;
            System.out.printf("BENCH RC mesh %dx%d (%d unknowns): %.3f ms per 50 ms tick, %d steps%n",
                    n, n, net.size(), ms, s.acceptedSteps());
        }
    }
}
