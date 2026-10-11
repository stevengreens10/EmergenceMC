package dev.emergence.core.sim.elements;

import dev.emergence.core.chem.Reaction;
import dev.emergence.core.chem.Species;
import dev.emergence.core.chem.VaporLiquid;
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
 * <p>
 * <b>Liquids</b> ({@link #withLiquid}): condensable species ({@link VaporLiquid}) also have a liquid pool l_i in the
 * vessel (its volume taken from the gas's). Each evaporates or condenses at the rate κ·(φ_i·x_i·γ_i·p_sat,i(T) − p_i)/(R·T)
 * [mol/s], κ = k_G·A the gas-side mass-transfer conductance over the interface (k_G = {@value #K_G} m/s, a stirred or
 * boiling liquid), x the liquid's mole fractions, γ its activity coefficients, φ_i = l_i/(l_i + 10⁻⁴ mol) (no liquid,
 * no equilibrium pressure — a dry vessel's undersaturated vapour forms at most a negligible dew film), p_i the
 * partial pressure: the gas
 * approaches equilibrium with the liquid (Raoult's law, modified), and boils when the liquid's equilibrium pressure
 * exceeds the gas's. The liquid's internal energy is the vapour's enthalpy less the heat of vaporisation,
 * u_l = h_g(T) − L(T), with L from the same vapour-pressure curve (Clausius–Clapeyron), so evaporation cools and
 * condensation warms exactly as much as equilibrium requires, and energy and elements stay conserved.
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
    private PistonKinematics crank;
    private final List<Node> filmWall = new java.util.ArrayList<>();
    /** Gas-side mass-transfer coefficient over a liquid surface [m/s] (a stirred or boiling liquid). */
    public static final double K_G = 0.05;
    private static final double LIQ_EPS = 1e-4;
    // liquids: the condensable species (indices into sp), their data, initial liquid moles, unknowns and slots
    private int[] cond = new int[0];
    private VaporLiquid.Condensable[] cdata;
    private String[] condIds;
    private double[] l0;
    private int[] lIdx, lSlot;
    private double kA;
    private final List<double[]> filmGeom = new java.util.ArrayList<>(); // {area, thickness}

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
    public GasReactor withPiston(AngleTracker tracker, PistonKinematics crank) {
        this.tracker = tracker;
        this.crank = crank;
        return this;
    }

    /**
     * Wall film: the gas in the thermal boundary layer against a wall (area A, thickness δ) is at the wall's
     * temperature, not the bulk's; reactions run there at the wall temperature too, on concentrations c_i·T/T_w
     * (same pressure, hotter gas), over the film volume A·δ. The species it converts belong to the well-mixed gas, so
     * the heat of reaction appears in the bulk and energy stays exactly conserved. This is how a hot surface ignites
     * a mixture that the bulk temperature alone would not (hot-surface ignition); at a wall no hotter than the gas it
     * merely adds the film's share to the bulk rate.
     */
    public GasReactor withWallFilm(Node wall, double area, double thickness) {
        filmWall.add(wall);
        filmGeom.add(new double[]{area, thickness});
        return this;
    }

    public Node pressureNode() {
        return pNode;
    }

    /**
     * Gives the vessel liquids: every condensable species present gets a liquid pool, starting with {@code liquid[i]}
     * moles (indexed like the species), exchanging with the gas over an interface of area {@code area} [m²].
     */
    public GasReactor withLiquid(double[] liquid, double area) {
        java.util.List<Integer> c = new java.util.ArrayList<>();
        for (int i = 0; i < sp.length; i++) if (VaporLiquid.of(sp[i].id()) != null) c.add(i);
        cond = c.stream().mapToInt(Integer::intValue).toArray();
        cdata = new VaporLiquid.Condensable[cond.length];
        condIds = new String[cond.length];
        l0 = new double[cond.length];
        for (int k = 0; k < cond.length; k++) {
            cdata[k] = VaporLiquid.of(sp[cond[k]].id());
            condIds[k] = sp[cond[k]].id();
            l0[k] = Math.max(0, liquid[cond[k]]);
        }
        lIdx = new int[cond.length];
        lSlot = new int[cond.length];
        kA = K_G * area;
        return this;
    }

    /** Internal energy of a mole of species k's liquid at T [J/mol]: h_g(T) − L(T). */
    private double uLiquid(int k, double t) {
        return sp[cond[k]].internalEnergy(t) + R * t - cdata[k].latentHeat(t);
    }

    /** Its isochoric heat capacity [J/(mol·K)]. */
    private double cvLiquid(int k, double t) {
        return sp[cond[k]].cv(t) + R - cdata[k].dLatentHeat(t);
    }

    /** Volume the liquids take up [m³]. */
    private double liquidVolume(double[] l) {
        double v = 0;
        for (int k = 0; k < l.length; k++) v += Math.max(0, l[k]) * cdata[k].liquidVolume();
        return v;
    }

    /**
     * The gas's share of a vessel of volume V with liquid volume vl: V − vl, smoothly floored (½(g + √(g² + 4f²)), f =
     * 2 % of V) so a vessel the liquid fills still has some gas space, and the slopes stay continuous.
     */
    private static double gasVolume(double v, double vl) {
        double g = v - vl, f = 0.02 * v;
        return 0.5 * (g + Math.sqrt(g * g + 4 * f * f));
    }

    /** Evaporation rates [mol/s] (condensation negative) of each condensable at T with gas moles n and liquid moles l. */
    private double[] evaporation(double t, double[] n, double[] l, double vg) {
        int q = cond.length;
        double[] r = new double[q];
        double sumL = 0, sumN = 0;
        for (int k = 0; k < q; k++) {
            sumL += Math.max(0, l[k]);
            sumN += Math.max(0, n[cond[k]]);
        }
        double[] x = new double[q];
        for (int k = 0; k < q; k++) {
            double y = sumN > 0 ? Math.max(0, n[cond[k]]) / sumN : 1.0 / q;
            x[k] = (Math.max(0, l[k]) + LIQ_EPS * y) / (sumL + LIQ_EPS);
        }
        double[] g = VaporLiquid.activity(condIds, x);
        for (int k = 0; k < q; k++) {
            // the liquid's equilibrium pressure, present only as far as there is liquid (φ = l/(l + ε)): smooth through
            // equilibrium; a dry vessel's undersaturated vapour forms at most a dew film of order ε
            double phi = Math.max(0, l[k]) / (Math.max(0, l[k]) + LIQ_EPS);
            double pk = n[cond[k]] * R * t / vg, pStar = phi * x[k] * g[k] * cdata[k].psat(t);
            r[k] = kA * (pStar - pk) / (R * t);
        }
        return r;
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
            // positivity near depletion: an update that would take the last of a species just below zero (by less
            // than there is) takes it to a tenth of what is left instead — overshooting below zero, where the softened
            // rate law stops, made the next update throw it back up (a two-cycle at the end of every fast burn).
            // Gross excursions (the first iterates of a violent transient) are left to Newton.
            ctx.limitNewton(nIdx[i], (old, proposed) -> old > 0 && proposed < 0 && proposed > -old ? 0.1 * old : proposed);
            nSlot[i] = ctx.allocState(tol);
        }
        for (int k = 0; k < cond.length; k++) {
            double tl = Math.max(1e-12, 1e-9 * (total + l0[k]));
            lIdx[k] = ctx.allocUnknown("l:" + condIds[k], tl);
            ctx.setInitial(lIdx[k], l0[k]);
            ctx.limitNewton(lIdx[k], (old, proposed) -> old > 0 && proposed < 0 && proposed > -old ? 0.1 * old : proposed);
            lSlot[k] = ctx.allocState(tl);
        }
        // the energy slot holds U minus the formation energy of the initial charge, so its truncation-error
        // tolerance is set by the sensible energy, not by the (large) chemical energy
        for (int i = 0; i < sp.length; i++) uRef += n0[i] * sp[i].internalEnergy(Species.T0);
        for (int k = 0; k < cond.length; k++) uRef += l0[k] * uLiquid(k, Species.T0);
        double cv = 0;
        for (int i = 0; i < sp.length; i++) cv += n0[i] * sp[i].cv(300);
        for (int k = 0; k < cond.length; k++) cv += l0[k] * cvLiquid(k, 300);
        uSlot = ctx.allocState(Math.max(1e-9, cv * 1e-3));
    }

    /** Softly non-negative concentration and its derivative. */
    private static double[] pos(double c) {
        double r = Math.sqrt(c * c + EPS * EPS);
        return new double[]{0.5 * (c + r), 0.5 * (1 + c / r)};
    }

    /**
     * Concentration factor c^p of a rate law and d/dc. An order p &lt; 1 (a global, empirical rate law — fractional,
     * or negative as methane's −0.3 in Westbrook–Dryer) has an infinite slope at c = 0 and would keep consuming a
     * species that is all but gone, driving it negative; there the factor is multiplied by (c/(c + c_cut))^(1 − p)
     * with c_cut = 10⁻⁴ mol/m³ (≈ 10 ppm in air): unchanged at measurable concentrations, and at trace level the
     * factor falls linearly to zero (c/c_cut^(1−p)) with a finite slope, so the last traces burn out cleanly.
     */
    private static double[] power(double[] c, double p) {
        double f = Math.pow(c[0], p), df = p * f / c[0];
        if (p < 1) {
            double q = 1 - p, w = c[0] / (c[0] + CUT), dw = CUT / ((c[0] + CUT) * (c[0] + CUT));
            double wq = Math.pow(w, q);
            df = df * wq + f * q * wq / w * dw;
            f *= wq;
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
        double vessel = crank == null ? this.volume : crank.volume(th), dV = crank == null ? 0 : crank.dVolume(th);
        double[] n = new double[m];
        for (int i = 0; i < m; i++) n[i] = ctx.x(nIdx[i]);
        int q = cond.length;
        double[] l = new double[q];
        for (int k = 0; k < q; k++) l[k] = ctx.x(lIdx[k]);
        double volume = q == 0 ? vessel : gasVolume(vessel, liquidVolume(l));
        // energy: dU/dt (heat flows in from other elements' contributions to this row)
        double u = -uRef, dudt = 0;
        for (int i = 0; i < m; i++) {
            u += n[i] * sp[i].internalEnergy(t);
            dudt += n[i] * sp[i].cv(t);
        }
        for (int k = 0; k < q; k++) {
            u += l[k] * uLiquid(k, t);
            dudt += l[k] * cvLiquid(k, t);
        }
        double du = ctx.ddt(uSlot, u), a = ctx.alpha();
        ctx.addF(ti, du);
        ctx.addJ(ti, ti, a * dudt);
        for (int i = 0; i < m; i++) ctx.addJ(ti, nIdx[i], a * sp[i].internalEnergy(t));
        for (int k = 0; k < q; k++) ctx.addJ(ti, lIdx[k], a * uLiquid(k, t));
        if (q > 0) loadLiquid(ctx, t, n, l, vessel);
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
        if (pNode != null) {
            loadPressure(ctx, t, volume, dV, ia);
            for (int k = 0; k < q; k++) {
                double dl = Math.max(1e-10, 1e-7 * Math.abs(l[k])), old = l[k];
                l[k] = old + dl;
                double dvg = (gasVolume(vessel, liquidVolume(l)) - volume) / dl;
                l[k] = old;
                ctx.addJ(pNode.index(), lIdx[k], ctx.x(pNode.index()) * dvg);
            }
        }
        for (int w = 0; w < filmWall.size(); w++) loadFilm(ctx, filmWall.get(w), filmGeom.get(w), n, t, volume);
    }

    /**
     * The liquids: dl_k/dt = −r_k, and the gas gains r_k (the evaporation rates of {@link #evaporation}); their slopes by
     * finite differences in T, the condensables' gas moles and the liquid moles (the liquid's volume moves the gas's).
     */
    private void loadLiquid(LoadContext ctx, double t, double[] n, double[] l, double vessel) {
        int q = cond.length, ti = temp.index();
        double vg = gasVolume(vessel, liquidVolume(l));
        double[] r = evaporation(t, n, l, vg);
        double a = ctx.alpha();
        for (int k = 0; k < q; k++) {
            ctx.addF(lIdx[k], ctx.ddt(lSlot[k], l[k]) + r[k]);
            ctx.addJ(lIdx[k], lIdx[k], a);
            ctx.addF(nIdx[cond[k]], -r[k]);
        }
        // slopes
        double ht = 1e-4 * Math.max(1, t);
        double[] rt = evaporation(t + ht, n, l, vg);
        for (int k = 0; k < q; k++) {
            double d = (rt[k] - r[k]) / ht;
            ctx.addJ(lIdx[k], ti, d);
            ctx.addJ(nIdx[cond[k]], ti, -d);
        }
        for (int j = 0; j < q; j++) {
            int i = cond[j];
            double h = Math.max(1e-10, 1e-7 * Math.abs(n[i]));
            double old = n[i];
            n[i] = old + h;
            double[] rn = evaporation(t, n, l, vg);
            n[i] = old;
            for (int k = 0; k < q; k++) {
                double d = (rn[k] - r[k]) / h;
                ctx.addJ(lIdx[k], nIdx[i], d);
                ctx.addJ(nIdx[cond[k]], nIdx[i], -d);
            }
            double hl = Math.max(1e-10, 1e-7 * Math.abs(l[j]));
            double oldL = l[j];
            l[j] = oldL + hl;
            double[] rl = evaporation(t, n, l, gasVolume(vessel, liquidVolume(l)));
            l[j] = oldL;
            for (int k = 0; k < q; k++) {
                double d = (rl[k] - r[k]) / hl;
                ctx.addJ(lIdx[k], lIdx[j], d);
                ctx.addJ(nIdx[cond[k]], lIdx[j], -d);
            }
        }
    }

    /** Reactions in a wall film at the wall temperature (see {@link #withWallFilm}). */
    private void loadFilm(LoadContext ctx, Node wall, double[] geom, double[] n, double t, double volume) {
        int m = sp.length, wi = wall.index(), ti = temp.index();
        double tw = Math.max(50, Math.min(4000, ctx.x(wi))), vf = geom[0] * geom[1], ratio = t / tw;
        double[][] c = new double[m][];
        for (int i = 0; i < m; i++) c[i] = pos(n[i] / volume * ratio);
        double[] dRdn = new double[m];
        for (int r = 0; r < rx.length; r++) {
            java.util.Arrays.fill(dRdn, 0);
            double kf = rx[r].kf(tw), rf = kf;
            double[][] fac = new double[ord[r].length][];
            for (int k = 0; k < ord[r].length; k++) {
                fac[k] = power(c[ord[r][k]], ordVal[r][k]);
                rf *= fac[k][0];
            }
            // d/dn_i through c_i = n_i·T/(V·T_w); d/dT (bulk) and d/dT_w through the same ratio and k(T_w)
            double dlnRdRatio = 0;
            for (int k = 0; k < ord[r].length; k++) {
                int i = ord[r][k];
                if (fac[k][0] > 0) {
                    double g = rf / fac[k][0] * fac[k][1];
                    dRdn[i] += g * ratio / volume;
                    dlnRdRatio += g * n[i] / volume;
                }
            }
            double rate = rf, dRdTw = rf * rx[r].dlnKfdT(tw) - dlnRdRatio * ratio / tw, dRdT = dlnRdRatio / tw;
            if (rx[r].reversible()) {
                double rr = kf / rx[r].kc(tw), dRr = 0;
                for (int k = 0; k < prodIdx[r].length; k++) rr *= Math.pow(c[prodIdx[r][k]][0], prodNu[r][k]);
                for (int k = 0; k < prodIdx[r].length; k++) {
                    int i = prodIdx[r][k];
                    double g = rr * prodNu[r][k] * c[i][1] / c[i][0];
                    dRdn[i] -= g * ratio / volume;
                    dRr += g * n[i] / volume;
                }
                rate -= rr;
                dRdTw -= rr * (rx[r].dlnKfdT(tw) - rx[r].dlnKcdT(tw)) - dRr * ratio / tw;
                dRdT -= dRr / tw;
            }
            for (int i = 0; i < m; i++) {
                if (nu[r][i] == 0) continue;
                double g = -vf * nu[r][i];
                ctx.addF(nIdx[i], g * rate);
                ctx.addJ(nIdx[i], wi, g * dRdTw);
                ctx.addJ(nIdx[i], ti, g * dRdT);
                for (int j = 0; j < m; j++) if (dRdn[j] != 0) ctx.addJ(nIdx[i], nIdx[j], g * dRdn[j]);
            }
        }
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

    /** Moles of an element held, gas and liquid (for conservation checks). */
    public double elementMoles(StateView s, String element) {
        double e = 0;
        for (int i = 0; i < sp.length; i++) e += s.x(nIdx[i]) * sp[i].atoms(element);
        for (int k = 0; k < cond.length; k++) e += s.x(lIdx[k]) * sp[cond[k]].atoms(element);
        return e;
    }

    @Override
    public double storedEnergy(StateView s) {
        double u = 0, t = s.x(temp);
        for (int i = 0; i < sp.length; i++) u += s.x(nIdx[i]) * sp[i].internalEnergy(t);
        for (int k = 0; k < cond.length; k++) u += s.x(lIdx[k]) * uLiquid(k, t);
        return u;
    }

    /** Liquid moles of species i [mol] (0 if it has none). */
    public double liquidMoles(StateView s, int i) {
        for (int k = 0; k < cond.length; k++) if (cond[k] == i) return s.x(lIdx[k]);
        return 0;
    }

    /** Unknown index of species i's liquid moles, or −1. */
    public int liquidIndex(int i) {
        for (int k = 0; k < cond.length; k++) if (cond[k] == i) return lIdx[k];
        return -1;
    }

    @Override
    public String label() {
        return "GasReactor(" + volume + " m³)";
    }
}
