package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Fluid;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * A fixed control volume V filled with one fluid, described by its pressure (fluid node) and its
 * temperature (thermal node). Conservation of mass, d(ρV)/dt = Σ ṁ_in, is the fluid node's balance;
 * the first law for an open system, d(m·u)/dt = Σ ṁ_in·h_in + Q̇_in, is the thermal node's balance —
 * flow branches carry enthalpy into and out of it and wall convection adds heat. Lumped: the fluid is
 * assumed well mixed (uniform p, T) within the volume.
 */
public final class FluidVolume implements Element {
    private final Node p, t;
    private final Fluid fluid;
    private final double volume;
    private int slotM, slotU;

    public FluidVolume(Node pressure, Node temperature, Fluid fluid, double volume) {
        if (pressure.domain() != Domain.FLUID || temperature.domain() != Domain.THERMAL) {
            throw new IllegalArgumentException("FluidVolume needs a fluid node and a thermal node");
        }
        if (!(volume > 0)) throw new IllegalArgumentException("volume must be > 0");
        this.p = pressure;
        this.t = temperature;
        this.fluid = fluid;
        this.volume = volume;
    }

    public Fluid fluid() {
        return fluid;
    }

    public double volume() {
        return volume;
    }

    public Node pressureNode() {
        return p;
    }

    public Node temperatureNode() {
        return t;
    }

    @Override
    public void setup(SetupContext ctx) {
        double m = fluid.density(101_325, 293.15) * volume;
        slotM = ctx.allocState(1e-7 * m);
        slotU = ctx.allocState(1e-7 * m * fluid.cv(293.15) * 293.15);
    }

    @Override
    public void load(LoadContext ctx) {
        double pp = ctx.x(p.index()), tt = Math.max(ctx.x(t.index()), 1.0);
        double rho = fluid.density(pp, tt), drp = fluid.dDensityDp(pp, tt), drt = fluid.dDensityDt(pp, tt);
        double m = rho * volume, u = fluid.internalEnergy(pp, tt);
        double dm = ctx.ddt(slotM, m);
        ctx.addF(p.index(), dm);
        ctx.addJ(p.index(), p.index(), ctx.alpha() * volume * drp);
        ctx.addJ(p.index(), t.index(), ctx.alpha() * volume * drt);
        double dU = ctx.ddt(slotU, m * u);
        ctx.addF(t.index(), dU);
        ctx.addJ(t.index(), p.index(), ctx.alpha() * volume * drp * u);
        ctx.addJ(t.index(), t.index(), ctx.alpha() * (volume * drt * u + m * fluid.cv(tt)));
    }

    /** Mass of fluid currently held [kg]. */
    public double mass(StateView s) {
        return fluid.density(s.x(p), s.x(t)) * volume;
    }

    @Override
    public double storedEnergy(StateView s) {
        return mass(s) * fluid.internalEnergy(s.x(p), s.x(t));
    }

    @Override
    public String label() {
        return "FluidVolume(" + fluid.id() + ", " + volume + " m³)";
    }
}
