package dev.emergence.core.sim.elements;

import dev.emergence.core.materials.Material;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Conduction between the centres of two touching parts p and q: the half of each from its centre to the contact face,
 * in series, as two {@link Conductor}s joined at a contact node would be — but without the node. Nothing else meets
 * at a contact face, so the same current crosses both halves and the node only cost an unknown and a load (a circuit
 * of blocks and plates has one per contact: 92 of the loudspeaker's 505 unknowns).
 * <p>
 * Each half is real material, R_k(T_k) = ρ_k(T_k)·L_k/A_k at its own part's temperature, its Joule heat I²R_k
 * delivered to that part. With Seebeck coefficients S_p, S_q the contact lies at T_c = λ·T_p + (1 − λ)·T_q (λ from the
 * halves' thermal conductances) and the current obeys
 * <pre>  I·(R_p + R_q) = V_a − V_b − S_p·(T_c − T_p) − S_q·(T_q − T_c),</pre>
 * carrying Peltier heat S_p·T_p·I out of p, (S_p − S_q)·T_c·I released at the contact (shared λ : 1 − λ), and
 * S_q·T_q·I into q — the two halves' terms exactly, so a junction of unlike conductors is still a thermocouple and a
 * Peltier cooler, and the energy balance is the halves'.
 */
public final class ContactConductor implements Element {
    private final Node a, b, tp, tq;
    private final Material mp, mq;
    private final double geomP, geomQ, sp, sq, lambda, fixedT;

    /**
     * @param geomP, geomQ the halves' length over area [1/m]
     * @param tp, tq       the parts' thermal nodes (null: at {@code fixedTemperature}, the heat untracked)
     * @param sp, sq       Seebeck coefficients [V/K] (0 for none)
     * @param lambda       the contact temperature's weight on p
     */
    public ContactConductor(Node a, Node b, Material mp, double geomP, Node tp, double sp,
                            Material mq, double geomQ, Node tq, double sq, double lambda, double fixedTemperature) {
        this.a = a;
        this.b = b;
        this.mp = mp;
        this.mq = mq;
        this.geomP = geomP;
        this.geomQ = geomQ;
        this.tp = tp;
        this.tq = tq;
        this.sp = sp;
        this.sq = sq;
        this.lambda = lambda;
        this.fixedT = fixedTemperature;
    }

    private double tempP(StateView s) {
        return tp != null ? s.x(tp.index()) : fixedT;
    }

    private double tempQ(StateView s) {
        return tq != null ? s.x(tq.index()) : fixedT;
    }

    /** The halves' resistances and their temperature slopes, at the temperatures last asked (held in a fast phase). */
    private double rpAt = Double.NaN, rqAt = Double.NaN, rp, rq, drp, drq;

    private void resistances(double t1, double t2) {
        if (t1 != rpAt) {
            rp = mp.resistivity(t1) * geomP;
            drp = mp.resistivityDerivative(t1) * geomP;
            rpAt = t1;
        }
        if (t2 != rqAt) {
            rq = mq.resistivity(t2) * geomQ;
            drq = mq.resistivityDerivative(t2) * geomQ;
            rqAt = t2;
        }
    }

    /** The thermoelectric EMF a → b [V]. */
    private double emf(double t1, double t2) {
        double tc = lambda * t1 + (1 - lambda) * t2;
        return sp * (tc - t1) + sq * (t2 - tc);
    }

    @Override
    public void load(LoadContext ctx) {
        double t1 = tempP(ctx), t2 = tempQ(ctx);
        resistances(t1, t2);
        double r = rp + rq, g = 1 / r, d = sp - sq, tc = lambda * t1 + (1 - lambda) * t2;
        int ia = a.index(), ib = b.index(), jp = tp != null ? tp.index() : -1, jq = tq != null ? tq.index() : -1;
        double i = g * (ctx.x(ia) - ctx.x(ib) - emf(t1, t2));
        ctx.addFlow(a, b, i);
        ctx.stampConductance(a, b, g);
        // heat: Joule in each half; Peltier out of p, released at the contact, into q
        double fp = -i * i * rp + sp * t1 * i - lambda * d * tc * i;
        double fq = -i * i * rq - (1 - lambda) * d * tc * i - sq * t2 * i;
        if (jp >= 0) ctx.addF(jp, fp);
        if (jq >= 0) ctx.addF(jq, fq);
        if (jp < 0 && jq < 0) return;
        // the current's temperature slopes: through the EMF (dE/dT) and the resistance (−I·dR/dT), over R
        double diP = (-(d * lambda - sp) - i * drp) * g, diQ = (-(d * (1 - lambda) + sq) - i * drq) * g;
        boolean heldP = jp >= 0 && ctx.held(jp), heldQ = jq >= 0 && ctx.held(jq);
        // electrical rows' temperature columns (left out of a fast phase's matrix when held)
        if (jp >= 0 && !heldP) {
            ctx.addJ(ia, jp, diP);
            ctx.addJ(ib, jp, -diP);
        }
        if (jq >= 0 && !heldQ) {
            ctx.addJ(ia, jq, diQ);
            ctx.addJ(ib, jq, -diQ);
        }
        // heat rows: ∂f/∂x = (∂f/∂I)·∂I/∂x + the explicit temperature terms
        double fpI = -2 * i * rp + sp * t1 - lambda * d * tc, fqI = -2 * i * rq - (1 - lambda) * d * tc - sq * t2;
        if (jp >= 0) {
            double dpp = fpI * diP - i * i * drp + sp * i - lambda * d * lambda * i;
            double dpq = fpI * diQ - lambda * d * (1 - lambda) * i;
            if (heldP) {
                ctx.addJ(jp, jp, dpp); // the coupling's stiffness the multirate solver watches
            } else {
                ctx.addJ(jp, ia, fpI * g);
                ctx.addJ(jp, ib, -fpI * g);
                ctx.addJ(jp, jp, dpp);
                if (jq >= 0) ctx.addJ(jp, jq, dpq);
            }
        }
        if (jq >= 0) {
            double dqq = fqI * diQ - i * i * drq - (1 - lambda) * d * (1 - lambda) * i - sq * i;
            double dqp = fqI * diP - (1 - lambda) * d * lambda * i;
            if (heldQ) {
                ctx.addJ(jq, jq, dqq);
            } else {
                ctx.addJ(jq, ia, fqI * g);
                ctx.addJ(jq, ib, -fqI * g);
                ctx.addJ(jq, jq, dqq);
                if (jp >= 0) ctx.addJ(jq, jp, dqp);
            }
        }
    }

    public double resistance(StateView s) {
        return mp.resistivity(tempP(s)) * geomP + mq.resistivity(tempQ(s)) * geomQ;
    }

    public double current(StateView s) {
        return (s.across(a, b) - emf(tempP(s), tempQ(s))) / resistance(s);
    }

    @Override
    public double untrackedLoss(StateView s) {
        double i = current(s), loss = 0;
        if (tp == null) loss += i * i * mp.resistivity(fixedT) * geomP;
        if (tq == null) loss += i * i * mq.resistivity(fixedT) * geomQ;
        return loss;
    }
}
