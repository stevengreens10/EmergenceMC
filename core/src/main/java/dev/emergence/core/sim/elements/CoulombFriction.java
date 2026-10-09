package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Dry (Coulomb) friction between two mechanical nodes: τ = τ_c · tanh(Δω / ω_s).
 * <p>
 * The smooth tanh regularisation with a small velocity scale ω_s replaces the discontinuous sign
 * function, a standard approach that keeps the implicit solver well posed. Below ~ω_s the contact
 * behaves like a very stiff viscous damper (approximating stiction: a body held by friction creeps at
 * ≲ ω_s·τ/τ_c). For a bearing, τ_c = μ·F_n·r_bore. Dissipation τ·Δω becomes heat.
 */
public final class CoulombFriction implements Element {
    private final Node a, b, thermal;
    private double tauC;
    private final double ws;

    public CoulombFriction(Node a, Node b, double frictionTorque, double velocityScale, Node thermal) {
        this.a = a;
        this.b = b;
        this.tauC = frictionTorque;
        this.ws = velocityScale;
        this.thermal = thermal;
    }

    public void setFrictionTorque(double t) {
        this.tauC = t;
    }

    public double frictionTorque() {
        return tauC;
    }

    @Override
    public void load(LoadContext ctx) {
        // (stamped even when released, so the matrix pattern does not change when a brake is applied)
        double dw = ctx.x(a.index()) - ctx.x(b.index());
        double th = Math.tanh(dw / ws);
        double tau = tauC * th;
        double dtau = tauC * (1 - th * th) / ws;
        ctx.addFlow(a, b, tau);
        ctx.stampConductance(a, b, dtau);
        if (thermal != null) {
            int t = thermal.index();
            double p = tau * dw;
            double dp = dtau * dw + tau;
            ctx.addF(t, -p);
            ctx.addJ(t, a.index(), -dp);
            ctx.addJ(t, b.index(), dp);
        }
    }

    /** {friction torque [N·m], dissipated power [W]} now. */
    public double[] torqueAndPower(StateView s) {
        double dw = s.across(a, b), tau = tauC * Math.tanh(dw / ws);
        return new double[]{tau, tau * dw};
    }

    public double torque(StateView s) {
        return tauC * Math.tanh(s.across(a, b) / ws);
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double dw = s.across(a, b);
        return tauC * Math.tanh(dw / ws) * dw;
    }
}
