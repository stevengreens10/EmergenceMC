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

    public double heatTransferCoefficient(double ts, double ta) {
        double dT = Math.abs(ts - ta);
        double tf = 0.5 * (ts + ta);
        double nu = Air.kinematicViscosity(tf);
        double alpha = Air.thermalDiffusivity(tf);
        double pr = nu / alpha;
        double ra = PhysicalConstants.STANDARD_GRAVITY * (1.0 / tf) * dT * length * length * length / (nu * alpha);
        double nusselt = switch (shape) {
            case HORIZONTAL_CYLINDER -> {
                double v = 0.60 + 0.387 * Math.pow(ra, 1.0 / 6) / Math.pow(1 + Math.pow(0.559 / pr, 9.0 / 16), 8.0 / 27);
                yield v * v;
            }
            case VERTICAL_PLATE -> {
                double v = 0.825 + 0.387 * Math.pow(ra, 1.0 / 6) / Math.pow(1 + Math.pow(0.492 / pr, 9.0 / 16), 8.0 / 27);
                yield v * v;
            }
            case SPHERE -> 2.0 + 0.589 * Math.pow(ra, 0.25) / Math.pow(1 + Math.pow(0.469 / pr, 9.0 / 16), 4.0 / 9);
        };
        return nusselt * Air.conductivity(tf) / length;
    }

    private double q(double ts, double ta) {
        return heatTransferCoefficient(ts, ta) * area * (ts - ta);
    }

    @Override
    public void load(LoadContext ctx) {
        double ts = ctx.x(surface.index());
        double ta = ctx.x(ambient.index());
        double q = q(ts, ta);
        double eps = 1e-3;
        double dqs = (q(ts + eps, ta) - q(ts - eps, ta)) / (2 * eps);
        double dqa = (q(ts, ta + eps) - q(ts, ta - eps)) / (2 * eps);
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
