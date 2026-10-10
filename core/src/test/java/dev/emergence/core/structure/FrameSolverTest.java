package dev.emergence.core.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The frame solver against classical beam theory. */
class FrameSolverTest {
    static final double E = 200e9, G = 77e9;
    static final double B = 0.0625, H = 0.01; // a 62.5 mm × 10 mm steel bar
    static final double A = B * H, I_WEAK = B * H * H * H / 12, I_STRONG = H * B * B * B / 12, J = B * H * H * H / 3 * (1 - 0.63 * H / B);

    /** A straight bar of n members along x, each of length l; bending stiffness i2 (about z) for loads along y. */
    static FrameSolver bar(int n, double l) {
        FrameSolver f = new FrameSolver(n + 1);
        for (int i = 0; i < n; i++) f.addMember(new FrameSolver.Member(i, i + 1, 0, l, E, G, A, I_STRONG, I_WEAK, J));
        return f;
    }

    @Test
    void cantileverTipDeflectionAndRootMomentMatchBeamTheory() {
        int n = 10;
        double l = 0.0625, len = n * l, p = 20;
        FrameSolver f = bar(n, l);
        f.fix(0);
        f.force(n, 1, -p);
        double[] u = f.solve();
        double d = p * len * len * len / (3 * E * I_WEAK);
        assertEquals(-d, u[6 * n + 1], 1e-5 * d, "δ = PL³/3EI");
        assertEquals(-p * len * len / (2 * E * I_WEAK), u[6 * n + 5], 1e-6 * p * len * len / (2 * E * I_WEAK), "θ = PL²/2EI");
        var root = FrameSolver.forces(f.members().get(0), u);
        assertEquals(p * len, Math.abs(root.atA()[5]), 1e-6 * p * len, "root moment = P·L");
        assertEquals(p, Math.abs(root.atA()[1]), 1e-6 * p, "shear = P");
    }

    @Test
    void simplySupportedMidspanDeflection() {
        int n = 8;
        double l = 0.0625, len = n * l, p = 50;
        FrameSolver g = bar(n, l);
        g.pin(0);
        g.pin(n);
        g.force(n / 2, 1, -p);
        double[] u = g.solve();
        double d = p * len * len * len / (48 * E * I_WEAK);
        assertEquals(-d, u[6 * (n / 2) + 1], 1e-6 * d, "δ = PL³/48EI");
    }

    @Test
    void axialColumnShortensByPLOverEA() {
        int n = 5;
        double l = 0.0625, p = 1e4;
        FrameSolver f = new FrameSolver(n + 1);
        for (int i = 0; i < n; i++) f.addMember(new FrameSolver.Member(i, i + 1, 1, l, E, G, A, I_STRONG, I_WEAK, J));
        f.fix(0);
        f.force(n, 1, -p);
        double[] u = f.solve();
        assertEquals(-p * n * l / (E * A), u[6 * n + 1], 1e-6 * p * n * l / (E * A));
        assertEquals(-p, FrameSolver.forces(f.members().get(0), u).atA()[0], 1e-6 * p, "compression N = −P");
    }

    @Test
    void bendingAboutEitherAxisUsesTheRightSecondMoment() {
        // the same flat bar loaded along z bends about y: stiffer by (B/H)²
        int n = 6;
        double l = 0.0625, len = n * l, p = 20;
        FrameSolver f = bar(n, l);
        f.fix(0);
        f.force(n, 2, -p);
        double[] u = f.solve();
        double d = p * len * len * len / (3 * E * I_STRONG);
        assertEquals(-d, u[6 * n + 2], 1e-6 * d);
    }

    @Test
    void portalFrameSwayMatchesStiffnessMethodClosedForm() {
        // two clamped columns of height h joined by a rigid-ish beam: lateral load H sways the top by
        // Δ = H h³ / (24 E I) when the beam is infinitely stiff (each column fixed-fixed: 12EI/h³)
        double h = 0.25, l = 0.0625, hl = 1000;
        int nc = 4;
        FrameSolver f = new FrameSolver(2 * (nc + 1));
        for (int i = 0; i < nc; i++) {
            // columns axially rigid (A × 10⁴), so overturning does not tilt the beam: pure bending sway
            f.addMember(new FrameSolver.Member(i, i + 1, 1, l, E, G, A * 1e4, I_STRONG, I_WEAK, J));
            f.addMember(new FrameSolver.Member(nc + 1 + i, nc + 2 + i, 1, l, E, G, A * 1e4, I_STRONG, I_WEAK, J));
        }
        // a very stiff beam between the column tops
        f.addMember(new FrameSolver.Member(nc, 2 * nc + 1, 0, 0.25, E * 1e4, G * 1e4, A, I_STRONG, I_WEAK, J));
        f.fix(0);
        f.fix(nc + 1);
        f.force(nc, 0, hl);
        double[] u = f.solve();
        // columns bend about z (in the x–y plane): for a member along y, local t1 = z, t2 = x; bending with
        // displacement along x (t2) uses the second moment about t1 = z, which is i1 = I_STRONG
        double d = hl * h * h * h / (24 * E * I_STRONG);
        assertEquals(d, u[6 * nc], 0.01 * d, "portal sway");
    }

    /**
     * Euler buckling of a cantilever column (clamped foot, free top) under an end load: P_cr = π²EI/(4L²). Twelve
     * members of a slender steel strip; the computed factor μ = P/P_cr for loads below and above it.
     */
    @Test
    void cantileverColumnBucklesAtEulerLoad() {
        int n = 12;
        double h = 1.0 / 16, b = 0.0625, t = 0.00125, e = 200e9;
        double i1 = b * t * t * t / 12, i2 = t * b * b * b / 12;
        double pcr = Math.PI * Math.PI * e * Math.min(i1, i2) / (4 * Math.pow(n * h, 2));
        for (double p : new double[]{0.5 * pcr, 2 * pcr}) {
            FrameSolver f = new FrameSolver(n + 1);
            f.fix(0);
            for (int k = 0; k < n; k++) f.addMember(new FrameSolver.Member(k, k + 1, 1, h, e, e / 2.6, b * t, i1, i2, b * t * t * t / 3));
            f.force(n, 1, -p);
            double[] u = f.solve();
            double[] mode = new double[6 * (n + 1)];
            double mu = f.bucklingFactor(u, mode);
            System.out.printf("cantilever column: P = %.2f N, P_cr(Euler) = %.2f N, computed P/P_cr = %.4f (expected %.4f)%n", p, pcr, mu, p / pcr);
            assertEquals(p / pcr, mu, 0.01 * p / pcr, "the Euler critical load");
            // the top sways most: the first mode of a cantilever
            double top = Math.hypot(mode[6 * n], mode[6 * n + 2]), mid = Math.hypot(mode[6 * (n / 2)], mode[6 * (n / 2) + 2]);
            assertTrue(top > mid, "the free top deflects most");
        }
    }

    /** A column in tension, or stocky and lightly loaded, has nothing to buckle. */
    @Test
    void tensionDoesNotBuckle() {
        int n = 6;
        double h = 1.0 / 16, e = 200e9, a = h * h, ii = h * h * h * h / 12;
        FrameSolver f = new FrameSolver(n + 1);
        f.fix(n);
        for (int k = 0; k < n; k++) f.addMember(new FrameSolver.Member(k, k + 1, 1, h, e, e / 2.6, a, ii, ii, 0.14 * h * h * h * h));
        f.force(0, 1, -100); // hanging from the top
        assertEquals(0, f.bucklingFactor(f.solve(), null), 1e-9);
    }
}
