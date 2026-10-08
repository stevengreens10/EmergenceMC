package dev.emergence.core.em;

import dev.emergence.core.math.Elliptic;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Analytical validation of the magnetostatic field kernels (VALIDATION.md §Electromagnetism). */
class FieldValidationTest {
    static final double MU0 = PhysicalConstants.MU0;

    @Test
    void ellipticIntegralsMatchReferenceValues() {
        // K(0)=E(0)=π/2; K(0.5)=1.854074677301372, E(0.5)=1.350643881047675 (Abramowitz & Stegun 17.3)
        double[] k0 = Elliptic.ke(0);
        assertEquals(Math.PI / 2, k0[0], 1e-14);
        assertEquals(Math.PI / 2, k0[1], 1e-14);
        double[] k5 = Elliptic.ke(0.5);
        assertEquals(1.854074677301372, k5[0], 1e-13);
        assertEquals(1.350643881047675, k5[1], 1e-13);
    }

    @Test
    void blockMagnetOnAxisFieldMatchesClosedForm() {
        double L = 0.02, W = 0.01, T = 0.005, br = 1.3;
        CuboidMagnet m = CuboidMagnet.axisAligned(Vec3.ZERO, L, W, T, Vec3.Z, br);
        for (double z : new double[]{0.001, 0.005, 0.02}) {
            double[] b = new double[3];
            m.addB(0, 0, T / 2 + z, b);
            double zt = z + T;
            double exact = br / Math.PI * (Math.atan(L * W / (2 * z * Math.sqrt(4 * z * z + L * L + W * W)))
                    - Math.atan(L * W / (2 * zt * Math.sqrt(4 * zt * zt + L * L + W * W))));
            assertEquals(exact, b[2], 1e-9 * br, "z=" + z);
            assertEquals(0, b[0], 1e-9);
            assertEquals(0, b[1], 1e-9);
        }
    }

    @Test
    void blockMagnetFarFieldIsDipole() {
        CuboidMagnet m = CuboidMagnet.axisAligned(Vec3.ZERO, 0.01, 0.01, 0.01, new Vec3(1, 2, 0.5), 1.2);
        Vec3 mom = m.moment();
        Vec3 r = new Vec3(0.13, -0.21, 0.17);
        double rl = r.length();
        Vec3 rh = r.mul(1 / rl);
        Vec3 exact = rh.mul(3 * mom.dot(rh)).sub(mom).mul(MU0 / (4 * Math.PI * rl * rl * rl));
        double[] b = new double[3];
        m.addB(r.x(), r.y(), r.z(), b);
        assertEquals(exact.x(), b[0], 2e-3 * exact.length());
        assertEquals(exact.y(), b[1], 2e-3 * exact.length());
        assertEquals(exact.z(), b[2], 2e-3 * exact.length());
    }

    @Test
    void fieldInsideMagnetIsBrMinusDemagnetisation() {
        // Inside a long rod magnetised along its axis the internal B approaches B_r (demag factor → 0)
        CuboidMagnet m = CuboidMagnet.axisAligned(Vec3.ZERO, 0.002, 0.002, 0.2, Vec3.Z, 1.0);
        double[] b = new double[3];
        m.addB(0, 0, 0, b);
        assertEquals(1.0, b[2], 0.005);
        // ... and inside a thin plate magnetised through its thickness B → 0 (H = −M)
        CuboidMagnet p = CuboidMagnet.axisAligned(Vec3.ZERO, 0.2, 0.2, 0.001, Vec3.Z, 1.0);
        double[] bp = new double[3];
        p.addB(0, 0, 0, bp);
        assertEquals(0.0, bp[2], 0.01);
    }

    @Test
    void loopOnAxisField() {
        double a = 0.05, i = 3.0;
        double[] out = new double[2];
        for (double z : new double[]{0, 0.02, 0.1}) {
            FieldMath.loopB(a, i, 0, z, out);
            double exact = MU0 * i * a * a / (2 * Math.pow(a * a + z * z, 1.5));
            assertEquals(exact, out[1], 1e-12 * Math.max(1, exact) + 1e-15);
        }
    }

    @Test
    void loopFieldSatisfiesFluxConservation() {
        // Flux of a loop's field through a large concentric disk in its own plane (beyond the wire) is
        // negative of the flux through the inner disk; check ∇·B = 0 numerically via a small pillbox.
        double a = 0.05;
        double[] p = new double[2], m = new double[2];
        double rho = 0.03, z = 0.01, h = 1e-6;
        FieldMath.loopB(a, 1, rho + h, z, p);
        FieldMath.loopB(a, 1, rho - h, z, m);
        double dBrDr = (p[0] - m[0]) / (2 * h);
        double[] c = new double[2];
        FieldMath.loopB(a, 1, rho, z, c);
        FieldMath.loopB(a, 1, rho, z + h, p);
        FieldMath.loopB(a, 1, rho, z - h, m);
        double dBzDz = (p[1] - m[1]) / (2 * h);
        double div = dBrDr + c[0] / rho + dBzDz;
        assertEquals(0, div, 1e-6 * Math.abs(dBzDz) + 1e-12);
    }

    @Test
    void coaxialMutualMatchesFarFieldDipoleLimit() {
        double a = 0.01, b = 0.02, d = 1.0;
        double approx = MU0 * Math.PI * a * a * b * b / (2 * d * d * d);
        assertEquals(approx, FieldMath.coaxialLoopMutual(a, b, d), 1e-3 * approx);
    }

    @Test
    void mutualByFluxIntegrationAgreesWithMaxwellFormula() {
        CoilGeometry c1 = new CoilGeometry(Vec3.ZERO, Vec3.Z, 0.02, 0.0, 0.0, 1);
        CoilGeometry c2 = new CoilGeometry(new Vec3(0, 0, 0.015), Vec3.Z, 0.03, 0.0, 0.0, 1);
        double maxwell = FieldMath.coaxialLoopMutual(0.02, 0.03, 0.015);
        double integrated = c1.fluxLinkage(c2.withCurrent(1.0));
        assertEquals(maxwell, integrated, 1e-4 * maxwell);
        assertEquals(maxwell, c1.mutualInductance(c2), 1e-12);
    }

    @Test
    void solenoidInductanceMatchesNagaoka() {
        // N=100, a=1 cm, ℓ=10 cm. Nagaoka coefficient for 2a/ℓ = 0.2 is K = 0.9201 (Grover table).
        int n = 100;
        double a = 0.01, l = 0.1;
        CoilGeometry c = new CoilGeometry(Vec3.ZERO, Vec3.Z, a, l, 0.0, n);
        double ideal = MU0 * n * n * Math.PI * a * a / l;
        double expected = 0.9201 * ideal;
        // Nagaoka's coefficient describes a zero-thickness current sheet, so use a vanishing wire size
        assertEquals(expected, c.selfInductance(1e-6), 0.01 * expected);
    }
}
