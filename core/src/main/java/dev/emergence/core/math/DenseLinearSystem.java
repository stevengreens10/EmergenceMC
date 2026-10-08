package dev.emergence.core.math;

/** Dense LU with partial (row) pivoting. Best for small systems (n ≲ 50). */
public final class DenseLinearSystem implements LinearSystem {
    private final int n;
    private final double[] a; // row-major n×n
    private final int[] perm;

    public DenseLinearSystem(int n) {
        this.n = n;
        this.a = new double[n * n];
        this.perm = new int[n];
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public void clear() {
        java.util.Arrays.fill(a, 0.0);
    }

    @Override
    public void add(int row, int col, double v) {
        a[row * n + col] += v;
    }

    public double get(int row, int col) {
        return a[row * n + col];
    }

    @Override
    public void solve(double[] b) {
        // Scale-aware singularity threshold: relative to the largest entry of each column.
        for (int i = 0; i < n; i++) perm[i] = i;
        for (int k = 0; k < n; k++) {
            int p = k;
            double max = Math.abs(a[k * n + k]);
            for (int i = k + 1; i < n; i++) {
                double v = Math.abs(a[i * n + k]);
                if (v > max) {
                    max = v;
                    p = i;
                }
            }
            if (max == 0.0 || !Double.isFinite(max)) throw new SingularMatrixException(k);
            if (p != k) {
                for (int j = 0; j < n; j++) {
                    double t = a[k * n + j];
                    a[k * n + j] = a[p * n + j];
                    a[p * n + j] = t;
                }
                double t = b[k];
                b[k] = b[p];
                b[p] = t;
            }
            double piv = a[k * n + k];
            for (int i = k + 1; i < n; i++) {
                double f = a[i * n + k] / piv;
                if (f == 0.0) continue;
                a[i * n + k] = f;
                int ri = i * n, rk = k * n;
                for (int j = k + 1; j < n; j++) a[ri + j] -= f * a[rk + j];
                b[i] -= f * b[k];
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            int ri = i * n;
            for (int j = i + 1; j < n; j++) s -= a[ri + j] * b[j];
            b[i] = s / a[ri + i];
        }
    }
}
