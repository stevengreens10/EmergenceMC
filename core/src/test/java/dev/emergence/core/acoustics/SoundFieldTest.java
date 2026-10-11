package dev.emergence.core.acoustics;

import dev.emergence.core.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The compact-dipole sound field against classical results. */
class SoundFieldTest {
    static final SoundField AIR = SoundField.air(293.15, 101_325);

    /** Fills a source's history with U(t) = U0 sin ωt, sampled every dt from t0 to t1. */
    static void sine(DipoleRadiator s, double u0, double w, double dt, double t0, double t1) {
        for (double t = t0; t <= t1 + 1e-12; t += dt) s.record(t, u0 * Math.sin(w * t));
    }

    @Test
    void airProperties() {
        assertEquals(1.204, AIR.density(), 0.002);
        assertEquals(343.2, AIR.soundSpeed(), 0.3);
    }

    /** Close to a steadily moving sphere the field is the potential flow round it: u_r = U a³cosθ/r³, u_θ = U a³sinθ/(2r³). */
    @Test
    void nearFieldIsPotentialFlowRoundASphere() {
        double a = 0.05, u = 0.3;
        var s = new DipoleRadiator(Vec3.ZERO, new Vec3(0, 0, 1), 2 * Math.PI * a * a * a);
        for (double t = 0; t <= 0.01; t += 1e-5) s.record(t, u);
        SoundField f = new SoundField(AIR.density(), AIR.soundSpeed());
        f.add(s);
        double r = 0.12;
        for (double th : new double[]{0, 0.4, 1.1, Math.PI / 2, 2.5}) {
            Vec3 rh = new Vec3(Math.sin(th), 0, Math.cos(th)), thh = new Vec3(Math.cos(th), 0, -Math.sin(th));
            Vec3 v = f.velocity(rh.mul(r), 0.01, null, 0);
            assertEquals(u * a * a * a * Math.cos(th) / (r * r * r), v.dot(rh), 1e-9, "u_r at θ=" + th);
            assertEquals(u * a * a * a * Math.sin(th) / (2 * r * r * r), v.dot(thh), 1e-9, "u_θ at θ=" + th);
        }
    }

    /**
     * Far away the intensity p·u_r carried through a sphere is the power a translating sphere radiates at low ka,
     * (π/6)·ρc·k⁴a⁶·U0² (Kinsler et al.) — p and u computed independently from the same retarded source.
     */
    @Test
    void radiatedPowerOfAnOscillatingSphere() {
        double a = 0.03, u0 = 0.01, f = 500, w = 2 * Math.PI * f, c = AIR.soundSpeed(), k = w / c;
        var s = new DipoleRadiator(Vec3.ZERO, new Vec3(0, 0, 1), 2 * Math.PI * a * a * a);
        double r = 30 * c / f; // 30 wavelengths
        double tEnd = r / c + 0.02;
        sine(s, u0, w, 1 / (f * 64), 0, tEnd);
        SoundField fld = new SoundField(AIR.density(), c);
        fld.add(s);
        // ∮⟨p u·r̂⟩ dA over one period, by θ (the field is axisymmetric)
        double power = 0;
        int nth = 90, nt = 64;
        for (int i = 0; i < nth; i++) {
            double th = Math.PI * (i + 0.5) / nth;
            Vec3 x = new Vec3(Math.sin(th), 0, Math.cos(th)).mul(r);
            double flux = 0;
            for (int j = 0; j < nt; j++) {
                double t = tEnd - j / (f * nt);
                flux += fld.pressure(x, t, null) * fld.velocity(x, t, null, 0).dot(x.mul(1 / r)) / nt;
            }
            power += flux * 2 * Math.PI * r * r * Math.sin(th) * Math.PI / nth;
        }
        double expect = Math.PI / 6 * AIR.density() * c * Math.pow(k, 4) * Math.pow(a, 6) * u0 * u0;
        System.out.printf("oscillating sphere: radiated %.4g W, (π/6)ρck⁴a⁶U² = %.4g W%n", power, expect);
        assertEquals(expect, power, 0.02 * expect);
    }

    /** A pulse arrives r/c later, and its far-field pressure falls as 1/r. */
    @Test
    void soundTravelsAtTheSpeedOfSound() {
        var s = new DipoleRadiator(Vec3.ZERO, new Vec3(1, 0, 0), 1e-3);
        double sig = 2e-4;
        for (double t = 0; t <= 0.02; t += 1e-5) s.record(t, Math.exp(-Math.pow((t - 0.002) / sig, 2)));
        SoundField f = new SoundField(AIR.density(), AIR.soundSpeed());
        f.add(s);
        double[] peak = new double[2], at = new double[2];
        double[] dist = {2, 4};
        for (int k = 0; k < 2; k++) {
            Vec3 x = new Vec3(dist[k], 0, 0);
            for (double t = 0; t <= 0.02; t += 1e-6) {
                double p = Math.abs(f.pressure(x, t, null));
                if (p > peak[k]) {
                    peak[k] = p;
                    at[k] = t;
                }
            }
        }
        assertEquals(2 / AIR.soundSpeed(), at[1] - at[0], 2e-5, "the pulse takes Δr/c");
        assertEquals(2.0, peak[0] / peak[1], 0.1, "p ∝ 1/r far away");
    }

    /** The force A's motion exerts on B equals the force the same motion of B exerts on A. */
    @Test
    void reciprocity() {
        Vec3 pa = new Vec3(0, 0, 0), pb = new Vec3(0.4, 0.2, -0.3);
        Vec3 sa = new Vec3(1, 0, 0), sb = new Vec3(0, 0.6, 0.8);
        double ma = 2e-4, mb = 5e-4, w = 2 * Math.PI * 700;
        double[] forces = new double[2];
        for (int k = 0; k < 2; k++) {
            var a = new DipoleRadiator(pa, sa, ma);
            var b = new DipoleRadiator(pb, sb, mb);
            var mover = k == 0 ? a : b;
            var still = k == 0 ? b : a;
            sine(mover, 0.02, w, 1e-5, 0, 0.02);
            for (double t = 0; t <= 0.02; t += 1e-5) still.record(t, 0);
            SoundField f = new SoundField(AIR.density(), AIR.soundSpeed());
            f.add(a);
            f.add(b);
            forces[k] = f.force(still, 0.0173);
        }
        System.out.printf("reciprocity: F(A→B) %.5g N, F(B→A) %.5g N%n", forces[0], forces[1]);
        assertEquals(forces[0], forces[1], 1e-6 * Math.abs(forces[0]) + 1e-15);
    }
}
