package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Fluid;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * Convective heat exchange between a fluid control volume and the solid wall around it,
 * Q̇ = h·A·(T_wall − T_fluid) with h = Nu·k/D_h. Nu from the flow through the volume: fully developed
 * laminar Nu = 3.66 (constant wall temperature) below Re ≈ 2300, Gnielinski's correlation
 * Nu = (f/8)(Re − 1000)Pr / (1 + 12.7√(f/8)(Pr^⅔ − 1)), f = (0.79 ln Re − 1.64)⁻², above Re ≈ 3000
 * (±10 %, 0.5 &lt; Pr &lt; 2000), blended in between. Re is taken from the mean |ṁ| of the branches
 * through the volume. A quiescent volume therefore exchanges heat at the laminar (conduction-like)
 * rate; natural convection inside closed vessels is not modelled separately (conservative).
 */
public final class FluidWallConvection implements Element {
    private final Node fluidT, wallT;
    private final Fluid fluid;
    private final double area, dh, flowArea;
    private final java.util.List<FluidBranch> branches;

    /**
     * @param branches the flow branches through this volume (their flow sets the Reynolds number)
     */
    public FluidWallConvection(Node fluidT, Node wallT, Fluid fluid, double wallArea, double hydraulicDiameter,
                               double flowArea, java.util.List<FluidBranch> branches) {
        this.fluidT = fluidT;
        this.wallT = wallT;
        this.fluid = fluid;
        this.area = wallArea;
        this.dh = hydraulicDiameter;
        this.flowArea = flowArea;
        this.branches = java.util.List.copyOf(branches);
    }

    /** Heat transfer coefficient h [W/(m²·K)] at the current state. */
    public double coefficient(StateView s) {
        double tf = Math.max(s.x(fluidT), 1.0);
        double mdot = 0;
        for (FluidBranch b : branches) mdot += Math.abs(b.massFlow(s));
        if (!branches.isEmpty()) mdot /= branches.size();
        double mu = fluid.viscosity(tf);
        double re = mdot * dh / (mu * flowArea);
        return nusselt(re, fluid.prandtl(tf)) * fluid.conductivity(tf) / dh;
    }

    static double nusselt(double re, double pr) {
        double lam = 3.66;
        if (re < 2300) return lam;
        double reT = Math.max(re, 3000);
        double f = Math.pow(0.79 * Math.log(reT) - 1.64, -2);
        double turb = (f / 8) * (reT - 1000) * pr / (1 + 12.7 * Math.sqrt(f / 8) * (Math.pow(pr, 2.0 / 3) - 1));
        if (re >= 3000) return Math.max(lam, turb);
        double w = (re - 2300) / 700;
        return lam * (1 - w) + Math.max(lam, turb) * w;
    }

    @Override
    public void load(LoadContext ctx) {
        double ha = coefficient(ctx) * area;
        double q = ha * (ctx.x(wallT.index()) - ctx.x(fluidT.index())); // into the fluid
        ctx.addF(wallT.index(), q);
        ctx.addF(fluidT.index(), -q);
        ctx.stampConductance(wallT, fluidT, ha);
    }

    @Override
    public String label() {
        return "FluidWallConvection(" + fluid.id() + ")";
    }
}
