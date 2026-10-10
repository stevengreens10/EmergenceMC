package dev.emergence.core.sim.elements;

import dev.emergence.core.materials.Material;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Eddy-current drag between a conductor and a magnet moving relative to one another (Arago's disc, eddy-current
 * brakes, a magnet falling through a copper tube). Motion of a conducting sheet across a field induces an EMF v×B in
 * it; the currents it drives dissipate σ·E² and push back on the motion with F = σ·t·A·B_n²·v per unit of sheet in the
 * field — a viscous coupling between the two bodies' speeds, its coefficient c(x) depending on where the moving one
 * is (the sheet passing under the pole): τ = c(x)·(ω_a − ω_b). The dissipated power heats the conductor. The
 * coefficient is tabulated at the reference resistivity and scaled by ρ_ref/ρ(T) at the conductor's temperature.
 * <p>
 * Low-speed regime: valid while the magnetic Reynolds number μ0σvt ≪ 1 (the induced currents' own field is
 * negligible); faster, real drag peaks and falls, which this does not show.
 */
public final class EddyCurrentDrag implements Element {
    private final Node a, b, thermal;
    private final AngleTracker pose;
    private final double[] table;
    private final double lo, hi, rhoRef;
    private final boolean periodic;
    private final Material conductor;

    /**
     * @param a         the conductor's body node (or a reference node if it is fixed)
     * @param b         the magnet's body node (or a reference node)
     * @param thermal   the conductor's thermal node (receives the heat), or null
     * @param pose      the moving body whose angle or travel sets the coefficient (null: constant, table[0])
     * @param table     c(x) at evenly spaced x from lo to hi [N·m·s or N·s/m]
     * @param periodic  true if x is an angle (the table spans one turn)
     */
    public EddyCurrentDrag(Node a, Node b, Node thermal, AngleTracker pose, double[] table, double lo, double hi, boolean periodic,
                           Material conductor, double rhoRef) {
        this.a = a;
        this.b = b;
        this.thermal = thermal;
        this.pose = pose;
        this.table = table;
        this.lo = lo;
        this.hi = hi;
        this.periodic = periodic;
        this.conductor = conductor;
        this.rhoRef = rhoRef;
    }

    /** The drag coefficient at pose x, before the temperature correction. */
    public double coefficient(double x) {
        int n = table.length;
        if (pose == null || n == 1) return table[0];
        double f;
        if (periodic) {
            double t = ((x - lo) % (hi - lo) + (hi - lo)) % (hi - lo);
            f = t / (hi - lo) * n;
            int i = (int) Math.floor(f) % n, j = (i + 1) % n;
            double w = f - Math.floor(f);
            return table[i] * (1 - w) + table[j] * w;
        }
        f = (Math.max(lo, Math.min(hi, x)) - lo) / (hi - lo) * (n - 1);
        int i = Math.min(n - 2, (int) Math.floor(f));
        double w = f - i;
        return table[i] * (1 - w) + table[i + 1] * w;
    }

    private double c(StateView s) {
        double x = pose == null ? 0 : s.x(pose.angleIndex());
        double t = thermal == null ? 293.15 : s.x(thermal.index());
        return coefficient(x) * rhoRef / conductor.resistivity(t);
    }

    @Override
    public void load(LoadContext ctx) {
        double c = c(ctx);
        double dw = ctx.x(a.index()) - ctx.x(b.index());
        ctx.addFlow(a, b, c * dw);
        ctx.stampConductance(a, b, c);
        if (thermal != null) {
            int t = thermal.index();
            ctx.addF(t, -c * dw * dw);
            ctx.addJ(t, a.index(), -2 * c * dw);
            ctx.addJ(t, b.index(), 2 * c * dw);
        }
    }

    /** Power dissipated in the conductor now [W]. */
    public double power(StateView s) {
        double dw = s.across(a, b);
        return c(s) * dw * dw;
    }

    @Override
    public double untrackedLoss(StateView s) {
        return thermal == null ? power(s) : 0.0;
    }

    @Override
    public String label() {
        return "eddy-current drag";
    }
}
