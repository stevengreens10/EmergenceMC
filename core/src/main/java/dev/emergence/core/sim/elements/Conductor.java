package dev.emergence.core.sim.elements;

import dev.emergence.core.materials.Material;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * A segment of real conductive material: R(T) = ρ(T)·L/A with the material's measured temperature
 * dependence. Fully coupled electro-thermal element: the resistance depends on the temperature of the
 * thermal node, and Joule heating is delivered to that node. This is what lets a thin tungsten wire
 * glow, a copper wire act as a fuse, or a thermistor-like drift appear without special cases.
 * <p>
 * Assumes uniform current density (valid below the skin-effect frequency f ≈ ρ/(π μ0 μr r²), e.g.
 * ~4 kHz for a 1 mm copper wire).
 */
public final class Conductor implements Element {
    private final Node a, b, thermal;
    private final Material material;
    private final double geom; // L / A  [1/m]
    private final double fixedT;

    /**
     * @param length       conduction path length [m]
     * @param area         cross-sectional area [m²]
     * @param thermal      thermal node of the conductor (null → evaluated at {@code fixedTemperature})
     */
    public Conductor(Node a, Node b, Material material, double length, double area, Node thermal, double fixedTemperature) {
        this.a = a;
        this.b = b;
        this.material = material;
        this.geom = length / area;
        this.thermal = thermal;
        this.fixedT = fixedTemperature;
    }

    private double temperature(StateView s) {
        return thermal != null ? s.x(thermal.index()) : fixedT;
    }

    public double resistance(StateView s) {
        return material.resistivity(temperature(s)) * geom;
    }

    @Override
    public void load(LoadContext ctx) {
        double T = temperature(ctx);
        double r = material.resistivity(T) * geom;
        double drdT = material.resistivityDerivative(T) * geom;
        double v = ctx.x(a.index()) - ctx.x(b.index());
        double g = 1.0 / r;
        ctx.addFlow(a, b, g * v);
        ctx.stampConductance(a, b, g);
        if (thermal != null) {
            int t = thermal.index();
            double didT = -v * drdT / (r * r);
            ctx.addJ(a.index(), t, didT);
            ctx.addJ(b.index(), t, -didT);
            double p = g * v * v;
            ctx.addF(t, -p);
            ctx.addJ(t, a.index(), -2 * g * v);
            ctx.addJ(t, b.index(), 2 * g * v);
            ctx.addJ(t, t, v * v * drdT / (r * r));
        }
    }

    public double current(StateView s) {
        return s.across(a, b) / resistance(s);
    }

    public double power(StateView s) {
        double v = s.across(a, b);
        return v * v / resistance(s);
    }

    @Override
    public double untrackedLoss(StateView s) {
        return thermal == null ? power(s) : 0.0;
    }
}
