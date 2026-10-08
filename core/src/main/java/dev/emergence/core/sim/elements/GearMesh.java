package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * Involute spur-gear mesh between two coaxial-parallel rotating bodies.
 * <p>
 * Kinematics are enforced exactly by a Lagrange multiplier F (the tangential tooth force along the
 * pitch line): r_a·ω_a + s·r_b·ω_b = 0, with s = +1 for an external mesh (opposite rotation) and
 * s = −1 for an internal (ring) mesh. The ideal reaction torques are r_a·F and s·r_b·F, which transmit
 * power without loss by construction (F·(r_a ω_a + s r_b ω_b) = 0).
 * <p>
 * Sliding friction between teeth dissipates P = f·|F|·|v_pitch| with the classic approximation
 * f ≈ μ·π·(1/z_a + 1/z_b) (Buckingham; contact ratio ≈ 1.5–1.8). This loss appears as a torque
 * opposing the motion of gear a and is delivered to the thermal node as heat.
 * Backlash and tooth compliance are not yet modelled (see KNOWN_LIMITATIONS.md).
 */
public final class GearMesh implements Element {
    private final Node a, b, thermal;
    private final double ra, rb, s, lossFactor;
    private int fIdx;
    private static final double WS = 1e-3;

    public GearMesh(Node a, Node b, double pitchRadiusA, double pitchRadiusB, int teethA, int teethB,
                    boolean internal, double frictionCoefficient, Node thermal) {
        this.a = a;
        this.b = b;
        this.ra = pitchRadiusA;
        this.rb = pitchRadiusB;
        this.s = internal ? -1.0 : 1.0;
        this.lossFactor = frictionCoefficient * Math.PI * (1.0 / teethA + 1.0 / teethB);
        this.thermal = thermal;
    }

    @Override
    public void setup(SetupContext ctx) {
        fIdx = ctx.allocUnknown("F(gear)", 1e-6);
    }

    @Override
    public void load(LoadContext ctx) {
        double f = ctx.x(fIdx);
        int ia = a.index(), ib = b.index();
        ctx.addF(ia, ra * f);
        ctx.addJ(ia, fIdx, ra);
        ctx.addF(ib, s * rb * f);
        ctx.addJ(ib, fIdx, s * rb);
        // constraint row
        ctx.addF(fIdx, ra * ctx.x(ia) + s * rb * ctx.x(ib));
        ctx.addJ(fIdx, ia, ra);
        ctx.addJ(fIdx, ib, s * rb);
        if (lossFactor > 0) {
            double wa = ctx.x(ia);
            double mag = Math.sqrt(f * f + 1e-6);
            double th = Math.tanh(wa / WS);
            double tl = lossFactor * ra * mag * th;
            ctx.addF(ia, tl);
            ctx.addJ(ia, fIdx, lossFactor * ra * th * f / mag);
            ctx.addJ(ia, ia, lossFactor * ra * mag * (1 - th * th) / WS);
            if (thermal != null) {
                int t = thermal.index();
                double p = tl * wa;
                ctx.addF(t, -p);
                ctx.addJ(t, fIdx, -lossFactor * ra * th * f / mag * wa);
                ctx.addJ(t, ia, -(tl + lossFactor * ra * mag * (1 - th * th) / WS * wa));
            }
        }
    }

    public double toothForce(StateView st) {
        return st.x(fIdx);
    }

    public double ratio() {
        return rb / ra;
    }

    @Override
    public double untrackedLoss(StateView st) {
        if (thermal != null || lossFactor == 0) return 0;
        double f = st.x(fIdx);
        double wa = st.x(a.index());
        return lossFactor * ra * Math.sqrt(f * f + 1e-6) * Math.tanh(wa / WS) * wa;
    }
}
