package dev.emergence.core.perf;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.demo.ControlExhibits;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import org.junit.jupiter.api.Test;

/**
 * The costly exhibits as they run in the world (switched on), against real time: milliseconds of CPU per 50 ms game
 * tick (above 50, slower than real time), steps per tick, and the unknowns that limit the step. A measurement for the
 * real-time plan (NEXT_STEPS 0b), printed, not asserted.
 */
@org.junit.jupiter.api.parallel.Isolated("wall-clock timings: run alone")
class RunningExhibitsBenchTest {

    private static void bench(Exhibit e, String... switches) {
        Exhibits.Built b = Exhibits.build(e, 293.15);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        for (String sw : switches) c.toggleSwitch(b.id(e, sw));
        for (int i = 0; i < 40; i++) c.step(0.05); // two seconds to get going (and warm the JIT)
        double ms = SolverBenchmarkTest.measure(c, 40, null);
        System.out.printf("RUNNING %-12s unknowns=%4d  %.1f ms/tick (%.2f× real time)%n", e.id, c.network().size(), ms, ms / 50);
    }

    @Test
    void switchingExhibits() {
        bench(ControlExhibits.inverter());
        bench(Exhibits.boost(), "switch");
        bench(ControlExhibits.pwmMotor());
        bench(ControlExhibits.hBridge(), "forward");
        bench(dev.emergence.core.demo.AcousticsExhibits.loudspeaker());
        bench(dev.emergence.core.demo.AcousticsExhibits.loudspeaker(), "switch");
    }

    /** The engines running: the gas engine started by hand (fuel open, ten seconds at its handle), the steam engines on. */
    @Test
    void engines() {
        Exhibit e = dev.emergence.core.demo.EngineExhibits.gasEngine();
        Exhibits.Built b = Exhibits.build(e, 293.15);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        c.toggleSwitch(b.id(e, "needle"));
        long handle = b.id(e, "handle"), crank = b.id(e, "crankshaft");
        for (int i = 0; i < 200; i++) {
            c.setCrankEffort(handle, 1, false);
            c.step(0.05);
        }
        c.setCrankEffort(handle, 0, false);
        for (int i = 0; i < 40; i++) c.step(0.05);
        double ms = SolverBenchmarkTest.measure(c, 40, null);
        System.out.printf("RUNNING %-12s unknowns=%4d  %.1f ms/tick (%.2f× real time) at %.0f rad/s%n", e.id, c.network().size(), ms, ms / 50,
                c.probe(crank).angularVelocity());
        bench(Exhibits.steamEngine());
        bench(Exhibits.condensingEngine());
    }
}
