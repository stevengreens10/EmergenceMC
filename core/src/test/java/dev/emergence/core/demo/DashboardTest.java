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
                bat = b.id(e, "battery"), shunt = b.embeddedId(e, 4, "shunt");
        double t0 = c.time();
        long wall0 = System.nanoTime();
        double seconds = Double.parseDouble(System.getProperty("diag.dashRun", "30"));
        for (int i = 0; i < Math.round(seconds / 0.05); i++) {
            c.step(0.05);
        }
        // the generator's current pulses with each magnet's pass: its mean over the last second, sampled every millisecond
        // (what the gauge's 0.2 s filter averages; samples 50 ms apart alias)
        double q1 = 0;
        for (int i = 0; i < 1000; i++) {
            c.step(0.001);
            q1 += -c.probe(shunt).current() * 0.001;
        }
        double wall = (System.nanoTime() - wall0) / 1e9;
        double meanCurrent = q1 / 1.0;
        double[] truth = {
                (c.pressure(fuel) - pa) / 1e5 * 10,                                 // bar, 1 place
                (c.pressure(boiler) - pa) / 1e5 * 100,                              // bar, 2 places
                Math.abs(c.probe(shaft).angularVelocity()) * 60 / (2 * Math.PI),    // rpm
                c.pressure(cond) / 1e3 * 10,                                        // kPa absolute, 1 place
                meanCurrent * 1e3,                                                  // mA (its mean over the run)
                c.probe(bat).voltage() * 100};                                      // V, 2 places
        // what each may be off by: the ADC's step and noise, the gain's rounding, and: the condenser's offset for the
        // world's air pressure (2.8 kPa) against the test's; the generator's for its supply's 5.6 V; the engine's for
        // the speed's ripple within a turn; the battery's for its diodes' drop
        double[] tol = {15, 12, 6, 30, 0.15 * truth[4] + 2, 6};
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
        // the battery gauge's reference, for its constant
        var refAdc = (dev.emergence.core.digital.SpiAdc) c.digitalChip(b.embeddedId(e, 5, "adc")).component();
        System.out.printf("  battery reference: ADC %d at %.3f V: k = %.0f%n", refAdc.lastCode(), c.probe(bat).voltage(),
                refAdc.lastCode() * c.probe(bat).voltage() * 100);
        DisplayPanel screen = (DisplayPanel) c.digitalChip(b.embeddedId(e, 1, "controller")).component();
        System.out.println(GaugeTest.render(screen));
        for (int k = 0; k < n; k++) assertEquals(0, chip[k].instance().violations(), StationExhibit.GAUGES[k].label() + ": timing");
        assertTrue(ok, "every gauge shows its quantity within its accuracy");
    }

    static String shown(long v, int dp) {
        return dp == 0 ? Long.toString(v) : String.format("%d.%0" + dp + "d", v / (long) Math.pow(10, dp), v % (long) Math.pow(10, dp));
    }
}
