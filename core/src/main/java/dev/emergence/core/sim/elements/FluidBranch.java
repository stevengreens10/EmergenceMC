package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.Fluid;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Quasi-steady flow between two control volumes a and b, with the mass flow ṁ (a → b) as unknown:
 * <pre>
 *   p_a − p_b = Δp_friction + Δp_minor + Δp_valve + ρ·g·(z_b − z_a) − Δp_rotor
 * </pre>
 * <ul>
 *   <li>Friction: Darcy–Weisbach Δp = f·(L/D)·ρv|v|/2 with f = 64/Re (laminar, giving Hagen–Poiseuille
 *       exactly) and Haaland's explicit fit to Colebrook for turbulent flow (±2 %), blended over
 *       2000 &lt; Re &lt; 4000.</li>
 *   <li>Minor losses: K·ρv|v|/2 (bends, entries, fittings); a partly open valve is a sharp-edged orifice of
 *       area C_d·φ·A (C_d = 0.62); a closed valve (φ = 0) blocks the flow.</li>
 *   <li>Rotor (optional): a bladed rotor on a rotating body acting on the flow, from Euler's turbomachine
 *       equation with no inlet swirl. Blade speed U = ω·r, axial velocity c_x = ṁ/(ρA), exit relative
 *       flow angle β: Δh₀ = U² − U·c_x·tan β, ideal pressure rise ρΔh₀, profile loss ζ·ρw²/2 with w the
 *       relative velocity. Shaft torque τ = ṁ·r·(U − c_x tan β), so τ·ω = ṁ·Δh₀ exactly: the same rotor
 *       is a fan or pump when driven and a turbine when the flow drives it.</li>
 * </ul>
 * Energy: the flow carries the upstream specific enthalpy h = u + p/ρ (upwinded, smoothly through
 * ṁ = 0) and the rotor's shaft work is added to the downstream volume, so friction heat stays in the
 * fluid and the first law holds exactly. Kinetic energy of the flow and fluid inertia are neglected
 * (low Mach number, no acoustic or water-hammer transients); flow is single phase and unchoked.
 */
public final class FluidBranch implements Element {
    private final Node pa, ta, pb, tb;
    private final Fluid fluid;
    private final double length, diameter, area, roughness, minorK, dz;
    private double opening = 1.0;
    private Node rotor;
    private double rotorRadius, tanBeta, hand = 1, lossCoefficient = 0.1;
    private int mIdx;
    private double mScale;

    /**
     * @param dz height of b above a [m]
     */
    public FluidBranch(Node pa, Node ta, Node pb, Node tb, Fluid fluid, double length, double diameter, double roughness,
                       double minorK, double dz) {
        this.pa = pa;
        this.ta = ta;
        this.pb = pb;
        this.tb = tb;
        this.fluid = fluid;
        this.length = length;
        this.diameter = diameter;
        this.area = Math.PI * diameter * diameter / 4;
        this.roughness = roughness;
        this.minorK = minorK;
        this.dz = dz;
    }

    /** Valve opening φ ∈ [0, 1] (1 = no valve restriction). */
    public void setOpening(double opening) {
        this.opening = Math.max(0, Math.min(1, opening));
    }

    public double opening() {
        return opening;
    }

    /**
     * Adds a bladed rotor on the rotating body whose angular-velocity node is {@code omega}.
     *
     * @param meanRadius  mean blade radius [m]
     * @param exitAngle   blade exit angle β from the axial direction [rad]
     * @param handedness  +1 if positive ω pumps a → b, −1 otherwise
     */
    public FluidBranch withRotor(Node omega, double meanRadius, double exitAngle, double handedness, double lossCoefficient) {
        this.rotor = omega;
        this.rotorRadius = meanRadius;
        this.tanBeta = Math.tan(exitAngle);
        this.hand = Math.signum(handedness);
        this.lossCoefficient = lossCoefficient;
        return this;
    }

    public int flowIndex() {
        return mIdx;
    }

    @Override
    public void setup(SetupContext ctx) {
        mScale = Math.max(fluid.density(101_325, 293.15) * area * 1e-3, 1e-9); // 1 mm/s through the bore
        mIdx = ctx.allocUnknown("mdot", 1e-6 * mScale);
    }

    /** Evaluates [R (Pa), energy leaving a (W), energy leaving b (W), torque taken from the rotor (N·m)]. */
    private void eval(double m, double pA, double pB, double tA, double tB, double w, double[] out) {
        tA = Math.max(tA, 1.0);
        tB = Math.max(tB, 1.0);
        double pm = 0.5 * (pA + pB), tm = 0.5 * (tA + tB);
        double rho = Math.max(fluid.density(pm, tm), 1e-9), mu = fluid.viscosity(tm);
        double v = m / (rho * area);
        double dp = frictionLoss(v, rho, mu);
        double vs = 10 * mu / (rho * diameter);
        dp += minorK * rho * v * Math.sqrt(v * v + vs * vs) / 2;
        if (opening < 1) {
            double av = 0.62 * opening * area;
            double vo = m / (rho * av), vso = 10 * mu / (rho * diameter);
            dp += rho * vo * Math.sqrt(vo * vo + vso * vso) / 2;
        }
        dp += rho * PhysicalConstants.STANDARD_GRAVITY * dz;
        double power = 0, torque = 0;
        if (rotor != null) {
            double u = hand * w * rotorRadius;
            double cx = v;
            double ct = u - cx * tanBeta; // swirl given to the flow
            double dh0 = u * ct;
            double w2 = cx * cx + (u - ct) * (u - ct);
            dp -= rho * dh0;
            dp += lossCoefficient * rho * w2 / 2 * Math.tanh(m / mScale);
            power = m * dh0;
            torque = m * hand * rotorRadius * ct;
        }
        double up = 0.5 * (1 + Math.tanh(m / (1e-3 * mScale)));
        double h = up * fluid.enthalpy(pA, tA) + (1 - up) * fluid.enthalpy(pB, tB);
        double flowH = m * h;
        out[0] = pA - pB - dp;
        out[1] = flowH - (1 - up) * power;
        out[2] = -flowH - up * power;
        out[3] = torque;
    }

    private double frictionLoss(double v, double rho, double mu) {
        double re = Math.abs(v) * rho * diameter / mu;
        double lam = 32 * mu * length * v / (diameter * diameter);
        if (re <= 2000) return lam;
        double turb = darcyTurbulent(Math.max(re, 2000), roughness / diameter) * (length / diameter) * rho * v * Math.abs(v) / 2;
        if (re >= 4000) return turb;
        double s = (re - 2000) / 2000;
        s = s * s * (3 - 2 * s);
        return lam * (1 - s) + turb * s;
    }

    /** Haaland (1983) explicit approximation of the Colebrook equation. */
    static double darcyTurbulent(double re, double relRoughness) {
        double x = -1.8 * Math.log10(Math.pow(relRoughness / 3.7, 1.11) + 6.9 / re);
        return 1 / (x * x);
    }

    @Override
    public void load(LoadContext ctx) {
        int[] idx = {mIdx, pa.index(), pb.index(), ta.index(), tb.index(), rotor == null ? -1 : rotor.index()};
        double[] val = new double[6];
        for (int k = 0; k < 6; k++) val[k] = ctx.x(idx[k]);
        double[] f0 = new double[4];
        boolean closed = opening <= 0;
        eval(val[0], val[1], val[2], val[3], val[4], val[5], f0);
        int[] rows = {mIdx, ta.index(), tb.index(), rotor == null ? -1 : rotor.index()};
        if (closed) {
            ctx.addF(mIdx, val[0] * 1e3);
            ctx.addJ(mIdx, mIdx, 1e3);
        } else {
            ctx.addF(mIdx, f0[0]);
        }
        for (int r = 1; r < 4; r++) ctx.addF(rows[r], f0[r]);
        ctx.addF(pa.index(), val[0]);
        ctx.addF(pb.index(), -val[0]);
        ctx.addJ(pa.index(), mIdx, 1);
        ctx.addJ(pb.index(), mIdx, -1);
        // finite-difference Jacobian of the residual, energy and torque rows
        double[] f1 = new double[4];
        for (int k = 0; k < 6; k++) {
            if (idx[k] < 0) continue;
            double step = switch (k) {
                case 0 -> 1e-6 * Math.max(Math.abs(val[0]), mScale);
                case 1, 2 -> 1e-7 * Math.max(Math.abs(val[k]), 1e3);
                case 3, 4 -> 1e-5 * Math.max(Math.abs(val[k]), 1);
                default -> 1e-6 * Math.max(Math.abs(val[k]), 1);
            };
            double[] v = val.clone();
            v[k] += step;
            eval(v[0], v[1], v[2], v[3], v[4], v[5], f1);
            for (int r = 0; r < 4; r++) {
                if (r == 0 && closed) continue;
                ctx.addJ(rows[r], idx[k], (f1[r] - f0[r]) / step);
            }
        }
    }

    /** Mass flow a → b [kg/s]. */
    public double massFlow(StateView s) {
        return s.x(mIdx);
    }

    @Override
    public String label() {
        return "FluidBranch(" + fluid.id() + ", L=" + length + ", D=" + diameter + (rotor != null ? ", rotor" : "") + ")";
    }
}
