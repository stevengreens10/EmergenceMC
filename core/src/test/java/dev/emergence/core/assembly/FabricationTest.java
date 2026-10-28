package dev.emergence.core.assembly;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.core.demo.FabExhibits;
import dev.emergence.core.digital.ChipProcess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Wafer processing (oxide growth, dopant diffusion) and the chips a process makes. */
class FabricationTest {
    @Test
    void erfcAndItsInverse() {
        assertEquals(0.1572992, WaferProcessing.erfc(1), 1e-6);
        assertEquals(0.004677735, WaferProcessing.erfc(2), 1e-8);
        assertEquals(2.209050e-5, WaferProcessing.erfc(3), 1e-10);
        assertEquals(3.0, WaferProcessing.inverseErfc(WaferProcessing.erfc(3)), 1e-6);
    }

    /**
     * Dry oxidation of (100) silicon in 1 atm of oxygen at 1100 °C for an hour: Deal–Grove alone gives 98 nm (B =
     * 0.0235 µm²/h, B/A = 0.167 µm/h), the textbook charts about 0.1 µm; with the thin-oxide term a little more. Deep
     * in the parabolic regime the rate is Deal–Grove's.
     */
    @Test
    void oxideGrowsAsDealAndGrove() {
        double t = 1373.15, x = WaferProcessing.grow(WaferProcessing.NATIVE_OXIDE, t, 1, 3600);
        double kt = 8.617333262e-5 * t, b = 772 * Math.exp(-1.23 / kt), ba = 3.71e6 * Math.exp(-2.0 / kt), a = b / ba;
        double dg = (-a + Math.sqrt(a * a + 4 * b)) / 2 * 1e-6;
        System.out.printf("oxide, 1 h at 1100 °C in O2: %.1f nm (Deal–Grove alone %.1f nm)%n", x * 1e9, dg * 1e9);
        assertEquals(98e-9, dg, 2e-9);
        assertTrue(x > dg && x < dg + 30e-9, "the thin-oxide term adds the first few tens of nanometres: " + x);
        double thick = 500e-9, r = WaferProcessing.oxideRate(thick, t, 1);
        assertEquals(b * 1e-12 / 3600 / (2 * thick + a * 1e-6), r, 1e-3 * r);
        // in air, a fifth as fast where the reaction limits; at room temperature, nothing
        assertTrue(WaferProcessing.grow(WaferProcessing.NATIVE_OXIDE, t, 0.21, 3600) < 0.5 * x);
        assertEquals(WaferProcessing.NATIVE_OXIDE, WaferProcessing.grow(WaferProcessing.NATIVE_OXIDE, 300, 0.21, 3.15e7), 1e-12);
    }

    /** Boron from a constant source at 1100 °C for an hour: D = 1.5·10⁻¹³ cm²/s, √(Dt) = 0.235 µm, x_j ≈ 1.36 µm. */
    @Test
    void boronDiffusesFromItsFilm() {
        double t = 1373.15, d = WaferProcessing.BORON.diffusivity(t);
        assertEquals(1.5e-17, d, 0.1e-17);
        double xj = WaferProcessing.BORON.junction(d * 3600);
        System.out.printf("boron, 1 h at 1100 °C: D = %.3g cm²/s, x_j = %.2f µm%n", d * 1e4, xj * 1e6);
        assertEquals(1.36e-6, xj, 0.05e-6);
        // phosphorus diffuses about as fast at these temperatures (a higher D₀, a higher activation energy)
        double dp = WaferProcessing.PHOSPHORUS.diffusivity(t);
        assertTrue(dp > 0.5 * d && dp < 2 * d, "phosphorus " + dp);
    }

    /** The process window: what oxide and junctions make chips that work, and how fast. */
    @Test
    void theProcessDecidesTheChips() {
        assertEquals(0.75, ChipProcess.REFERENCE.threshold(), 1e-9);
        assertEquals(1, ChipProcess.REFERENCE.delayScale(), 1e-12);
        assertEquals(1, ChipProcess.REFERENCE.leakageScale(), 1e-12);
        ChipProcess thick = new ChipProcess(150e-9, 0.5e-6, 3e-6, false);
        // 0.71 V for every 50 nm, less the 11 % of the depletion charge the reference's source and drain share
        assertEquals(0.75 + 2 * 0.71 * (1 - 0.111), thick.threshold(), 0.03);
        assertTrue(thick.works() && thick.delayScale() > 3, "a thick oxide makes slow chips: " + thick.delayScale());
        assertTrue(thick.leakageScale() < 1e-10, "that hardly leak");
        assertTrue(new ChipProcess(10e-9, 0.5e-6, 3e-6, false).fault().contains("turn off"));
        assertTrue(new ChipProcess(400e-9, 0.5e-6, 3e-6, false).fault().contains("barely"));
        assertTrue(new ChipProcess(50e-9, 0.05e-6, 3e-6, false).fault().contains("shallow"));
        assertTrue(new ChipProcess(50e-9, 1.5e-6, 3e-6, false).fault().contains("punch-through"));
        ChipProcess p = new ChipProcess(50e-9, 0.5e-6, 3e-6, true);
        assertTrue(p.delayScale() > 2 && p.delayScale() < 1350.0 / 480, "holes are slower (by less than their mobility, saturating later): " + p.delayScale());
        // a channel shortened towards punch-through: its threshold falls (charge sharing), it leaks, and it gains less
        // speed than the square law would give (velocity saturation)
        ChipProcess shortCh = new ChipProcess(50e-9, 1.2e-6, 3e-6, false), longCh = new ChipProcess(50e-9, 0.2e-6, 3e-6, false);
        assertTrue(shortCh.threshold() < longCh.threshold() - 0.1, "threshold roll-off: " + shortCh.threshold() + " against " + longCh.threshold());
        assertTrue(shortCh.leakageScale() > 10 * longCh.leakageScale(), "the short channel leaks more");
        double lr = shortCh.effectiveLength() / longCh.effectiveLength();
        assertTrue(shortCh.delayScale() / longCh.delayScale() > lr * lr, "less than the square law's gain");
        // a longer gate tolerates deeper junctions, at a price in speed
        ChipProcess longGate = new ChipProcess(50e-9, 1.5e-6, 10e-6, false);
        assertTrue(longGate.works() && longGate.delayScale() > 5, "10 µm gates: " + longGate.delayScale());
    }

    /**
     * The furnace exhibit: the battery heats the element, the element the wafer, to about 1200 °C; the oxide grows and
     * the phosphorus diffuses at the wafer's simulated temperature. Then processed long enough, the chips it makes work.
     */
    @Test
    void theFurnaceProcessesItsWafer() {
        Exhibit e = FabExhibits.waferFurnace();
        Exhibits.Built b = Exhibits.build(e, 293.15);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        long wafer = b.id(e, "wafer"), heater = b.id(e, "heater");
        double[] w0 = c.wafer(wafer);
        assertEquals(WaferProcessing.NATIVE_OXIDE, w0[0], 1e-12);
        assertEquals(0, w0[1]);
        assertNotNull(c.chipProcess(wafer).fault(), "an unprocessed wafer makes no working chips");
        double works = Double.NaN;
        java.util.List<Long> destroyed = new java.util.ArrayList<>();
        for (int i = 1; i <= 900; i++) {
            destroyed.addAll(c.step(1));
            if (i % 60 == 0 || i == 30) {
                double[] w = c.wafer(wafer);
                ChipProcess cp = c.chipProcess(wafer);
                System.out.printf("t=%3d s: heater %.0f K, wafer %.0f K, oxide %.1f nm, junction %.3f µm: %s%n", i, c.temperature(heater),
                        c.temperature(wafer), w[0] * 1e9, w[1] * 1e6, cp.works() ? String.format("chips %.2f× slower", cp.delayScale()) : cp.fault());
            }
            if (Double.isNaN(works) && c.chipProcess(wafer).works()) works = i;
        }
        assertTrue(destroyed.isEmpty(), "nothing burns out: " + destroyed);
        assertEquals(1462, c.temperature(wafer), 30, "the furnace holds the wafer near 1190 °C");
        assertTrue(works > 120 && works < 600, "the oxide is thick enough to make working chips after a few minutes: " + works);
        ChipProcess cp = c.chipProcess(wafer);
        assertTrue(cp.works(), cp.fault());
        assertFalse(cp.pChannel(), "phosphorus: n-channel");
        // the process is kept with the machine
        AssemblyState s = c.exportState();
        CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), s);
        assertArrayEquals(c.wafer(wafer), again.wafer(wafer), 1e-15);
        // a 3 µm mask on the same diffusion leaves no channel: the junctions (1.7 µm deep) spread 1.4 µm under each side
        assertTrue(new ChipProcess(cp.oxide(), cp.junction(), 3e-6, false).fault().contains("punch-through"));
    }

    /**
     * Two steps, as a player can run them: the wafer heated bare (no film) grows its gate oxide alone; the part edited
     * to carry a phosphorus film (the same part, its process kept with the machine) and heated again takes the dopant
     * in, its oxide growing on from where it was. With a 3 µm mask one step works only in the few minutes between its
     * oxide's becoming thick enough and its junctions' running under the gates; its fastest chips come at the end of
     * that window, their channels short and their thresholds low, and leak several times more than the two steps'
     * chips.
     */
    @Test
    void anOxideFirstThenTheDopant() {
        Exhibit e = FabExhibits.waferFurnace();
        Exhibits.Built b = Exhibits.build(e, 293.15);
        long wafer = b.id(e, "wafer");
        Assembly bare = withDopant(b.assembly(), wafer, 0, 3e-6);
        CompiledAssembly c = AssemblyCompiler.compile(bare, b.state());
        for (int i = 0; i < 480; i++) c.step(1);
        double[] w1 = c.wafer(wafer);
        System.out.printf("two steps: after 8 min bare, oxide %.1f nm, junction %.2f µm%n", w1[0] * 1e9, w1[1] * 1e6);
        assertTrue(w1[0] > 20e-9, "the oxide has grown: " + w1[0]);
        assertEquals(0, w1[1], "no film: no junction");
        assertNull(c.chipProcess(wafer), "no film: not a wafer for chips yet");
        // the film put on, the furnace kept hot: the same machine state, the part edited
        CompiledAssembly c2 = AssemblyCompiler.compile(withDopant(b.assembly(), wafer, 2, 3e-6), c.exportState());
        assertEquals(w1[0], c2.wafer(wafer)[0], 1e-15, "the oxide carried over the edit");
        for (int i = 0; i < 40; i++) c2.step(1);
        ChipProcess two = c2.chipProcess(wafer);
        System.out.printf("two steps: then 40 s with phosphorus, oxide %.1f nm, junction %.2f µm: %s%n", two.oxide() * 1e9, two.junction() * 1e6,
                two.works() ? String.format("3 µm chips %.2f× slower than ready-made", two.delayScale()) : two.fault());
        assertTrue(two.works(), two.fault());
        // in one step: a window between the oxide's being thick enough and the junctions' running under the gates
        CompiledAssembly one = AssemblyCompiler.compile(withDopant(b.assembly(), wafer, 2, 3e-6), b.state());
        double from = Double.NaN, to = Double.NaN;
        ChipProcess fastest = null;
        for (int i = 1; i <= 900; i++) {
            one.step(1);
            ChipProcess p = one.chipProcess(wafer);
            if (p.works() && (fastest == null || p.delayScale() < fastest.delayScale())) fastest = p;
            if (p.works() && Double.isNaN(from)) from = i;
            if (!p.works() && !Double.isNaN(from) && Double.isNaN(to)) to = i;
        }
        System.out.printf("one step, 3 µm mask: chips work from %.0f s to %.0f s; the fastest %.2f× the reference's delay, leaking %.2g×; "
                + "the two steps' %.2f×, leaking %.2g×%n", from, to, fastest.delayScale(), fastest.leakageScale(), two.delayScale(), two.leakageScale());
        assertTrue(to - from > 0 && to - from < 600, "one step's window: " + from + " .. " + to);
        assertTrue(two.leakageScale() < fastest.leakageScale() / 3, "the two steps' chips leak several times less than one step's fastest");
    }

    /** The assembly with part {@code id}'s dopant film and mask set (an edit in the part editor: the same part). */
    static Assembly withDopant(Assembly a, long id, int dopant, double gate) {
        Assembly out = new Assembly();
        for (Part p : a.parts()) {
            if (p.id() != id) {
                out.add(p);
                continue;
            }
            java.util.Map<String, Double> m = new java.util.HashMap<>(p.params());
            m.put("dopant", (double) dopant);
            m.put(ChipProcess.GATE, gate);
            out.add(new Part(p.id(), p.kind(), p.material(), p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(), p.axis(), m, p.text()));
        }
        out.ambientTemperature(a.ambientTemperature()).ambientPressure(a.ambientPressure());
        return out;
    }

    /**
     * Chips from a slower process miss their clock: a design clocked just inside its limit for the reference process
     * keeps up there and counts violations when made in a process three times slower.
     */
    @Test
    void aSlowProcessMissesTheClock() throws Exception {
        var d = dev.emergence.core.digital.ChipDesign.compile("""
                chip count
                in clk
                out [7:0] q
                reg [7:0] n = 0
                assign q = n
                on rise clk { n <= n + 1 }
                """);
        double period = 1.5 / d.estimate().maxClock();
        ChipProcess slow = new ChipProcess(130e-9, 0.5e-6, 3e-6, false);
        assertTrue(slow.delayScale() > 2, "slow: " + slow.delayScale());
        long[] v = new long[2];
        for (int k = 0; k < 2; k++) {
            var in = d.instantiate(java.util.Map.of());
            in.slowdown(k == 0 ? 1 : slow.delayScale());
            for (int i = 0; i < 20; i++) {
                in.setInput(0, true, i * period);
                in.setInput(0, false, (i + 0.5) * period);
            }
            v[k] = in.violations();
        }
        assertEquals(0, v[0], "the reference process keeps up");
        assertTrue(v[1] > 0, "the slow one does not");
    }
}
