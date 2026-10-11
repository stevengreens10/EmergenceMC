package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Air;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Forced convection from a free jet of air onto a part: air leaving a duct's open end (mass flow ṁ out of the duct,
 * exit area A₀, diameter D) blows on a part a distance x downstream. The jet keeps its exit velocity U₀ = ṁ/(ρA₀)
 * through its potential core and then slows as a round turbulent jet does, u = U₀·6.2·D/x beyond x = 6.2·D
 * (Rajaratnam; ±15 %). The part, of surface A and size L across the flow, exchanges Q̇ = h·A·(T_part − T_air) with
 * the air (an ambient reservoir), h = Nu·k/L from the Churchill–Bernstein cross-flow correlation
 * Nu = 0.3 + 0.62 Re^½ Pr^⅓ / [1 + (0.4/Pr)^⅔]^¼ · [1 + (Re/282 000)^⅝]^⅘ (±20 % for cylinders; applied to blocks and
 * fins alike), in addition to its natural convection. Suction into a duct makes no jet (it draws from all around).
 */
public final class JetConvection implements Element {
    private final Node part, air;
    private final FluidBranch vent;
    private final double exitArea, exitDiameter, distance, area, length;

    /**
     * @param vent the branch venting the duct to the air (its mass flow positive out of the duct)
     */
    public JetConvection(Node part, Node air, FluidBranch vent, double exitArea, double exitDiameter, double distance, double area,
                         double length) {
        this.part = part;
        this.air = air;
        this.vent = vent;
        this.exitArea = exitArea;
        this.exitDiameter = exitDiameter;
        this.distance = distance;
        this.area = area;
        this.length = length;
    }

    /** Jet velocity at the part [m/s] for an outflow ṁ [kg/s] (smoothly zero for inflow). */
    public double velocity(double mdot, double tAir) {
        double m = 0.5 * (mdot + Math.sqrt(mdot * mdot + 1e-16)); // ṁ⁺, smooth through zero
        double rho = Air.density(tAir, 101_325);
        double u0 = m / (rho * exitArea), core = 6.2 * exitDiameter;
        return distance <= core ? u0 : u0 * core / distance;
    }

    /** Heat-transfer coefficient [W/(m²·K)] at jet velocity u, film temperature tf. */
    public double coefficient(double u, double tf) {
        double nu = Air.kinematicViscosity(tf), k = Air.conductivity(tf), pr = Air.prandtl(tf);
        double re = Math.max(u, 0) * length / nu;
        // the 0.3 of Nu at Re → 0 is conduction through still air, which natural convection already covers; √Re is
        // taken as √(Re + 1) − 1 (the same above Re ≈ 100, where the correlation is meant to be used; at a creep, a
        // finite slope rather than an infinite one, so Newton is not thrown as the fan starts)
        double forced = 0.62 * (Math.sqrt(re + 1) - 1) * Math.cbrt(pr) / Math.pow(1 + Math.pow(0.4 / pr, 2.0 / 3), 0.25)
                * Math.pow(1 + Math.pow(re / 282_000, 5.0 / 8), 4.0 / 5);
        return forced * k / length;
    }

    private double q(double tp, double ta, double mdot) {
        double tf = Math.max(150, Math.min(2000, 0.5 * (tp + ta)));
        return coefficient(velocity(mdot, ta), tf) * area * (tp - ta);
    }

    @Override
    public void load(LoadContext ctx) {
        int flowIdx = vent.flowIndex(); // allocated in the branch's setup
        double tp = ctx.x(part.index()), ta = ctx.x(air.index()), m = ctx.x(flowIdx);
        double qv = q(tp, ta, m);
        ctx.addFlow(part, air, qv);
        double et = 1e-3, em = Math.max(1e-7, 1e-4 * Math.abs(m));
        double dqt = (q(tp + et, ta, m) - qv) / et, dqm = (q(tp, ta, m + em) - qv) / em;
        ctx.addJ(part.index(), part.index(), dqt);
        ctx.addJ(part.index(), air.index(), -dqt);
        ctx.addJ(air.index(), part.index(), -dqt);
        ctx.addJ(air.index(), air.index(), dqt);
        ctx.addFlowJ(part, air, flowIdx, dqm);
    }

    /** Heat carried off the part [W]. */
    public double heatFlow(StateView s) {
        return q(s.x(part), s.x(air), s.x(vent.flowIndex()));
    }

    @Override
    public String label() {
        return "jet convection";
    }
}
