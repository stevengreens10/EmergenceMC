package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Linear elastic compliance between two mechanical nodes (torsion spring / shaft torsional
 * stiffness / linear spring). The transmitted torque τ (force F) is an explicit unknown obeying
 * d(τ/k)/dt = ω_a − ω_b, i.e. Hooke's law in rate form. Stored energy τ²/(2k).
 * <p>
 * A shaft of length L, polar moment J_p and shear modulus G has k = G·J_p/L (Saint-Venant torsion).
 */
public final class Spring implements Element {
    private final Node a, b;
    private final double k;
    private int tIdx, slot;
    private double preload;

    public Spring(Node a, Node b, double stiffness) {
        if (!(stiffness > 0)) throw new IllegalArgumentException("k must be > 0");
        this.a = a;
        this.b = b;
        this.k = stiffness;
    }

    /** Initial transmitted torque/force (i.e. initial deflection k·δ0). */
    public Spring preload(double torque) {
        this.preload = torque;
        return this;
    }

    @Override
    public void setup(SetupContext ctx) {
        tIdx = ctx.allocUnknown("tau(spring)", 1e-9);
        slot = ctx.allocState(1e-9 / k + 1e-12);
        if (preload != 0) ctx.setInitial(tIdx, preload);
    }

    @Override
    public void load(LoadContext ctx) {
        double tau = ctx.x(tIdx);
        ctx.addF(a.index(), tau);
        ctx.addF(b.index(), -tau);
        ctx.addJ(a.index(), tIdx, 1);
        ctx.addJ(b.index(), tIdx, -1);
        double defRate = ctx.ddt(slot, tau / k);
        ctx.addF(tIdx, defRate - (ctx.x(a.index()) - ctx.x(b.index())));
        ctx.addJ(tIdx, tIdx, ctx.alpha() / k);
        ctx.addJ(tIdx, a.index(), -1);
        ctx.addJ(tIdx, b.index(), 1);
    }

    public double torque(StateView s) {
        return s.x(tIdx);
    }

    @Override
    public double storedEnergy(StateView s) {
        double t = s.x(tIdx);
        return 0.5 * t * t / k;
    }
}
