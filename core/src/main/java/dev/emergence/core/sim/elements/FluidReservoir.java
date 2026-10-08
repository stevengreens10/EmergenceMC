package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * An infinite fluid reservoir (the open atmosphere, a lake) holding a fluid node at pressure p_res and
 * its thermal node at T_res. Mass and enthalpy that flow in or out are absorbed or supplied by the
 * reservoir; the energy is recorded as external power so the network's energy ledger stays closed.
 */
public final class FluidReservoir implements Element {
    private final Node p, t;
    private double pressure, temperature;
    private int mIdx, eIdx;

    public FluidReservoir(Node pressureNode, Node temperatureNode, double pressure, double temperature) {
        this.p = pressureNode;
        this.t = temperatureNode;
        this.pressure = pressure;
        this.temperature = temperature;
    }

    public void setPressure(double pressure) {
        this.pressure = pressure;
    }

    @Override
    public void setup(SetupContext ctx) {
        mIdx = ctx.allocUnknown("m(reservoir)", 1e-12);
        eIdx = ctx.allocUnknown("E(reservoir)", 1e-6);
        ctx.setInitial(p.index(), pressure);
        ctx.setInitial(t.index(), temperature);
    }

    @Override
    public void load(LoadContext ctx) {
        ctx.addF(p.index(), ctx.x(mIdx));
        ctx.addJ(p.index(), mIdx, 1);
        ctx.addF(mIdx, ctx.x(p.index()) - pressure);
        ctx.addJ(mIdx, p.index(), 1);
        ctx.addF(t.index(), ctx.x(eIdx));
        ctx.addJ(t.index(), eIdx, 1);
        ctx.addF(eIdx, ctx.x(t.index()) - temperature);
        ctx.addJ(eIdx, t.index(), 1);
    }

    /** Mass flow currently absorbed by the reservoir [kg/s] (negative when it supplies fluid). */
    public double massAbsorbed(StateView s) {
        return s.x(mIdx);
    }

    /** Energy flow (enthalpy and heat) currently absorbed by the reservoir [W]. */
    public double energyAbsorbed(StateView s) {
        return s.x(eIdx);
    }

    @Override
    public double externalPower(StateView s) {
        return -s.x(eIdx);
    }
}
