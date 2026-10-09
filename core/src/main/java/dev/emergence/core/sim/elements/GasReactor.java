package dev.emergence.core.sim.elements;

import dev.emergence.core.chem.Reaction;
import dev.emergence.core.chem.Species;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

import java.util.List;

/**
 * A well-mixed, closed volume of reacting ideal gases (a continuously stirred batch reactor). Unknowns are the moles
 * n_i of each species; the temperature is a thermal node, so walls, heaters and coolers exchange heat with it through
 * ordinary thermal elements. Balances:
 * <ul>
 *   <li>species: dn_i/dt = V·Σ_r ν_ir·r_r, with r_r = r_f − r_r' from {@link Reaction} (mass action, Arrhenius,
 *       reverse rates through K_c so reversible reactions settle at equilibrium);</li>
 *   <li>energy: dU/dt = Σ Q̇_in, with U = Σ n_i·u_i(T) and u_i including the energy of formation — reaction heat is
 *       not a source term but the change of composition at constant U, so energy and elements are conserved
 *       exactly (to the solver's tolerance).</li>
 * </ul>
 * Pressure p = Σ n_i·R·T/V. Concentrations entering the rate laws are softly bounded below by zero so Newton
 * iterates that overshoot slightly negative do not produce complex powers. Valid while the gas is ideal, well mixed
 * (stirred, or small and slow compared with diffusion) and below the dissociation temperatures the species list
 * omits. A local flame front is not resolved: ignition happens when the bulk runs away thermally (Semenov).
 */
public final class GasReactor implements Element {
    private static final double R = PhysicalConstants.GAS_CONSTANT, EPS = 1e-9, CUT = 1e-4;

    private final Species[] sp;
    private final Reaction[] rx;
    private final double[][] nu;       // [reaction][species]
    private final int[][] ord;         // species index per reaction order entry
    private final double[][] ordVal;
    private final int[][] prodIdx;     // products for reverse rates
    private final double[][] prodNu;
    private final double volume;
    private final Node temp;
    private final double[] n0;
    private final int[] nIdx, nSlot;
    private int uSlot;
    private double uRef;
    private Node pNode;
    private AngleTracker tracker;
    private SliderCrank crank;

    /**
     * @param species  species present (their order fixes the unknowns)
     * @param reactions reactions among them
     * @param volume   gas volume [m³]
     * @param temp     thermal node of the gas (its initial value is the starting temperature)
     * @param moles    initial moles of each species
     */
    public GasReactor(List<Species> species, List<Reaction> reactions, double volume, Node temp, double[] moles) {
        if (temp.domain() != Domain.THERMAL) throw new IllegalArgumentException("GasReactor needs a thermal node");
        this.sp = species.toArray(new Species[0]);
        this.rx = reactions.toArray(new Reaction[0]);
        this.volume = volume;
        this.temp = temp;
        this.n0 = moles.clone();
        this.nIdx = new int[sp.length];
        this.nSlot = new int[sp.length];
        nu = new double[rx.length][sp.length];
        ord = new int[rx.length][];
        ordVal = new double[rx.length][];
        prodIdx = new int[rx.length][];
        prodNu = new double[rx.length][];
        for (int r = 0; r < rx.length; r++) {
            for (int i = 0; i < sp.length; i++) nu[r][i] = rx[r].nu(sp[i]);
            var o = rx[r].orders();
            ord[r] = new int[o.size()];
            ordVal[r] = new double[o.size()];
            int k = 0;
            for (var e : o.entrySet()) {
                ord[r][k] = indexOf(e.getKey());
                ordVal[r][k++] = e.getValue();
            }
            var p = rx[r].products();
            prodIdx[r] = new int[p.size()];
            prodNu[r] = new double[p.size()];
            k = 0;
            for (var e : p.entrySet()) {
                prodIdx[r][k] = indexOf(e.getKey());
                prodNu[r][k++] = e.getValue();
            }
        }
    }

    /**
     * Gives the gas a pressure unknown (a fluid-domain node) defined by p·V = Σn·R·T, so that pipes, valves and pistons
     * can act on it like on any fluid volume.
     */
    public GasReactor withPressureNode(Node p) {
        if (p.domain() != Domain.FLUID) throw new IllegalArgumentException("pressure node must be a fluid node");
        this.pNode = p;
        return this;
    }

    /** Makes the volume a piston cylinder's: V(θ) from the slider-crank (work on the piston via {@link PistonCoupling}). */
    public GasReactor withPiston(AngleTracker tracker, SliderCrank crank) {
        this.tracker = tracker;
        this.crank = crank;
        return this;
    }

    public Node pressureNode() {
        return pNode;
    }

    /** Unknown index of species i's moles. */
    public int molesIndex(int i) {
        return nIdx[i];
    }

    public int indexOf(Species s) {
        for (int i = 0; i < sp.length; i++) if (sp[i] == s) return i;
        throw new IllegalArgumentException(s.id() + " is not in the reactor");
    }

    @Override
    public void setup(SetupContext ctx) {
        double total = 0;
        for (double v : n0) total += Math.abs(v);
        double tol = Math.max(1e-12, 1e-9 * total);
        for (int i = 0; i < sp.length; i++) {
            nIdx[i] = ctx.allocUnknown("n:" + sp[i].id(), tol);
            ctx.setInitial(nIdx[i], n0[i]);
            nSlot[i] = ctx.allocState(tol);
        }
        // the energy slot holds U minus the formation energy of the initial charge, so its truncation-error
        // tolerance is set by the sensible energy, not by the (large) chemical energy
        for (int i = 0; i < sp.length; i++) uRef += n0[i] * sp[i].internalEnergy(Species.T0);
        double cv = 0;
        for (int i = 0; i < sp.length; i++) cv += n0[i] * sp[i].cv(300);
        uSlot = ctx.allocState(Math.max(1e-9, cv * 1e-3));
    }

    /** Softly non-negative concentration and its derivative. */
    private static double[] pos(double c) {
        double r = Math.sqrt(c * c + EPS * EPS);
        return new double[]{0.5 * (c + r), 0.5 * (1 + c / r)};
    }

    /**
     * Concentration factor c^p of a rate law and d/dc. A fractional order p &lt; 1 (a global, empirical rate law)
     * has an infinite slope at c = 0 and would keep consuming a species that is all but gone, driving it negative;
     * there the factor is multiplied by c/(c + c_cut) with c_cut = 10⁻⁴ mol/m³ (≈ 10 ppm in air), which leaves the
     * law unchanged at measurable concentrations and shuts it off smoothly at trace level.
     */
    private static double[] power(double[] c, double p) {
        double f = Math.pow(c[0], p), df = p * f / c[0];
        if (p < 1) {
            double w = c[0] / (c[0] + CUT), dw = CUT / ((c[0] + CUT) * (c[0] + CUT));
            df = df * w + f * dw;
            f *= w;
        }
        return new double[]{f, df * c[1]};
    }

    @Override
    public void load(LoadContext ctx) {
        int ti = temp.index();
        double t = Math.max(ctx.x(ti), 50);
        int m = sp.length;
        int ia = tracker == null ? -1 : tracker.angleIndex();
        double th = ia < 0 ? 0 : ctx.x(ia);
        double volume = crank == null ? this.volume : crank.volume(th), dV = crank == null ? 0 : crank.dVolume(th);
        double[] n = new double[m];
        for (int i = 0; i < m; i++) n[i] = ctx.x(nIdx[i]);
        // energy: dU/dt (heat flows in from other elements' contributions to this row)
        double u = -uRef, dudt = 0;
        for (int i = 0; i < m; i++) {
            u += n[i] * sp[i].internalEnergy(t);
            dudt += n[i] * sp[i].cv(t);
        }
        double du = ctx.ddt(uSlot, u), a = ctx.alpha();
        ctx.addF(ti, du);
        ctx.addJ(ti, ti, a * dudt);
        for (int i = 0; i < m; i++) ctx.addJ(ti, nIdx[i], a * sp[i].internalEnergy(t));
        // species: dn_i/dt − V Σ ν r = 0
        for (int i = 0; i < m; i++) {
            ctx.addF(nIdx[i], ctx.ddt(nSlot[i], n[i]));
            ctx.addJ(nIdx[i], nIdx[i], a);
        }
        double[][] c = new double[m][];
        for (int i = 0; i < m; i++) c[i] = pos(n[i] / volume);
        double[] dRdn = new double[m];
        for (int r = 0; r < rx.length; r++) {
            java.util.Arrays.fill(dRdn, 0);
            double kf = rx[r].kf(t), rf = kf;
            double[][] fac = new double[ord[r].length][];
            for (int k = 0; k < ord[r].length; k++) {
                fac[k] = power(c[ord[r][k]], ordVal[r][k]);
                rf *= fac[k][0];
            }
            for (int k = 0; k < ord[r].length; k++) {
                int i = ord[r][k];
                if (fac[k][0] > 0) dRdn[i] += rf / fac[k][0] * fac[k][1] / volume;
            }
            double rate = rf, dRdT = rf * rx[r].dlnKfdT(t);
            if (rx[r].reversible()) {
                double rr = kf / rx[r].kc(t);
                for (int k = 0; k < prodIdx[r].length; k++) rr *= Math.pow(c[prodIdx[r][k]][0], prodNu[r][k]);
                for (int k = 0; k < prodIdx[r].length; k++) {
                    int i = prodIdx[r][k];
                    dRdn[i] -= rr * prodNu[r][k] * c[i][1] / (c[i][0] * volume);
                }
                rate -= rr;
                dRdT -= rr * (rx[r].dlnKfdT(t) - rx[r].dlnKcdT(t));
            }
            // with a moving piston, V·r(n/V) also depends on the angle: d(V r)/dV = r − Σ_j (∂r/∂c_j)·c_j
            double dVr = 0;
            if (ia >= 0) {
                dVr = rate;
                for (int j = 0; j < m; j++) dVr -= dRdn[j] * volume * (n[j] / volume);
                dVr *= dV;
            }
            for (int i = 0; i < m; i++) {
                if (nu[r][i] == 0) continue;
                double g = -volume * nu[r][i];
                ctx.addF(nIdx[i], g * rate);
                ctx.addJ(nIdx[i], ti, g * dRdT);
                for (int j = 0; j < m; j++) if (dRdn[j] != 0) ctx.addJ(nIdx[i], nIdx[j], g * dRdn[j]);
                if (ia >= 0) ctx.addJ(nIdx[i], ia, -nu[r][i] * dVr);
            }
        }
        if (pNode != null) loadPressure(ctx, t, volume, dV, ia);
    }

    /** Pressure row p·V − R·T·Σn = 0 (only with a pressure node). */
    private void loadPressure(LoadContext ctx, double t, double volume, double dV, int ia) {
        int pi = pNode.index(), ti = temp.index();
        double p = ctx.x(pi), nt = 0;
        for (int i = 0; i < sp.length; i++) nt += ctx.x(nIdx[i]);
        ctx.addF(pi, p * volume - R * t * nt);
        ctx.addJ(pi, pi, volume);
        ctx.addJ(pi, ti, -R * nt);
        for (int i = 0; i < sp.length; i++) ctx.addJ(pi, nIdx[i], -R * t);
        if (ia >= 0) ctx.addJ(pi, ia, p * dV);
    }

    public List<Species> species() {
        return List.of(sp);
    }

    public double moles(StateView s, int i) {
        return s.x(nIdx[i]);
    }

    public double moles(StateView s, Species x) {
        return s.x(nIdx[indexOf(x)]);
    }

    public double temperature(StateView s) {
        return s.x(temp);
    }

    public Node temperatureNode() {
        return temp;
    }

    /** Volume at the reference (or fixed) state [m³]. */
    public double volume() {
        return volume;
    }

    /** Current volume [m³]. */
    public double volume(StateView s) {
        return crank == null ? volume : crank.volume(s.x(tracker.angleIndex()));
    }

    /** Pressure of the gas mixture [Pa]. */
    public double pressure(StateView s) {
        double nt = 0;
        for (int i = 0; i < sp.length; i++) if (sp[i].isGas()) nt += Math.max(0, s.x(nIdx[i]));
        return pNode != null ? s.x(pNode) : nt * R * s.x(temp) / volume(s);
    }

    /** Mole fraction of species i in the gas. */
    public double moleFraction(StateView s, int i) {
        double nt = 0;
        for (int k = 0; k < sp.length; k++) nt += Math.max(0, s.x(nIdx[k]));
        return nt > 0 ? Math.max(0, s.x(nIdx[i])) / nt : 0;
    }

    /** Total mass of the gas [kg]. */
    public double mass(StateView s) {
        double mm = 0;
        for (int i = 0; i < sp.length; i++) mm += s.x(nIdx[i]) * sp[i].molarMass();
        return mm;
    }

    /** Moles of an element held (for conservation checks). */
    public double elementMoles(StateView s, String element) {
        double e = 0;
        for (int i = 0; i < sp.length; i++) e += s.x(nIdx[i]) * sp[i].atoms(element);
        return e;
    }

    @Override
    public double storedEnergy(StateView s) {
        double u = 0, t = s.x(temp);
        for (int i = 0; i < sp.length; i++) u += s.x(nIdx[i]) * sp[i].internalEnergy(t);
        return u;
    }

    @Override
    public String label() {
        return "GasReactor(" + volume + " m³)";
    }
}
