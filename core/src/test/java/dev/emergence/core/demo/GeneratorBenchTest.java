package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static dev.emergence.core.assembly.PartKind.*;

/**
 * A bench for designing the power station's generator: its rotor on a heavy flywheel at the station's speed (-Ddiag.gb.w,
 * rad/s). -Ddiag.genBench=open: each coil's open-circuit EMF over a turn (across 1 MΩ), its peak, its RMS and its sign
 * against the first's. -Ddiag.genBench=bank: the coils in series through a bridge into a lead–acid bank
 * (-Ddiag.gb.cells), the power it takes and the shaft's.
 */
class GeneratorBenchTest {
    static final int Y = 3;
    /** The four pole positions on the rotor (y, z), a quarter turn apart. */
    static final int[][] POLES = {{Y + 2, 5}, {Y, 7}, {Y - 2, 5}, {Y, 3}};

    /** The rotor and its eight coils; returns the coils' indices (east 0–3, west 4–7) and the shaft's last. */
    static int[] rotor(Exhibit e, int turns, double wire) {
        String st = "steel_1045", al = "aluminium_6061";
        int gs = e.fill(SHAFT, st, 9, Y, 5, 16, Y, 5, Dir.EAST, "diameter", 0.012);
        e.add(BEARING, st, 11, Y, 5, Dir.EAST);
        e.fill(BLOCK, st, 11, 0, 5, 11, Y - 1, 5, Dir.UP);
        e.add(BEARING, st, 16, Y, 5, Dir.EAST);
        e.fill(BLOCK, st, 16, 0, 5, 16, Y - 1, 5, Dir.UP);
        e.fill(BLOCK, st, 9, Y - 2, 3, 9, Y + 2, 7, Dir.EAST);           // the flywheel: some 0.8 kg·m²
        e.init(gs, new Exhibit.Initial(Double.NaN, Double.parseDouble(System.getProperty("diag.gb.w", "56")), 0, 0));
        // the rotor: an aluminium disc at x = 13 with the magnets set in it, alternately north and south east
        for (int yy = Y - 2; yy <= Y + 2; yy++)
            for (int z = 3; z <= 7; z++) {
                boolean pole = false;
                for (int[] p : POLES) pole |= p[0] == yy && p[1] == z;
                if (!pole) e.add(BLOCK, al, 13, yy, z, Dir.EAST, "fill", 0.3);
            }
        for (int k = 0; k < 4; k++) e.add(MAGNET, "ndfeb_n42", 13, POLES[k][0], POLES[k][1], k % 2 == 0 ? Dir.EAST : Dir.WEST);
        int[] coil = new int[9];
        for (int s = 0; s < 2; s++)
            for (int k = 0; k < 4; k++)
                coil[4 * s + k] = e.add(COIL, "copper", s == 0 ? 14 : 12, POLES[k][0], POLES[k][1], Dir.EAST, "turns", turns,
                        "wire_diameter", wire, "lead_axis", k % 2 == 0 ? 2 : 1);
        coil[8] = gs;
        return coil;
    }

    /** Coil i's two leads' cells: a (the lower) and b. */
    static int[][] leads(int i) {
        int x = i < 4 ? 14 : 12, yy = POLES[i % 4][0], z = POLES[i % 4][1];
        return i % 2 == 0 ? new int[][]{{x, yy, z - 1}, {x, yy, z + 1}} : new int[][]{{x, yy - 1, z}, {x, yy + 1, z}};
    }

    static Router router(Exhibit e) {
        Router r = new Router(e, 7, 0, 0, 30, Y + 5, 16);
        return r.keepClear(13, Y - 3, 2, 13, Y + 3, 8).keepClear(9, Y - 3, 2, 9, Y + 3, 8);
    }

    static int turns() {
        return Integer.getInteger("diag.gb.turns", 100);
    }

    static double wire() {
        return Double.parseDouble(System.getProperty("diag.gb.wire", "1e-3"));
    }

    @Test
    @EnabledIfSystemProperty(named = "diag.genBench", matches = "open")
    void openCircuit() {
        Exhibit e = new Exhibit("bench", "Generator bench", java.util.List.of("BENCH"), "");
        int[] coil = rotor(e, turns(), wire());
        // each across a 1 MΩ resistor (all but open: an unconnected coil is in no circuit)
        int[] load = new int[8];
        Router r = router(e);
        for (int i = 0; i < 8; i++) {
            load[i] = e.add(RESISTOR, "alumina", 22, 0, 2 * i, Dir.EAST, "resistance", 1e6);
            int[][] l = leads(i);
            r.pin("a" + i, l[0][0], l[0][1], l[0][2]).pin("b" + i, l[1][0], l[1][1], l[1][2]);
            r.pin("a" + i, 21, 0, 2 * i).pin("b" + i, 23, 0, 2 * i);
        }
        r.route();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        c.warnings().forEach(x -> System.out.println("warning: " + x));
        c.step(0.05);
        double[] peak = new double[8], sq = new double[8], dot = new double[8];
        int n = 0;
        double w = Math.abs(c.probe(b.ids().get(coil[8])).angularVelocity());
        double t0 = c.time(), turn = 2 * Math.PI / w;
        while (c.time() - t0 < turn) {
            c.step(2e-4);
            double v0 = c.probe(b.ids().get(load[0])).voltage();
            for (int k = 0; k < 8; k++) {
                double v = c.probe(b.ids().get(load[k])).voltage();
                peak[k] = Math.max(peak[k], Math.abs(v));
                sq[k] += v * v;
                dot[k] += v * v0;
            }
            n++;
        }
        System.out.printf("bench: %.1f rad/s, %d turns of %.2f mm (%.2f Ω a coil)%n", w, turns(), wire() * 1e3, coilResistance());
        for (int k = 0; k < 8; k++)
            System.out.printf("  coil %s %d: peak %.3f V, RMS %.3f V, against coil 0: %+.2f%n", k < 4 ? "east" : "west", k % 4, peak[k],
                    Math.sqrt(sq[k] / n), dot[k] / Math.sqrt(sq[0] * sq[k] + 1e-30));
    }

    static double coilResistance() {
        double d = wire();
        return 1.72e-8 * turns() * 2 * Math.PI * 0.5 * (0.45 + 0.45 * 0.35) * 0.0625 / (Math.PI * d * d / 4);
    }

    @Test
    @EnabledIfSystemProperty(named = "diag.genBench", matches = "bank")
    void intoABank() {
        Exhibit e = new Exhibit("bench", "Generator bench", java.util.List.of("BENCH"), "");
        int[] coil = rotor(e, turns(), wire());
        int cells = Integer.getInteger("diag.gb.cells", 24);
        Router r = new Router(e, 7, 0, 0, 30 + Integer.getInteger("diag.gb.cells", 24) + 2, Y + 5, 16)
                .keepClear(13, Y - 3, 2, 13, Y + 3, 8).keepClear(9, Y - 3, 2, 9, Y + 3, 8);
        // the coils in series, each the way round that adds its EMF (those at the odd positions, their leads along y,
        // read the other way: see openCircuit), from ac1 to ac2
        String prev = "ac1";
        for (int i = 0; i < 8; i++) {
            int[][] l = leads(i);
            int[] in = i % 2 == 0 ? l[0] : l[1], out = i % 2 == 0 ? l[1] : l[0];
            String next = i == 7 ? "ac2" : "s" + i;
            r.pin(prev, in[0], in[1], in[2]).pin(next, out[0], out[1], out[2]);
            prev = next;
        }
        // the bridge (current west → east through each diode) and the bank (+ south)
        int[] d = new int[4];
        for (int k = 0; k < 4; k++) d[k] = e.add(DIODE, "silicon", 22, 0, 2 * k, Dir.EAST);
        r.pin("ac1", 21, 0, 0).pin("ac1", 23, 0, 4);
        r.pin("ac2", 21, 0, 2).pin("ac2", 23, 0, 6);
        // -Ddiag.gb.strings=<n>: the bank built of blocks, n strings of `cells` two-volt cells of 8 A·h end to end along x
        // (x 30…), stacked up (a battery's leads are on its end faces and its north and south faces: stacked, they do not
        // touch), joined at each end by a copper bus; otherwise one battery of `cells` cells and 48 A·h
        int strings = Integer.getInteger("diag.gb.strings", 0);
        java.util.List<Integer> blocks = new java.util.ArrayList<>();
        if (strings > 0) {
            for (int s = 0; s < strings; s++)
                for (int k = 0; k < cells; k++) blocks.add(e.add(BATTERY, "pvc", 30 + k, s, 6, Dir.EAST, "cells", 1, "capacity", 8, "charge", 0.7));
            e.fill(WIRE, "copper", 29, 0, 6, 29, strings - 1, 6, Dir.UP, "diameter", 0.004);
            e.fill(WIRE, "copper", 30 + cells, 0, 6, 30 + cells, strings - 1, 6, Dir.UP, "diameter", 0.004);
            r.pin("pos", 23, 0, 0).pin("pos", 23, 0, 2).pin("pos", 30 + cells + 1, 0, 6);
            r.pin("neg", 21, 0, 4).pin("neg", 21, 0, 6).pin("neg", 28, 0, 6);
        } else {
            blocks.add(e.add(BATTERY, "pvc", 27, 0, 6, Dir.SOUTH, "cells", cells, "capacity", 48, "charge", 0.7));
            r.pin("pos", 23, 0, 0).pin("pos", 23, 0, 2).pin("pos", 27, 0, 7);
            r.pin("neg", 21, 0, 4).pin("neg", 21, 0, 6).pin("neg", 27, 0, 5);
        }
        r.route();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        c.warnings().forEach(x -> System.out.println("warning: " + x));
        long[] bats = blocks.stream().mapToLong(b.ids()::get).toArray();
        long bat = bats[0], shaft = b.ids().get(coil[8]);
        c.step(0.2);
        var body = c.bodyOf(shaft);
        double w0 = Math.abs(c.probe(shaft).angularVelocity()), t0 = c.time();
        double q0 = 0;
        for (long id : bats) q0 += c.batteryChargeDelivered(id);
        double e0 = 0.5 * body.inertia() * w0 * w0, pBat = 0, iPeak = 0, heat = 0;
        long wall = System.nanoTime(), stepWall = 0;
        while (c.time() - t0 < 1.0) {
            double t = c.time();
            long s0 = System.nanoTime();
            c.step(1e-3);
            stepWall += System.nanoTime() - s0;
            double dt = c.time() - t;
            double iNow = 0;
            for (long id : bats) {
                var p = c.probe(id);
                pBat += -p.current() * p.voltage() * dt;
                iNow += p.current();
            }
            iPeak = Math.max(iPeak, Math.abs(iNow) / Math.max(1, strings));
        }
        double w1 = Math.abs(c.probe(shaft).angularVelocity()), span = c.time() - t0;
        double pShaft = (e0 - 0.5 * body.inertia() * w1 * w1) / span;
        double q1 = 0, vBank = 0;
        for (long id : bats) q1 += c.batteryChargeDelivered(id);
        for (int k = 0; k < (strings > 0 ? cells : 1); k++) vBank += c.probe(bats[k]).voltage();
        double iMean = -(q1 - q0) / span / Math.max(1, cells);
        double rTotal = 8 * coilResistance();
        System.out.printf("bank: %d cells (%.2f V), %d turns of %.2f mm a coil, %.2f Ω in all; %.1f → %.1f rad/s%n", cells, vBank,
                turns(), wire() * 1e3, rTotal, w0, w1);
        System.out.printf("  into the bank %.2f W (%.3f A mean, %.2f A peak); from the shaft %.2f W (bearings' share included); %.1f s of wall time (%.2f s stepping)%n",
                pBat / span, iMean, iPeak, pShaft, (System.nanoTime() - wall) / 1e9, stepWall / 1e9);
        for (int k = 0; k < 4; k++) System.out.printf("  diode %d: %.1f K%n", k + 1, c.temperature(b.ids().get(d[k])));
        System.out.printf("  coil east 0: %.1f K%n", c.temperature(b.ids().get(coil[0])));
    }
}
