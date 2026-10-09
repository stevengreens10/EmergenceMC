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
 * kinematics (no shear deformation — a member of length ≈ its depth is somewhat too stiff, ≲ 30 %), no
 * buckling (a separate stability check).
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
