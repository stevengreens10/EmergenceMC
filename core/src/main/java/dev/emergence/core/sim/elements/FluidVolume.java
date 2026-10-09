package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Fluid;
import dev.emergence.core.fluids.WaterSteam;
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
 * <p>
 * <b>Water and steam</b> ({@link WaterSteam}): the volume gains a third unknown, the phase coordinate s of
 * {@link WaterSteam#state} (compressed liquid, saturated liquid–vapour mixture with vapour volume fraction s,
 * or superheated vapour), and the pressure node is tied to it by p = p(T, s). Mass and energy balances are
 * the same, so boiling and condensation follow from heat and flow alone: heat added at saturation raises s at
 * nearly constant T until the pressure (fixed by T on the saturation curve) drives vapour out. Phases are
 * assumed in equilibrium and separated by gravity: a port below the liquid level draws liquid, above it vapour
 * (see {@link #port}).
 */
public final class FluidVolume implements Element {
    private final Node p, t;
    private final Fluid fluid;
    private final double volume;
    private int slotM, slotU;
    private AngleTracker tracker;
    private SliderCrank crank;
    private final WaterSteam steam;
    private double phase0 = 1;
    private int sIdx = -1;

    public FluidVolume(Node pressure, Node temperature, Fluid fluid, double volume) {
        if (pressure.domain() != Domain.FLUID || temperature.domain() != Domain.THERMAL) {
            throw new IllegalArgumentException("FluidVolume needs a fluid node and a thermal node");
        }
        if (!(volume > 0)) throw new IllegalArgumentException("volume must be > 0");
        this.p = pressure;
        this.t = temperature;
        this.fluid = fluid;
        this.volume = volume;
        this.steam = fluid instanceof WaterSteam w ? w : null;
    }

    /**
     * Initial state of a water/steam volume: saturated at temperature t with liquid filling the given fraction of
     * the volume (0 = all vapour, 1 = all liquid).
     */
    public FluidVolume withLiquidFraction(double fill, double t) {
        this.phase0 = WaterSteam.phaseForLiquidFraction(t, fill);
        return this;
    }

    /** Initial phase coordinate s of a water/steam volume (see {@link WaterSteam#state}). */
    public FluidVolume withPhase(double s) {
        this.phase0 = s;
        return this;
    }

    /** True if this volume holds water/steam with phase change. */
    public boolean twoPhase() {
        return steam != null;
    }

    /** Index of the phase-coordinate unknown, or −1 for a single-phase fluid. */
    public int phaseIndex() {
        return sIdx;
    }

    /** Phase coordinate s now (NaN for a single-phase fluid). */
    public double phase(StateView s) {
        return sIdx < 0 ? Double.NaN : s.x(sIdx);
    }

    /** Vapour volume fraction now (1 for a gas, 0 for a liquid). */
    public double vapourFraction(StateView s) {
        if (steam == null) return fluid.isGas() ? 1 : 0;
        double[] st = new double[9];
        WaterSteam.state(s.x(t), s.x(sIdx), st);
        return st[WaterSteam.ALPHA_V];
    }

    /**
     * Properties of the fluid that leaves through a port at relative height z (0 = bottom, 1 = top of the
     * volume). For water/steam the liquid lies below the level 1 − α_v and the vapour above; within ±5 % of
     * the height around the level both leave in proportion (homogeneous flow, viscosity by McAdams'
     * 1/μ = x/μ_v + (1−x)/μ_l). Null for single-phase fluids.
     */
    public Port port(double z) {
        return steam == null ? null : new Port(z);
    }

    /** A flow connection to a water/steam volume at a given height. */
    public final class Port {
        private final double z;
        private final double[] st = new double[9];

        Port(double z) {
            this.z = Math.max(0, Math.min(1, z));
        }

        /** The unknown the outflow depends on besides p and T (the phase coordinate). */
        public int phaseIndex() {
            return sIdx;
        }

        /** Fills out = [h, ρ, μ, x (vapour mass fraction), 1/ρ] of the outflow for temperature t and phase s. */
        public void outflow(double t, double s, double[] out) {
            // finite-difference Jacobians perturb p and ṁ far more often than (T, s): reuse the last result
            if (t == lastT && s == lastS) {
                System.arraycopy(last, 0, out, 0, 5);
                return;
            }
            WaterSteam.state(t, s, st, join);
            double av = st[WaterSteam.ALPHA_V], level = 1 - av, band = 0.05;
            double w = smooth((z - level) / band + 0.5);
            double wv = w * Math.min(1, av / band), wl = (1 - w) * Math.min(1, (1 - av) / band);
            if (s > 1) { wv = 1; wl = 0; }
            if (s < 0) { wv = 0; wl = 1; }
            double x = wv / Math.max(wv + wl, 1e-12);
            double tt = Math.max(t, 273.16);
            out[0] = x * st[WaterSteam.H_V] + (1 - x) * st[WaterSteam.H_L];
            out[4] = x / st[WaterSteam.RHO_V] + (1 - x) / st[WaterSteam.RHO_L];
            out[1] = 1 / out[4];
            out[2] = 1 / (x / steam.vapourViscosity(tt) + (1 - x) / steam.liquidViscosity(tt));
            out[3] = x;
            lastT = t;
            lastS = s;
            System.arraycopy(out, 0, last, 0, 5);
        }

        private double lastT = Double.NaN, lastS = Double.NaN;
        private final WaterSteam.JoinCache join = new WaterSteam.JoinCache();
        private final double[] last = new double[5];
    }

    private static double smooth(double u) {
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    /**
     * Makes this a piston cylinder: the volume follows the crank angle, V(θ) = A·(c + s(θ)). The work the
     * gas does on the piston is accounted by the matching {@link PistonCoupling}.
     */
    public FluidVolume withPiston(AngleTracker tracker, SliderCrank crank) {
        this.tracker = tracker;
        this.crank = crank;
        return this;
    }

    /** Current volume [m³]. */
    public double volume(StateView s) {
        return crank == null ? volume : crank.volume(s.x(tracker.angleIndex()));
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
        // keep Newton iterates physical: no pressure or temperature leaps through zero
        ctx.limitNewtonStep(p.index(), fluid.isGas() ? 2e5 : 5e6);
        ctx.limitNewtonStep(t.index(), 150);
        slotU = ctx.allocState(1e-7 * m * fluid.cv(293.15) * 293.15);
        if (steam != null) {
            sIdx = ctx.allocUnknown("phase", 1e-7);
            ctx.setInitial(sIdx, phase0);
            ctx.limitNewtonStep(sIdx, 0.25);
        }
    }

    @Override
    public void load(LoadContext ctx) {
        if (steam != null) {
            loadTwoPhase(ctx);
            return;
        }
        double pp = fluid.isGas() ? Math.max(ctx.x(p.index()), 1.0) : ctx.x(p.index()), tt = Math.max(ctx.x(t.index()), 1.0);
        double rho = fluid.density(pp, tt), drp = fluid.dDensityDp(pp, tt), drt = fluid.dDensityDt(pp, tt);
        double vol = volume, dvol = 0;
        int ia = -1;
        if (crank != null) {
            ia = tracker.angleIndex();
            double th = ctx.x(ia);
            vol = crank.volume(th);
            dvol = crank.dVolume(th);
        }
        double m = rho * vol, u = fluid.internalEnergy(pp, tt);
        double dm = ctx.ddt(slotM, m);
        ctx.addF(p.index(), dm);
        ctx.addJ(p.index(), p.index(), ctx.alpha() * vol * drp);
        ctx.addJ(p.index(), t.index(), ctx.alpha() * vol * drt);
        double dU = ctx.ddt(slotU, m * u);
        ctx.addF(t.index(), dU);
        ctx.addJ(t.index(), p.index(), ctx.alpha() * vol * drp * u);
        ctx.addJ(t.index(), t.index(), ctx.alpha() * (vol * drt * u + m * fluid.cv(tt)));
        if (ia >= 0) {
            ctx.addJ(p.index(), ia, ctx.alpha() * rho * dvol);
            ctx.addJ(t.index(), ia, ctx.alpha() * rho * dvol * u);
        }
    }

    private final double[] st0 = new double[9], stT = new double[9], stS = new double[9];
    private final WaterSteam.JoinCache join0 = new WaterSteam.JoinCache(), joinT = new WaterSteam.JoinCache();

    private void loadTwoPhase(LoadContext ctx) {
        int ip = p.index(), it = t.index();
        double tt = ctx.x(it), s = ctx.x(sIdx);
        double vol = volume, dvol = 0;
        int ia = -1;
        if (crank != null) {
            ia = tracker.angleIndex();
            double th = ctx.x(ia);
            vol = crank.volume(th);
            dvol = crank.dVolume(th);
        }
        double hT = 1e-4, hS = 1e-9;
        WaterSteam.state(tt + hT, s, stT, joinT);
        WaterSteam.state(tt, s, st0, join0);
        WaterSteam.state(tt, s + hS, stS, join0);
        double drT = (stT[WaterSteam.RHO] - st0[WaterSteam.RHO]) / hT, drS = (stS[WaterSteam.RHO] - st0[WaterSteam.RHO]) / hS;
        double deT = (stT[WaterSteam.RHO_U] - st0[WaterSteam.RHO_U]) / hT, deS = (stS[WaterSteam.RHO_U] - st0[WaterSteam.RHO_U]) / hS;
        double dpT = (stT[WaterSteam.P] - st0[WaterSteam.P]) / hT, dpS = (stS[WaterSteam.P] - st0[WaterSteam.P]) / hS;
        double a = ctx.alpha();
        // mass and energy balances
        ctx.addF(ip, ctx.ddt(slotM, st0[WaterSteam.RHO] * vol));
        ctx.addJ(ip, it, a * vol * drT);
        ctx.addJ(ip, sIdx, a * vol * drS);
        ctx.addF(it, ctx.ddt(slotU, st0[WaterSteam.RHO_U] * vol));
        ctx.addJ(it, it, a * vol * deT);
        ctx.addJ(it, sIdx, a * vol * deS);
        if (ia >= 0) {
            ctx.addJ(ip, ia, a * st0[WaterSteam.RHO] * dvol);
            ctx.addJ(it, ia, a * st0[WaterSteam.RHO_U] * dvol);
        }
        // the pressure node carries the equation-of-state pressure
        double sc = 1e-5;
        ctx.addF(sIdx, (ctx.x(ip) - st0[WaterSteam.P]) * sc);
        ctx.addJ(sIdx, ip, sc);
        ctx.addJ(sIdx, it, -dpT * sc);
        ctx.addJ(sIdx, sIdx, -dpS * sc);
    }

    /** Mass of fluid currently held [kg]. */
    public double mass(StateView s) {
        if (steam != null) {
            double[] st = new double[9];
            WaterSteam.state(s.x(t), s.x(sIdx), st);
            return st[WaterSteam.RHO] * volume(s);
        }
        return fluid.density(s.x(p), s.x(t)) * volume(s);
    }

    @Override
    public double storedEnergy(StateView s) {
        if (steam != null) {
            double[] st = new double[9];
            WaterSteam.state(s.x(t), s.x(sIdx), st);
            return st[WaterSteam.RHO_U] * volume(s);
        }
        return mass(s) * fluid.internalEnergy(s.x(p), s.x(t));
    }

    @Override
    public String label() {
        return "FluidVolume(" + fluid.id() + ", " + volume + " m³)";
    }
}
