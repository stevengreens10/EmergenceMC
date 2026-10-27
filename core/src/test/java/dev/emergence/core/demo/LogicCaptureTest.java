package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.instruments.LogicCapture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A logic analyser's probes on the counter display: its serial bus's levels as they switch, a counter output's voltage. */
class LogicCaptureTest {
    /** A wire of a routed net (the first the router laid for it). */
    static long wireOf(Exhibit e, Exhibits.Built b, String net) {
        for (var en : e.nets.entrySet()) if (en.getValue().equals(net)) return b.ids().get(en.getKey());
        throw new IllegalArgumentException("no net " + net);
    }

    @Test
    void probesRecordTheBusAndACounterOutput() {
        Exhibit e = MonitorExhibits.counterDisplay();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        LogicCapture cap = new LogicCapture(65_536);
        long[] probes = {wireOf(e, b, "sclk"), wireOf(e, b, "din"), wireOf(e, b, "q0"), -1, -1, -1, -1, -1};
        c.attachCapture(cap, wireOf(e, b, "gnd"), probes);
        for (int i = 0; i < 30; i++) c.step(0.05); // 1.5 s: the count steps once
        assertTrue(cap.digital(0) && cap.digital(1), "the serial bus's nets are digital (levels as they switch)");
        assertFalse(cap.digital(2), "a counter output through an LED is analog (voltages)");
        assertTrue(cap.size(0) > 10_000, "the 500 kHz bus's clock switched many times: " + cap.size(0));
        // its clock's edges come every 2 µs (17 a byte: every other one, roughly)
        double dt = cap.time(0, cap.size(0) - 1) - cap.time(0, cap.size(0) - 2);
        assertTrue(dt > 1e-6 && dt < 1e-4, "edges microseconds apart: " + dt);
        // the counter output swings between the rails
        double lo = 1e9, hi = -1e9;
        for (int i = 0; i < cap.size(2); i++) {
            lo = Math.min(lo, cap.value(2, i));
            hi = Math.max(hi, cap.value(2, i));
        }
        assertTrue(lo < 0.5 && hi > 4, "Q0 low and high: " + lo + " .. " + hi + " V");
        // an envelope of the last 100 µs: the clock's bins all span 0..1 (it switches inside each 10 µs bin)
        double t1 = cap.newest(), t0 = t1 - 100e-6;
        float[][] env = cap.envelope(0, t0, t1, 10);
        int toggling = 0;
        for (int i = 0; i < 10; i++) if (env[0][i] == 0 && env[1][i] == 1) toggling++;
        assertTrue(toggling >= 8, "the clock switches within (nearly) every 10 µs bin: " + toggling);
        // a single capture: armed on Q0 rising, it triggers at the next count, records 1 ms, holds
        cap.trigger(2, true, 2.5, 1e-3);
        cap.mode(LogicCapture.Mode.SINGLE);
        for (int i = 0; i < 50 && !cap.frozen(); i++) c.step(0.05);
        assertTrue(cap.frozen() && !Double.isNaN(cap.triggerTime()), "it triggered and holds");
        assertTrue(cap.valueAt(2, cap.triggerTime() + 1e-4) > 2.5, "Q0 is high just after its trigger");
        double held = cap.newest();
        c.step(0.05);
        assertEquals(held, cap.newest(), "holding: nothing more recorded");
        c.detachCapture(cap);
    }
}
