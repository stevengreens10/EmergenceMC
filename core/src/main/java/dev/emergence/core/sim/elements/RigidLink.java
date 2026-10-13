package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * A rigid rod pinned at each end to a body: a connecting rod, a four-bar linkage's coupler, a governor's arm. Each end
 * rides a body that is fixed, turns about a fixed axis (angle θ, a rotor), or slides along a fixed axis (displacement x)
 * — all in one frame (the ground, or a turning body both ends ride on). End A sits at P_A(q_A), end B at P_B(q_B); the
 * rod keeps |P_A − P_B| = L, the length it was built with.
 * <p>
 * Enforced by a Lagrange multiplier λ, the rod's tension, at velocity level with Baumgarte stabilisation:
 * <pre>ċ + c/τ = 0,   c = |d| − L,   ċ = d̂·(J_A q̇_A − J_B q̇_B),   d = P_A − P_B,   J = ∂P/∂q</pre>
 * (J = n × (P − p) on a rotor about axis n through p, the axis a on a slide). The tension pulls the ends together:
 * generalised forces Q_A = −λ d̂·J_A, Q_B = +λ d̂·J_B, whose power −λ·ċ = λc/τ vanishes with the drift — the rod does no
 * work, transmitting power exactly from one body to the other. τ = 10 ms pulls a drifted length back without stiffening
 * the system. The rod's own mass and its pins' friction are neglected (the bodies carry the masses).
 */
public final class RigidLink implements Element {
    /** How an end's body moves. */
    public enum Kind { FIXED, ROTOR, SLIDE }

    /** One end: its body's velocity node and position tracker (null when fixed), how it moves, and the pin's place. */
    public record End(Kind kind, Node node, AngleTracker pose, Vec3 axis, Vec3 pivot, Vec3 point) {
        public static End fixed(Vec3 point) {
            return new End(Kind.FIXED, null, null, null, null, point);
        }

        public static End rotor(Node omega, AngleTracker angle, Vec3 axis, Vec3 pivot, Vec3 point) {
            return new End(Kind.ROTOR, omega, angle, axis, pivot, point);
        }

        public static End slide(Node velocity, AngleTracker position, Vec3 axis, Vec3 point) {
            return new End(Kind.SLIDE, velocity, position, axis, null, point);
        }

        /** The pin's position at the body's pose q. */
        Vec3 at(double q) {
            return switch (kind) {
                case FIXED -> point;
                case ROTOR -> pivot.add(point.sub(pivot).rotateAbout(axis, q));
                case SLIDE -> point.add(axis.mul(q));
            };
        }

        /** ∂P/∂q at the pose q. */
        Vec3 jacobian(double q) {
            return switch (kind) {
                case FIXED -> Vec3.ZERO;
                case ROTOR -> axis.cross(at(q).sub(pivot));
                case SLIDE -> axis;
            };
        }

        double pose(StateView s) {
            return pose == null ? 0 : s.x(pose.angleIndex());
        }

        double rate(StateView s) {
            return node == null ? 0 : s.x(node.index());
        }
    }

    private static final double TAU = 0.01;
    private final End a, b;
    private final double length;
    private int lIdx;

    public RigidLink(End a, End b) {
        this.a = a;
        this.b = b;
        this.length = a.point.sub(b.point).length();
        if (!(length > 0)) throw new IllegalArgumentException("a link needs two distinct pins");
    }

    @Override
    public void setup(SetupContext ctx) {
        lIdx = ctx.allocUnknown("T(link)", 1e-6);
    }

    /** [constraint residual ċ + c/τ, d̂·J_A, d̂·J_B] at the given poses and rates. */
    private double[] eval(double qa, double qb, double va, double vb) {
        Vec3 d = a.at(qa).sub(b.at(qb));
        double len = Math.max(d.length(), 1e-12);
        Vec3 u = d.mul(1 / len);
        double ja = u.dot(a.jacobian(qa)), jb = u.dot(b.jacobian(qb));
        return new double[]{ja * va - jb * vb + (len - length) / TAU, ja, jb};
    }

    @Override
    public void load(LoadContext ctx) {
        double qa = a.pose(ctx), qb = b.pose(ctx), va = a.rate(ctx), vb = b.rate(ctx), lam = ctx.x(lIdx);
        double[] e0 = eval(qa, qb, va, vb);
        double ha = 1e-7 * Math.max(1, Math.abs(qa)), hb = 1e-7 * Math.max(1, Math.abs(qb));
        double[] ea = a.pose == null ? e0 : eval(qa + ha, qb, va, vb), eb = b.pose == null ? e0 : eval(qa, qb + hb, va, vb);
        // the constraint row
        ctx.addF(lIdx, e0[0]);
        if (a.node != null) {
            ctx.addJ(lIdx, a.node.index(), e0[1]);
            ctx.addJ(lIdx, a.pose.angleIndex(), (ea[0] - e0[0]) / ha);
        }
        if (b.node != null) {
            ctx.addJ(lIdx, b.node.index(), -e0[2]);
            ctx.addJ(lIdx, b.pose.angleIndex(), (eb[0] - e0[0]) / hb);
        }
        // the tension on each end: a force on a node is −addF, so +λ·d̂·J_A on A, −λ·d̂·J_B on B
        if (a.node != null) {
            int n = a.node.index();
            ctx.addF(n, lam * e0[1]);
            ctx.addJ(n, lIdx, e0[1]);
            ctx.addJ(n, a.pose.angleIndex(), lam * (ea[1] - e0[1]) / ha);
            if (b.pose != null) ctx.addJ(n, b.pose.angleIndex(), lam * (eb[1] - e0[1]) / hb);
        }
        if (b.node != null) {
            int n = b.node.index();
            ctx.addF(n, -lam * e0[2]);
            ctx.addJ(n, lIdx, -e0[2]);
            ctx.addJ(n, b.pose.angleIndex(), -lam * (eb[2] - e0[2]) / hb);
            if (a.pose != null) ctx.addJ(n, a.pose.angleIndex(), -lam * (ea[2] - e0[2]) / ha);
        }
    }

    /** The rod's tension [N] (negative: compression). */
    public double tension(StateView s) {
        return s.x(lIdx);
    }

    /** The rod's present length [m] (its built length, less the drift the stabilisation is pulling back). */
    public double length(StateView s) {
        return a.at(a.pose(s)).sub(b.at(b.pose(s))).length();
    }

    /** Where the pins are now [m]: {end A (x, y, z), end B}. */
    public double[] pins(StateView s) {
        Vec3 pa = a.at(a.pose(s)), pb = b.at(b.pose(s));
        return new double[]{pa.x(), pa.y(), pa.z(), pb.x(), pb.y(), pb.z()};
    }

    public double builtLength() {
        return length;
    }

    @Override
    public String label() {
        return "rigid link";
    }
}
