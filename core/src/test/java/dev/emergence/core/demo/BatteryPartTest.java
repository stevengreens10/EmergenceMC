package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import org.junit.jupiter.api.Test;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/** The battery as a part: wired to a resistor by its terminals, metered, and carrying its charge through a recompile. */
class BatteryPartTest {
    @Test
    void batteryDrivesAResistorAndKeepsItsCharge() {
        Exhibit e = new Exhibit("batterytest", "Battery test", java.util.List.of(), "");
        e.name("battery", e.add(BATTERY, "pvc", 2, 1, 2, Dir.EAST, "cells", 6, "capacity", 0.2));
        e.name("load", e.add(RESISTOR, "alumina", 2, 1, 6, Dir.EAST, "resistance", 10));
        Router r = new Router(e, 0, 0, 0, 6, 3, 8);
        r.pin("a", 3, 1, 2).pin("a", 3, 1, 6);
        r.pin("b", 1, 1, 2).pin("b", 1, 1, 6);
        r.route();
        Exhibits.Built b = Exhibits.build(e, 293.15);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        long bat = b.id(e, "battery"), load = b.id(e, "load");
        for (int k = 0; k < 20; k++) c.step(0.05);
        double amps = c.probe(bat).current(), volts = Math.abs(c.probe(bat).voltage()), rInt = 6 * 0.1 / 0.2;
        System.out.printf("battery part: %.3f A at %.2f V into 10 Ω (12.75 V / (10 + %.1f Ω internal) = %.3f A); load %.2f W%n",
                amps, volts, rInt, 12.75 / (10 + rInt), c.probe(load).power());
        assertEquals(12.75 / (10 + rInt), Math.abs(amps), 0.03 * 12.75 / (10 + rInt), "Ohm's law with its internal resistance");
        for (int k = 0; k < 200; k++) c.step(0.5);
        var state = c.exportState();
        double soc = state.capacitorVoltages().get(bat);
        assertTrue(soc < 0.9 && soc > 0.5, "a hundred seconds at an ampere uses some of its 720 C: " + soc);
        CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), state);
        again.step(0.05);
        assertEquals(soc, again.exportState().capacitorVoltages().get(bat), 1e-3, "its charge carries over a recompile");
        assertTrue(Math.abs(again.probe(bat).voltage()) < volts, "and its lower voltage with it");
    }
}
