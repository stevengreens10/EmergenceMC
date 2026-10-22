package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Dry (Coulomb) friction between two mechanical nodes, limited to τ_c (for a bearing τ_c = μ·F_n·r_bore), as a
 * Dahl bristle model (Dahl 1968; the LuGre model without its Stribeck term): the friction torque τ_b is a state that
 * builds up elastically with the relative motion over a pre-sliding deflection θ₀ and saturates at τ_c once the
 * surfaces slide,
 * <pre>  dτ_b/dt = (τ_c·Δω − |Δω|·τ_b) / θ₀ ,</pre>
 * so a part held by friction stays held — the bristles carry the load at rest — where a friction that is only a
 * function of the sliding speed lets it creep. θ₀ = ω_s × 1 ms (10⁻⁵ rad for bearings, 10⁻⁶ m for slides). The
 * bristles are damped as LuGre's are, in proportion to their own deflection rate: τ = τ_b + (θ₀/ω_s)·dτ_b/dt, which in
 * stick is the old law's slope τ_c/ω_s and vanishes once they slide — no hump above τ_c to break away through (a
 * damping in the sliding speed itself added up to 45 % there). |Δω| is smoothed as √(Δω² + ε²), ε = 10⁻⁴·ω_s, which
 * is never below |Δω| (so the bristles never settle above τ_c) and lets a held load relax only at ε/θ₀ = 0.1/s: a body
 * held at a fraction f of τ_c creeps at ≈ ε·f/(1 − f), 10⁴ times slower than the speed-only law. Dissipation τ·Δω becomes heat (the bristles' elastic energy, τ_c·θ₀/2, is negligible).
 * <p>
 * With {@code diag.stiction=false} the older law τ = τ_c·tanh(Δω/ω_s) is used: a stiff viscous damper below ~ω_s,
 * whose held bodies creep at ≲ ω_s·τ/τ_c.
 */
public final class CoulombFriction implements Element {
    private static final boolean STICTION = !"false".equals(System.getProperty("diag.stiction"));

    private final Node a, b, thermal;
    private double tauC;
    private final double ws, theta0;
    private int zIdx = -1, zSlot;

    public CoulombFriction(Node a, Node b, double frictionTorque, double velocityScale, Node thermal) {
        this.a = a;
        this.b = b;
        this.tauC = frictionTorque;
        this.ws = velocityScale;
        this.theta0 = velocityScale * 1e-3;
        this.thermal = thermal;
    }

    public void setFrictionTorque(double t) {
        this.tauC = t;
    }

    public double frictionTorque() {
        return tauC;
    }

    @Override
    public void setup(SetupContext ctx) {
        if (!STICTION) return;
        zIdx = ctx.allocUnknown("friction", 1e-9);
        zSlot = ctx.allocState(1e-9);
    }

    @Override
    public void load(LoadContext ctx) {
        // (stamped even when released, so the matrix pattern does not change when a brake is applied)
        double dw = ctx.x(a.index()) - ctx.x(b.index());
        double th = Math.tanh(dw / ws), sech2 = 1 - th * th;
        double tau, dtau;
        if (zIdx < 0) {
            tau = tauC * th;
            dtau = tauC * sech2 / ws;
            ctx.addFlow(a, b, tau);
            ctx.stampConductance(a, b, dtau);
        } else {
            double z = ctx.x(zIdx);
            // bristle state: dz/dt = (τ_c·Δω − |Δω|·z)/θ₀, |Δω| smoothed as √(Δω² + ε²) — never below |Δω|, or slow
            // sliding would settle the bristles above τ_c (Δω·tanh(Δω/ω_s) locked a fan-driven windmill at rest)
            double eps = 1e-4 * ws, abs = Math.sqrt(dw * dw + eps * eps), dabs = dw / abs;
            ctx.addF(zIdx, ctx.ddt(zSlot, z) - (tauC * dw - abs * z) / theta0);
            ctx.addJ(zIdx, zIdx, ctx.alpha() + abs / theta0);
            double dzdw = -(tauC - dabs * z) / theta0;
            ctx.addJ(zIdx, a.index(), dzdw);
            ctx.addJ(zIdx, b.index(), -dzdw);
            // the torque: the bristles' load and their damping, (θ₀/ω_s)·dz/dt
            tau = z + (tauC * dw - abs * z) / ws;
            dtau = (tauC - dabs * z) / ws;
            ctx.addFlow(a, b, tau);
            ctx.stampConductance(a, b, dtau);
            ctx.addFlowJ(a, b, zIdx, 1 - abs / ws);
            if (thermal != null) {
                int t = thermal.index();
                ctx.addF(t, -tau * dw);
                ctx.addJ(t, a.index(), -(dtau * dw + tau));
                ctx.addJ(t, b.index(), dtau * dw + tau);
                ctx.addJ(t, zIdx, -(1 - abs / ws) * dw);
            }
            return;
        }
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
        double dw = s.across(a, b), tau = torque(s);
        return new double[]{tau, tau * dw};
    }

    public double torque(StateView s) {
        double dw = s.across(a, b), th = Math.tanh(dw / ws);
        if (zIdx < 0) return tauC * th;
        double z = s.x(zIdx), eps = 1e-4 * ws;
        return z + (tauC * dw - Math.sqrt(dw * dw + eps * eps) * z) / ws;
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        return torque(s) * s.across(a, b);
    }
}
