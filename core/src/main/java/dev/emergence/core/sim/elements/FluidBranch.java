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
 *   <li>Rotor (optional): a row of staggered blades on a rotating body, by blade-element theory at the
 *       mean radius. In the rotor frame the blades meet the relative velocity w = (c_x, −U) (axial flow
 *       speed c_x = ṁ/(ρA), blade speed U = ω·r, no inlet swirl) at an angle of attack α to their chord,
 *       which is set at the stagger angle β from the axis. Lift ⟂ w and drag ∥ w per unit of blade area
 *       σA: C_L = sin 2α·(1 + (π − 1)·e^{−(sin α/0.25)²}) (thin-aerofoil slope 2π below ~14°, flat-plate
 *       sin 2α after stall), C_D = C_D0 + 1.8 sin²α. The axial force on the fluid is a pressure rise
 *       Δp = F_x/A, the tangential force a torque on the shaft, and the shaft power F_θ·U all enters the
 *       fluid. Because lift does no work in the blade frame, power − Q·Δp = drag·|w| ≥ 0: energy is
 *       conserved and the second law holds. One model covers all four quadrants — fan or pump when
 *       driven, turbine or windmill when the flow drives it, reversing with the flow, stalling at
 *       extreme incidence.</li>
 * </ul>
 * <b>Orifice branches</b> (valves): when an orifice is set the restriction dominates and the flow is that of
 * an isentropic nozzle of area C_d·A·φ — for a gas ṁ = C_d A p_u √(2γ/((γ−1)RT_u)·(r^{2/γ} − r^{(γ+1)/γ}))
 * with r = p_d/p_u, choking at the critical ratio r* = (2/(γ+1))^{γ/(γ−1)} (flow then independent of the
 * downstream pressure); for a liquid ṁ = C_d A √(2ρΔp). The flow is regularised for |Δp| ≲ 1 Pa so it
 * stays differentiable through zero. Pipe friction in such a short branch is neglected.
 * <p>
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
    private double dh;
    private double orificeArea = 0, dischargeCoefficient = 0.62;
    private Node rotor;
    private double rotorRadius, rotorArea, stagger, pitch = 1, bodySense = 1, flowAlongAxis = 1, cd0 = 0.02, solidity = 0.8;
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
        this.dh = diameter;
        this.roughness = roughness;
        this.minorK = minorK;
        this.dz = dz;
    }

    /** Hydraulic diameter for friction when the passage is not a plain round bore (e.g. finned tube) [m]. */
    public FluidBranch withHydraulicDiameter(double d) {
        this.dh = Math.min(d, diameter);
        return this;
    }

    /** Valve opening φ ∈ [0, 1] (1 = no valve restriction). */
    public void setOpening(double opening) {
        this.opening = Math.max(0, Math.min(1, opening));
    }

    public double opening() {
        return opening;
    }

    /** Makes this branch an orifice (valve seat) of the given area and discharge coefficient. */
    public FluidBranch withOrifice(double area, double cd) {
        this.orificeArea = area;
        this.dischargeCoefficient = cd;
        return this;
    }

    /** Mass flow a → b through the orifice for the given end states [kg/s]. */
    double orificeFlow(double pA, double pB, double tA, double tB) {
        double dp = pA - pB;
        boolean fwd = dp >= 0;
        double pu = fwd ? pA : pB, pd = fwd ? pB : pA, tu = fwd ? tA : tB;
        double area = dischargeCoefficient * orificeArea * opening;
        double reg = Math.tanh(Math.abs(dp) / 1.0);
        double m;
        if (fluid instanceof dev.emergence.core.fluids.IdealGas g) {
            double gam = g.gamma(tu), r = g.gasConstant();
            double rCrit = Math.pow(2 / (gam + 1), gam / (gam - 1));
            double ratio = Math.max(pd / pu, rCrit);
            double psi = Math.pow(ratio, 2 / gam) - Math.pow(ratio, (gam + 1) / gam);
            m = area * pu * Math.sqrt(Math.max(2 * gam / ((gam - 1) * r * tu) * psi, 0));
        } else {
            m = area * Math.sqrt(2 * fluid.density(pu, tu) * Math.abs(dp));
        }
        return (fwd ? 1 : -1) * m * reg;
    }

    /**
     * Adds a bladed rotor on the rotating body whose angular-velocity node is {@code omega}.
     *
     * @param meanRadius    mean blade radius [m]
     * @param annulusArea   flow area through the blade row [m²]
     * @param staggerAngle  blade chord angle from the rotor axis [rad]
     * @param pitch         +1: positive rotation about the rotor's axis pushes fluid along it; −1 the other hand
     * @param bodySense     +1 if the body's positive rotation is positive about the rotor axis, −1 otherwise
     * @param flowAlongAxis +1 if this branch's a → b direction is along the rotor's axis, −1 otherwise
     * @param cd0           blade profile drag coefficient at zero incidence
     * @param solidity      blade area per annulus area (chord·count / circumference)
     */
    public FluidBranch withRotor(Node omega, double meanRadius, double annulusArea, double staggerAngle, double pitch,
                                 double bodySense, double flowAlongAxis, double cd0, double solidity) {
        this.rotor = omega;
        this.rotorRadius = meanRadius;
        this.rotorArea = annulusArea;
        this.stagger = staggerAngle;
        this.pitch = Math.signum(pitch);
        this.bodySense = Math.signum(bodySense);
        this.flowAlongAxis = Math.signum(flowAlongAxis);
        this.cd0 = cd0;
        this.solidity = solidity;
        return this;
    }

    /** Blade lift coefficient: thin-aerofoil slope before stall, flat plate after. */
    static double liftCoefficient(double alpha) {
        double s = Math.sin(alpha);
        return Math.sin(2 * alpha) * (1 + (Math.PI - 1) * Math.exp(-(s / 0.25) * (s / 0.25)));
    }

    public int flowIndex() {
        return mIdx;
    }

    /** True if this branch connects to the given control volume. */
    public boolean touches(FluidVolume v) {
        return v != null && (v.pressureNode() == pa || v.pressureNode() == pb);
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
        boolean orifice = orificeArea > 0;
        double v = m / (rho * area);
        double dp = frictionLoss(v, rho, mu);
        double vs = 10 * mu / (rho * diameter);
        dp += minorK * rho * v * Math.sqrt(v * v + vs * vs) / 2;
        if (!orifice && opening < 1) {
            double av = 0.62 * opening * area;
            double vo = m / (rho * av), vso = 10 * mu / (rho * diameter);
            dp += rho * vo * Math.sqrt(vo * vo + vso * vso) / 2;
        }
        dp += rho * PhysicalConstants.STANDARD_GRAVITY * dz;
        double power = 0, torque = 0;
        if (rotor != null) {
            // rotor frame: x along the rotor axis, θ along its positive rotation
            double u = bodySense * w * rotorRadius;
            double cx = flowAlongAxis * m / (rho * rotorArea);
            double wx = cx, wt = -u, wn = Math.hypot(wx, wt);
            double fx = 0, ft = 0;
            if (wn > 1e-9) {
                double ex = wx / wn, et = wt / wn;
                double cxh = Math.cos(stagger), cth = -pitch * Math.sin(stagger); // chord direction
                double alpha = Math.atan2(cxh * et - cth * ex, cxh * ex + cth * et);
                double q = 0.5 * rho * wn * wn * solidity * rotorArea;
                double sa = Math.sin(alpha);
                double lift = q * liftCoefficient(alpha), drag = q * (cd0 + 1.8 * sa * sa);
                // force on the blades: lift along w rotated +90°, drag along w; the fluid feels the opposite
                double bx = lift * (-et) + drag * ex, bt = lift * ex + drag * et;
                fx = -bx;
                ft = -bt;
            }
            dp -= flowAlongAxis * fx / rotorArea;   // pressure rise along a → b
            power = ft * u;                          // work done on the fluid by the moving blades
            torque = -bodySense * (-ft) * rotorRadius; // torque taken from the body node (= −torque on the rotor)
        }
        double up = 0.5 * (1 + Math.tanh(m / (1e-3 * mScale)));
        double h = up * fluid.enthalpy(pA, tA) + (1 - up) * fluid.enthalpy(pB, tB);
        double flowH = m * h;
        // orifice branch: flow set by the nozzle relation (residual in kg/s)
        out[0] = orifice ? (m - orificeFlow(pA, pB, tA, tB)) * 1e5 / Math.max(mScale * 1e3, 1e-12) : pA - pB - dp;
        out[1] = flowH - (1 - up) * power;
        out[2] = -flowH - up * power;
        out[3] = torque;
    }

    private double frictionLoss(double v, double rho, double mu) {
        double re = Math.abs(v) * rho * dh / mu;
        double lam = 32 * mu * length * v / (dh * dh);
        if (re <= 2000) return lam;
        double turb = darcyTurbulent(Math.max(re, 2000), roughness / dh) * (length / dh) * rho * v * Math.abs(v) / 2;
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
