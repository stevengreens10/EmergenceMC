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
 * downstream pressure); for a liquid ṁ = C_d A √(2ρΔp). Below |Δp| ≈ 10 Pa the seat flow turns laminar,
 * ṁ ∝ Δp: the nozzle flow is scaled by (|Δp|/√(Δp² + Δp₀²))^½, smooth with a finite slope through zero.
 * Pipe friction in such a short branch is neglected.
 * <p>
 * Energy: the flow carries the upstream specific enthalpy h = u + p/ρ (upwinded, smoothly through
 * ṁ = 0) and the rotor's shaft work is added to the downstream volume, so friction heat stays in the
 * fluid and the first law holds exactly. Kinetic energy of the flow and fluid inertia are neglected
 * (low Mach number, no acoustic or water-hammer transients).
 * <p>
 * <b>Water/steam ends</b> ({@link FluidVolume.Port}): the fluid leaving a two-phase volume is the phase at the
 * port's height (liquid below the level, vapour above). Its enthalpy, density and viscosity then come from the
 * port, upwinded like the enthalpy, and the flow is treated as homogeneous. A valve passing vapour uses the
 * compressible nozzle relation with γ = 1.3 and R = 461.5 J/(kg·K) (steam); a liquid the incompressible one;
 * a mixture the vapour-mass-weighted blend (flashing two-phase choking is not modelled).
 * <p>
 * <b>Check and relief valves</b> ({@link #withCheck}): a spring-loaded disc that opens only for forward flow,
 * lifting in proportion to the pressure difference above its cracking pressure (quasi-static spring).
 * <p>
 * <b>Cam-operated valves</b> ({@link #withCam}): the orifice opening follows a cam (an eccentric) on a turning
 * body, opening fraction smoothstep((cos(θ − θ_c) − c)/(1 − c)): open while the cam's throw is within
 * arccos(c) of pointing at the valve. Valve gear inertia, spring and friction forces on the cam are neglected.
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
    private String tag = "";
    /** Pressure drop below which flow through a valve seat is treated as laminar (linear) [Pa]. */
    static final double LAMINAR_DP = 10;
    private FluidVolume.Port portA, portB;
    private double crack = Double.NaN, fullOpen, checkSense = 1;
    private AngleTracker cam;
    private double camPhase, camLap;
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
        return orificeFlow(pA, pB, tA, tB, null, null);
    }

    /** As {@link #orificeFlow(double, double, double, double)}, with the outflow properties of two-phase ends. */
    double orificeFlow(double pA, double pB, double tA, double tB, double[] endA, double[] endB) {
        double dp = pA - pB;
        boolean fwd = dp >= 0;
        double pu = Math.max(fwd ? pA : pB, 1.0), pd = Math.max(fwd ? pB : pA, 0.0), tu = Math.max(fwd ? tA : tB, 1.0);
        double area = dischargeCoefficient * orificeArea * opening * camOpening;
        if (!Double.isNaN(crack)) area *= checkLift(checkSense * dp);
        // below ~10 Pa the flow through the seat turns laminar: ṁ ∝ Δp (smoothly joined to the nozzle law)
        double reg = Math.sqrt(Math.abs(dp) / Math.sqrt(dp * dp + LAMINAR_DP * LAMINAR_DP));
        double m;
        double[] up = fwd ? endA : endB;
        if (fluid instanceof dev.emergence.core.fluids.IdealGas g) {
            m = area * gasNozzle(pu, pd, tu, g.gamma(tu), g.gasConstant());
        } else if (up != null) {
            double x = up[3];
            double gas = x > 0 ? gasNozzle(pu, pd, tu, 1.3, 461.5) : 0;
            double liq = x < 1 ? Math.sqrt(2 * up[1] * Math.abs(dp)) : 0;
            m = area * (x * gas + (1 - x) * liq);
        } else {
            m = area * Math.sqrt(2 * fluid.density(pu, tu) * Math.abs(dp));
        }
        return (fwd ? 1 : -1) * m * reg;
    }

    /** Isentropic ideal-gas nozzle mass flux ṁ/A [kg/(s·m²)], choking at the critical pressure ratio. */
    static double gasNozzle(double pu, double pd, double tu, double gam, double r) {
        double rCrit = Math.pow(2 / (gam + 1), gam / (gam - 1));
        double ratio = Math.max(pd / pu, rCrit);
        double psi = Math.pow(ratio, 2 / gam) - Math.pow(ratio, (gam + 1) / gam);
        return pu * Math.sqrt(Math.max(2 * gam / ((gam - 1) * r * tu) * psi, 0));
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

    /** Phase-aware ends: the ports at which this branch meets two-phase volumes (null for an ordinary end). */
    public FluidBranch withPorts(FluidVolume.Port a, FluidVolume.Port b) {
        this.portA = a;
        this.portB = b;
        return this;
    }

    /**
     * Makes this branch's orifice a spring-loaded check (or relief) valve passing only a → b: closed below the
     * cracking pressure difference, fully open {@code fullOpenRise} above it.
     */
    public FluidBranch withCheck(double crackingPressure, double fullOpenRise) {
        return withCheck(crackingPressure, fullOpenRise, 1);
    }

    /** As {@link #withCheck(double, double)}; sense −1 makes the valve pass only b → a. */
    public FluidBranch withCheck(double crackingPressure, double fullOpenRise, double sense) {
        this.crack = crackingPressure;
        this.fullOpen = Math.max(fullOpenRise, 1);
        this.checkSense = Math.signum(sense);
        return this;
    }

    /**
     * Makes this branch's orifice open and close with a cam on a turning body: fully open when the body's angle
     * is {@code phase}, and open while cos(θ − phase) exceeds {@code lap} (lap ∈ [0, 1)).
     */
    public FluidBranch withCam(AngleTracker tracker, double phase, double lap) {
        this.cam = tracker;
        this.camPhase = phase;
        this.camLap = Math.max(0, Math.min(lap, 0.95));
        return this;
    }

    /** Cam opening fraction at body angle θ. */
    public double camLift(double theta) {
        double u = (Math.cos(theta - camPhase) - camLap) / (1 - camLap);
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    /** True if this branch's valve is worked by a cam. */
    public boolean hasCam() {
        return cam != null;
    }

    /** Effective opening now, including any cam. */
    public double opening(StateView s) {
        return cam == null ? opening : opening * camLift(s.x(cam.angleIndex()));
    }

    /** Lift fraction of a check valve for the pressure difference p_a − p_b. */
    double checkLift(double dp) {
        double u = (dp - crack) / fullOpen;
        if (u <= 0) return 1e-6 * Math.exp(Math.max(u, -50)); // a seated disc: a vanishing leak keeps Newton smooth
        return Math.min(1, u) + 1e-6;
    }

    /** Diagnostic tag (e.g. the parts this branch joins), shown in solver messages. */
    public FluidBranch withTag(String tag) {
        this.tag = "[" + tag + "]";
        return this;
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
        // flow resolution consistent with the pressure tolerance (10 mPa): the laminar conductance
        // ρAD²/(32μL) of the lightest phase the branch carries (vapour for water/steam)
        boolean wet = fluid instanceof dev.emergence.core.fluids.WaterSteam;
        double rho = wet ? 0.6 : fluid.density(101_325, 293.15), mu = wet ? 1.2e-5 : fluid.viscosity(293.15);
        double g = rho * area * dh * dh / (32 * mu * Math.max(length, 1e-3));
        mIdx = ctx.allocUnknown("mdot" + tag, Math.max(1e-6 * mScale, 1e-2 * g));
    }

    private final double[] endA = new double[5], endB = new double[5];
    private double camOpening = 1;

    /** Evaluates [R (Pa), energy leaving a (W), energy leaving b (W), torque taken from the rotor (N·m)]. */
    private void eval(double m, double pA, double pB, double tA, double tB, double w, double sA, double sB, double th, double[] out) {
        camOpening = cam == null ? 1 : camLift(th) + 1e-6;
        tA = Math.max(tA, 1.0);
        tB = Math.max(tB, 1.0);
        double up = 0.5 * (1 + Math.tanh(m / (1e-3 * mScale)));
        double rho, mu, hA, hB;
        boolean ported = portA != null || portB != null;
        if (ported) {
            end(portA, pA, tA, sA, endA);
            end(portB, pB, tB, sB, endB);
            rho = Math.max(1 / (up * endA[4] + (1 - up) * endB[4]), 1e-9);
            mu = up * endA[2] + (1 - up) * endB[2];
            hA = endA[0];
            hB = endB[0];
        } else {
            double pm = Math.max(0.5 * (pA + pB), fluid.isGas() ? 1.0 : -1e9), tm = 0.5 * (tA + tB);
            rho = Math.max(fluid.density(pm, tm), 1e-9);
            mu = fluid.viscosity(tm);
            hA = fluid.enthalpy(pA, tA);
            hB = fluid.enthalpy(pB, tB);
        }
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
        double h = up * hA + (1 - up) * hB;
        double flowH = m * h;
        // orifice branch: flow set by the nozzle relation (residual in kg/s)
        out[0] = orifice ? (m - orificeFlow(pA, pB, tA, tB, ported ? endA : null, ported ? endB : null)) * 1e5 / Math.max(mScale * 1e3, 1e-12)
                : pA - pB - dp;
        out[1] = flowH - (1 - up) * power;
        out[2] = -flowH - up * power;
        out[3] = torque;
    }

    /** Outflow properties [h, ρ, μ, x, 1/ρ] at one end: from its port, or from the single-phase fluid. */
    private void end(FluidVolume.Port port, double p, double t, double s, double[] out) {
        if (port != null) {
            port.outflow(t, s, out);
            return;
        }
        double rho = Math.max(fluid.density(p, t), 1e-9);
        out[0] = fluid.enthalpy(p, t);
        out[1] = rho;
        out[2] = fluid.viscosity(t);
        out[3] = fluid.isGas() ? 1 : 0;
        out[4] = 1 / rho;
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
        int[] idx = {mIdx, pa.index(), pb.index(), ta.index(), tb.index(), rotor == null ? -1 : rotor.index(),
                portA == null ? -1 : portA.phaseIndex(), portB == null ? -1 : portB.phaseIndex(),
                cam == null ? -1 : cam.angleIndex()};
        double[] val = new double[9];
        for (int k = 0; k < 9; k++) val[k] = idx[k] < 0 ? 0 : ctx.x(idx[k]);
        double[] f0 = new double[4];
        boolean closed = opening <= 0;
        eval(val[0], val[1], val[2], val[3], val[4], val[5], val[6], val[7], val[8], f0);
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
        for (int k = 0; k < 9; k++) {
            if (idx[k] < 0) continue;
            double step = switch (k) {
                case 0 -> 1e-6 * Math.max(Math.abs(val[0]), mScale);
                case 1, 2 -> 1e-7 * Math.max(Math.abs(val[k]), 1e3);
                case 3, 4 -> 1e-5 * Math.max(Math.abs(val[k]), 1);
                case 6, 7 -> 1e-9;
                default -> 1e-6 * Math.max(Math.abs(val[k]), 1);
            };
            double[] v = val.clone();
            v[k] += step;
            eval(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], f1);
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
