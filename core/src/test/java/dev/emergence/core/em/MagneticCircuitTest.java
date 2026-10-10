package dev.emergence.core.em;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Validation of the reluctance lattice against magnetic-circuit hand calculations. */
class MagneticCircuitTest {
    static final double H = 1.0 / 16, MU0 = ReluctanceLattice.MU0;

    /** A square ring of iron cells, side n cells, in the x–z plane; the cell at (0, 0, n/2) is the coil's (μ_coil). */
    static Map<Long, Double> ring(int n, double muIron, double muCoil, int coilZ) {
        Map<Long, Double> m = ReluctanceLattice.cells();
        for (int i = 0; i < n; i++) {
            m.put(ReluctanceLattice.key(i, 0, 0), muIron);
            m.put(ReluctanceLattice.key(i, 0, n - 1), muIron);
            m.put(ReluctanceLattice.key(0, 0, i), muIron);
            m.put(ReluctanceLattice.key(n - 1, 0, i), muIron);
        }
        m.put(ReluctanceLattice.key(0, 0, coilZ), muCoil);
        return m;
    }

    /**
     * A coil on a closed iron ring: L = N²/ℛ with ℛ the cells' reluctances h/(μ0 μr h²) in series round the ring —
     * the iron carries the flux, the air around it almost none (μr 5000).
     */
    @Test
    void closedCoreInductanceIsTurnsSquaredOverReluctance() {
        int n = 7, z = 3;
        double muIron = 5000, muCoil = 5000, turns = 100;
        var cut = new ReluctanceLattice.Cut(0, 0, z, 2, 1, turns);
        var lat = new ReluctanceLattice(ring(n, muIron, muCoil, z), List.of(cut), H, 3);
        double l = lat.inductances(List.of(cut), false)[0][0];
        // mean path: 4(n − 1) cells of iron
        double rel = 4 * (n - 1) / (MU0 * muIron * H);
        double expected = turns * turns / rel;
        System.out.printf("closed core: lattice L = %.4g H, magnetic circuit N²/R = %.4g H%n", l, expected);
        assertEquals(expected, l, 0.15 * expected, "N²/ℛ round the ring");
        double air = lat.inductances(List.of(cut), true)[0][0];
        assertTrue(l > 100 * air, "the iron multiplies the inductance: " + l / air);
    }

    /** Two coils on one closed core: almost all the flux of one links the other, M ≈ √(L1 L2) (k → 1). */
    @Test
    void coilsOnOneClosedCoreAreTightlyCoupled() {
        int n = 7;
        Map<Long, Double> m = ring(n, 5000, 5000, 3);
        var a = new ReluctanceLattice.Cut(0, 0, 3, 2, 1, 50);
        var b = new ReluctanceLattice.Cut(n - 1, 0, 3, 2, -1, 200); // the far leg, flux going the other way round
        var lat = new ReluctanceLattice(m, List.of(a, b), H, 3);
        double[][] l = lat.inductances(List.of(a, b), false);
        double k = l[0][1] / Math.sqrt(l[0][0] * l[1][1]);
        System.out.printf("transformer core: L1 %.4g H, L2 %.4g H, M %.4g H, k = %.4f, L2/L1 = %.3f (N2²/N1² = 16)%n", l[0][0], l[1][1], l[0][1], k, l[1][1] / l[0][0]);
        assertTrue(k > 0.95, "tight coupling (the rest leaks across the ring's air window): " + k);
        assertEquals(16, l[1][1] / l[0][0], 0.5, "inductance ∝ N²");
        assertTrue(l[0][1] > 0, "flux linking both the same way round the ring");
    }

    /** An open (straight) iron core in a coil adds less than a closed one: the return path is through air. */
    @Test
    void openCoreAddsLessThanClosedCore() {
        Map<Long, Double> rod = ReluctanceLattice.cells();
        for (int z = 0; z < 7; z++) rod.put(ReluctanceLattice.key(0, 0, z), 5000.0);
        var cut = new ReluctanceLattice.Cut(0, 0, 3, 2, 1, 100);
        var open = new ReluctanceLattice(rod, List.of(cut), H, 3);
        double lOpen = open.inductances(List.of(cut), false)[0][0], lAir = open.inductances(List.of(cut), true)[0][0];
        var closed = new ReluctanceLattice(ring(7, 5000, 5000, 3), List.of(cut), H, 3);
        double lClosed = closed.inductances(List.of(cut), false)[0][0];
        System.out.printf("air %.4g H, open rod %.4g H, closed ring %.4g H%n", lAir, lOpen, lClosed);
        assertTrue(lOpen > 2 * lAir && lOpen < 0.2 * lClosed, "a rod helps, a closed core far more");
    }
}
