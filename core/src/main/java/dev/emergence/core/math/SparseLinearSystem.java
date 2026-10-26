package dev.emergence.core.math;

import java.util.Arrays;

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
    private static final double PIVOT_THRESHOLD = Double.parseDouble(System.getProperty("diag.pivotThreshold", "0.1"));
    private static final double PIVOT_COLLAPSE = 1e-8;
    /** Columns of fewest entries the Markowitz search weighs at each pivot. */
    private static final int CANDIDATES = Integer.getInteger("diag.markowitzColumns", 4);
    /** Columns taken in a minimum-degree order of the pattern (else chosen by Markowitz cost as elimination goes). */
    private static final boolean MIN_DEGREE = "md".equals(System.getProperty("diag.ordering", "markowitz"));
    /** The pattern's minimum-degree column order (recomputed when the pattern changes). */
    private int[] mdOrder;
    /**
     * How many of the first pivots stand alone: a row with only its diagonal, in a column with only that entry (an
     * unknown a multirate phase holds). Minimum-degree order takes them first; refactorisation and substitution give
     * each a division, not the general row pass (a phase holding most of a machine's unknowns spent a third of its
     * solves passing over them).
     */
    private int nIso;
    /** Each pivot's value, and its share of its U row (|d| / max(|d|, row's largest)), when the pivot order was last chosen (see refactor). */
    private double[] pivotAtFactor, pivotShareAtFactor;

    private final int n;
    // Assembled matrix entries
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
    /** The substitutions' vector (every entry written before it is read: never cleared, unlike {@link #work}). */
    private final double[] sub;
    private boolean factored;

    public SparseLinearSystem(int n) {
        this.n = n;
        this.work = new double[n];
        this.sub = new double[n];
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public void clear() {
        Arrays.fill(val, 0, nnz, 0.0);
        beginSegment(-1);
    }

    /*
     * Assembly replay: each element stamps the same (row, col) entries in the same order on every pass (every Newton
     * iteration of every step), so each element's sequence of entry indices is recorded and replayed — an add costs a
     * comparison and an array read instead of a hash lookup. Where an element's sequence differs (it stamps
     * conditionally), the hash table answers and that element's record is rewritten from there; the others are
     * unaffected. Adds outside any segment always use the hash table.
     */
    private long[][] segKey = new long[0][];
    private int[][] segIdx = new int[0][];
    private int[] segLen = new int[0];
    private long[] seqKey;
    private int[] seqIdx;
    private int seg = -1, cursor;

    @Override
    public void beginSegment(int s) {
        if (seg >= 0) {
            segKey[seg] = seqKey;
            segIdx[seg] = seqIdx;
            segLen[seg] = Math.max(segLen[seg], cursor);
        }
        seg = s;
        cursor = 0;
        if (s < 0) return;
        if (s >= segKey.length) {
            int cap = Math.max(s + 1, segKey.length * 2);
            segKey = Arrays.copyOf(segKey, cap);
            segIdx = Arrays.copyOf(segIdx, cap);
            segLen = Arrays.copyOf(segLen, cap);
        }
        if (segKey[s] == null) {
            segKey[s] = new long[8];
            segIdx[s] = new int[8];
        }
        seqKey = segKey[s];
        seqIdx = segIdx[s];
    }

    // primitive open-addressing (row,col) → entry index table; avoids boxing in the hot assembly path
    private long[] hkeys = new long[256];
    private int[] hvals = new int[256];
    private int hsize;

    /** Fibonacci hashing: the high bits of key·2⁶⁴/φ spread (row, col) keys evenly (no probe clustering). */
    private static int hash(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 32);
    }

    private int lookup(long key) {
        int mask = hkeys.length - 1;
        int h = hash(key);
        for (int i = h & mask; ; i = (i + 1) & mask) {
            long k = hkeys[i];
            if (k == 0) return -1;
            if (k == key) return hvals[i];
        }
    }

    private void insert(long key, int val) {
        if ((hsize + 1) * 2 > hkeys.length) {
            long[] ok = hkeys;
            int[] ov = hvals;
            hkeys = new long[ok.length * 2];
            hvals = new int[ok.length * 2];
            hsize = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != 0) insert(ok[i], ov[i]);
        }
        int mask = hkeys.length - 1;
        int h = hash(key);
        int i = h & mask;
        while (hkeys[i] != 0) i = (i + 1) & mask;
        hkeys[i] = key;
        hvals[i] = val;
        hsize++;
    }

    @Override
    public int visitPattern(int from, EntryVisitor v) {
        for (int k = from; k < nnz; k++) v.visit(rowOf[k], colOf[k]);
        return nnz;
    }

    @Override
    public void add(int row, int col, double v) {
        long key = (((long) row + 1) << 32) | (col & 0xffffffffL); // +1 so no key is 0 (empty marker)
        if (seg < 0) {
            if (v == 0.0) return;
            int idx = entry(key, row, col); // first: it may grow val
            val[idx] += v;
            return;
        }
        int c = cursor++;
        if (c < segLen[seg] && seqKey[c] == key) {
            val[seqIdx[c]] += v;
            return;
        }
        // an element that leaves out an entry this time (a zero it does not stamp, a branch of its code not taken): pick
        // up its recorded sequence a few entries on, rather than rewriting the record — rewritten, a record that
        // alternated between two patterns missed every time, and each miss cost a hash lookup for the rest of the element
        for (int ahead = 1; ahead <= 4 && c + ahead < segLen[seg]; ahead++) {
            if (seqKey[c + ahead] != key) continue;
            cursor = c + ahead + 1;
            val[seqIdx[c + ahead]] += v;
            return;
        }
        if (v == 0.0) {
            // a zero creates no entry: kept in the record if the entry is there, else as if not stamped
            int known = lookup(key);
            if (known < 0) {
                cursor = c;
                return;
            }
            if (c >= seqKey.length) {
                seqKey = Arrays.copyOf(seqKey, seqKey.length * 2);
                seqIdx = Arrays.copyOf(seqIdx, seqIdx.length * 2);
            }
            seqKey[c] = key;
            seqIdx[c] = known;
            return;
        }
        if (REPLAY_MISSES != null) REPLAY_MISSES.merge(seg, 1, Integer::sum);
        if (seg == MISS_TRACE_SEG && segLen[seg] > 0 && missTraces++ < 40)
            System.out.printf("replay miss seg %d at %d of %d: (%d,%d) where the record has %s%n", seg, c, segLen[seg], row, col,
                    c < segLen[seg] ? "(" + ((seqKey[c] >>> 32) - 1) + "," + (int) seqKey[c] + ")" : "nothing");
        int idx = entry(key, row, col);
        if (c >= seqKey.length) {
            seqKey = Arrays.copyOf(seqKey, seqKey.length * 2);
            seqIdx = Arrays.copyOf(seqIdx, seqIdx.length * 2);
        }
        seqKey[c] = key;
        seqIdx[c] = idx;
        val[idx] += v;
    }

    /**
     * A thread's private accumulator for assembling in parallel: each element's adds land where its recorded sequence
     * says, in the sink's own copy of the values, read-only against this system's records and table. An add that
     * would change them — a new entry, an element stamping off its record with a nonzero — marks the sink missed, and
     * the pass is done again serially. Sinks' values are summed into this system by {@link #merge}, in a fixed order.
     */
    public final class Sink implements LinearSystem {
        private double[] local = new double[Math.max(nnz, 1)];
        private long[] sKey;
        private int[] sIdx;
        private int sSeg = -1, sCursor, sLen;
        private boolean missed;

        @Override
        public int size() {
            return n;
        }

        @Override
        public void clear() {
            if (local.length < nnz) local = new double[nnz];
            else Arrays.fill(local, 0, nnz, 0.0);
            missed = false;
            sSeg = -1;
        }

        public boolean missed() {
            return missed;
        }

        @Override
        public void beginSegment(int s) {
            sSeg = s;
            sCursor = 0;
            if (s < 0 || s >= segKey.length || segKey[s] == null) {
                sKey = null;
                sLen = 0;
                return;
            }
            sKey = segKey[s];
            sIdx = segIdx[s];
            sLen = segLen[s];
        }

        @Override
        public void add(int row, int col, double v) {
            long key = (((long) row + 1) << 32) | (col & 0xffffffffL);
            if (sSeg < 0 || sKey == null) {
                if (v != 0.0) missed = true;
                return;
            }
            int c = sCursor++;
            if (c < sLen && sKey[c] == key) {
                local[sIdx[c]] += v;
                return;
            }
            for (int ahead = 1; ahead <= 4 && c + ahead < sLen; ahead++) {
                if (sKey[c + ahead] != key) continue;
                sCursor = c + ahead + 1;
                local[sIdx[c + ahead]] += v;
                return;
            }
            if (v == 0.0) {
                // a zero adds nothing: only keep the cursor where the system's own pass would leave it
                if (lookup(key) < 0) sCursor = c;
                return;
            }
            missed = true;
        }

        @Override
        public int visitPattern(int from, EntryVisitor v) {
            throw new UnsupportedOperationException("a sink only accumulates");
        }

        @Override
        public double diagonal(int i) {
            throw new UnsupportedOperationException("a sink only accumulates");
        }

        @Override
        public void solve(double[] rhs) {
            throw new UnsupportedOperationException("a sink only accumulates");
        }
    }

    /** A new private accumulator over this system's current entries (see {@link Sink}). */
    public Sink newSink() {
        return new Sink();
    }

    /** Adds the sinks' accumulated values into this system's entries, sink by sink in order. */
    public void merge(Sink[] sinks) {
        merge(sinks, 0, nnz);
    }

    /** {@link #merge} over entries [from, to) only (disjoint ranges may merge on separate threads). */
    public void merge(Sink[] sinks, int from, int to) {
        for (Sink s : sinks) {
            double[] l = s.local;
            int m = Math.min(Math.min(nnz, to), l.length);
            for (int i = from; i < m; i++) val[i] += l[i];
        }
    }

    /** The entry index of (row, col), created if new. */
    private int entry(long key, int row, int col) {
        int idx = lookup(key);
        if (idx < 0) {
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
            insert(key, idx);
            patternChanged = true;
        }
        return idx;
    }

    /** Each diagonal's entry index once known (entries are never removed, so a found index stays good). */
    private int[] diagIdx;

    @Override
    public double diagonal(int i) {
        if (diagIdx == null) {
            diagIdx = new int[n];
            Arrays.fill(diagIdx, -1);
        }
        int e = diagIdx[i];
        if (e < 0) e = diagIdx[i] = lookup((((long) i + 1) << 32) | (i & 0xffffffffL));
        return e < 0 ? 0.0 : val[e];
    }

    public int nonZeros() {
        return nnz;
    }

    /** Multiply-adds in one numeric refactorisation (diagnostics): each L entry's U row. */
    public long factorFlops() {
        if (lPat == null) return 0;
        long c = 0;
        for (int s = 0; s < n; s++) for (int k : lPat[s]) c += 1 + uPat[k].length;
        return c;
    }

    /** The pivots costing most in a refactorisation (diagnostics): {column, multiply-adds, L length, U length}, costliest first. */
    public long[][] costliestPivots(int k) {
        long[][] r = new long[n][];
        for (int s = 0; s < n; s++) {
            long c = 0;
            for (int q : lPat[s]) c += 1 + uPat[q].length;
            r[s] = new long[]{colPerm[s], c, lPat[s].length, uPat[s].length, s};
        }
        Arrays.sort(r, (a, b) -> Long.compare(b[1], a[1]));
        return Arrays.copyOf(r, Math.min(k, n));
    }

    /** Number of stored L+U off-diagonal entries (fill diagnostics). */
    public int factorNonZeros() {
        if (lPat == null) return 0;
        int c = 0;
        for (int s = 0; s < n; s++) c += lPat[s].length + uPat[s].length;
        return c;
    }

    /** Diagnostics ({@code diag.replayMisses=true}): assembly adds the replay missed (a hash lookup), by segment (element). */
    public static final java.util.Map<Integer, Integer> REPLAY_MISSES = Boolean.getBoolean("diag.replayMisses")
            ? new java.util.concurrent.ConcurrentHashMap<>() : null;

    /** Diagnostics ({@code diag.missTrace=<segment>}): that segment's first replay misses printed. */
    static final int MISS_TRACE_SEG = Integer.getInteger("diag.missTrace", -2);
    private static int missTraces;

    /** Diagnostics ({@code diag.collapses=true}): how often each unknown's pivot collapsed (by column). */
    public static final java.util.Map<Integer, Integer> COLLAPSES = Boolean.getBoolean("diag.collapses")
            ? new java.util.concurrent.ConcurrentHashMap<>() : null;

    /** Diagnostics: factorisations with a fresh pivot order (pattern changed / pivot unstable), and reused ones. */
    public static final java.util.concurrent.atomic.LongAdder FULL_PATTERN = new java.util.concurrent.atomic.LongAdder(),
            FULL_UNSTABLE = new java.util.concurrent.atomic.LongAdder(), REFACTORS = new java.util.concurrent.atomic.LongAdder(),
            REFACTOR_FLOPS = new java.util.concurrent.atomic.LongAdder();
    private long flopsNow;

    @Override
    public void solve(double[] b) {
        if (patternChanged || !factored) {
            FULL_PATTERN.increment();
            buildRowIndex();
            mdOrder = MIN_DEGREE ? minimumDegreeOrder() : null;
            fullFactor();
            patternChanged = false;
        } else if (!refactor()) {
            FULL_UNSTABLE.increment();
            fullFactor();
        } else {
            REFACTORS.increment();
            REFACTOR_FLOPS.add(flopsNow);
        }
        substitute(b);
    }

    private void substitute(double[] b) {
        // Forward substitution (L has unit diagonal)
        double[] y = sub;
        for (int s = 0; s < nIso; s++) y[s] = b[rowPerm[s]];
        for (int s = nIso; s < n; s++) {
            double v = b[rowPerm[s]];
            int[] lp = lPat[s];
            double[] lv = lVal[s];
            for (int k = 0; k < lp.length; k++) v -= lv[k] * y[lp[k]];
            y[s] = v;
        }
        // Back substitution
        for (int s = n - 1; s >= nIso; s--) {
            double v = y[s];
            int[] up = uPat[s];
            double[] uv = uVal[s];
            for (int k = 0; k < up.length; k++) v -= uv[k] * y[up[k]];
            y[s] = v / uDiag[s];
        }
        for (int s = 0; s < nIso; s++) y[s] /= uDiag[s];
        for (int s = 0; s < n; s++) b[colPerm[s]] = y[s];
    }

    private void buildRowIndex() {
        aRowStart = new int[n + 1];
        for (int e = 0; e < nnz; e++) aRowStart[rowOf[e] + 1]++;
        for (int i = 0; i < n; i++) aRowStart[i + 1] += aRowStart[i];
        aRowIdx = new int[nnz];
        int[] fill = Arrays.copyOf(aRowStart, n);
        for (int e = 0; e < nnz; e++) aRowIdx[fill[rowOf[e]]++] = e;
    }

    /**
     * Right-looking Markowitz elimination; records pivot order and fill pattern. The active submatrix is held as plain
     * arrays: each row its column indices and values, each column its rows in ascending order (the order the pivot
     * search meets them, so ties break as they always have), and each elimination scatters the target row through a
     * dense position map. No boxed keys or hash tables: with them, a two-phase plant spent half its solves here.
     */
    private void fullFactor() {
        int[][] rc = new int[n][];
        double[][] rv = new double[n][];
        int[] rl = new int[n];
        int[][] cr = new int[n][];
        int[] cl = new int[n];
        {
            int[] rcount = new int[n], ccount = new int[n];
            for (int e = 0; e < nnz; e++) {
                rcount[rowOf[e]]++;
                ccount[colOf[e]]++;
            }
            for (int i = 0; i < n; i++) {
                rc[i] = new int[Math.max(4, 2 * rcount[i])];
                rv[i] = new double[rc[i].length];
                cr[i] = new int[Math.max(4, 2 * ccount[i])];
            }
            for (int e = 0; e < nnz; e++) {
                int r = rowOf[e], c = colOf[e];
                int k = rl[r]++;
                rc[r][k] = c;
                rv[r][k] = val[e];
                cr[c][cl[c]++] = r;
            }
            for (int c = 0; c < n; c++) Arrays.sort(cr[c], 0, cl[c]);
        }
        int[] posOf = new int[n];
        Arrays.fill(posOf, -1);
        boolean[] colDone = new boolean[n];
        rowPerm = new int[n];
        colPerm = new int[n];
        int[] rowStep = new int[n];
        colStep = new int[n];
        // L entries recorded as (original row, step, value); U rows recorded per step.
        int lCountAll = 0;
        int[] lRow = new int[Math.max(16, nnz)], lStep = new int[lRow.length];
        double[] lV = new double[lRow.length];
        int[][] uColsOrig = new int[n][];
        double[][] uValsOrig = new double[n][];
        uDiag = new double[n];

        // the unknowns that stand alone — a row with one entry, in a column with only it: what a multirate phase holds —
        // pivoted first, in one pass; the search below then weighs only the rest (scanning all the columns at every
        // pivot, a phase holding nine tenths of a machine spent a fifth of its time choosing pivots for those)
        int s0 = 0;
        for (int r = 0; r < n; r++) {
            if (rl[r] != 1) continue;
            int c = rc[r][0];
            double v = rv[r][0];
            if (cl[c] != 1 || colDone[c] || v == 0) continue;
            rowPerm[s0] = r;
            colPerm[s0] = c;
            rowStep[r] = s0;
            colStep[c] = s0;
            uDiag[s0] = v;
            uColsOrig[s0] = new int[0];
            uValsOrig[s0] = new double[0];
            colDone[c] = true;
            cl[c] = 0;
            rc[r] = null;
            rv[r] = null;
            rl[r] = 0;
            s0++;
        }
        int[] act = new int[n - s0];
        int[] nAct = {0};
        for (int c = 0; c < n; c++) if (!colDone[c]) act[nAct[0]++] = c;
        int mdPos = 0;
        for (int s = s0; s < n; s++) {
            int bestR = -1, bestC = -1;
            long bestCost = Long.MAX_VALUE;
            double bestMag = 0;
            int[] order;
            if (mdOrder != null) {
                // the next column in minimum-degree order, its pivot the shortest row among those large enough
                while (colDone[mdOrder[mdPos]]) mdPos++;
                order = new int[]{mdOrder[mdPos]};
            } else {
                // --- choose pivot: scan columns with smallest counts first
                order = smallestColumns(cl, colDone, CANDIDATES, act, nAct);
            }
            for (int c : order) {
                if (c < 0) continue;
                double colMax = 0;
                for (int q = 0; q < cl[c]; q++) colMax = Math.max(colMax, Math.abs(valueAt(rc, rv, rl, cr[c][q], c)));
                if (colMax == 0) continue;
                int cc = cl[c];
                for (int q = 0; q < cl[c]; q++) {
                    int r = cr[c][q];
                    double v = Math.abs(valueAt(rc, rv, rl, r, c));
                    if (v < PIVOT_THRESHOLD * colMax) continue;
                    long cost = (long) (rl[r] - 1) * (cc - 1);
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
                    for (int q = 0; q < cl[c]; q++) {
                        int r = cr[c][q];
                        double v = Math.abs(valueAt(rc, rv, rl, r, c));
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
            int[] prc = rc[r];
            double[] prv = rv[r];
            int plen = rl[r];
            double piv = valueAt(rc, rv, rl, r, c);
            uDiag[s] = piv;
            // record U row (excluding pivot column)
            int[] uc = new int[plen - 1];
            double[] uv = new double[plen - 1];
            int k = 0;
            for (int q = 0; q < plen; q++) {
                if (prc[q] == c) continue;
                uc[k] = prc[q];
                uv[k++] = prv[q];
            }
            uColsOrig[s] = uc;
            uValsOrig[s] = uv;
            // remove pivot row from column lists
            for (int q = 0; q < plen; q++) removeSorted(cr, cl, prc[q], r);
            colDone[c] = true;
            // eliminate column c from the other rows that have it
            int nt = cl[c];
            int[] targets = Arrays.copyOf(cr[c], nt);
            cl[c] = 0;
            for (int ti = 0; ti < nt; ti++) {
                int i = targets[ti];
                int[] irc = rc[i];
                double[] irv = rv[i];
                int ilen = rl[i];
                // take column c out of row i (the order within a row does not matter)
                double f = 0;
                for (int q = 0; q < ilen; q++) {
                    if (irc[q] != c) continue;
                    f = irv[q] / piv;
                    irc[q] = irc[ilen - 1];
                    irv[q] = irv[ilen - 1];
                    ilen--;
                    break;
                }
                if (lCountAll == lRow.length) {
                    lRow = Arrays.copyOf(lRow, 2 * lRow.length);
                    lStep = Arrays.copyOf(lStep, lRow.length);
                    lV = Arrays.copyOf(lV, lRow.length);
                }
                lRow[lCountAll] = i;
                lStep[lCountAll] = s;
                lV[lCountAll++] = f;
                for (int q = 0; q < ilen; q++) posOf[irc[q]] = q;
                for (int t = 0; t < uc.length; t++) {
                    int j = uc[t];
                    int pq = posOf[j];
                    if (pq >= 0) {
                        irv[pq] -= f * uv[t];
                    } else {
                        if (ilen == irc.length) {
                            irc = Arrays.copyOf(irc, 2 * irc.length);
                            irv = Arrays.copyOf(irv, irc.length);
                        }
                        irc[ilen] = j;
                        irv[ilen] = -f * uv[t];
                        posOf[j] = ilen++;
                        insertSorted(cr, cl, j, i);
                    }
                }
                for (int q = 0; q < ilen; q++) posOf[irc[q]] = -1;
                rc[i] = irc;
                rv[i] = irv;
                rl[i] = ilen;
            }
            rc[r] = null;
            rv[r] = null;
        }
        // --- convert to permuted compressed form
        int[] lCount = new int[n];
        for (int e = 0; e < lCountAll; e++) lCount[rowStep[lRow[e]]]++;
        lPat = new int[n][];
        lVal = new double[n][];
        for (int s = 0; s < n; s++) {
            lPat[s] = new int[lCount[s]];
            lVal[s] = new double[lCount[s]];
        }
        int[] fillCount = new int[n];
        for (int e = 0; e < lCountAll; e++) {
            int ps = rowStep[lRow[e]];
            int f = fillCount[ps]++;
            lPat[ps][f] = lStep[e];
            lVal[ps][f] = lV[e];
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
        flopsNow = factorFlops();
        nIso = 0;
        while (nIso < n && lPat[nIso].length == 0 && uPat[nIso].length == 0
                && aRowStart[rowPerm[nIso] + 1] - aRowStart[rowPerm[nIso]] == 1) nIso++;
        pivotAtFactor = uDiag.clone();
        pivotShareAtFactor = new double[n];
        for (int s = 0; s < n; s++) {
            double m = Math.abs(uDiag[s]);
            for (double v : uVal[s]) m = Math.max(m, Math.abs(v));
            pivotShareAtFactor[s] = Math.abs(uDiag[s]) / m;
        }
        factored = true;
    }

    /** The active value at (r, c) (rows are short: a scan). */
    private static double valueAt(int[][] rc, double[][] rv, int[] rl, int r, int c) {
        int[] cols = rc[r];
        for (int q = 0, len = rl[r]; q < len; q++) if (cols[q] == c) return rv[r][q];
        return 0;
    }

    /** Removes row r from column c's ascending list. */
    private static void removeSorted(int[][] cr, int[] cl, int c, int r) {
        int[] a = cr[c];
        int len = cl[c];
        int q = Arrays.binarySearch(a, 0, len, r);
        if (q < 0) return;
        System.arraycopy(a, q + 1, a, q, len - q - 1);
        cl[c] = len - 1;
    }

    /** Inserts row r into column c's ascending list. */
    private static void insertSorted(int[][] cr, int[] cl, int c, int r) {
        int[] a = cr[c];
        int len = cl[c];
        int q = Arrays.binarySearch(a, 0, len, r);
        if (q >= 0) return;
        q = -q - 1;
        if (len == a.length) a = cr[c] = Arrays.copyOf(a, 2 * a.length);
        System.arraycopy(a, q, a, q + 1, len - q);
        a[q] = r;
        cl[c] = len + 1;
    }

    /** Numeric refactorisation reusing the pivot order and fill pattern. Returns false if a pivot is unstable. */
    private boolean refactor() {
        double[] w = work;
        for (int s = 0; s < nIso; s++) {
            double d = val[aRowIdx[aRowStart[rowPerm[s]]]];
            if (!Double.isFinite(d) || d == 0 || Math.abs(d) < PIVOT_COLLAPSE * Math.abs(pivotAtFactor[s])) {
                if (COLLAPSES != null) COLLAPSES.merge(colPerm[s], 1, Integer::sum);
                return false;
            }
            uDiag[s] = d;
        }
        for (int s = nIso; s < n; s++) {
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
            // The pivot order stays good while no pivot collapses relative to what it was when the order was chosen
            // (threshold pivoting chose it as large within its column), neither alone nor against its own U row. Rows
            // mix units — a species balance carries a reaction rate's temperature slope many orders above its own
            // diagonal — so a pivot's share of its row is judged against its share then, not against 1: an absolute
            // floor rejected, every solve, the very pivot that the fresh factorisation then chose again.
            double share = Math.abs(d) / Math.max(rowMax, Math.abs(d));
            if (!Double.isFinite(d) || d == 0 || Math.abs(d) < PIVOT_COLLAPSE * Math.abs(pivotAtFactor[s])
                    || !(share >= PIVOT_COLLAPSE * pivotShareAtFactor[s])) {
                if (COLLAPSES != null) COLLAPSES.merge(colPerm[s], 1, Integer::sum);
                Arrays.fill(w, 0.0);
                return false;
            }
            uDiag[s] = d;
        }
        return true;
    }

    /**
     * A minimum-degree elimination order of the symmetrised pattern (A + Aᵀ): each step the node with the fewest
     * neighbours, whose neighbours then become a clique. Adjacency is held as bitsets (cheap at a few thousand
     * unknowns; recomputed only when the pattern changes).
     */
    private int[] minimumDegreeOrder() {
        int words = (n + 63) >>> 6;
        long[][] adj = new long[n][words];
        for (int e = 0; e < nnz; e++) {
            int r = rowOf[e], c = colOf[e];
            if (r == c) continue;
            adj[r][c >>> 6] |= 1L << c;
            adj[c][r >>> 6] |= 1L << r;
        }
        int[] deg = new int[n];
        for (int i = 0; i < n; i++) for (long w : adj[i]) deg[i] += Long.bitCount(w);
        boolean[] done = new boolean[n];
        int[] order = new int[n];
        for (int s = 0; s < n; s++) {
            int v = -1, best = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!done[i] && deg[i] < best) {
                    best = deg[i];
                    v = i;
                }
            }
            order[s] = v;
            done[v] = true;
            long[] av = adj[v];
            for (int w = 0; w < words; w++) {
                long bits = av[w];
                while (bits != 0) {
                    int u = (w << 6) + Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    long[] au = adj[u];
                    int d = 0;
                    for (int k = 0; k < words; k++) {
                        au[k] |= av[k];
                        if (k == (u >>> 6)) au[k] &= ~(1L << u);
                        if (k == (v >>> 6)) au[k] &= ~(1L << v);
                        d += Long.bitCount(au[k]);
                    }
                    deg[u] = d;
                }
            }
        }
        return order;
    }

    /** The k remaining columns with fewest entries, from the active list (compacted as columns finish). */
    private int[] smallestColumns(int[] colCount, boolean[] colDone, int k, int[] act, int[] nAct) {
        int[] best = new int[k];
        int[] bestCount = new int[k];
        Arrays.fill(best, -1);
        Arrays.fill(bestCount, Integer.MAX_VALUE);
        int w = 0;
        for (int q = 0; q < nAct[0]; q++) {
            int c = act[q];
            if (colDone[c]) continue;
            act[w++] = c;
            int cnt = colCount[c];
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
        nAct[0] = w;
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
