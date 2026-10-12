package dev.emergence.core.sim.elements;

import dev.emergence.core.chem.Species;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

import java.util.List;

/**
 * Flow of an ideal-gas mixture through a passage (pipe, orifice, valve) between two ends, each a {@link GasReactor}
 * with a pressure node or a fixed reservoir (the atmosphere, a supply at constant state). The unknown is the molar
 * flow ṅ from a to b; the pressure difference drives it against turbulent and laminar resistance,
 * <pre>  p_a − p_b = K·ṁ|ṁ|/(2ρA_eff²) + 32·μ·L·ṁ/(ρ·A_eff·D²),   ṁ = M·ṅ,</pre>
 * with density ρ = pM/RT, molar mass M, temperature and composition taken from the upstream end (smoothly blended
 * through ṅ ≈ 0). Two corrections make it a gas passage rather than a liquid one: the hydrostatic head ρ·g·Δz of the
 * gas in it (so a column of hot gas is lighter than the air outside and a chimney draws), and choking — the
 * downstream pressure the passage feels never falls below the critical ratio (2/(γ+1))^{γ/(γ−1)} ≈ 0.53 of the
 * upstream pressure, so a high-pressure supply through a valve delivers the choked flow, not more. The flow carries species ṅ·x_i and enthalpy ṅ·h(T_up) — h including formation enthalpies — out of
 * the upstream volume and into the downstream one, so mass, elements and energy are conserved; what crosses a
 * reservoir boundary is counted as external power. A valve scales the open area by its opening (fully shut: a
 * 10⁻⁶ leak keeps the equations regular). Quasi-steady: the gas in the passage itself stores nothing.
 */
public final class MixtureBranch implements Element {
    private static final double R = PhysicalConstants.GAS_CONSTANT;
    /**
     * Width [mol/s] of the blend between the two ends' properties as the flow reverses: 10⁻⁵ mol/s (0.3 mg/s of air) —
     * negligible as a transport, but wide enough that Newton does not flip between the two states at every iteration
     * when a passage carries almost nothing either way (both ends use the same blend, so nothing is lost). 10⁻⁶ failed
     * once liquids drained through a still's pipes, nudging their gas to and fro.
     */
    private static final double Q_BLEND = 1e-5;

    /** One end of the passage: a reacting-gas volume, or a reservoir of fixed pressure, temperature and composition. */
    public static final class End {
        final GasReactor gas;
        final double p, t;
        final double[] x;

        private End(GasReactor gas, double p, double t, double[] x) {
            this.gas = gas;
            this.p = p;
            this.t = t;
            this.x = x;
        }

        public static End of(GasReactor gas) {
            if (gas.pressureNode() == null) throw new IllegalArgumentException("the gas volume needs a pressure node");
            return new End(gas, 0, 0, null);
        }

        public static End reservoir(double pressure, double temperature, double[] moleFractions) {
            return new End(null, pressure, temperature, moleFractions.clone());
        }
    }

    private final Species[] sp;
    private final End a, b;
    private final double area, diameter, length, k;
    private double dz;
    private double opening = 1;
    private int qIdx;
    private String tag = "";

    /**
     * @param species  the species list shared by both ends (same order)
     * @param area     open flow area [m²]
     * @param diameter hydraulic diameter [m]
     * @param length   passage length [m]
     * @param k        loss coefficient (entrance, exit, bends, plus f·L/D)
     */
    public MixtureBranch(List<Species> species, End a, End b, double area, double diameter, double length, double k) {
        this.sp = species.toArray(new Species[0]);
        this.a = a;
        this.b = b;
        this.area = area;
        this.diameter = diameter;
        this.length = Math.max(length, diameter);
        this.k = Math.max(k, 0.5);
        for (End e : new End[]{a, b}) {
            if (e.gas != null && !e.gas.species().equals(species)) throw new IllegalArgumentException("species lists differ");
            if (e.gas == null && e.x.length != sp.length) throw new IllegalArgumentException("reservoir composition size");
        }
    }

    /** Height of b above a [m] (the gas column's weight opposes upward flow). */
    public MixtureBranch withRise(double dz) {
        this.dz = dz;
        return this;
    }

    public MixtureBranch withTag(String tag) {
        this.tag = tag;
        return this;
    }

    private AngleTracker cam;
    private double camPhase, camLap;

    /**
     * A valve worked by a cam (an eccentric) on a turning body: the opening is scaled by
     * smoothstep((cos(θ − θ_c) − c)/(1 − c)) — open while the cam's throw is within arccos(c) of pointing at the valve
     * (as {@link FluidBranch#withCam}).
     */
    public MixtureBranch withCam(AngleTracker tracker, double phase, double lap) {
        this.cam = tracker;
        this.camPhase = phase;
        this.camLap = Math.max(0, Math.min(lap, 0.95));
        return this;
    }

    double camLift(double theta) {
        double u = (Math.cos(theta - camPhase) - camLap) / (1 - camLap);
        u = Math.max(0, Math.min(1, u));
        return u * u * (3 - 2 * u);
    }

    /** True if this passage's valve is worked by a cam. */
    public boolean hasCam() {
        return cam != null;
    }

    /** Valve opening 0 (shut) … 1 (fully open). */
    public void setOpening(double o) {
        this.opening = Math.max(0, Math.min(1, o));
    }

    public double opening() {
        return opening;
    }

    @Override
    public void setup(SetupContext ctx) {
        qIdx = ctx.allocUnknown("ndot:" + tag, 1e-9);
    }

    // ---- local state vector: [ṅ, p_a, T_a, n_a…, p_b, T_b, n_b…] (reservoir ends contribute no unknowns)
    private int m() {
        return sp.length;
    }

    private int[] columns() {
        int m = m();
        int[] c = new int[1 + 2 * (2 + m) + (cam != null ? 1 : 0)];
        java.util.Arrays.fill(c, -1);
        c[0] = qIdx;
        if (cam != null) c[c.length - 1] = cam.angleIndex(); // the cam's angle, last
        for (int side = 0; side < 2; side++) {
            End e = side == 0 ? a : b;
            int o = 1 + side * (2 + m);
            if (e.gas == null) continue;
            c[o] = e.gas.pressureNode().index();
            c[o + 1] = e.gas.temperatureNode().index();
            for (int i = 0; i < m; i++) c[o + 2 + i] = e.gas.molesIndex(i);
        }
        return c;
    }

    /** End state {p, T, M, x…} from the local vector. */
    private double[] endState(double[] v, int side) {
        End e = side == 0 ? a : b;
        int m = m(), o = 1 + side * (2 + m);
        double[] s = new double[3 + m];
        if (e.gas == null) {
            s[0] = e.p;
            s[1] = e.t;
            System.arraycopy(e.x, 0, s, 3, m);
        } else {
            s[0] = v[o];
            s[1] = Math.max(v[o + 1], 50);
            double nt = 0;
            for (int i = 0; i < m; i++) nt += Math.max(0, v[o + 2 + i]);
            for (int i = 0; i < m; i++) s[3 + i] = nt > 0 ? Math.max(0, v[o + 2 + i]) / nt : 1.0 / m;
        }
        double mm = 0;
        for (int i = 0; i < m; i++) mm += s[3 + i] * sp[i].molarMass();
        s[2] = mm;
        return s;
    }

    /**
     * Contributions to the rows [branch, (a: p-row unused, T, n…), (b: …, T, n…)] at local state v, laid out like
     * the columns.
     */
    private double[] residual(double[] v) {
        int m = m();
        double[] f = new double[v.length];
        double q = v[0];
        double[] sa = endState(v, 0), sb = endState(v, 1);
        double q0 = Q_BLEND, w = 0.5 * (1 + Math.tanh(q / q0));
        double tUp = w * sa[1] + (1 - w) * sb[1], pUp = w * sa[0] + (1 - w) * sb[0], mUp = w * sa[2] + (1 - w) * sb[2];
        double rho = Math.max(pUp, 1) * mUp / (R * tUp);
        double open = cam == null ? opening : opening * camLift(v[v.length - 1]);
        double aEff = Math.max(open, 1e-6) * area, mdot = mUp * q;
        double mu = 1.8e-5 * Math.pow(tUp / 300, 0.7);
        // choking: the passage feels at most the critical pressure drop (smoothly: soft max of the downstream
        // pressure and the critical fraction of the upstream one), γ from the upstream mixture
        double cpUp = 0;
        for (int i = 0; i < m; i++) cpUp += (w * sa[3 + i] + (1 - w) * sb[3 + i]) * sp[i].cp(tUp);
        double gamma = cpUp / (cpUp - R), crit = Math.pow(2 / (gamma + 1), gamma / (gamma - 1));
        double pHi = Math.max(sa[0], sb[0]), pLo = Math.min(sa[0], sb[0]), pc = crit * pHi;
        // C¹ max(pLo, pc): exact unless pLo is within 1 % of the critical pressure
        double soft = 0.01 * pHi, d = pLo - pc;
        double pLoEff = pc + (d >= soft ? d : d <= -soft ? 0 : (d + soft) * (d + soft) / (4 * soft));
        // the weight of the gas standing in the passage: half of it from each end (the mean of their densities, not
        // the upwind one — with heavier gas above lighter, an upwind head has no consistent flow near zero: flowing
        // down it is heavy, flowing up light, and Newton chatters between them)
        double rhoA = Math.max(sa[0], 1) * sa[2] / (R * sa[1]), rhoB = Math.max(sb[0], 1) * sb[2] / (R * sb[1]);
        double dp = Math.signum(sa[0] - sb[0]) * (pHi - Math.min(pLoEff, pHi)) - 0.5 * (rhoA + rhoB) * 9.80665 * dz;
        f[0] = dp - k * mdot * Math.abs(mdot) / (2 * rho * aEff * aEff) - 32 * mu * length * mdot / (rho * aEff * diameter * diameter);
        double h = 0;
        double[] xu = new double[m];
        for (int i = 0; i < m; i++) {
            xu[i] = w * sa[3 + i] + (1 - w) * sb[3 + i];
            h += xu[i] * sp[i].enthalpy(tUp);
        }
        for (int side = 0; side < 2; side++) {
            End e = side == 0 ? a : b;
            if (e.gas == null) continue;
            double sgn = side == 0 ? 1 : -1; // flow leaves a, enters b
            int o = 1 + side * (2 + m);
            f[o + 1] = sgn * q * h;
            for (int i = 0; i < m; i++) f[o + 2 + i] = sgn * q * xu[i];
        }
        return f;
    }

    @Override
    public void load(LoadContext ctx) {
        int[] col = columns();
        double[] v = new double[col.length];
        for (int j = 0; j < col.length; j++) if (col[j] >= 0) v[j] = ctx.x(col[j]);
        double[] f0 = residual(v);
        for (int r = 0; r < col.length; r++) if (col[r] >= 0 && f0[r] != 0) ctx.addF(rowOf(col, r), f0[r]);
        // Jacobian by finite differences over the few unknowns this passage touches. The flow column is central, with a
        // relative step of 10⁻⁴: the residual is smooth (quadratic) in the flow, and at high pressure a step of 10⁻⁷
        // changes it by no more than the rounding of its ~10⁷ Pa terms (a noisy column made Newton cycle).
        for (int j = 0; j < col.length; j++) {
            if (col[j] < 0) continue;
            double x0 = v[j];
            boolean central = j == 0;
            double hj = (central ? 1e-4 : 1e-7) * Math.max(Math.abs(x0), scale(j));
            v[j] = x0 + hj;
            double[] f1 = residual(v);
            double[] fm = null;
            if (central) {
                v[j] = x0 - hj;
                fm = residual(v);
            }
            v[j] = x0;
            for (int r = 0; r < col.length; r++) {
                if (col[r] < 0) continue;
                double d = central ? (f1[r] - fm[r]) / (2 * hj) : (f1[r] - f0[r]) / hj;
                if (d != 0) ctx.addJ(rowOf(col, r), col[j], d);
                // the flow equation is a pressure balance of terms as large as the pressures themselves: their
                // rounding (a few ulps) moves the flow by that over the equation's slope — a floor on its resolution
                if (central && r == 0 && d != 0) {
                    double p = Math.max(Math.abs(endState(v, 0)[0]), Math.abs(endState(v, 1)[0]));
                    ctx.noiseFloor(qIdx, 8 * Math.ulp(p) / Math.abs(d));
                }
            }
        }
    }

    /** The row a local entry belongs to: the branch row, or the end's temperature/species rows (pressure rows untouched). */
    private int rowOf(int[] col, int r) {
        int m = m();
        if (r == 0) return qIdx;
        if (r >= 1 + 2 * (2 + m)) return -1; // the cam angle: an input only
        int o = (r - 1) % (2 + m);
        return o == 0 ? -1 : col[r];
    }

    private double scale(int j) {
        int m = m();
        if (j == 0) return 1e-3;
        if (j >= 1 + 2 * (2 + m)) return 1; // cam angle [rad]
        int o = (j - 1) % (2 + m);
        return o == 0 ? 1e3 : o == 1 ? 1 : 1e-6;
    }

    /** Molar flow a → b [mol/s]. */
    public double molarFlow(StateView s) {
        return s.x(qIdx);
    }

    /** Mass flow a → b [kg/s]. */
    public double massFlow(StateView s) {
        int[] col = columns();
        double[] v = new double[col.length];
        for (int j = 0; j < col.length; j++) if (col[j] >= 0) v[j] = s.x(col[j]);
        double q = v[0], w = 0.5 * (1 + Math.tanh(q / Q_BLEND));
        return q * (w * endState(v, 0)[2] + (1 - w) * endState(v, 1)[2]);
    }

    /** Enthalpy brought in from (or sent out to) reservoir ends: energy crossing the system boundary. */
    @Override
    public double externalPower(StateView s) {
        if (a.gas != null && b.gas != null) return 0;
        int[] col = columns();
        double[] v = new double[col.length];
        for (int j = 0; j < col.length; j++) if (col[j] >= 0) v[j] = s.x(col[j]);
        double q = v[0], w = 0.5 * (1 + Math.tanh(q / Q_BLEND));
        double[] sa = endState(v, 0), sb = endState(v, 1);
        double tUp = w * sa[1] + (1 - w) * sb[1], h = 0;
        for (int i = 0; i < m(); i++) h += (w * sa[3 + i] + (1 - w) * sb[3 + i]) * sp[i].enthalpy(tUp);
        double in = 0;
        if (a.gas == null) in += q * h;  // from the reservoir at a into the system
        if (b.gas == null) in -= q * h;  // out of the system into the reservoir at b
        return in;
    }

    @Override
    public String label() {
        return "MixtureBranch(" + tag + ")";
    }
}
