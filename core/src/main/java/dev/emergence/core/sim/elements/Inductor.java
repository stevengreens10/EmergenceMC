package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Inductor with optional series winding resistance: v_a − v_b = R·i + dλ/dt, λ = L·i.
 * The branch current i (flowing a → b) is an explicit unknown. Copper loss i²R is deposited into
 * {@code thermal} if provided.
 */
public final class Inductor implements Element {
    private final Node a, b, thermal;
    private final double l, r;
    private int iIdx, slot;
    private double initialCurrent;

    public Inductor(Node a, Node b, double inductance, double seriesResistance, Node thermal) {
        if (!(inductance > 0)) throw new IllegalArgumentException("L must be > 0");
        this.a = a;
        this.b = b;
        this.l = inductance;
        this.r = seriesResistance;
        this.thermal = thermal;
    }

    public Inductor(Node a, Node b, double inductance) {
        this(a, b, inductance, 0.0, null);
    }

    public Inductor initialCurrent(double i) {
        this.initialCurrent = i;
        return this;
    }

    @Override
    public void setup(SetupContext ctx) {
        iIdx = ctx.allocUnknown("I(" + label() + ")", 1e-9);
        slot = ctx.allocState(l * 1e-9);
        if (initialCurrent != 0) ctx.setInitial(iIdx, initialCurrent);
    }

    public int currentIndex() {
        return iIdx;
    }

    public double initialCurrentValue() {
        return initialCurrent;
    }

    @Override
    public void load(LoadContext ctx) {
        double i = ctx.x(iIdx);
        double v = ctx.x(a.index()) - ctx.x(b.index());
        double vl = ctx.ddt(slot, l * i);
        // KCL
        ctx.addF(a.index(), i);
        ctx.addF(b.index(), -i);
        ctx.addJ(a.index(), iIdx, 1);
        ctx.addJ(b.index(), iIdx, -1);
        // branch equation
        ctx.addF(iIdx, v - r * i - vl);
        ctx.addJ(iIdx, a.index(), 1);
        ctx.addJ(iIdx, b.index(), -1);
        ctx.addJ(iIdx, iIdx, -r - ctx.alpha() * l);
        if (thermal != null && r > 0) {
            ctx.addF(thermal.index(), -i * i * r);
            ctx.addJ(thermal.index(), iIdx, -2 * i * r);
        }
    }

    public double current(StateView s) {
        return s.x(iIdx);
    }

    @Override
    public double storedEnergy(StateView s) {
        double i = s.x(iIdx);
        return 0.5 * l * i * i;
    }

    @Override
    public double untrackedLoss(StateView s) {
        double i = s.x(iIdx);
        return thermal == null ? i * i * r : 0.0;
    }
}
