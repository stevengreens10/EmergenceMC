package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Fluid;
import dev.emergence.core.fluids.WaterSteam;
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
 * <p>
 * <b>Boiling and condensation</b> (water/steam volumes, {@link #withTwoPhase}): the wall is wetted by liquid
 * over the fraction 1 − α_v of its area and by vapour over α_v. Where the liquid wall is hotter than the
 * saturation temperature it boils with Rohsenow's nucleate-boiling flux
 * q'' = μ_l h_fg √(g(ρ_l − ρ_v)/σ)·(c_p,l ΔT_e/(C_sf h_fg Pr_l))³, C_sf = 0.013 (water on copper or steel;
 * ±100 % in flux, ±25 % in ΔT_e), limited smoothly by Zuber's critical heat flux
 * q''_max = 0.149 h_fg ρ_v (σg(ρ_l − ρ_v)/ρ_v²)^¼ (film boiling beyond burnout is not modelled). Where the
 * vapour wall is colder than saturation, vapour condenses on it as a laminar film (Nusselt):
 * h = 0.943 (ρ_l(ρ_l − ρ_v) g h'_fg k_l³/(μ_l L ΔT))^¼, h'_fg = h_fg + 0.68 c_p,l ΔT, L the wall height
 * (±20 %). A wetted wall warmer than saturation evaporates its film through the same film conductance (while
 * liquid remains to wet it), so the wall–vapour exchange is continuous through equilibrium. Single-phase
 * convection to each phase is added as above.
 */
public final class FluidWallConvection implements Element {
    private final Node fluidT, wallT;
    private final Fluid fluid;
    private final double area, dh, flowArea;
    private final java.util.List<FluidBranch> branches;
    private FluidVolume volume;
    private double wallHeight;

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

    /** Enables boiling and condensation on this wall for a water/steam volume whose wall is {@code height} tall. */
    public FluidWallConvection withTwoPhase(FluidVolume v, double height) {
        if (v.twoPhase()) {
            this.volume = v;
            this.wallHeight = Math.max(height, 1e-3);
        }
        return this;
    }

    private double reynoldsFlow(StateView s) {
        double mdot = 0;
        for (FluidBranch b : branches) mdot += Math.abs(b.massFlow(s));
        if (!branches.isEmpty()) mdot /= branches.size();
        return mdot * dh / flowArea;
    }

    private final double[] st = new double[9];
    private final WaterSteam.JoinCache join = new WaterSteam.JoinCache();
    private double cpT = Double.NaN, cpVal, cpT2 = Double.NaN, cpVal2;

    /** Heat flow from the wall into a water/steam volume [W]. */
    double twoPhaseHeat(double tw, double tf, double s, double mdotDh) {
        WaterSteam w = (WaterSteam) fluid;
        WaterSteam.state(tf, s, st, join);
        // saturated: the fluid is at the saturation temperature (exactly, so the film law is smooth through ΔT = 0)
        double ts = s >= 0 && s <= 1 ? tf : WaterSteam.tSat(st[WaterSteam.P]);
        double av = st[WaterSteam.ALPHA_V], fl = 1 - av;
        double[] sat = WaterSteam.saturationSet(ts);
        double rl = sat[WaterSteam.SAT_RHO_L], rv = sat[WaterSteam.SAT_RHO_V], hfg = sat[WaterSteam.SAT_H_V] - sat[WaterSteam.SAT_H_L];
        // liquid c_p ≈ dh'/dT from the saturation slope, held per temperature (it varies slowly)
        if (tf != cpT) {
            if (tf == cpT2) {
                double a = cpT, b = cpVal;
                cpT = cpT2;
                cpVal = cpVal2;
                cpT2 = a;
                cpVal2 = b;
            } else {
                cpT2 = cpT;
                cpVal2 = cpVal;
                cpT = tf;
                cpVal = w.cp(tf);
            }
        }
        double mul = w.liquidViscosity(tf), kl = w.liquidConductivity(tf), cpl = cpVal, prl = mul * cpl / kl;
        double g = dev.emergence.core.units.PhysicalConstants.STANDARD_GRAVITY, sigma = Math.max(WaterSteam.surfaceTension(ts), 1e-4);
        double q = 0;
        // single-phase convection to each phase
        double hl = nusselt(mdotDh / mul, prl) * kl / dh;
        double muv = w.vapourViscosity(tf), kv = WaterSteam.vapourConductivity(tf);
        double hv = nusselt(mdotDh / muv, muv * w.cpVapour() / kv) * kv / dh;
        q += (hl * fl + hv * av) * area * (tw - tf);
        // nucleate boiling on the wetted wall, smoothly capped at the critical heat flux
        double dte = tw - ts;
        if (dte > 0 && fl > 0) {
            double nb = mul * hfg * Math.sqrt(g * (rl - rv) / sigma) * Math.pow(cpl * dte / (0.013 * hfg * prl), 3);
            double chf = 0.149 * hfg * rv * Math.pow(sigma * g * (rl - rv) / (rv * rv), 0.25);
            double flux = nb / Math.pow(1 + Math.pow(nb / chf, 4), 0.25);
            q += flux * area * fl;
        }
        // liquid film on the vapour-covered wall: condensation where the wall is colder than saturation; where it
        // is warmer, the film (present while there is liquid to wet the wall) evaporates through the same film
        double dtc = ts - tw;
        if (av > 0) {
            double dry = fl / (fl + 0.002), wet = dry + (1 - dry) * 0.5 * (1 + Math.tanh(dtc / 0.02)); // smooth through ΔT = 0
            double hfgP = hfg + 0.68 * cpl * Math.abs(dtc);
            double c = 0.943 * Math.pow(rl * (rl - rv) * g * hfgP * kl * kl * kl / (mul * wallHeight), 0.25);
            q -= c * wet * dtc * Math.pow(dtc * dtc + 1e-4, -0.125) * area * av;
        }
        return q;
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
        if (volume != null) {
            int iw = wallT.index(), iff = fluidT.index(), is = volume.phaseIndex();
            double tw = ctx.x(iw), tf = ctx.x(iff), s = ctx.x(is), md = reynoldsFlow(ctx);
            double q = twoPhaseHeat(tw, tf, s, md);
            ctx.addF(iw, q);
            ctx.addF(iff, -q);
            double h = 1e-4;
            double dw = (twoPhaseHeat(tw + h, tf, s, md) - q) / h, df = (twoPhaseHeat(tw, tf + h, s, md) - q) / h;
            double ds = (twoPhaseHeat(tw, tf, s + 1e-9, md) - q) / 1e-9;
            ctx.addJ(iw, iw, dw);
            ctx.addJ(iw, iff, df);
            ctx.addJ(iw, is, ds);
            ctx.addJ(iff, iw, -dw);
            ctx.addJ(iff, iff, -df);
            ctx.addJ(iff, is, -ds);
            return;
        }
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
