package dev.emergence.core.assembly;

import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Archimedes: parts on vertical slides standing in still water. */
class BuoyancyTest {
    static final double RHO = 999.1, CELL = Part.CELL;
    long id = 1;
    final Assembly asm = new Assembly().anchoredWhen(p -> p.minY() == 0 && p.kind().structural && p.kind() != PartKind.SHAFT)
            // a pond two cells deep over the floor: its surface 8/9 up the second cell
            .waterWhen((x, y, z) -> y >= 1 && y <= 2 && x >= 0 && x <= 12 && z >= 0 && z <= 4);

    Part add(PartKind k, String m, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Object... kv) {
        Map<String, Double> pr = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) pr.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        Part p = new Part(id++, k, MaterialDatabase.standard().get(m), x0, y0, z0, x1, y1, z1, axis, pr);
        asm.add(p);
        return p;
    }

    /** A float at x on a light vertical rod in a linear bearing above the water, free to move 3 cm either way. */
    Part[] float_(int x, PartKind kind, String material, Object... kv) {
        Part rod = add(PartKind.SHAFT, "aluminium_6061", x, 1, 2, x, 5, 2, Dir.UP, "diameter", 0.005);
        add(PartKind.LINEAR_BEARING, "steel_1045", x, 4, 2, x, 4, 2, Dir.UP, "travel_fwd", 0.03, "travel_back", 0.03);
        // the bearing's post, a cell clear of the float (touching it, the float would be welded to the frame), and its arm
        add(PartKind.BLOCK, "steel_1045", x, 0, 4, x, 4, 4, Dir.UP);
        add(PartKind.BLOCK, "steel_1045", x, 4, 3, x, 4, 3, Dir.UP);
        if (kind == PartKind.BLOCK) return new Part[]{rod, add(kind, material, x, 2, 2, x, 2, 2, Dir.UP, kv)};
        // a vessel rides the rod on a small hub beside it (a tank in the rod's own cell is no hub)
        Part hub = add(PartKind.BLOCK, "aluminium_6061", x, 2, 2, x, 2, 2, Dir.UP, "fill", 0.1);
        return new Part[]{rod, add(kind, material, x + 1, 2, 2, x + 1, 2, 2, Dir.UP, kv), hub};
    }

    static void run(CompiledAssembly c, double seconds) {
        for (int i = 0; i < Math.round(seconds / 0.05); i++) c.step(0.05);
    }

    @Test
    void woodFloatsSteelSinksAndAHollowSteelVesselFloats() {
        Part[] oak = float_(1, PartKind.BLOCK, "oak");
        Part[] steel = float_(5, PartKind.BLOCK, "steel_1045");
        Part[] can = float_(9, PartKind.TANK, "steel_1045", "wall", 0.0005);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        run(c, 8);
        double xOak = c.probe(oak[0].id()).angle(), xSteel = c.probe(steel[0].id()).angle(), xCan = c.probe(can[0].id()).angle();
        System.out.printf("buoyancy: oak %.2f mm, steel %.2f mm, steel can %.2f mm (up from where they were built)%n",
                1e3 * xOak, 1e3 * xSteel, 1e3 * xCan);
        assertEquals(-0.03, xSteel, 1e-3, "the steel block sinks to the end of its rod's travel");
        // the oak floats where its weight and its rod's equal the water they displace: the float's draft d and the rod's
        // wetted length (from the floor cell's bottom to the surface) d + CELL
        for (Part[] f : new Part[][]{oak, can}) {
            double x = c.probe(f[0].id()).angle();
            double mass = PartGeometry.mass(f[0]) + PartGeometry.mass(f[1]) + (f.length > 2 ? PartGeometry.mass(f[2]) : 0);
            double aFloat = (f[1].kind() == PartKind.TANK ? CELL * CELL : PartGeometry.volume(f[1]) / CELL)
                    + (f.length > 2 ? PartGeometry.volume(f[2]) / CELL : 0);
            double aRod = Math.PI / 4 * 0.005 * 0.005;
            // ρ·(aFloat·d + aRod·(d + CELL)) = m, the rod's part inside the float counted once (the float's block
            // shares the rod's cell; the model counts both, as here)
            double d = (mass / RHO - aRod * CELL) / (aFloat + aRod);
            double surface = (2 + Assembly.WATER_TOP) * CELL, expected = surface - d - 2 * CELL;
            assertTrue(Math.abs(x) < 0.029, "afloat, off its stops: " + x);
            assertEquals(expected, x, 0.03 * d + 2e-4, f[1].material().name() + " floats at its draft " + 1e3 * d + " mm");
        }
        assertTrue(xCan > xOak, "the thin steel vessel rides higher than the oak");
    }

    /**
     * A float pushed under and let go bobs up and settles within a few bobs — the waves it makes carry its energy away
     * (drag alone, at millimetres a second, left it ringing for twenty seconds) — and the energy balances.
     */
    @Test
    void aFloatPushedUnderComesToRest() {
        Part[] oak = float_(1, PartKind.BLOCK, "oak");
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        run(c, 5);
        double rest = c.probe(oak[0].id()).angle();
        for (int i = 0; i < 10; i++) {
            c.setPushEffort(oak[0].id(), -1);
            c.step(0.05);
        }
        c.setPushEffort(oak[0].id(), 0);
        double pushed = c.probe(oak[0].id()).angle();
        assertTrue(pushed < rest - 5e-3, "pushed under: " + pushed + " from " + rest);
        run(c, 6);
        assertEquals(rest, c.probe(oak[0].id()).angle(), 5e-4, "back at rest");
        assertEquals(0, c.probe(oak[0].id()).angularVelocity(), 1e-3, "and still");
        // the hand's work went into the water's head and out through its drag and waves: the ledger balances
        System.out.printf("buoyancy energy: error %.3e J of %.3e J stored%n", c.energyError(), c.storedEnergy());
        // (the same rig without water ends 0.012 J out: the rounding of the parts' 1.8 MJ of heat; the pond adds none)
        assertEquals(0, c.energyError(), 0.03, "energy balances");
    }


    /**
     * An undershot paddle wheel over a stream: four plate paddles on arms two cells from a horizontal axle, the lowest
     * dipping into water two cells deep that runs at u along x. The stream drives the wheel its own way round, the
     * paddles at most as fast as the water (ω < u/r).
     */
    static double paddleWheel(double u) {
        BuoyancyTest t = new BuoyancyTest();
        t.asm.waterWhen((x, y, z) -> y >= 0 && y <= 1 && x >= 0 && x <= 10 && z >= 1 && z <= 3)
                .flowWhen((x, y, z) -> new dev.emergence.core.math.Vec3(u, 0, 0));
        Part shaft = t.add(PartKind.SHAFT, "steel_1045", 5, 3, 0, 5, 3, 4, Dir.SOUTH, "diameter", 0.01);
        t.add(PartKind.BEARING, "steel_1045", 5, 3, 0, 5, 3, 0, Dir.SOUTH);
        t.add(PartKind.BEARING, "steel_1045", 5, 3, 4, 5, 3, 4, Dir.SOUTH);
        t.add(PartKind.BLOCK, "steel_1045", 5, 0, 0, 5, 2, 0, Dir.UP);
        t.add(PartKind.BLOCK, "steel_1045", 5, 0, 4, 5, 2, 4, Dir.UP);
        // hub and arms of thin aluminium, the paddles at their ends tangent to the wheel
        t.add(PartKind.BLOCK, "aluminium_6061", 5, 3, 2, 5, 3, 2, Dir.SOUTH, "fill", 0.5);
        for (int[] arm : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
            t.add(PartKind.BLOCK, "aluminium_6061", 5 + arm[0], 3 + arm[1], 2, 5 + arm[0], 3 + arm[1], 2, Dir.SOUTH, "fill", 0.2);
            t.add(PartKind.BLOCK, "aluminium_6061", 5 + 2 * arm[0], 3 + 2 * arm[1], 2, 5 + 2 * arm[0], 3 + 2 * arm[1], 2,
                    arm[0] == 0 ? Dir.EAST : Dir.UP, "fill", 0.2);
        }
        CompiledAssembly c = AssemblyCompiler.compile(t.asm);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        run(c, 10);
        double w = c.probe(shaft.id()).angularVelocity();
        System.out.printf("paddle wheel in a %.1f m/s stream: %.2f rad/s (paddles at %.2f m/s), energy error %.2e J%n",
                u, w, w * 2 * CELL, c.energyError());
        assertEquals(0, c.energyError(), 0.05, "energy balances, the stream's work counted");
        return w;
    }

    @Test
    void aStreamTurnsAPaddleWheelItsOwnWayRound() {
        double fwd = paddleWheel(1.0), back = paddleWheel(-1.0);
        assertTrue(fwd > 2, "the stream turns the wheel: " + fwd);
        assertTrue(fwd * 2 * CELL < 1.0, "its paddles no faster than the water");
        assertEquals(-fwd, back, 0.05 * fwd, "the other way round in a stream the other way");
    }
}
