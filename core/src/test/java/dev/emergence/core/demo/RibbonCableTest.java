package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/** A ribbon cable's conductors: each joins the insulated wires of its colour, and the next ribbon block's. */
class RibbonCableTest {
    /**
     * A battery's + on a colour-1 wire and − on a colour-0 wire into a three-block ribbon (x 2..4, z 2). At the far end
     * a colour-1 tap feeds a 100 Ω resistor returning on colour 0; on the middle block a colour-2 tap feeds another
     * 100 Ω resistor, also returning on colour 0. Only the first carries current.
     */
    @Test
    void eachConductorCarriesItsOwnColour() {
        Exhibit e = new Exhibit("ribbon", "Ribbon", List.of(), "");
        String cu = "copper";
        e.add(BATTERY, "pvc", 1, 0, 0, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 1); // − at x 0, + at x 2
        e.add(INSULATED_WIRE, cu, 2, 0, 0, Dir.UP, "color", 1);
        e.add(INSULATED_WIRE, cu, 2, 0, 1, Dir.UP, "color", 1);                    // into the ribbon's first block
        for (int z = 0; z <= 2; z++) e.add(INSULATED_WIRE, cu, 0, 0, z, Dir.UP, "color", 0);
        e.add(INSULATED_WIRE, cu, 1, 0, 2, Dir.UP, "color", 0);                    // the ribbon's west end
        for (int x = 2; x <= 4; x++) e.add(RIBBON, cu, x, 0, 2, Dir.EAST);
        // the far end: colour 1 out of its east end, through r1, back onto the ribbon's top on colour 0
        e.add(INSULATED_WIRE, cu, 5, 0, 2, Dir.UP, "color", 1);
        e.name("r1", e.add(RESISTOR, "alumina", 5, 0, 3, Dir.SOUTH, "resistance", 100));
        int[][] back = {{5, 0, 4}, {5, 1, 4}, {5, 1, 3}, {5, 1, 2}, {4, 1, 2}};
        for (int[] c : back) e.add(INSULATED_WIRE, cu, c[0], c[1], c[2], Dir.UP, "color", 0);
        // the middle block: colour 2 off its top, through r2 standing up, back down onto colour 0
        e.add(INSULATED_WIRE, cu, 3, 1, 2, Dir.UP, "color", 2);
        e.name("r2", e.add(RESISTOR, "alumina", 3, 2, 2, Dir.UP, "resistance", 100));
        int[][] back2 = {{3, 3, 2}, {4, 3, 2}, {4, 2, 2}};
        for (int[] c : back2) e.add(INSULATED_WIRE, cu, c[0], c[1], c[2], Dir.UP, "color", 0);
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings " + c.warnings());
        for (int i = 0; i < 20; i++) c.step(0.05);
        double i1 = Math.abs(c.probe(b.id(e, "r1")).current()), i2 = Math.abs(c.probe(b.id(e, "r2")).current());
        // four NiMH cells (~5.3 V) across 100 Ω, through conductors 1 and 0
        assertTrue(i1 > 0.04 && i1 < 0.06, "conductor 1 carries the battery to r1: " + i1 + " A");
        assertTrue(i2 < 1e-6, "nothing drives conductor 2: " + i2 + " A");
    }
}
