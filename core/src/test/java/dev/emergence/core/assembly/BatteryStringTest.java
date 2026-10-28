package dev.emergence.core.assembly;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.core.demo.Router;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Batteries end to end, alike, are solved as one element of all their cells (AssemblyCompiler's battery strings): the
 * same as one battery of as many cells, each block reading its share; a wire at a joint keeps the joint a node.
 */
class BatteryStringTest {
    /** Six one-cell lead–acid blocks end to end along x at x 0…5 (or one six-cell block at x 0), across 10 Ω. */
    static Object[] bank(boolean blocks, boolean tap) {
        Exhibit e = new Exhibit("bank", "Bank", List.of("BANK"), "");
        int first = -1;
        if (blocks) for (int k = 0; k < 6; k++) {
            int id = e.add(BATTERY, "pvc", k, 0, 0, Dir.EAST, "cells", 1, "capacity", 2, "charge", 0.8);
            if (first < 0) first = id;
        }
        else first = e.add(BATTERY, "pvc", 0, 0, 0, Dir.EAST, "cells", 6, "capacity", 2, "charge", 0.8);
        int load = e.add(RESISTOR, "alumina", 3, 0, 6, Dir.EAST, "resistance", 10);
        int end = blocks ? 6 : 1;
        Router r = new Router(e, -3, 0, -4, 10, 2, 9);
        r.pin("neg", -1, 0, 0).pin("neg", 2, 0, 6).pin("pos", end, 0, 0).pin("pos", 4, 0, 6);
        int tapLoad = -1;
        if (tap) {
            // a second load from the joint between the third and fourth blocks (on the third's + side face) to the − end
            tapLoad = e.add(RESISTOR, "alumina", 3, 0, -3, Dir.EAST, "resistance", 10);
            r.pin("tap", 2, 0, 1).pin("tap", 4, 0, -3).pin("neg", 2, 0, -3);
        }
        r.route();
        Exhibits.Built b = Exhibits.build(e, 293.15);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        return new Object[]{c, b.ids().get(first), b.ids().get(load), blocks ? b.ids().get(first + 5) : 0L, tapLoad < 0 ? 0L : b.ids().get(tapLoad)};
    }

    @Test
    void aStringOfBlocksIsTheBatteryOfAllTheirCells() {
        Object[] s = bank(true, false), one = bank(false, false);
        CompiledAssembly cs = (CompiledAssembly) s[0], c1 = (CompiledAssembly) one[0];
        for (int i = 0; i < 100; i++) {
            cs.step(0.1);
            c1.step(0.1);
        }
        double iS = cs.probe((long) s[2]).current(), i1 = c1.probe((long) one[2]).current();
        double vBlock = cs.probe((long) s[1]).voltage(), v1 = c1.probe((long) one[1]).voltage();
        System.out.printf("battery string: six blocks %.6f A, one six-cell battery %.6f A; a block reads %.4f V (a sixth of %.4f V); "
                + "charge %.4f against %.4f%n", iS, i1, vBlock, v1, cs.batteryStateOfCharge((long) s[1]), c1.batteryStateOfCharge((long) one[1]));
        assertEquals(i1, iS, 1e-4 * Math.abs(i1), "the same current");
        assertEquals(v1 / 6, vBlock, 1e-3, "each block reads its sixth of the voltage");
        assertEquals(c1.batteryStateOfCharge((long) one[1]), cs.batteryStateOfCharge((long) s[3]), 1e-6, "the last block's charge as the battery's");
        // the heat shared among the blocks: each as warm as the others
        assertEquals(cs.temperature((long) s[1]), cs.temperature((long) s[3]), 1e-6, "the first and last blocks alike warm");
    }

    @Test
    void aWireAtAJointKeepsItANode() {
        Object[] s = bank(true, true);
        CompiledAssembly c = (CompiledAssembly) s[0];
        for (int i = 0; i < 20; i++) c.step(0.1);
        // the lower three blocks carry both loads' current, the upper three only the main load's
        double lower = c.probe((long) s[1]).current(), upper = c.probe((long) s[3]).current(), tap = c.probe((long) s[4]).current();
        System.out.printf("battery string tapped: the first block %.4f A, the last %.4f A, the tap's load %.4f A%n", lower, upper, tap);
        assertEquals(upper + Math.abs(tap), lower, 1e-3, "the lower blocks carry the tap's current too");
        assertTrue(Math.abs(tap) > 0.1, "the tap draws");
    }
}
