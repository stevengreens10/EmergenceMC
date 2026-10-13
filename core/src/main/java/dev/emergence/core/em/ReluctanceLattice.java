package dev.emergence.core.em;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Magnetic equivalent circuit on the cell lattice: what ferromagnetic parts do to the inductances of the coils around
 * them.
 * <p>
 * Every cell of a box around the coils and the iron is a node of magnetic scalar potential ψ; neighbouring cells are
 * joined by the permeance of the two half-cells in series, G = μ0·h·2μa·μb/(μa + μb) (cell size h, relative
 * permeabilities μa, μb). A coil drives its ampere-turns N·i across one face inside its winding (its "cut"): the
 * flux through that face is Φ = G·(ψ_low − ψ_high + s·N·i), s the sign of the coil's axis. Flux is conserved at every
 * node (div B = 0); the box's outer faces carry none. A coil's flux linkage is λ = N·s·Φ through its own cut, so a unit
 * current in coil j gives column j of the inductance matrix, L_kj = λ_k.
 * <p>
 * The lattice is coarse (a coil is a cell), so it is used for what the iron adds: the inductances solved with the
 * iron minus those solved with air everywhere, added to the coils' analytic air inductances. A closed iron path
 * through two coils thus gives L = N²/ℛ and coupling k → 1 (a transformer); an open core, the increase from the
 * iron's share of the path; a steel frame near a coil, a modest increase. The solve is linear; {@link
 * #peakSaturation} tells how hard a current pattern drives the iron, from which {@code MagneticSystem} saturates it.
 */
public final class ReluctanceLattice {
    public static final double MU0 = 4e-7 * Math.PI;

    /** A coil's cut: the face between cell (x, y, z) and its +axis neighbour, s = ±1 the coil's sense, n turns. */
    public record Cut(int x, int y, int z, int axis, double sign, double turns) {}

    private final int nx, ny, nz, x0, y0, z0;
    private final double[] mu;
    private final double h;

    /**
     * @param muR    relative permeability by cell (key {@link #key}); cells not listed are air
     * @param cuts   the coils
     * @param h      cell size [m]
     * @param margin air cells added around the bounding box of the iron and the cuts
     */
    public ReluctanceLattice(Map<Long, Double> muR, List<Cut> cuts, double h, int margin) {
        int ax = Integer.MAX_VALUE, ay = Integer.MAX_VALUE, az = Integer.MAX_VALUE, bx = Integer.MIN_VALUE, by = Integer.MIN_VALUE, bz = Integer.MIN_VALUE;
        for (long k : muR.keySet()) {
            int[] c = unkey(k);
            ax = Math.min(ax, c[0]); ay = Math.min(ay, c[1]); az = Math.min(az, c[2]);
            bx = Math.max(bx, c[0]); by = Math.max(by, c[1]); bz = Math.max(bz, c[2]);
        }
        for (Cut c : cuts) {
            ax = Math.min(ax, c.x); ay = Math.min(ay, c.y); az = Math.min(az, c.z);
            bx = Math.max(bx, c.x + 1); by = Math.max(by, c.y + 1); bz = Math.max(bz, c.z + 1);
        }
        x0 = ax - margin; y0 = ay - margin; z0 = az - margin;
        nx = bx - ax + 1 + 2 * margin; ny = by - ay + 1 + 2 * margin; nz = bz - az + 1 + 2 * margin;
        this.h = h;
        mu = new double[nx * ny * nz];
        Arrays.fill(mu, 1.0);
        for (var e : muR.entrySet()) {
            int[] c = unkey(e.getKey());
            mu[index(c[0], c[1], c[2])] = Math.max(1.0, e.getValue());
        }
    }

    public static long key(int x, int y, int z) {
        return ((long) (x + (1 << 20)) << 42) | ((long) (y + (1 << 20)) << 21) | (z + (1 << 20));
    }

    static int[] unkey(long k) {
        return new int[]{(int) (k >>> 42) - (1 << 20), (int) ((k >>> 21) & ((1 << 21) - 1)) - (1 << 20), (int) (k & ((1 << 21) - 1)) - (1 << 20)};
    }

    public int nodes() {
        return mu.length;
    }

    private int index(int x, int y, int z) {
        return ((x - x0) * ny + (y - y0)) * nz + (z - z0);
    }

    private double permeance(int a, int b) {
        return MU0 * h * 2 * mu[a] * mu[b] / (mu[a] + mu[b]);
    }

    /** Inductance matrix of the coils [H]; with {@code air}, every cell is air (μr = 1). */
    public double[][] inductances(List<Cut> cuts, boolean air) {
        double[] m = air ? filled(1.0) : mu;
        int n = cuts.size();
        double[][] l = new double[n][n];
        for (int j = 0; j < n; j++) {
            double[] psi = solve(m, cuts.get(j));
            for (int k = 0; k < n; k++) l[k][j] = cuts.get(k).turns * cuts.get(k).sign * cutFlux(m, psi, cuts.get(k), k == j ? cuts.get(j) : null);
        }
        // reciprocity holds exactly in the continuum; average away the iterative solver's residual asymmetry
        for (int j = 0; j < n; j++)
            for (int k = j + 1; k < n; k++) l[j][k] = l[k][j] = 0.5 * (l[j][k] + l[k][j]);
        return l;
    }

    /**
     * How near saturation the iron is for coil currents {@code currents} [A] (linear superposition): the largest
     * |B|/B_sat over the cells of {@code bSat} (key → saturation flux density [T]). A cell's B is the mean of the flux
     * densities through its two faces on each axis.
     */
    public double peakSaturation(List<Cut> cuts, double[] currents, Map<Long, Double> bSat) {
        double[] psi = new double[mu.length];
        for (int j = 0; j < cuts.size(); j++) {
            if (currents[j] == 0) continue;
            double[] pj = solve(mu, cuts.get(j));
            for (int i = 0; i < psi.length; i++) psi[i] += currents[j] * pj[i];
        }
        double worst = 0;
        for (var e : bSat.entrySet()) {
            int[] c = unkey(e.getKey());
            if (c[0] <= x0 || c[1] <= y0 || c[2] <= z0 || c[0] >= x0 + nx - 1 || c[1] >= y0 + ny - 1 || c[2] >= z0 + nz - 1) continue;
            double b2 = 0;
            for (int ax = 0; ax < 3; ax++) {
                int[] d = step(ax);
                double lo = faceFlux(psi, cuts, currents, c[0] - d[0], c[1] - d[1], c[2] - d[2], ax);
                double hi = faceFlux(psi, cuts, currents, c[0], c[1], c[2], ax);
                double bAx = 0.5 * (lo + hi) / (h * h);
                b2 += bAx * bAx;
            }
            worst = Math.max(worst, Math.sqrt(b2) / e.getValue());
        }
        return worst;
    }

    /**
     * Σ w·V·|B| over the given cells (V = h³, B the cell's mean flux density) for the coils carrying {@code currents} —
     * with w = H_c, the coercive mmf of the iron along the flux's path times the flux: ∫H_c|B| dV = H_c·l·Φ for a
     * uniform path, whatever the path's cross-section.
     */
    public double weightedFluxVolume(List<Cut> cuts, double[] currents, Map<Long, Double> weights) {
        double[] psi = new double[mu.length];
        for (int j = 0; j < cuts.size(); j++) {
            if (currents[j] == 0) continue;
            double[] pj = solve(mu, cuts.get(j));
            for (int i = 0; i < psi.length; i++) psi[i] += currents[j] * pj[i];
        }
        double sum = 0;
        for (var e : weights.entrySet()) {
            int[] c = unkey(e.getKey());
            if (c[0] <= x0 || c[1] <= y0 || c[2] <= z0 || c[0] >= x0 + nx - 1 || c[1] >= y0 + ny - 1 || c[2] >= z0 + nz - 1) continue;
            double b2 = 0;
            for (int ax = 0; ax < 3; ax++) {
                int[] d = step(ax);
                double lo = faceFlux(psi, cuts, currents, c[0] - d[0], c[1] - d[1], c[2] - d[2], ax);
                double hi = faceFlux(psi, cuts, currents, c[0], c[1], c[2], ax);
                double bAx = 0.5 * (lo + hi) / (h * h);
                b2 += bAx * bAx;
            }
            sum += e.getValue() * h * h * h * Math.sqrt(b2);
        }
        return sum;
    }

    /** Flux through the face between cell (x, y, z) and its +axis neighbour, with the coils' MMF on their cuts. */
    private double faceFlux(double[] psi, List<Cut> cuts, double[] currents, int x, int y, int z, int axis) {
        int[] d = step(axis);
        int a = index(x, y, z), b = index(x + d[0], y + d[1], z + d[2]);
        double mmf = 0;
        for (int j = 0; j < cuts.size(); j++) {
            Cut c = cuts.get(j);
            if (c.axis == axis && c.x == x && c.y == y && c.z == z) mmf += c.sign * c.turns * currents[j];
        }
        return g(mu, a, b) * (psi[a] - psi[b] + mmf);
    }

    private double[] filled(double v) {
        double[] a = new double[mu.length];
        Arrays.fill(a, v);
        return a;
    }

    /** Flux through cut c (in its +axis direction) for potentials psi, with source's unit current driving if c is the source. */
    private double cutFlux(double[] m, double[] psi, Cut c, Cut source) {
        int[] d = step(c.axis);
        int a = index(c.x, c.y, c.z), b = index(c.x + d[0], c.y + d[1], c.z + d[2]);
        double g = MU0 * h * 2 * m[a] * m[b] / (m[a] + m[b]);
        double mmf = source == null ? 0 : source.sign * source.turns;
        return g * (psi[a] - psi[b] + mmf);
    }

    private static int[] step(int axis) {
        return new int[]{axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0};
    }

    /** Potentials for unit current in coil c: conjugate gradients, Jacobi-preconditioned, one node held at 0. */
    private double[] solve(double[] m, Cut c) {
        int n = m.length;
        double[] rhs = new double[n];
        int[] d = step(c.axis);
        int a = index(c.x, c.y, c.z), b = index(c.x + d[0], c.y + d[1], c.z + d[2]);
        double g = MU0 * h * 2 * m[a] * m[b] / (m[a] + m[b]), e = c.sign * c.turns;
        // flux G·(ψa − ψb + E) leaves a and enters b: moved to the right-hand side
        rhs[a] -= g * e;
        rhs[b] += g * e;
        double[] diag = new double[n];
        for (int i = 0; i < n; i++) diag[i] = degree(m, i);
        double ground = 1e-9 * Arrays.stream(diag).max().orElse(1);
        for (int i = 0; i < n; i++) diag[i] += ground; // a weak tie of every node to ψ = 0 fixes the free constant
        double[] x = new double[n], r = rhs.clone(), z = new double[n], p = new double[n], q = new double[n];
        for (int i = 0; i < n; i++) z[i] = r[i] / diag[i];
        System.arraycopy(z, 0, p, 0, n);
        double rz = dot(r, z), r0 = Math.sqrt(dot(rhs, rhs));
        for (int it = 0; it < 20 * n && Math.sqrt(dot(r, r)) > 1e-10 * r0; it++) {
            apply(m, p, q, ground);
            double alpha = rz / dot(p, q);
            for (int i = 0; i < n; i++) {
                x[i] += alpha * p[i];
                r[i] -= alpha * q[i];
            }
            for (int i = 0; i < n; i++) z[i] = r[i] / diag[i];
            double rzNew = dot(r, z);
            double beta = rzNew / rz;
            rz = rzNew;
            for (int i = 0; i < n; i++) p[i] = z[i] + beta * p[i];
        }
        return x;
    }

    private double degree(double[] m, int i) {
        int z = i % nz, y = (i / nz) % ny, x = i / (nz * ny);
        double s = 0;
        if (x > 0) s += g(m, i, i - ny * nz);
        if (x < nx - 1) s += g(m, i, i + ny * nz);
        if (y > 0) s += g(m, i, i - nz);
        if (y < ny - 1) s += g(m, i, i + nz);
        if (z > 0) s += g(m, i, i - 1);
        if (z < nz - 1) s += g(m, i, i + 1);
        return s;
    }

    private double g(double[] m, int a, int b) {
        return MU0 * h * 2 * m[a] * m[b] / (m[a] + m[b]);
    }

    /** q = K·p for the permeance Laplacian (plus the weak ground tie). */
    private void apply(double[] m, double[] p, double[] q, double ground) {
        int stride = ny * nz;
        for (int i = 0; i < m.length; i++) {
            int z = i % nz, y = (i / nz) % ny, x = i / stride;
            double s = ground * p[i];
            if (x > 0) s += g(m, i, i - stride) * (p[i] - p[i - stride]);
            if (x < nx - 1) s += g(m, i, i + stride) * (p[i] - p[i + stride]);
            if (y > 0) s += g(m, i, i - nz) * (p[i] - p[i - nz]);
            if (y < ny - 1) s += g(m, i, i + nz) * (p[i] - p[i + nz]);
            if (z > 0) s += g(m, i, i - 1) * (p[i] - p[i - 1]);
            if (z < nz - 1) s += g(m, i, i + 1) * (p[i] - p[i + 1]);
            q[i] = s;
        }
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** Convenience: relative permeability map builder. */
    public static Map<Long, Double> cells() {
        return new HashMap<>();
    }
}
