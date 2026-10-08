package dev.emergence.core.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sparse LU solver designed for repeated Newton iterations on a fixed network topology.
 * <p>
 * The first factorisation (and any factorisation after the sparsity pattern changes or a pivot
 * becomes unstable) performs right-looking elimination with Markowitz pivot selection and threshold
 * partial pivoting (u = 0.1), which keeps fill-in low for the near-planar graphs produced by circuits
 * and mechanical networks. It records the pivot sequence and the L/U fill pattern. Subsequent
 * factorisations reuse that pattern with a cheap row-oriented numeric refactorisation
 * (the "KLU-style" strategy used by circuit simulators).
 */
public final class SparseLinearSystem implements LinearSystem {
    private static final double PIVOT_THRESHOLD = 0.1;
    private static final double REFACTOR_PIVOT_TOL = 1e-10;

    private final int n;
    // Assembled matrix entries
    private final Map<Long, Integer> pos = new HashMap<>();
    private int nnz;
    private int[] rowOf = new int[64], colOf = new int[64];
    private double[] val = new double[64];
    private boolean patternChanged = true;

    // Symbolic factorisation (permuted indices)
    private int[] rowPerm, colPerm, colStep;
    private int[] aRowStart, aRowIdx; // CSR over entry indices, by original row
    private int[][] lPat, uPat;      // per permuted row: L columns (<s) ascending, U columns (>s)
    private double[][] lVal, uVal;
    private double[] uDiag;
    private final double[] work;
    private boolean factored;

    public SparseLinearSystem(int n) {
        this.n = n;
        this.work = new double[n];
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public void clear() {
        Arrays.fill(val, 0, nnz, 0.0);
    }

    @Override
    public void add(int row, int col, double v) {
        long key = ((long) row << 32) | (col & 0xffffffffL);
        Integer idx = pos.get(key);
        if (idx == null) {
            idx = nnz++;
            if (nnz > val.length) {
                int cap = val.length * 2;
                rowOf = Arrays.copyOf(rowOf, cap);
                colOf = Arrays.copyOf(colOf, cap);
                val = Arrays.copyOf(val, cap);
            }
            rowOf[idx] = row;
            colOf[idx] = col;
            val[idx] = 0.0;
            pos.put(key, idx);
            patternChanged = true;
        }
        val[idx] += v;
    }

    @Override
    public double diagonal(int i) {
        Integer e = pos.get(((long) i << 32) | (i & 0xffffffffL));
        return e == null ? 0.0 : val[e];
    }

    public int nonZeros() {
        return nnz;
    }

    /** Number of stored L+U off-diagonal entries (fill diagnostics). */
    public int factorNonZeros() {
        if (lPat == null) return 0;
        int c = 0;
        for (int s = 0; s < n; s++) c += lPat[s].length + uPat[s].length;
        return c;
    }

    @Override
    public void solve(double[] b) {
        if (patternChanged || !factored) {
            buildRowIndex();
            fullFactor();
            patternChanged = false;
        } else if (!refactor()) {
            fullFactor();
        }
        // Forward substitution (L has unit diagonal)
        double[] y = work;
        for (int s = 0; s < n; s++) {
            double v = b[rowPerm[s]];
            int[] lp = lPat[s];
            double[] lv = lVal[s];
            for (int k = 0; k < lp.length; k++) v -= lv[k] * y[lp[k]];
            y[s] = v;
        }
        // Back substitution
        for (int s = n - 1; s >= 0; s--) {
            double v = y[s];
            int[] up = uPat[s];
            double[] uv = uVal[s];
            for (int k = 0; k < up.length; k++) v -= uv[k] * y[up[k]];
            y[s] = v / uDiag[s];
        }
        for (int s = 0; s < n; s++) b[colPerm[s]] = y[s];
        Arrays.fill(work, 0.0);
    }

    private void buildRowIndex() {
        aRowStart = new int[n + 1];
        for (int e = 0; e < nnz; e++) aRowStart[rowOf[e] + 1]++;
        for (int i = 0; i < n; i++) aRowStart[i + 1] += aRowStart[i];
        aRowIdx = new int[nnz];
        int[] fill = Arrays.copyOf(aRowStart, n);
        for (int e = 0; e < nnz; e++) aRowIdx[fill[rowOf[e]]++] = e;
    }

    /** Right-looking Markowitz elimination; records pivot order and fill pattern. */
    private void fullFactor() {
        List<Map<Integer, Double>> rows = new ArrayList<>(n);
        List<java.util.Set<Integer>> cols = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new HashMap<>());
            cols.add(new java.util.HashSet<>());
        }
        for (int e = 0; e < nnz; e++) {
            rows.get(rowOf[e]).merge(colOf[e], val[e], Double::sum);
            cols.get(colOf[e]).add(rowOf[e]);
        }
        boolean[] rowDone = new boolean[n], colDone = new boolean[n];
        rowPerm = new int[n];
        colPerm = new int[n];
        int[] rowStep = new int[n];
        colStep = new int[n];
        // L entries recorded as (original row i, step s, value); U rows recorded per step.
        List<int[]> lEntriesRow = new ArrayList<>();
        List<Double> lEntriesVal = new ArrayList<>();
        int[][] uColsOrig = new int[n][];
        double[][] uValsOrig = new double[n][];
        uDiag = new double[n];

        for (int s = 0; s < n; s++) {
            // --- choose pivot: scan columns with smallest counts first
            int bestR = -1, bestC = -1;
            long bestCost = Long.MAX_VALUE;
            double bestMag = 0;
            int[] order = smallestColumns(cols, colDone, 4);
            for (int c : order) {
                if (c < 0) continue;
                double colMax = 0;
                for (int r : cols.get(c)) colMax = Math.max(colMax, Math.abs(rows.get(r).get(c)));
                if (colMax == 0) continue;
                int cc = cols.get(c).size();
                for (int r : cols.get(c)) {
                    double v = Math.abs(rows.get(r).get(c));
                    if (v < PIVOT_THRESHOLD * colMax) continue;
                    long cost = (long) (rows.get(r).size() - 1) * (cc - 1);
                    if (cost < bestCost || (cost == bestCost && v > bestMag)) {
                        bestCost = cost;
                        bestR = r;
                        bestC = c;
                        bestMag = v;
                    }
                }
            }
            if (bestR < 0) {
                // fall back to any remaining nonzero (whole-matrix search)
                double max = 0;
                for (int c = 0; c < n; c++) {
                    if (colDone[c]) continue;
                    for (int r : cols.get(c)) {
                        double v = Math.abs(rows.get(r).get(c));
                        if (v > max) {
                            max = v;
                            bestR = r;
                            bestC = c;
                        }
                    }
                }
                if (bestR < 0 || max == 0) {
                    factored = false;
                    int badCol = 0;
                    for (int cc = 0; cc < n; cc++) if (!colDone[cc]) { badCol = cc; break; }
                    throw new SingularMatrixException(badCol);
                }
            }
            int r = bestR, c = bestC;
            rowPerm[s] = r;
            colPerm[s] = c;
            rowStep[r] = s;
            colStep[c] = s;
            Map<Integer, Double> prow = rows.get(r);
            double piv = prow.get(c);
            uDiag[s] = piv;
            // record U row (excluding pivot column)
            int m = prow.size() - 1;
            int[] uc = new int[m];
            double[] uv = new double[m];
            int k = 0;
            for (var en : prow.entrySet()) {
                if (en.getKey() == c) continue;
                uc[k] = en.getKey();
                uv[k++] = en.getValue();
            }
            uColsOrig[s] = uc;
            uValsOrig[s] = uv;
            // remove pivot row from column sets
            for (int j : prow.keySet()) cols.get(j).remove(r);
            rowDone[r] = true;
            colDone[c] = true;
            // eliminate column c from other rows
            List<Integer> targets = new ArrayList<>(cols.get(c));
            for (int i : targets) {
                Map<Integer, Double> irow = rows.get(i);
                double f = irow.remove(c) / piv;
                cols.get(c).remove(i);
                lEntriesRow.add(new int[]{i, s});
                lEntriesVal.add(f);
                for (int t = 0; t < uc.length; t++) {
                    int j = uc[t];
                    Double old = irow.get(j);
                    if (old == null) {
                        irow.put(j, -f * uv[t]);
                        cols.get(j).add(i);
                    } else {
                        irow.put(j, old - f * uv[t]);
                    }
                }
            }
            rows.set(r, null);
        }
        // --- convert to permuted compressed form
        int[] lCount = new int[n];
        for (int[] e : lEntriesRow) lCount[rowStep[e[0]]]++;
        lPat = new int[n][];
        lVal = new double[n][];
        for (int s = 0; s < n; s++) {
            lPat[s] = new int[lCount[s]];
            lVal[s] = new double[lCount[s]];
        }
        int[] fillCount = new int[n];
        for (int e = 0; e < lEntriesRow.size(); e++) {
            int ps = rowStep[lEntriesRow.get(e)[0]];
            int f = fillCount[ps]++;
            lPat[ps][f] = lEntriesRow.get(e)[1];
            lVal[ps][f] = lEntriesVal.get(e);
        }
        for (int s = 0; s < n; s++) sortPaired(lPat[s], lVal[s]);
        uPat = new int[n][];
        uVal = new double[n][];
        for (int s = 0; s < n; s++) {
            int[] uc = uColsOrig[s];
            int[] up = new int[uc.length];
            for (int t = 0; t < uc.length; t++) up[t] = colStep[uc[t]];
            double[] uv = uValsOrig[s].clone();
            sortPaired(up, uv);
            uPat[s] = up;
            uVal[s] = uv;
        }
        factored = true;
    }

    /** Numeric refactorisation reusing the pivot order and fill pattern. Returns false if a pivot is unstable. */
    private boolean refactor() {
        double[] w = work;
        for (int s = 0; s < n; s++) {
            int r = rowPerm[s];
            double rowMax = 0;
            for (int p = aRowStart[r]; p < aRowStart[r + 1]; p++) {
                int e = aRowIdx[p];
                w[colStep[colOf[e]]] += val[e];
            }
            int[] lp = lPat[s];
            double[] lv = lVal[s];
            for (int k = 0; k < lp.length; k++) {
                int kk = lp[k];
                double l = w[kk] / uDiag[kk];
                w[kk] = 0;
                lv[k] = l;
                if (l == 0) continue;
                int[] up = uPat[kk];
                double[] uv = uVal[kk];
                for (int t = 0; t < up.length; t++) w[up[t]] -= l * uv[t];
            }
            double d = w[s];
            w[s] = 0;
            int[] up = uPat[s];
            double[] uv = uVal[s];
            for (int t = 0; t < up.length; t++) {
                uv[t] = w[up[t]];
                rowMax = Math.max(rowMax, Math.abs(uv[t]));
                w[up[t]] = 0;
            }
            if (!(Math.abs(d) > REFACTOR_PIVOT_TOL * Math.max(rowMax, Math.abs(d))) || !Double.isFinite(d) || d == 0) {
                Arrays.fill(w, 0.0);
                return false;
            }
            uDiag[s] = d;
        }
        return true;
    }

    private int[] smallestColumns(List<java.util.Set<Integer>> cols, boolean[] colDone, int k) {
        int[] best = new int[k];
        int[] bestCount = new int[k];
        Arrays.fill(best, -1);
        Arrays.fill(bestCount, Integer.MAX_VALUE);
        for (int c = 0; c < n; c++) {
            if (colDone[c]) continue;
            int cnt = cols.get(c).size();
            if (cnt == 0) continue;
            for (int i = 0; i < k; i++) {
                if (cnt < bestCount[i]) {
                    for (int j = k - 1; j > i; j--) {
                        best[j] = best[j - 1];
                        bestCount[j] = bestCount[j - 1];
                    }
                    best[i] = c;
                    bestCount[i] = cnt;
                    break;
                }
            }
        }
        return best;
    }

    private static void sortPaired(int[] idx, double[] v) {
        // insertion sort (rows are short)
        for (int i = 1; i < idx.length; i++) {
            int ki = idx[i];
            double kv = v[i];
            int j = i - 1;
            while (j >= 0 && idx[j] > ki) {
                idx[j + 1] = idx[j];
                v[j + 1] = v[j];
                j--;
            }
            idx[j + 1] = ki;
            v[j + 1] = kv;
        }
    }
}
