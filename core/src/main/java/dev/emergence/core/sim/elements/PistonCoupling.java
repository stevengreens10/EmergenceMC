package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Couples the gas in a piston cylinder to a rotating crankshaft through slider-crank kinematics.
 * <ul>
 *   <li>The gas does work p·dV/dt on the piston: it leaves the gas's energy balance.</li>
 *   <li>The back of the piston faces the surrounding air at p_amb, so the shaft receives the torque
 *       τ = (p − p_amb)·dV/dθ; the remaining p_amb·dV/dt is work done on the atmosphere (an external
 *       reservoir in the energy ledger).</li>
 *   <li>Piston-ring friction F_ring acts along the stroke: torque F_ring·|ds/dθ| against the motion
 *       (regularised through ω = 0), its power deposited as heat in the cylinder wall.</li>
 * </ul>
 * The connecting rod and piston are massless (their inertia is small next to a flywheel), so the
 * coupling is exactly power conserving: (p − p_amb)·dV/dt + p_amb·dV/dt = p·dV/dt.
 */
public final class PistonCoupling implements Element {
    private final Node gasP, gasT, omega, wall;
    private final AngleTracker tracker;
    private final PistonKinematics crank;
    private final double pAmb, ringForce;

    public PistonCoupling(Node gasP, Node gasT, Node omega, AngleTracker tracker, PistonKinematics crank, double ambientPressure,
                          double ringFriction, Node wall) {
        this.gasP = gasP;
        this.gasT = gasT;
        this.omega = omega;
        this.tracker = tracker;
        this.crank = crank;
        this.pAmb = ambientPressure;
        this.ringForce = ringFriction;
        this.wall = wall;
    }

    public PistonKinematics crank() {
        return crank;
    }

    public AngleTracker tracker() {
        return tracker;
    }

    @Override
    public void load(LoadContext ctx) {
        int ia = tracker.angleIndex();
        double th = ctx.x(ia), w = ctx.x(omega.index()), p = ctx.x(gasP.index());
        double dv = crank.dVolume(th), d2v = crank.d2Volume(th);
        double dvdt = dv * w;
        // gas energy: p dV/dt leaves the gas
        ctx.addF(gasT.index(), p * dvdt);
        ctx.addJ(gasT.index(), gasP.index(), dvdt);
        ctx.addJ(gasT.index(), omega.index(), p * dv);
        ctx.addJ(gasT.index(), ia, p * d2v * w);
        // shaft: the gas pushes the crank (torque taken from the node is the negative of that)
        double tau = (p - pAmb) * dv;
        ctx.addF(omega.index(), -tau);
        ctx.addJ(omega.index(), gasP.index(), -dv);
        ctx.addJ(omega.index(), ia, -(p - pAmb) * d2v);
        // ring friction against the motion, heat into the wall
        if (ringForce > 0) {
            double ds = Math.abs(crank.dDisplacement(th));
            double sw = Math.tanh(w / 0.05);
            double tf = ringForce * ds * sw;
            ctx.addF(omega.index(), tf);
            ctx.addJ(omega.index(), omega.index(), ringForce * ds * (1 - sw * sw) / 0.05);
            if (wall != null) {
                ctx.addF(wall.index(), -tf * w);
                ctx.addJ(wall.index(), omega.index(), -(ringForce * ds * (sw + w * (1 - sw * sw) / 0.05)));
            }
        }
    }

    /** Piston displacement from TDC [m]. */
    public double displacement(StateView s) {
        return crank.displacement(s.x(tracker.angleIndex()));
    }

    /** Crank angle from TDC [rad]. */
    public double crankAngle(StateView s) {
        return crank.psi(s.x(tracker.angleIndex()));
    }

    @Override
    public double externalPower(StateView s) {
        // expanding against the atmosphere does work on it
        return -pAmb * crank.dVolume(s.x(tracker.angleIndex())) * s.x(omega.index());
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (ringForce <= 0 || wall != null) return 0;
        double w = s.x(omega.index());
        return ringForce * Math.abs(crank.dDisplacement(s.x(tracker.angleIndex()))) * Math.tanh(w / 0.05) * w;
    }

    @Override
    public String label() {
        return "PistonCoupling(A=" + crank.area() + ")";
    }
}
