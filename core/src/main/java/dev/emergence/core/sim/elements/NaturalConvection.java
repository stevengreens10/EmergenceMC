package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Air;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Free (natural) convection from a surface to still ambient air, Q̇ = h̄·A·(T_s − T_∞), with h̄ from
 * empirical Nusselt correlations evaluated at the film temperature (Incropera §9):
 * <ul>
 *   <li>Horizontal cylinder — Churchill &amp; Chu (1975), valid Ra ≤ 1e12.</li>
 *   <li>Vertical plate — Churchill &amp; Chu (1975), all Ra.</li>
 *   <li>Sphere / compact body — Churchill (1983), Ra ≤ 1e11, Pr ≥ 0.7.</li>
 * </ul>
 * Typical accuracy of these correlations is ±20 %.
 */
public final class NaturalConvection implements Element {
    public enum Shape { HORIZONTAL_CYLINDER, VERTICAL_PLATE, SPHERE }

    private final Node surface, ambient;
    private final double area, length;
    private final Shape shape;

    /**
     * @param area   wetted surface area [m²]
     * @param length characteristic length: diameter (cylinder/sphere) or height (plate) [m]
     */
    public NaturalConvection(Node surface, Node ambient, Shape shape, double area, double length) {
        this.surface = surface;
        this.ambient = ambient;
        this.shape = shape;
        this.area = area;
        this.length = length;
    }

    /**
     * The film-temperature groups, tabulated every kelvin from 100 to 4000 K (linear interpolation: relative error
     * ~10⁻⁶): ν·α, k, and each shape's Prandtl factor 1/[1 + (c/Pr)^{9/16}]^e.
     */
    private static final int T0 = 100, T1 = 4000;
    private static final double[] NU_ALPHA = new double[T1 - T0 + 1], K = new double[T1 - T0 + 1];
    private static final double[][] PR_FACTOR = new double[3][T1 - T0 + 1];

    static {
        for (int i = 0; i <= T1 - T0; i++) {
            double t = T0 + i, nu = Air.kinematicViscosity(t), alpha = Air.thermalDiffusivity(t), pr = nu / alpha;
            NU_ALPHA[i] = nu * alpha;
            K[i] = Air.conductivity(t);
            PR_FACTOR[0][i] = 1 / Math.pow(1 + Math.pow(0.559 / pr, 9.0 / 16), 8.0 / 27);
            PR_FACTOR[1][i] = 1 / Math.pow(1 + Math.pow(0.492 / pr, 9.0 / 16), 8.0 / 27);
            PR_FACTOR[2][i] = 1 / Math.pow(1 + Math.pow(0.469 / pr, 9.0 / 16), 4.0 / 9);
        }
    }

    private static double lerp(double[] tab, int i, double f) {
        return tab[i] + f * (tab[i + 1] - tab[i]);
    }

    public double heatTransferCoefficient(double ts, double ta) {
        double dT = Math.abs(ts - ta);
        double tf = Math.max(T0, Math.min(T1 - 1e-9, 0.5 * (ts + ta)));
        int i = (int) (tf - T0);
        double f = tf - T0 - i;
        double ra = PhysicalConstants.STANDARD_GRAVITY / tf * dT * length * length * length / lerp(NU_ALPHA, i, f);
        double pf = lerp(PR_FACTOR[shape.ordinal()], i, f);
        double nusselt = switch (shape) {
            case HORIZONTAL_CYLINDER -> {
                double v = 0.60 + 0.387 * Math.cbrt(Math.sqrt(ra)) * pf;
                yield v * v;
            }
            case VERTICAL_PLATE -> {
                double v = 0.825 + 0.387 * Math.cbrt(Math.sqrt(ra)) * pf;
                yield v * v;
            }
            case SPHERE -> 2.0 + 0.589 * Math.sqrt(Math.sqrt(ra)) * pf;
        };
        return nusselt * lerp(K, i, f) / length;
    }

    /**
     * Heat flow: h·A·(T_s − T_a) with h evaluated at temperatures bounded to 100–4000 K, so a Newton iterate that
     * strays to an unphysical temperature (even a negative one) still sees a finite coefficient and can recover.
     */
    private double q(double ts, double ta) {
        return heatTransferCoefficient(bound(ts), bound(ta)) * area * (ts - ta);
    }

    private static double bound(double t) {
        return Double.isNaN(t) ? 300 : Math.max(100, Math.min(4000, t));
    }

    @Override
    public void load(LoadContext ctx) {
        double ts = ctx.x(surface.index());
        double ta = ctx.x(ambient.index());
        double q = q(ts, ta);
        // one-sided slope on the surface temperature (away from the ambient, where h is smooth); the ambient's is
        // taken as its negative — the film-temperature terms differ, which only shades Newton's convergence
        double eps = ts >= ta ? 1e-3 : -1e-3;
        double dqs = (q(ts + eps, ta) - q) / eps, dqa = -dqs;
        ctx.addFlow(surface, ambient, q);
        ctx.addJ(surface.index(), surface.index(), dqs);
        ctx.addJ(surface.index(), ambient.index(), dqa);
        ctx.addJ(ambient.index(), surface.index(), -dqs);
        ctx.addJ(ambient.index(), ambient.index(), -dqa);
    }

    public double heatFlow(StateView s) {
        return q(s.x(surface.index()), s.x(ambient.index()));
    }
}
