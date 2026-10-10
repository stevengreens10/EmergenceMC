package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * A pair of contacts closed by a slide: conductors on a slide and on the frame (or another body) that face each other
 * across the slide's direction of travel meet only at the end of its travel towards one another — a relay's or a
 * limit switch's contacts. The contact conductance rises smoothly from a leakage to its closed value over the last
 * {@code width} of travel: G(x) = G_off + (G_on − G_off)·s((x − (end − width))/width) for a contact closing as x rises
 * to {@code end} (s the smoothstep), mirrored for one closing as x falls. Its I²R heats the contact.
 */
public final class TravelContact implements Element {
    private final Node a, b, thermal;
    private final AngleTracker slide;
    private final double end, sense, width, gOn, gOff;

    /**
     * @param sense +1 if the contacts close as the slide's travel x rises to {@code end}, −1 as it falls to it
     */
    public TravelContact(Node a, Node b, AngleTracker slide, double end, double sense, double width, double rClosed, Node thermal) {
        this.a = a;
        this.b = b;
        this.slide = slide;
        this.end = end;
        this.sense = sense;
        this.width = width;
        this.gOn = 1 / rClosed;
        this.gOff = 1e-9;
        this.thermal = thermal;
    }

    /** Closure 0 (open) … 1 (closed) at travel x, and its slope. */
    private double[] closure(double x) {
        double u = sense * (x - end) / width + 1;
        if (u <= 0) return new double[]{0, 0};
        if (u >= 1) return new double[]{1, 0};
        return new double[]{u * u * (3 - 2 * u), 6 * u * (1 - u) * sense / width};
    }

    public double conductance(StateView s) {
        return gOff + (gOn - gOff) * closure(s.x(slide.angleIndex()))[0];
    }

    @Override
    public void load(LoadContext ctx) {
        int xi = slide.angleIndex();
        double[] cl = closure(ctx.x(xi));
        double g = gOff + (gOn - gOff) * cl[0], dg = (gOn - gOff) * cl[1];
        double v = ctx.x(a.index()) - ctx.x(b.index());
        ctx.addFlow(a, b, g * v);
        ctx.stampConductance(a, b, g);
        if (dg != 0) ctx.addFlowJ(a, b, xi, dg * v);
        if (thermal != null) {
            int t = thermal.index();
            ctx.addF(t, -g * v * v);
            ctx.addJ(t, a.index(), -2 * g * v);
            ctx.addJ(t, b.index(), 2 * g * v);
            if (dg != 0) ctx.addJ(t, xi, -dg * v * v);
        }
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double v = s.across(a, b);
        return conductance(s) * v * v;
    }

    @Override
    public String label() {
        return "travel contact";
    }
}
