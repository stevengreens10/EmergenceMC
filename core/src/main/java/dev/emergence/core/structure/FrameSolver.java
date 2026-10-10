package dev.emergence.core.structure;

import dev.emergence.core.math.LinearSystem;
import dev.emergence.core.math.SingularMatrixException;

import java.util.ArrayList;
import java.util.List;

/**
 * Linear static analysis of a 3D frame by the direct stiffness method: nodes with six degrees of freedom
 * (three displacements, three rotations) joined by axis-aligned Euler–Bernoulli beam members (axial, two
 * bending planes, St Venant torsion). Members lie along a grid axis, so their local frames are permutations
 * of the global one. Supports clamp a node's six degrees of freedom.
 * <p>
 * Assumptions: small displacements, linear-elastic material up to the strength check, slender-beam
 * kinematics (no shear deformation — a member of length ≈ its depth is somewhat too stiff, ≲ 30 %). Elastic
 * stability by {@link #bucklingFactor}: linear (Euler) buckling of the loaded frame.
 */
public final class FrameSolver {
    /** A member between nodes a and b along global axis {@code axis} (0 x, 1 y, 2 z), b being at +axis of a. */
    public record Member(int a, int b, int axis, double length, double e, double g, double area, double i1, double i2,
                         double j) {
        /** i1: second moment about the first transverse axis ((axis+1) % 3), i2: about the second ((axis+2) % 3). */
    }

    /** End forces of a member in its local axes, at end a: N (tension +), V1, V2, T, M1, M2; and the same at b. */
    public record MemberForces(double[] atA, double[] atB) {
    }

    private final int nodes;
    private final List<Member> members = new ArrayList<>();
    private final boolean[] fixed; // per degree of freedom
    private final double[] load;

    public FrameSolver(int nodes) {
        this.nodes = nodes;
        this.fixed = new boolean[6 * nodes];
        this.load = new double[6 * nodes];
    }

    public void addMember(Member m) {
        members.add(m);
    }

    public List<Member> members() {
        return members;
    }

    /** Clamps a node (all six degrees of freedom). */
    public void fix(int node) {
        for (int k = 0; k < 6; k++) fixed[6 * node + k] = true;
    }

    /** Pins a node: its three displacements are held, it may rotate. */
    public void pin(int node) {
        for (int k = 0; k < 3; k++) fixed[6 * node + k] = true;
    }

    /** Adds a force [N] on a node along global axis k (0 x, 1 y, 2 z). */
    public void force(int node, int k, double f) {
        load[6 * node + k] += f;
    }

    /** Adds a moment [N·m] on a node about global axis k. */
    public void moment(int node, int k, double m) {
        load[6 * node + 3 + k] += m;
    }

    /** Local 12×12 stiffness of a member with local axis order (axial, t1, t2). */
    static double[][] localStiffness(Member m) {
        double l = m.length(), ea = m.e() * m.area() / l, gj = m.g() * m.j() / l;
        double[][] k = new double[12][12];
        // indices: u(0) v1(1) v2(2) θx(3) θ1(4) θ2(5) at a; +6 at b
        put(k, 0, 0, ea); put(k, 6, 6, ea); put(k, 0, 6, -ea);
        put(k, 3, 3, gj); put(k, 9, 9, gj); put(k, 3, 9, -gj);
        // bending in the (axial, t1) plane: displacement v1 with rotation about t2 (θ2); EI about t2 = i2
        bend(k, 1, 5, m.e() * m.i2(), l, +1);
        // bending in the (axial, t2) plane: displacement v2 with rotation about t1 (θ1); sign flips
        bend(k, 2, 4, m.e() * m.i1(), l, -1);
        return k;
    }

    private static void put(double[][] k, int i, int j, double v) {
        k[i][j] += v;
        if (i != j) k[j][i] += v;
    }

    /** Euler–Bernoulli bending terms for displacement dof v and rotation dof r (sign s couples them). */
    private static void bend(double[][] k, int v, int r, double ei, double l, int s) {
        double a = 12 * ei / (l * l * l), b = 6 * ei / (l * l), c = 4 * ei / l, d = 2 * ei / l;
        int v2 = v + 6, r2 = r + 6;
        put(k, v, v, a); put(k, v2, v2, a); put(k, v, v2, -a);
        put(k, v, r, s * b); put(k, v, r2, s * b); put(k, v2, r, -s * b); put(k, v2, r2, -s * b);
        put(k, r, r, c); put(k, r2, r2, c); put(k, r, r2, d);
    }

    /** Global dof index of a member's local dof q (0..11): local axes (axial, t1, t2) map to (axis, axis+1, axis+2). */
    static int globalDof(Member m, int q) {
        int node = q < 6 ? m.a() : m.b();
        int local = q % 6, base = local < 3 ? 0 : 3, dir = (m.axis() + local % 3) % 3;
        return 6 * node + base + dir;
    }

    /**
     * Local 12×12 geometric stiffness of a member carrying axial force p (tension +): the consistent matrix of a
     * cubic beam element (Przemieniecki), in the same dof order and bending-plane sign conventions as the elastic one.
     */
    static double[][] localGeometric(Member m, double p) {
        double l = m.length();
        double[][] k = new double[12][12];
        geo(k, 1, 5, p, l, +1);
        geo(k, 2, 4, p, l, -1);
        return k;
    }

    private static void geo(double[][] k, int v, int r, double p, double l, int s) {
        double a = 6 * p / (5 * l), b = p / 10, c = 2 * p * l / 15, d = -p * l / 30;
        int v2 = v + 6, r2 = r + 6;
        put(k, v, v, a); put(k, v2, v2, a); put(k, v, v2, -a);
        put(k, v, r, s * b); put(k, v, r2, s * b); put(k, v2, r, -s * b); put(k, v2, r2, -s * b);
        put(k, r, r, c); put(k, r2, r2, c); put(k, r, r2, d);
    }

    /**
     * Elastic stability of the frame under the loads that gave displacements u: the largest μ of
     * (K + K_G/μ)·φ = 0 — i.e. the loads times 1/μ would make it buckle. μ ≥ 1: the structure buckles as it is
     * loaded; {@code mode} (length 6n) receives the buckling shape. Inverse (power) iteration on K⁻¹(−K_G),
     * shifted when a tension-stiffening eigenvalue dominates. Returns 0 if nothing is in compression worth checking:
     * every compressed member far below even the Euler load of a 20-member column of its section.
     */
    public double bucklingFactor(double[] u, double[] mode) {
        int n = 6 * nodes;
        double[] axial = new double[members.size()];
        double screen = 0;
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            axial[i] = forces(m, u).atA()[0];
            if (axial[i] < 0) {
                double ei = m.e() * Math.min(m.i1(), m.i2());
                screen = Math.max(screen, -axial[i] * m.length() * m.length() / (Math.PI * Math.PI * ei) * 4 * 20 * 20);
            }
        }
        if (screen < 0.2) return 0;
        LinearSystem k = assemble();
        double[] x = new double[n];
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < n; i++) x[i] = fixed[i] ? 0 : rnd.nextDouble() - 0.5;
        double shift = 0, mu = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int it = 0; it < 300; it++) {
                double[] y = geometricTimes(axial, x);
                for (int i = 0; i < n; i++) y[i] = fixed[i] ? 0 : -y[i];
                k.solve(y); // y = K⁻¹(−K_G x)
                for (int i = 0; i < n; i++) y[i] += shift * x[i];
                double num = 0, den = 0;
                for (int i = 0; i < n; i++) {
                    num += y[i] * x[i];
                    den += x[i] * x[i];
                }
                double next = num / den, norm = 0;
                for (double v : y) norm += v * v;
                norm = Math.sqrt(norm);
                if (norm == 0) return 0;
                for (int i = 0; i < n; i++) x[i] = y[i] / norm;
                boolean done = it > 5 && Math.abs(next - mu) < 1e-6 * Math.abs(next);
                mu = next;
                if (done) break;
            }
            mu -= shift;
            if (mu > 0 || pass == 1) break;
            shift = -mu; // a tension eigenvalue dominated: shift it to zero and look for the compressive one
            for (int i = 0; i < n; i++) x[i] = fixed[i] ? 0 : rnd.nextDouble() - 0.5;
        }
        if (mode != null) System.arraycopy(x, 0, mode, 0, n);
        return Math.max(0, mu);
    }

    private double[] geometricTimes(double[] axial, double[] x) {
        double[] y = new double[x.length];
        for (int i = 0; i < members.size(); i++) {
            if (axial[i] == 0) continue;
            Member m = members.get(i);
            double[][] kg = localGeometric(m, axial[i]);
            for (int p = 0; p < 12; p++) {
                int gp = globalDof(m, p);
                double s = 0;
                for (int q = 0; q < 12; q++) if (kg[p][q] != 0) s += kg[p][q] * x[globalDof(m, q)];
                y[gp] += s;
            }
        }
        return y;
    }

    /** The elastic stiffness matrix (clamped dofs held by unit diagonals). */
    private LinearSystem assemble() {
        int n = 6 * nodes;
        LinearSystem k = LinearSystem.create(n);
        for (Member m : members) {
            double[][] kl = localStiffness(m);
            for (int p = 0; p < 12; p++) {
                int gp = globalDof(m, p);
                if (fixed[gp]) continue;
                for (int q = 0; q < 12; q++) {
                    int gq = globalDof(m, q);
                    if (fixed[gq] || kl[p][q] == 0) continue;
                    k.add(gp, gq, kl[p][q]);
                }
            }
        }
        double scale = 0;
        for (Member m : members) scale = Math.max(scale, m.e() * m.area() / m.length());
        for (int i = 0; i < n; i++) k.add(i, i, fixed[i] ? 1 : 1e-15 * Math.max(scale, 1));
        return k;
    }

    /** Solves K·u = f; returns the 6n displacement vector (clamped dofs zero). */
    public double[] solve() {
        int n = 6 * nodes;
        LinearSystem k = LinearSystem.create(n);
        for (Member m : members) {
            double[][] kl = localStiffness(m);
            for (int p = 0; p < 12; p++) {
                int gp = globalDof(m, p);
                if (fixed[gp]) continue;
                for (int q = 0; q < 12; q++) {
                    int gq = globalDof(m, q);
                    if (fixed[gq] || kl[p][q] == 0) continue;
                    k.add(gp, gq, kl[p][q]);
                }
            }
        }
        double[] u = new double[n];
        double scale = 0;
        for (Member m : members) scale = Math.max(scale, m.e() * m.area() / m.length());
        for (int i = 0; i < n; i++) {
            if (fixed[i]) {
                k.add(i, i, 1);
                u[i] = 0;
            } else {
                // a vanishing spring to ground keeps unconnected rotations (e.g. a lone node's torsion) solvable
                k.add(i, i, 1e-15 * Math.max(scale, 1));
                u[i] = load[i];
            }
        }
        try {
            k.solve(u);
        } catch (SingularMatrixException e) {
            throw new IllegalStateException("structure is a mechanism (not stable): " + e.getMessage(), e);
        }
        return u;
    }

    /** End forces of member m (local axes) from the displacement vector u. */
    public static MemberForces forces(Member m, double[] u) {
        double[][] kl = localStiffness(m);
        double[] ul = new double[12];
        for (int q = 0; q < 12; q++) ul[q] = u[globalDof(m, q)];
        double[] f = new double[12];
        for (int p = 0; p < 12; p++) {
            double s = 0;
            for (int q = 0; q < 12; q++) s += kl[p][q] * ul[q];
            f[p] = s;
        }
        // f = k·u are the forces the nodes apply to the member's ends; axial tension is N = −f[0] = f[6]
        double[] a = {-f[0], f[1], f[2], f[3], f[4], f[5]}, b = {f[6], f[7], f[8], f[9], f[10], f[11]};
        return new MemberForces(a, b);
    }
}
