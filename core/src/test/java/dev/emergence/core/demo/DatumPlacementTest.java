package dev.emergence.core.demo;

import dev.emergence.core.assembly.Assembly;
import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Part;
import dev.emergence.core.assembly.PartKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a floating circuit is grounded must not matter. Each circuit's datum is grounded through a shunt that every other
 * node's 10⁻¹² shunt returns through — microamps from a circuit's many low-resistance wire nodes. The datum was its first
 * node: in the world, where part ids are random, the periscope's photodiode came first, its 2.2 MΩ sense line became
 * the datum, and those return currents held it at 0.38 V against 3.3 µA of photocurrent — the receiver never lit.
 * The datum is now the supply's negative terminal. Here the periscope is compiled with its photodiode first, as the
 * world had it.
 */
class DatumPlacementTest {
    @Test
    void theReceiverLightsWithItsPhotodiodeFirst() {
        Exhibit e = OpticsExhibits.periscope();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        Assembly src = b.assembly();
        long pd = b.id(e, "pd");
        List<Part> order = new ArrayList<>();
        order.add(src.get(pd));
        for (Part p : src.parts()) if (p.id() != pd) order.add(p);
        Assembly a = new Assembly().ambientTemperature(src.ambientTemperature()).ambientPressure(src.ambientPressure());
        for (Part p : order) a.add(p);
        a.anchoredWhen(p -> p.minY() == 0 && p.kind().structural && p.kind() != PartKind.SHAFT);
        CompiledAssembly c = AssemblyCompiler.compile(a, b.state());
        for (int i = 0; i < 20; i++) c.step(0.05);
        double lit = c.probe(b.id(e, "lit")).current(), pdI = c.probe(pd).current();
        System.out.printf("periscope, photodiode first: photocurrent %.3g µA, green LED %.3g mA%n", -1e6 * pdI, 1e3 * lit);
        assertTrue(Math.abs(pdI) > 1e-6, "light reaches the photodiode");
        assertTrue(lit > 2e-3, "the green LED lights: " + lit + " A");
    }
}
