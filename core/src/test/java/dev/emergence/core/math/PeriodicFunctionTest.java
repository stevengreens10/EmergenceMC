package dev.emergence.core.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeriodicFunctionTest {
    static double f(double x) {
        return Math.exp(3 * Math.cos(x)) + 0.5 * Math.sin(2 * x);
    }

    static double df(double x) {
        return -3 * Math.sin(x) * Math.exp(3 * Math.cos(x)) + Math.cos(2 * x);
    }

    @Test
    void fourierAndSplineInterpolateSmoothPeriodicData() {
        int n = 128;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = f(2 * Math.PI * i / n);
        PeriodicFunction fo = new PeriodicFourier(y), sp = new PeriodicSpline(y);
        double[] o = new double[3];
        for (double x = -7; x < 7; x += 0.137) {
            fo.eval(x, o);
            assertEquals(f(x), o[0], 1e-5, "fourier value at " + x); // harmonics below 1e-7 of the largest are truncated
            assertEquals(df(x), o[1], 1e-4, "fourier derivative at " + x);
            sp.eval(x, o);
            assertEquals(f(x), o[0], 1e-3, "spline value at " + x);
        }
    }
}
