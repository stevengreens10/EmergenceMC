package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.digital.DesignComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The computer thermostat: the EM-8, running its program from its ROM, holds the plate near its setpoint. */
class Em8ThermostatExhibitTest {
    @Test
    void theProgramHoldsThePlateNearItsSetpoint() {
        Exhibit e = Em8Exhibits.thermostat();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        var cpu = (DesignComponent) c.digitalChip(b.id(e, "cpu")).component();
        System.out.printf("em8: %d gates, fastest clock %.0f kHz%n", cpu.instance().design().estimate().gates(),
                cpu.instance().design().estimate().maxClock() / 1e3);
        boolean onSeen = false, offAfterOn = false;
        double tMin = 1e9, tMax = -1e9;
        int ledsMatch = 0, ledsChecked = 0, lastLeds = -1;
        for (int i = 1; i <= 90 * 20; i++) {
            c.step(0.05);
            double heater = Math.abs(c.probe(b.id(e, "heater")).current());
            if (heater > 0.2) onSeen = true;
            else if (onSeen && heater < 0.01) offAfterOn = true;
            double t = c.probe(b.id(e, "plate")).temperature();
            if (i > 60 * 20) {
                tMin = Math.min(tMin, t);
                tMax = Math.max(tMax, t);
            }
            if (i % 100 == 0) {
                // ram 0 is shifted in bit by bit; ram 3 holds the last whole reading's top four bits, as shown
                long reading = cpu.instance().memory("ram", 0), shown = cpu.instance().memory("ram", 3);
                int leds = 0;
                for (int k = 4; k <= 7; k++) if (Math.abs(c.probe(b.id(e, "led" + k)).current()) > 1e-3) leds |= 1 << (k - 4);
                ledsChecked++;
                if ((shown >> 4) == leds) ledsMatch++;
                lastLeds = leds;
                System.out.printf("t %4.1f s: plate %.1f K, heater %.2f A, reading so far %d, shown %d, LEDs %d, edges %d, violations %d%n", i * 0.05, t, heater,
                        reading, shown >> 4, leds, cpu.instance().edges(), cpu.instance().violations());
            }
        }
        assertEquals(0, cpu.instance().violations(), "the clock is within the processor's speed");
        assertTrue(onSeen, "the program turned the heater on (the plate started cold)");
        assertTrue(offAfterOn, "and off again at the setpoint");
        assertTrue(tMin > 312 && tMax < 324, "the plate held near 45 °C (318 K): " + tMin + " .. " + tMax);
        assertTrue(ledsMatch >= ledsChecked - 1, "the LEDs show the reading's top bits: " + ledsMatch + " of " + ledsChecked);
        assertEquals(138 >> 4, lastLeds, "held at the setpoint (reading 138), the LEDs show its top bits");
    }
}
