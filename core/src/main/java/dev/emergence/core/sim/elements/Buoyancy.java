package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

import java.util.List;

/**
 * Still water on the parts of a slide: Archimedes' upthrust ρ·g·V_sub on each part, along the guide, and the water's
 * drag on the moving part, ½·ρ·C_d·A·v|v| over its submerged fraction.
 *
 * <p>Each part is taken as a prism of its displaced volume spread evenly over its height h (displaced area
 * A = V / h), its bottom at {@code bottom + a_y·x} for the slide's displacement x, its submerged depth the water's
 * surface less its bottom, clamped to [0, h] — C¹, the corners rounded over a millimetre. With the water's level fixed
 * (an open pond, an external reservoir) the upthrust is conservative: its potential ρ·g·A·∫ depth is this element's
 * stored energy, so the ledger balances as the float bobs. The drag (C_d of a bluff body, ≈ 1) is lost to the
 * water; so are the waves a float makes as it heaves through the surface, which damp its bobbing far more than drag
 * does at a few millimetres a second: the radiation damping of a small body heaving in deep water,
 * B = ρ·ω³·A_w²/(2π·g) for a waterplane area A_w (Newman, <i>Marine Hydrodynamics</i>, §6.18), taken at the float's
 * own heave frequency ω = √(ρ·g·A_w/m) and applied while the surface cuts the part. Not modelled: the added mass of
 * the water a part accelerates; the pond's walls reflecting the waves; currents.
 */
public final class Buoyancy implements Element {
    /** One part on the slide: its displaced volume's footprint, height, bottom at x = 0, the water's surface over it, and its section across the guide. */
    public record Immersed(double area, double height, double bottom, double surface, double frontal) {}

    private static final double KNEE = 1e-3, DRAG_CD = 1.0, V_SMOOTH = 1e-3;
    private final Node node;
    private final AngleTracker tracker;
    private final double ay, rho, g, omega;
    private final List<Immersed> parts;

    /**
     * @param ay  the guide's axis' upward component (its displacement x raises the parts by ay·x)
     * @param rho the water's density [kg/m³]
     * @param g    gravity [m/s²]
     * @param mass the slide's moving mass [kg] (its heave frequency sets the waves' damping)
     */
    public Buoyancy(Node node, AngleTracker tracker, double ay, double rho, double g, double mass, List<Immersed> parts) {
        this.node = node;
        this.tracker = tracker;
        this.ay = ay;
        this.rho = rho;
        this.g = g;
        this.parts = List.copyOf(parts);
        double aw = 0;
        for (Immersed p : parts) aw += p.area();
        this.omega = Math.sqrt(rho * g * aw * ay * ay / Math.max(mass, 1e-6));
    }

    /** The waves' damping coefficient of a part whose surface clearance is u [N·s/m along the guide]. */
    private double radiation(Immersed p, double u) {
        double cut = depth(u, p.height())[1]; // 1 while the surface crosses the part, 0 above or under it
        return cut * rho * omega * omega * omega * p.area() * p.area() / (2 * Math.PI * g) * ay * ay;
    }

    /** C¹ ramp: 0 below 0, u²/2k over the knee k, then u − k/2; [value, slope, integral]. */
    private static double[] ramp(double u) {
        if (u <= 0) return new double[]{0, 0, 0};
        if (u < KNEE) return new double[]{u * u / (2 * KNEE), u / KNEE, u * u * u / (6 * KNEE)};
        return new double[]{u - KNEE / 2, 1, u * u / 2 - KNEE * u / 2 + KNEE * KNEE / 6};
    }

    /** Submerged depth of a part whose surface clearance is u (the surface less its bottom): [depth, d depth/du, ∫depth du]. */
    private static double[] depth(double u, double h) {
        double[] a = ramp(u), b = ramp(u - h);
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private double clearance(Immersed p, double x) {
        return p.surface() - (p.bottom() + ay * x);
    }

    @Override
    public void load(LoadContext ctx) {
        int ix = tracker.angleIndex(), iv = node.index();
        double x = ctx.x(ix), v = ctx.x(iv);
        double sv = Math.sqrt(v * v + V_SMOOTH * V_SMOOTH);
        double f = 0, dfdx = 0, dfdv = 0;
        for (Immersed p : parts) {
            double[] d = depth(clearance(p, x), p.height());
            // upthrust along the guide; the clearance falls as the part rises (du/dx = −ay)
            f += rho * g * p.area() * d[0] * ay;
            dfdx += -rho * g * p.area() * d[1] * ay * ay;
            // drag over the submerged fraction
            double frac = d[0] / p.height(), dfrac = -d[1] * ay / p.height(), cd = 0.5 * rho * DRAG_CD * p.frontal();
            f += -cd * frac * v * sv;
            dfdx += -cd * dfrac * v * sv;
            dfdv += -cd * frac * (sv + v * v / sv);
            double b = radiation(p, clearance(p, x));
            f += -b * v;
            dfdv += -b;
        }
        ctx.addF(iv, -f);
        ctx.addJ(iv, ix, -dfdx);
        ctx.addJ(iv, iv, -dfdv);
    }

    /** The upthrust's potential, ρ·g·A·∫depth over the clearance: rising takes it from the water's head. */
    @Override
    public double storedEnergy(StateView s) {
        double x = s.x(tracker.angleIndex()), e = 0;
        for (Immersed p : parts) e += rho * g * p.area() * depth(clearance(p, x), p.height())[2];
        return e;
    }

    @Override
    public double untrackedLoss(StateView s) {
        double x = s.x(tracker.angleIndex()), v = s.x(node), loss = 0;
        double sv = Math.sqrt(v * v + V_SMOOTH * V_SMOOTH);
        for (Immersed p : parts) {
            double frac = depth(clearance(p, x), p.height())[0] / p.height();
            loss += 0.5 * rho * DRAG_CD * p.frontal() * frac * v * v * sv + radiation(p, clearance(p, x)) * v * v;
        }
        return loss;
    }

    /** Upthrust and drag on the slide now [N, along its guide]. */
    public double force(StateView s) {
        double x = s.x(tracker.angleIndex()), v = s.x(node), f = 0;
        double sv = Math.sqrt(v * v + V_SMOOTH * V_SMOOTH);
        for (Immersed p : parts) {
            double d = depth(clearance(p, x), p.height())[0];
            f += rho * g * p.area() * d * ay - 0.5 * rho * DRAG_CD * p.frontal() * d / p.height() * v * sv - radiation(p, clearance(p, x)) * v;
        }
        return f;
    }

    @Override
    public String label() {
        return "Buoyancy(" + parts.size() + " parts)";
    }
}
