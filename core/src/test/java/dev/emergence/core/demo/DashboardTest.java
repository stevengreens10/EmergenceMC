package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.digital.DesignComponent;
import dev.emergence.core.digital.DisplayPanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The power station's dashboard: each gauge, reading its sensor through miniature electronics, shows what the
 * simulation holds — the fuel bottles' and the boiler's pressure, the engine's speed, the condenser's pressure, the
 * generator's current and the battery's voltage — within its measurement's accuracy.
 */
class DashboardTest {
    @Test
    void theGaugesShowTheStationsState() {
        Exhibit e = StationExhibit.station();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        int n = StationExhibit.GAUGES.length;
        DesignComponent[] chip = new DesignComponent[n];
        for (int k = 0; k < n; k++) chip[k] = (DesignComponent) c.digitalChip(b.embeddedId(e, k, "gauge")).component();
        double pa = b.assembly().ambientPressure();
        long fuel = b.id(e, "fuel"), boiler = b.id(e, "boiler"), shaft = b.id(e, "shaft"), cond = b.id(e, "condenser"),
                bat = b.id(e, "battery"), shunt = b.id(e, "genShunt");
        // -Ddiag.sun=<W/m²>: the sun high in the sky, every part in it (as a world by day)
        double sun = Double.parseDouble(System.getProperty("diag.sun", "0"));
        if (sun > 0) c.setDaylight(new dev.emergence.core.math.Vec3(-0.3, -1, -0.2).normalize(), sun, id -> true);
        double t0 = c.time(), q0 = c.batteryChargeDelivered(bat);
        long wall0 = System.nanoTime();
        double seconds = Double.parseDouble(System.getProperty("diag.dashRun", "30"));
        // -Ddiag.powerTrace=true: every half second, the power and current gauges against the generator's mean current
        // (from the battery's charge) and power over that half second
        boolean trace = Boolean.getBoolean("diag.powerTrace");
        double qa = c.batteryChargeDelivered(bat);
        for (int i = 0; i < Math.round(seconds / 0.05); i++) {
            c.step(0.05);
            if (trace && i % 10 == 9) {
                double qb = c.batteryChargeDelivered(bat), iBat = -(qb - qa) / 0.5;
                qa = qb;
                System.out.printf("  t=%5.1f s: power gauge %d, current gauge %d; battery %.3f V, mean charging current %.1f mA (%.1f ×10 mW); shunt now %.1f mA%n",
                        c.time(), chip[6].instance().value("v"), chip[4].instance().value("v"), c.probe(bat).voltage(), 1e3 * iBat,
                        100 * iBat * c.probe(bat).voltage(), -1e3 * c.probe(shunt).current());
            }
        }
        // the generator's current pulses with each magnet's pass: its mean over the last second, sampled every millisecond
        // (what the gauge's 0.2 s filter averages; samples 50 ms apart alias)
        double q1 = 0;
        double vMax = 0, regMax = 0;
        for (int i = 0; i < 1000; i++) {
            c.step(0.001);
            q1 += -c.probe(shunt).current() * 0.001;
            vMax = Math.max(vMax, c.probe(bat).voltage());
            regMax = Math.max(regMax, c.probe(b.id(e, "regulator")).voltage());
        }
        double wall = (System.nanoTime() - wall0) / 1e9;
        // the station's own electricity unharmed: its battery still charges, its regulator not dumping (with the
        // generator's return led to the dashboard and back, its current through the station's ground wiring shifted the
        // regulator's sense and it dumped 550 mA into its load)
        double charged = -(c.batteryChargeDelivered(bat) - q0) / 3.6, dump = Math.abs(c.probe(b.id(e, "dump")).current());
        System.out.printf("  over the last second, sampled every millisecond: the battery's terminal voltage at most %.4f V, the regulator's output at most %.3f V%n", vMax, regMax);
        System.out.printf("  battery: %.3f mA·h taken in; the regulator's dump load %.1f mA%n", charged, 1e3 * dump);
        // the regulator's state: what decides whether it dumps (its output, the lamp, the shunt MOSFET) and how warm its parts are
        System.out.printf("  regulator: battery %.4f V; output %.3f V; lamp %.2f mA; shunt MOSFET %.2f mA; regulator %.1f K, shunt %.1f K, lamp %.1f K, dump %.1f K; sun %.0f W/m², %.1f W absorbed in all%n",
                c.probe(bat).voltage(), c.probe(b.id(e, "regulator")).voltage(), 1e3 * Math.abs(c.probe(b.id(e, "lamp")).current()),
                1e3 * Math.abs(c.probe(b.id(e, "shunt")).current()), c.temperature(b.id(e, "regulator")), c.temperature(b.id(e, "shunt")),
                c.temperature(b.id(e, "lamp")), c.temperature(b.id(e, "dump")), sun, c.sunlightAbsorbed());
        double meanCurrent = q1 / 1.0;
        double[] truth = {
                (c.pressure(fuel) - pa) / 1e5 * 10,                                 // bar, 1 place
                (c.pressure(boiler) - pa) / 1e5 * 100,                              // bar, 2 places
                Math.abs(c.probe(shaft).angularVelocity()) * 60 / (2 * Math.PI),    // rpm
                c.pressure(cond) / 1e3 * 10,                                        // kPa absolute, 1 place
                meanCurrent * 1e3,                                                  // mA (its mean over the run)
                c.probe(bat).voltage() * 100,                                       // V, 2 places
                meanCurrent * c.probe(bat).voltage() * 100};                        // W, 2 places
        // what each may be off by: the ADC's step and noise, the gain's rounding, and: the condenser's offset for the
        // world's air pressure (2.8 kPa) against the test's; the generator's for its supply's 5.6 V; the engine's for
        // the speed's ripple within a turn; the battery's for its diodes' drop
        double[] tol = {15, 12, 6, 30, 0.15 * truth[4] + 2, 6, 0.15 * truth[6] + 2};
        System.out.printf("dashboard: %.0f s in %.1f s of wall time; ambient %.0f Pa%n", c.time() - t0, wall, pa);
        boolean ok = true;
        for (int k = 0; k < n; k++) {
            long v = chip[k].instance().value("v");
            var g = StationExhibit.GAUGES[k];
            System.out.printf("  %-16s shows %5d (%s %s), truth %.1f; violations %d%n", g.label(), v, shown(v, g.dp()), g.unit(), truth[k],
                    chip[k].instance().violations());
            ok &= Math.abs(v - truth[k]) <= tol[k];
        }
        var genAdc = (dev.emergence.core.digital.SpiAdc) c.digitalChip(b.embeddedId(e, 4, "adc")).component();
        System.out.printf("  generator: shunt %.4f V (%.4f A), amplifier out %.4f V, ADC %d%n", c.probe(shunt).voltage(), c.probe(shunt).current(),
                c.probe(b.embeddedId(e, 4, "amp")).voltage(), genAdc.lastCode());
        // the power gauge's readings, for its constant: k = P·code2²/code (P in tens of milliwatts)
        var pcur = (dev.emergence.core.digital.SpiAdc) c.digitalChip(b.embeddedId(e, 6, "adc")).component();
        var pr = (dev.emergence.core.digital.SpiAdc) c.digitalChip(b.embeddedId(e, 6, "adc2")).component();
        System.out.printf("  power: current ADC %d, reference ADC %d: k = %.0f%n", pcur.lastCode(), pr.lastCode(),
                truth[6] * pr.lastCode() * pr.lastCode() / Math.max(1, pcur.lastCode()));
        // the battery gauge's reference, for its constant
        var refAdc = (dev.emergence.core.digital.SpiAdc) c.digitalChip(b.embeddedId(e, 5, "adc")).component();
        System.out.printf("  battery reference: ADC %d at %.3f V: k = %.0f%n", refAdc.lastCode(), c.probe(bat).voltage(),
                refAdc.lastCode() * c.probe(bat).voltage() * 100);
        // what the screens draw from the supply: each controller's own current and its lit pixels'
        double screensA = 0;
        StringBuilder lits = new StringBuilder();
        for (int k = 0; k < n; k++) {
            var dp = (DisplayPanel) c.digitalChip(b.embeddedId(e, k, "controller")).component();
            screensA += dp.leakage(c.probe(bat).voltage());
            lits.append(' ').append(dp.shown());
        }
        System.out.printf("  screens: %.2f mA from the supply in all (lit pixels:%s)%n", 1e3 * screensA, lits);
        DisplayPanel screen = (DisplayPanel) c.digitalChip(b.embeddedId(e, 1, "controller")).component();
        System.out.println(GaugeTest.render(screen));
        for (int k = 0; k < n; k++) assertEquals(0, chip[k].instance().violations(), StationExhibit.GAUGES[k].label() + ": timing");
        assertTrue(ok, "every gauge shows its quantity within its accuracy");
        // the fuel's trend takes a reading in thirty (none yet in 30 s of readings every 2 s), the boiler's every one
        if (seconds < 50) assertEquals(0, chip[0].instance().value("wr"), "the fuel gauge's slow trend: no point yet"); // (its first after 30 readings, some 60 s)
        assertTrue(chip[1].instance().value("wr") >= 10, "the boiler's trend: a point a reading, " + chip[1].instance().value("wr"));
        // running as it should, none of its alarms: no card inverted
        for (int k = 0; k < n; k++) {
            var ctl = (DisplayPanel) c.digitalChip(b.embeddedId(e, k, "controller")).component();
            assertFalse(ctl.inverted(), StationExhibit.GAUGES[k].label() + " in alarm while the station runs as it should");
        }
        assertTrue(charged > 0, "the battery still charges: " + charged + " mA·h");
        assertTrue(dump < 0.01, "the regulator is not dumping: " + dump + " A");
    }

    static String shown(long v, int dp) {
        return dp == 0 ? Long.toString(v) : String.format("%d.%0" + dp + "d", v / (long) Math.pow(10, dp), v % (long) Math.pow(10, dp));
    }
}
