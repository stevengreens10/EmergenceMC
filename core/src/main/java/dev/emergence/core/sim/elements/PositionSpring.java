package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * A linear spring between two moving ends (or one end and the fixed frame), acting on their positions: the extension
 * e = s_a·x_a + s_b·x_b (each end's tracked displacement projected on the spring's axis with sign s) gives the force
 * F = −k·e on the ends, and stores ½k·e²; a spring fitted δ shorter than its free length (preloaded) starts from e = −δ
 * and pushes its ends apart by kδ where they were placed. Positions rather than an internal deflection state
 * mean a reloaded machine resumes with exactly the spring force its positions imply.
 */
public final class PositionSpring implements Element {
    private final Node na, nb;
    private final AngleTracker ta, tb;
    private final double sa, sb, k, rest;

    /**
     * @param na,ta,sa velocity node, position tracker and projection sign of end a (null node: fixed)
     * @param k        stiffness [N/m]
     */
    public PositionSpring(Node na, AngleTracker ta, double sa, Node nb, AngleTracker tb, double sb, double k) {
        this(na, ta, sa, nb, tb, sb, k, 0);
    }

    /** @param preload δ, how much shorter than its free length the spring was fitted [m] */
    public PositionSpring(Node na, AngleTracker ta, double sa, Node nb, AngleTracker tb, double sb, double k, double preload) {
        this.rest = -preload;
        this.na = na;
        this.ta = ta;
        this.sa = sa;
        this.nb = nb;
        this.tb = tb;
        this.sb = sb;
        this.k = k;
    }

    private double extension(StateView s) {
        double e = rest;
        if (ta != null) e += sa * s.x(ta.angleIndex());
        if (tb != null) e += sb * s.x(tb.angleIndex());
        return e;
    }

    @Override
    public void load(LoadContext ctx) {
        double e = extension(ctx), f = -k * e;
        // generalised force on each end: s·F (a force on the node is −addF)
        if (na != null) {
            ctx.addF(na.index(), -sa * f);
            ctx.addJ(na.index(), ta.angleIndex(), sa * k * sa);
            if (tb != null) ctx.addJ(na.index(), tb.angleIndex(), sa * k * sb);
        }
        if (nb != null) {
            ctx.addF(nb.index(), -sb * f);
            ctx.addJ(nb.index(), tb.angleIndex(), sb * k * sb);
            if (ta != null) ctx.addJ(nb.index(), ta.angleIndex(), sb * k * sa);
        }
    }

    /** Spring force (positive in tension... negative extension = compression) [N]. */
    public double force(StateView s) {
        return -k * extension(s);
    }

    @Override
    public double storedEnergy(StateView s) {
        double e = extension(s);
        return 0.5 * k * e * e;
    }
}
