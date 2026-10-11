package dev.emergence.core.acoustics;

import dev.emergence.core.math.Vec3;

/**
 * A rigid body moving through air along a fixed direction ŝ — a plate on a slide, a piston rod, an armature — seen as a
 * compact acoustic dipole (its size small against the wavelength). Moving at velocity U it displaces air like a point
 * dipole of strength m(t) = M·U(t), M = V + m_a/ρ the body's volume plus its added mass per unit air density: for a
 * thin disc of radius a moving broadside m_a/ρ = (8/3)a³ (Lamb), for a sphere V + m_a/ρ = 2πa³ (the classic potential
 * flow round a moving sphere is reproduced exactly at short range).
 * <p>
 * The radiator keeps the history of its velocity (one sample per accepted solver step) so the field can be evaluated at
 * the retarded time t − r/c; derivatives up to the third come from a least-squares cubic through the samples nearest that
 * time.
 */
public final class DipoleRadiator {
    private final Vec3 centre, axis;
    private final double moment;
    private double[] ts = new double[256], us = new double[256];
    private int start, count;

    /**
     * @param centre where the dipole sits [m]
     * @param axis   direction of motion (unit vector); U is the velocity along it
     * @param moment M [m³]
     */
    public DipoleRadiator(Vec3 centre, Vec3 axis, double moment) {
        this.centre = centre;
        this.axis = axis.normalize();
        this.moment = moment;
    }

    /** Equivalent M of a flat plate of area S broadside to its motion plus a solid volume V: (8/3)(S/π)^{3/2} + V. */
    public static double plateMoment(double area, double volume) {
        double a = Math.sqrt(area / Math.PI);
        return 8.0 / 3.0 * a * a * a + volume;
    }

    public Vec3 centre() {
        return centre;
    }

    public Vec3 axis() {
        return axis;
    }

    public double moment() {
        return moment;
    }

    /** Records the velocity at time t (accepted steps, increasing t; a repeated time replaces the last sample). */
    public void record(double t, double u) {
        if (count > 0) {
            int last = (start + count - 1) % ts.length;
            if (t <= ts[last] + 1e-15) {
                if (Math.abs(t - ts[last]) < 1e-15) us[last] = u;
                return;
            }
        }
        if (count == ts.length) grow();
        int i = (start + count) % ts.length;
        ts[i] = t;
        us[i] = u;
        count++;
    }

    /** Drops samples older than {@code t}, keeping a few before it for the fits. */
    public void forgetBefore(double t) {
        while (count > 8 && ts[(start + 4) % ts.length] < t) {
            start = (start + 1) % ts.length;
            count--;
        }
    }

    private void grow() {
        double[] nt = new double[ts.length * 2], nu = new double[ts.length * 2];
        for (int k = 0; k < count; k++) {
            nt[k] = ts[(start + k) % ts.length];
            nu[k] = us[(start + k) % ts.length];
        }
        ts = nt;
        us = nu;
        start = 0;
    }

    public double latestTime() {
        return count == 0 ? Double.NEGATIVE_INFINITY : ts[(start + count - 1) % ts.length];
    }

    /**
     * {U, U̇, Ü, U⃛} at time τ: a least-squares cubic through the (up to) eight samples nearest τ. Before the first sample
     * the body is taken to have been at rest; past the last, the last values are held (a step longer than the delay).
     */
    public double[] derivatives(double tau) {
        double[] out = new double[4];
        if (count == 0) return out;
        double t0 = ts[start];
        if (tau < t0) return out;
        double tl = latestTime();
        if (tau > tl) tau = tl;
        // nearest sample by binary search
        int lo = 0, hi = count - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ts[(start + mid) % ts.length] <= tau) lo = mid;
            else hi = mid;
        }
        int n = Math.min(8, count);
        int first = Math.max(0, Math.min(count - n, lo - n / 2 + 1));
        if (n < 4) {
            out[0] = us[(start + lo) % ts.length];
            if (n >= 2) {
                int a = (start + count - 2) % ts.length, b = (start + count - 1) % ts.length;
                out[1] = (us[b] - us[a]) / (ts[b] - ts[a]);
            }
            return out;
        }
        // scale time by the span for conditioning; normal equations of a cubic in s = (t − τ)/span
        double span = Math.max(1e-12, ts[(start + first + n - 1) % ts.length] - ts[(start + first) % ts.length]);
        double[][] a = new double[4][5];
        for (int k = 0; k < n; k++) {
            int i = (start + first + k) % ts.length;
            double s = (ts[i] - tau) / span;
            double[] pw = {1, s, s * s, s * s * s};
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) a[r][c] += pw[r] * pw[c];
                a[r][4] += pw[r] * us[i];
            }
        }
        double[] coef = solve4(a);
        if (coef == null) {
            out[0] = us[(start + lo) % ts.length];
            return out;
        }
        out[0] = coef[0];
        out[1] = coef[1] / span;
        out[2] = 2 * coef[2] / (span * span);
        out[3] = 6 * coef[3] / (span * span * span);
        return out;
    }

    private static double[] solve4(double[][] a) {
        for (int c = 0; c < 4; c++) {
            int p = c;
            for (int r = c + 1; r < 4; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            if (Math.abs(a[p][c]) < 1e-300) return null;
            double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            for (int r = 0; r < 4; r++) {
                if (r == c) continue;
                double f = a[r][c] / a[c][c];
                for (int k = c; k < 5; k++) a[r][k] -= f * a[c][k];
            }
        }
        double[] x = new double[4];
        for (int r = 0; r < 4; r++) x[r] = a[r][4] / a[r][r];
        return x;
    }
}
