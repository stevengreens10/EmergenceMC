package dev.emergence.core.math;

/**
 * Trigonometric interpolant of uniformly sampled periodic data over [0, 2π), truncated to the harmonics
 * that carry signal. Unlike a cubic spline (whose third derivative jumps at every knot) every derivative
 * is smooth, which matters for implicit integrators whose error estimators look at third derivatives of
 * quantities driven through these tables (e.g. flux linkage vs rotor angle).
 * <p>
 * f(φ) = a0 + Σ_k [a_k cos kφ + b_k sin kφ], k = 1..K, coefficients from the discrete Fourier transform
 * of the samples; K is the highest harmonic with amplitude above 1e-7 of the largest.
 */
public final class PeriodicFourier implements PeriodicFunction {
    private final double a0;
    private final double[] a, b;
    private final int k;
    private final double range;

    public PeriodicFourier(double[] samples) {
        int n = samples.length;
        int kmax = (n - 1) / 2;
        double[] ca = new double[kmax + 1], cb = new double[kmax + 1];
        double mean = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : samples) {
            mean += v;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        this.a0 = mean / n;
        this.range = hi - lo;
        double maxAmp = 0;
        for (int h = 1; h <= kmax; h++) {
            double sc = 0, ss = 0;
            for (int j = 0; j < n; j++) {
                double ph = 2 * Math.PI * h * j / n;
                sc += samples[j] * Math.cos(ph);
                ss += samples[j] * Math.sin(ph);
            }
            ca[h] = 2 * sc / n;
            cb[h] = 2 * ss / n;
            maxAmp = Math.max(maxAmp, Math.hypot(ca[h], cb[h]));
        }
        int keep = 0;
        for (int h = 1; h <= kmax; h++) if (Math.hypot(ca[h], cb[h]) > 1e-7 * maxAmp) keep = h;
        this.k = keep;
        this.a = new double[keep + 1];
        this.b = new double[keep + 1];
        System.arraycopy(ca, 0, a, 0, keep + 1);
        System.arraycopy(cb, 0, b, 0, keep + 1);
    }

    /** Writes {f, f', f''} at φ. Uses the Chebyshev recurrence for cos/sin of multiples of φ. */
    @Override
    public void eval(double phi, double[] out) {
        double c1 = Math.cos(phi), s1 = Math.sin(phi);
        double ck = 1, sk = 0;
        double f = a0, d1 = 0, d2 = 0;
        for (int h = 1; h <= k; h++) {
            double cn = ck * c1 - sk * s1, sn = sk * c1 + ck * s1;
            ck = cn;
            sk = sn;
            double t = a[h] * ck + b[h] * sk;
            f += t;
            d1 += h * (b[h] * ck - a[h] * sk);
            d2 -= h * h * t;
        }
        out[0] = f;
        out[1] = d1;
        out[2] = d2;
    }

    @Override
    public double range() {
        return range;
    }

    public int harmonics() {
        return k;
    }
}
