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
        this(a, b, pitchRadiusA, pitchRadiusB, internal ? -1 : 1,
                frictionCoefficient * Math.PI * (1.0 / teethA + 1.0 / teethB), thermal);
    }

    /**
     * General form: constraint r_a·ω_a + s·r_b·v_b = 0 and a friction torque on a of lossFactor·r_a·|F|.
     */
    private GearMesh(Node a, Node b, double ra, double rb, double s, double lossFactor, Node thermal) {
        this.a = a;
        this.b = b;
        this.ra = ra;
        this.rb = rb;
        this.s = s;
        this.lossFactor = lossFactor;
        this.thermal = thermal;
    }

    /**
     * Rack and pinion: a pinion of pitch radius r and z teeth (node a, angular velocity) driving a rack on a slide
     * (node b, velocity): r·ω + s·v = 0, s = ±1 from the geometry. Tooth friction as for a gear pair with the rack's
     * 1/z = 0.
     */
    public static GearMesh rackAndPinion(Node pinion, Node rack, double pitchRadius, int teeth, double s,
                                         double frictionCoefficient, Node thermal) {
        return new GearMesh(pinion, rack, pitchRadius, 1, s, frictionCoefficient * Math.PI / teeth, thermal);
    }

    /**
     * Lead screw: the screw (node a, angular velocity) and its nut on a slide (node b, velocity) with
     * (lead/2π)·ω + s·v = 0. The axial force F on the nut presses the thread flanks with N ≈ |F|/(cos λ·cos α) (lead
     * angle λ = atan(lead/π d_m), flank half-angle α), sliding at ω·r_m/cos λ, so friction takes the torque
     * μ·|F|·r_m/(cos α·cos²λ): efficiency η = sin λ cos λ/(sin λ cos λ + μ/cos α) (the exact η = tan λ(1 − μ' tan λ)/(tan λ
     * + μ') to first order in μ' = μ/cos α), and the screw holds a load by itself once μ' ≳ tan λ.
     */
    public static GearMesh leadScrew(Node screw, Node nut, double lead, double meanDiameter, double flankHalfAngle,
                                     double s, double frictionCoefficient, Node thermal) {
        double rl = lead / (2 * Math.PI), rm = meanDiameter / 2, lam = Math.atan(lead / (Math.PI * meanDiameter));
        double torquePerForce = frictionCoefficient / Math.cos(flankHalfAngle) * rm / (Math.cos(lam) * Math.cos(lam));
        return new GearMesh(screw, nut, rl, 1, s, torquePerForce / rl, thermal);
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

    /** Constraint sign s (−1 internal mesh, +1 external; set by geometry for racks and screws). */
    public double sense() {
        return s;
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
