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

    /**
     * The analyser decodes the counter display's serial bus from the levels it recorded: the controller's set-up
     * commands come first (display on 0xAF, horizontal addressing 0x20 0x00, as the design sends them).
     */
    @Test
    void itDecodesTheDisplaysSpiBus() {
        Exhibit e = MonitorExhibits.counterDisplay();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        LogicCapture cap = new LogicCapture(65_536);
        long[] probes = {wireOf(e, b, "sclk"), wireOf(e, b, "din"), wireOf(e, b, "cs"), -1, -1, -1, -1, -1};
        c.attachCapture(cap, wireOf(e, b, "gnd"), probes);
        // a single capture on the select's first fall (the bus fills the record in 36 ms, so a rolling one has lost
        // the start by the time it is read)
        cap.trigger(2, false, 2.5, 3e-3);
        cap.mode(LogicCapture.Mode.SINGLE);
        for (int i = 0; i < 4; i++) c.step(0.05);
        assertTrue(cap.frozen(), "triggered and holding");
        var frames = dev.emergence.core.instruments.BusDecoder.spi(cap, 0, 1, 2, true, 0, cap.newest());
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(8, frames.size()); i++) s.append(frames.get(i).text()).append(' ');
        System.out.println("counter display bus: " + frames.size() + " bytes, first " + s);
        assertTrue(frames.size() > 50, "the set-up and the screen's first bytes: " + frames.size());
        assertEquals(0xAF, frames.get(0).value());
        assertEquals(0x20, frames.get(1).value());
        assertEquals(0x00, frames.get(2).value());
        assertEquals(1, frames.get(0).channel(), "reported on the data line");
        String csv = dev.emergence.core.instruments.BusDecoder.csv(cap, new String[]{"sclk", "din", "cs"});
        assertTrue(csv.startsWith("time_s,\"sclk (level)\",\"din (level)\",\"cs (level)\"\n"), csv.substring(0, 80));
        assertTrue(csv.lines().count() > cap.size(0), "a row for every change");
        c.detachCapture(cap);
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
