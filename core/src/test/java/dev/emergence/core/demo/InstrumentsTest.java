package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.instruments.DataLogger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The data logger: resampling, ring buffer, CSV, display envelope, and live capture from an exhibit. */
class InstrumentsTest {

    @Test
    void resamplesOntoItsClockByInterpolation() {
        DataLogger log = new DataLogger(List.of(new DataLogger.Channel("x", "V")), 0.01, 1000);
        // a ramp x = 3t offered at irregular solver steps
        double[] ts = {0, 0.004, 0.013, 0.0131, 0.05, 0.051, 0.09};
        for (double t : ts) log.offer(t, new double[]{3 * t});
        assertEquals(10, log.size(), "samples at 0, 0.01, …, 0.09");
        for (int i = 0; i < log.size(); i++) {
            assertEquals(0.01 * i, log.time(i), 1e-12);
            assertEquals(0.03 * i, log.value(0, i), 1e-6, "interpolated between steps");
        }
        // a recompiled source restarts its clock at 0: the record runs on
        log.rebase(0);
        log.offer(0.02, new double[]{1});
        assertEquals(12, log.size());
        assertEquals(0.11, log.time(11), 1e-12);
    }

    @Test
    void ringBufferCsvAndEnvelope() {
        DataLogger log = new DataLogger(List.of(new DataLogger.Channel("a", "A"), new DataLogger.Channel("b", "K")), 1, 5);
        for (int t = 0; t <= 8; t++) log.offer(t, new double[]{t, t % 2 == 0 ? Double.NaN : 10 * t});
        assertEquals(5, log.size(), "keeps the newest capacity samples");
        assertEquals(4, log.time(0), 1e-12);
        assertEquals(8, log.value(0, 4), 1e-12);
        String[] rows = log.csv().split("\n");
        assertEquals("time [s],a [A],b [K]", rows[0]);
        assertEquals(6, rows.length);
        assertEquals("4.000000,4.000000,", rows[1], "a missing quantity is an empty field");
        assertEquals("5.000000,5.000000,50.00000", rows[2]);
        float[][] env = log.envelope(0, 4, 2);
        assertEquals(4, env[0][0], 1e-6);
        assertEquals(5, env[1][0], 1e-6);
        assertEquals(6, env[0][1], 1e-6);
        assertEquals(8, env[1][1], 1e-6);
    }

    /**
     * Clipped to the hand generator's load resistor and rotor, the loggers see the EMF repeat once per turn:
     * the period of the logged voltage (its autocorrelation peak) equals 2π/ω of the logged speed, and the logged
     * angle is the integral of the logged speed. (The waveform is not a sine: each magnet passing a coil gives a
     * bipolar pulse.)
     */
    @Test
    void capturesTheGeneratorWaveform() {
        Exhibit e = Exhibits.generator();
        Exhibits.Built b = Exhibits.build(e, 293.15);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        long crank = b.id(e, "crank"), load = b.id(e, "load");
        DataLogger vLog = CompiledAssembly.newLogger(1e-3, 10_000), wLog = CompiledAssembly.newLogger(1e-3, 10_000);
        c.attachLogger(vLog, load);
        c.attachLogger(wLog, crank);
        for (int i = 0; i < 60; i++) {
            c.setCrankEffort(crank, 1, true);
            c.step(0.05);
        }
        int n = vLog.size();
        assertTrue(n > 2900, "about 3000 samples at 1 kHz: " + n);
        assertTrue(Double.isNaN(vLog.value(4, n - 1)), "a resistor has no speed");
        double w = 0, vmax = 0;
        int m = wLog.size();
        for (int i = m - 1000; i < m; i++) w += wLog.value(4, i) / 1000;
        for (int i = n - 1000; i < n; i++) vmax = Math.max(vmax, Math.abs(vLog.value(2, i)));
        assertTrue(vmax > 0.1, "an EMF is logged: " + vmax);
        // the lag (between 0.6 and 1.4 turns) at which the last 0.6 s of voltage best matches itself
        int lag0 = (int) Math.round(2 * Math.PI / w / 1e-3), best = -1, start = n - 600 - (int) (1.4 * lag0);
        double bestC = -2;
        for (int lag = (int) (0.6 * lag0); lag <= (int) (1.4 * lag0); lag++) {
            double xy = 0, xx = 0, yy = 0;
            for (int i = start; i < start + 600; i++) {
                double x = vLog.value(2, i), y = vLog.value(2, i + lag);
                xy += x * y;
                xx += x * x;
                yy += y * y;
            }
            double corr = xy / Math.sqrt(xx * yy);
            if (corr > bestC) {
                bestC = corr;
                best = lag;
            }
        }
        double period = best * 1e-3;
        System.out.printf("generator on the logger: %.1f rad/s, voltage period %.4f s (2π/ω = %.4f s)%n", w, period, 2 * Math.PI / w);
        assertEquals(2 * Math.PI / w, period, 0.03 * period, "one cycle per turn");
        assertTrue(bestC > 0.95, "the waveform repeats each turn: correlation " + bestC);
        // angle = ∫ω dt over the last second (trapezoid on the logged samples)
        double integral = 0;
        for (int i = m - 1000; i < m - 1; i++) integral += 0.5 * (wLog.value(4, i) + wLog.value(4, i + 1)) * 1e-3;
        assertEquals(wLog.value(5, m - 1) - wLog.value(5, m - 1000), integral, 0.01 * Math.abs(integral));
        // a detached logger stops recording
        c.detachLogger(vLog);
        c.step(0.05);
        assertEquals(n, vLog.size());
    }
}
