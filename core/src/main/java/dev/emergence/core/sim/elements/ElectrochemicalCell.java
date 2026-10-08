package dev.emergence.core.sim.elements;

import dev.emergence.core.chem.Electrochemistry;
import dev.emergence.core.chem.Electrochemistry.HalfReaction;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A body of electrolyte with every conductor that touches it acting as an electrode.
 *
 * <h2>Model</h2>
 * At each electrode/solution interface all thermodynamically available half-reactions proceed in parallel
 * (a mixed-potential model). For half-reaction r on electrode e:
 * <pre>
 *   E_eq,r = E°_r + (RT/nF)·ln(a_ox^ν)                         (Nernst; a = c / 1 M, metals/gases a = 1)
 *   η_r    = (φ_metal − φ_solution,e) − E_eq,r
 *   i_r    = A·i0·[ e^{α n F η/RT} − e^{−(1−α) n F η/RT} ]       (Butler–Volmer, anodic positive)
 * </pre>
 * with the cathodic branch limited by mass transport (i_lim = n F k_m c A / ν) and anodic metal dissolution
 * only while the electrode metal remains. The ionic path from each interface to the bulk solution is a
 * resistance d/(κ A). Species amounts in solution and electrode metal are integrated by Faraday's law
 * (dn/dt = ν i / nF), so cells discharge, electrodes dissolve, metals plate out and gases evolve.
 *
 * <h2>Energy</h2>
 * The chemical free energy is an external reservoir: it supplies −Σ i_r E_eq,r. Interface losses i·η and
 * ionic Joule heat i²R are delivered to the electrode and electrolyte thermal nodes. Reversible (entropic,
 * T·ΔS) heat is neglected.
 *
 * <h2>Validity</h2>
 * Dilute-solution activities (a ≈ c), constant conductivity, unstirred diffusion layer (k_m ≈ 1e-5 m/s),
 * no ion transport between separate electrolyte bodies (only conduction), no passivation, no gas
 * accumulation. Typical accuracy: open-circuit voltages within ~20 mV of tabulated cell potentials;
 * polarisation behaviour qualitatively right, magnitudes within an order of magnitude.
 */
public final class ElectrochemicalCell implements Element {
    private static final double F = PhysicalConstants.FARADAY, R_GAS = PhysicalConstants.GAS_CONSTANT;

    public static final class Electrode {
        final Node metal, thermal;
        final String material;
        final double area, rIon;
        final double metalMoles0;
        final HalfReaction own;
        final List<HalfReaction> branches = new ArrayList<>();
        final double[] i0;
        int uIdx = -1, mIdx = -1, mSlot = -1;
        /** Per-branch unknown index of deposited foreign metal (−1 if not applicable). */
        int[] depIdx, depSlot;
        final Object tag;

        Electrode(Node metal, Node thermal, String material, double area, double rIon, double metalMoles, Object tag) {
            this.metal = metal;
            this.thermal = thermal;
            this.material = material;
            this.area = area;
            this.rIon = rIon;
            this.tag = tag;
            Electrochemistry db = Electrochemistry.standard();
            this.own = db.metalCouple(material);
            this.metalMoles0 = own != null ? metalMoles : 0;
            for (HalfReaction r : db.reactions()) branches.add(r);
            this.i0 = new double[branches.size()];
            for (int k = 0; k < i0.length; k++) i0[k] = db.exchangeCurrent(branches.get(k).id(), material);
            depIdx = new int[branches.size()];
            depSlot = new int[branches.size()];
            java.util.Arrays.fill(depIdx, -1);
        }

        public Object tag() {
            return tag;
        }
    }

    private final Node bulk, thermal;
    private final double volumeLitres;
    private final Map<String, Double> initialMolar;
    private final List<Electrode> electrodes = new ArrayList<>();
    private final Map<String, Integer> speciesIdx = new LinkedHashMap<>();
    private final Map<String, Integer> speciesSlot = new LinkedHashMap<>();
    private final Map<String, Double> overrideMoles = new LinkedHashMap<>();
    private final double km;

    /**
     * @param bulk         electrical node of the bulk solution
     * @param thermal      thermal node of the electrolyte
     * @param volumeLitres liquid volume [L]
     * @param initialMolar initial concentrations [mol/L] of electroactive species
     */
    public ElectrochemicalCell(Node bulk, Node thermal, double volumeLitres, Map<String, Double> initialMolar) {
        this.bulk = bulk;
        this.thermal = thermal;
        this.volumeLitres = volumeLitres;
        this.initialMolar = new LinkedHashMap<>(initialMolar);
        Electrochemistry db = Electrochemistry.standard();
        this.km = db.massTransferCoefficient();
        for (HalfReaction r : db.reactions()) this.initialMolar.putIfAbsent(r.speciesOx(), 0.0);
    }

    /** Adds an electrode of the given material, wetted area [m²] and ionic path length [m]. */
    public Electrode addElectrode(Node metal, Node electrodeThermal, String material, double area, double pathLength,
                                  double conductivity, double metalMoles, Object tag) {
        Electrode e = new Electrode(metal, electrodeThermal, material, area,
                pathLength / (conductivity * Math.max(area, 1e-8)), metalMoles, tag);
        electrodes.add(e);
        return e;
    }

    /** Restores species amounts [mol] from a previous state. */
    public void setInitialMoles(String species, double moles) {
        overrideMoles.put(species, moles);
    }


    /** Overrides the remaining metal of an electrode from a previous state. */
    public void setRemainingMetal(Electrode e, double moles) {
        remaining.put(e, moles);
    }

    private final Map<Electrode, Double> remaining = new LinkedHashMap<>();
    private final Map<String, Double> deposits = new LinkedHashMap<>();

    /** Restores the amount of a foreign metal deposited on an electrode (key: tag + ":" + metal). */
    public void setDeposit(String key, double moles) {
        deposits.put(key, moles);
    }

    public List<Electrode> electrodes() {
        return electrodes;
    }

    @Override
    public void setup(SetupContext ctx) {
        ctx.limitNewtonStep(bulk.index(), 0.1);
        for (var en : initialMolar.entrySet()) {
            int idx = ctx.allocUnknown("n(" + en.getKey() + ")", 1e-12);
            speciesIdx.put(en.getKey(), idx);
            speciesSlot.put(en.getKey(), ctx.allocState(1e-9 + 1e-4 * Math.abs(en.getValue() * volumeLitres)));
            double n0 = overrideMoles.getOrDefault(en.getKey(), en.getValue() * volumeLitres);
            ctx.setInitial(idx, n0);
        }
        for (Electrode e : electrodes) {
            e.uIdx = ctx.allocUnknown("phi_s(" + e.material + ")", 1e-6);
            ctx.limitNewtonStep(e.uIdx, 0.1);
            ctx.limitNewtonStep(e.metal.index(), 0.1);
            // start each metal electrode at its own equilibrium potential (consistent initial state)
            if (e.own != null && e.metal.index() >= 0) {
                double c = overrideMoles.getOrDefault(e.own.speciesOx(), initialMolar.getOrDefault(e.own.speciesOx(), 0.0) * volumeLitres) / volumeLitres;
                double eq = e.own.e0() + R_GAS * 298.15 / (e.own.n() * F) * e.own.stoichOx() * Math.log(Math.max(c, 1e-6));
                ctx.setInitial(e.metal.index(), eq);
            }
            for (int b = 0; b < e.branches.size(); b++) {
                HalfReaction hr = e.branches.get(b);
                if (hr.metal() != null && !hr.metal().equals(e.material) && initialMolar.getOrDefault(hr.speciesOx(), 0.0) > 0) {
                    e.depIdx[b] = ctx.allocUnknown("deposit(" + hr.metal() + ")", 1e-12);
                    e.depSlot[b] = ctx.allocState(1e-12);
                    ctx.setInitial(e.depIdx[b], deposits.getOrDefault(e.tag + ":" + hr.metal(), 0.0));
                }
            }
            if (e.own != null) {
                e.mIdx = ctx.allocUnknown("metal(" + e.material + ")", 1e-12);
                e.mSlot = ctx.allocState(1e-9 + 1e-6 * e.metalMoles0);
                ctx.setInitial(e.mIdx, remaining.getOrDefault(e, e.metalMoles0));
            }
        }
        buildLocalIndex();
    }

    // ------------------------------------------------------------------ local variable layout
    private int[] vars;       // global unknown indices of local variables (−1 = reference)
    private int nl;

    private void buildLocalIndex() {
        List<Integer> v = new ArrayList<>();
        for (Electrode e : electrodes) v.add(e.metal.index());
        for (Electrode e : electrodes) v.add(e.uIdx);
        v.add(bulk.index());
        for (int idx : speciesIdx.values()) v.add(idx);
        for (Electrode e : electrodes) v.add(e.mIdx);
        v.add(thermal.index());
        for (Electrode e : electrodes) v.add(e.thermal == null ? -1 : e.thermal.index());
        nl = v.size();
        vars = new int[nl];
        for (int i = 0; i < nl; i++) vars[i] = v.get(i);
    }

    private int offU() { return electrodes.size(); }
    private int offBulk() { return 2 * electrodes.size(); }
    private int offSpecies() { return offBulk() + 1; }
    private int offMetal() { return offSpecies() + speciesIdx.size(); }
    private int offT() { return offMetal() + electrodes.size(); }

    /** Static residual contributions R (same layout as vars) and side outputs. */
    private double[][] branchCurrents;

    private double evaluate(double[] l, double[] r, double[] chemPowerOut) {
        java.util.Arrays.fill(r, 0);
        int ne = electrodes.size();
        double temp = Math.max(l[offT()], 200);
        double chemPower = 0;
        List<String> sp = new ArrayList<>(speciesIdx.keySet());
        for (int k = 0; k < ne; k++) {
            Electrode e = electrodes.get(k);
            double vMetal = l[k], u = l[offU() + k], phi = l[offBulk()];
            double delta = vMetal - u;
            double tEl = e.thermal != null ? Math.max(l[offT() + 1 + k], 200) : temp;
            double iTot = 0, loss = 0;
            for (int b = 0; b < e.branches.size(); b++) {
                HalfReaction hr = e.branches.get(b);
                int si = sp.indexOf(hr.speciesOx());
                double moles = Math.max(l[offSpecies() + si], 0);
                double c = moles / volumeLitres;
                double f = F / (R_GAS * tEl);
                double eEq = hr.e0() + (R_GAS * tEl / (hr.n() * F)) * hr.stoichOx() * Math.log(Math.max(c, 1e-12));
                double eta = delta - eEq;
                boolean ownMetal = hr.metal() != null && hr.metal().equals(e.material);
                // anodic (oxidation) availability
                double anod = 0;
                if (ownMetal) {
                    double m = e.mIdx >= 0 ? Math.max(l[offMetal() + k], 0) : 0;
                    anod = Math.min(1.0, m / 1e-6);
                } else if (hr.oxidationOnly()) {
                    anod = 1.0; // water oxidation
                }
                double cath = (hr.oxidationOnly() || c <= 0) ? 0 : 1.0;
                double ia = anod > 0 ? anod * e.area * e.i0[b] * expClamp(hr.alphaA() * f * eta) : 0;
                double ic = 0;
                if (cath > 0) {
                    double raw = e.area * e.i0[b] * Math.min(c, 1.0) * expClamp(-hr.alphaC() * f * eta);
                    double ilim = hr.n() * F * km * c * 1000.0 * e.area / hr.stoichOx();
                    ic = raw / (1 + raw / Math.max(ilim, 1e-30));
                }
                double i = ia - ic;
                iTot += i;
                loss += i * eta;
                chemPower += -i * eEq;
                // species production by oxidation (i > 0)
                double rate = hr.stoichOx() * i / (hr.n() * F);
                r[offSpecies() + si] -= rate;
                if (ownMetal && e.mIdx >= 0) r[offMetal() + k] += i / (hr.n() * F);
                if (branchCurrents != null) branchCurrents[k][b] = i;
            }
            double iIon = (u - phi) / e.rIon;
            r[k] += iTot;
            r[offU() + k] += -iTot + iIon;
            r[offBulk()] -= iIon;
            if (e.thermal != null) r[offT() + 1 + k] -= loss;
            else r[offT()] -= loss;
            r[offT()] -= iIon * iIon * e.rIon;
        }
        if (chemPowerOut != null) chemPowerOut[0] = chemPower;
        return chemPower;
    }

    /** e^x, continued linearly above x = 25 (currents there are far beyond anything reachable) for Newton robustness. */
    private static double expClamp(double x) {
        if (x > 25) return Math.exp(25) * (1 + (x - 25));
        if (x < -300) return 0;
        return Math.exp(x);
    }

    private final double[] lv = new double[64], rv = new double[64], rp = new double[64], rm = new double[64];

    @Override
    public void load(LoadContext ctx) {
        double[] l = nl <= 64 ? lv : new double[nl];
        double[] r = nl <= 64 ? rv : new double[nl];
        for (int i = 0; i < nl; i++) l[i] = ctx.x(vars[i]);
        evaluate(l, r, null);
        for (int i = 0; i < nl; i++) ctx.addF(vars[i], r[i]);
        // numerical Jacobian of the static part
        double[] p = nl <= 64 ? rp : new double[nl], m = nl <= 64 ? rm : new double[nl];
        for (int j = 0; j < nl; j++) {
            if (vars[j] < 0) continue;
            double x0 = l[j];
            double h = 1e-7 * Math.max(Math.abs(x0), scale(j));
            l[j] = x0 + h;
            evaluate(l, p, null);
            l[j] = x0 - h;
            evaluate(l, m, null);
            l[j] = x0;
            for (int i = 0; i < nl; i++) {
                double d = (p[i] - m[i]) / (2 * h);
                if (d != 0) ctx.addJ(vars[i], vars[j], d);
            }
        }
        // dynamics: species and electrode-metal amounts
        for (var en : speciesIdx.entrySet()) {
            int idx = en.getValue();
            ctx.addF(idx, ctx.ddt(speciesSlot.get(en.getKey()), ctx.x(idx)));
            ctx.addJ(idx, idx, ctx.alpha());
        }
        for (Electrode e : electrodes) {
            if (e.mIdx < 0) continue;
            ctx.addF(e.mIdx, ctx.ddt(e.mSlot, ctx.x(e.mIdx)));
            ctx.addJ(e.mIdx, e.mIdx, ctx.alpha());
        }
        // foreign-metal deposits: d(dep)/dt = −i_cathodic/(nF) of that branch (explicit in the branch current,
        // which is a function of the other local variables: Jacobian by finite differences of the branch)
        int ne = electrodes.size();
        boolean any = false;
        for (Electrode e : electrodes) for (int d : e.depIdx) if (d >= 0) any = true;
        if (!any) return;
        branchCurrents = new double[ne][];
        for (int k = 0; k < ne; k++) branchCurrents[k] = new double[electrodes.get(k).branches.size()];
        evaluate(l, r, null);
        double[][] base = new double[ne][];
        for (int k = 0; k < ne; k++) base[k] = branchCurrents[k].clone();
        double[][][] dI = new double[nl][][];
        for (int j = 0; j < nl; j++) {
            if (vars[j] < 0) continue;
            double x0 = l[j], h = 1e-7 * Math.max(Math.abs(x0), scale(j));
            l[j] = x0 + h;
            evaluate(l, r, null);
            double[][] up = new double[ne][];
            for (int k = 0; k < ne; k++) up[k] = branchCurrents[k].clone();
            l[j] = x0;
            dI[j] = new double[ne][];
            for (int k = 0; k < ne; k++) {
                dI[j][k] = new double[base[k].length];
                for (int b = 0; b < base[k].length; b++) dI[j][k][b] = (up[k][b] - base[k][b]) / h;
            }
        }
        branchCurrents = null;
        for (int k = 0; k < ne; k++) {
            Electrode e = electrodes.get(k);
            for (int b = 0; b < e.branches.size(); b++) {
                int d = e.depIdx[b];
                if (d < 0) continue;
                double nF = e.branches.get(b).n() * F;
                ctx.addF(d, ctx.ddt(e.depSlot[b], ctx.x(d)) + base[k][b] / nF);
                ctx.addJ(d, d, ctx.alpha());
                for (int j = 0; j < nl; j++) if (dI[j] != null && dI[j][k][b] != 0) ctx.addJ(d, vars[j], dI[j][k][b] / nF);
            }
        }
    }

    /** Moles of foreign metals deposited on an electrode (metal → mol). */
    public Map<String, Double> deposits(Electrode e, StateView s) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int b = 0; b < e.branches.size(); b++) {
            if (e.depIdx[b] >= 0) m.put(e.branches.get(b).metal(), s.x(e.depIdx[b]));
        }
        return m;
    }

    /** Gas evolution rate at an electrode [mol/s] (H2 from proton reduction plus O2 from water oxidation). */
    public double gasEvolution(Electrode e, StateView s) {
        int k = electrodes.indexOf(e);
        double[] l = read(s);
        branchCurrents = new double[electrodes.size()][];
        for (int q = 0; q < electrodes.size(); q++) branchCurrents[q] = new double[electrodes.get(q).branches.size()];
        evaluate(l, new double[nl], null);
        double rate = 0;
        for (int b = 0; b < e.branches.size(); b++) {
            HalfReaction hr = e.branches.get(b);
            if (hr.gas() == null) continue;
            double i = branchCurrents[k][b];
            if (hr.oxidationOnly() && i > 0) rate += i / (hr.n() * F);
            if (!hr.oxidationOnly() && i < 0) rate += -i / (hr.n() * F);
        }
        branchCurrents = null;
        return rate;
    }

    private double scale(int j) {
        if (j < offSpecies()) return 1.0;             // potentials [V]
        if (j < offT()) return 1e-3;                   // amounts [mol]
        return 300;                                    // temperatures [K]
    }

    private double[] read(StateView s) {
        double[] l = new double[nl];
        for (int i = 0; i < nl; i++) l[i] = s.x(vars[i]);
        return l;
    }

    @Override
    public double externalPower(StateView s) {
        double[] out = new double[1];
        evaluate(read(s), new double[nl], out);
        return out[0];
    }

    // ------------------------------------------------------------------ instruments

    /** Moles of a dissolved species. */
    public double moles(String species, StateView s) {
        Integer idx = speciesIdx.get(species);
        return idx == null ? 0 : s.x(idx);
    }

    public double concentration(String species, StateView s) {
        return moles(species, s) / volumeLitres;
    }

    public Map<String, Double> allMoles(StateView s) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (var en : speciesIdx.entrySet()) m.put(en.getKey(), s.x(en.getValue()));
        return m;
    }

    /** Remaining electrode metal [mol] (NaN if the electrode is inert). */
    public double remainingMetal(Electrode e, StateView s) {
        return e.mIdx < 0 ? Double.NaN : s.x(e.mIdx);
    }

    public double initialMetal(Electrode e) {
        return e.metalMoles0;
    }

    /** Net interface current of an electrode (anodic positive) [A]. */
    public double electrodeCurrent(Electrode e, StateView s) {
        int k = electrodes.indexOf(e);
        double[] l = read(s);
        return (l[offU() + k] - l[offBulk()]) / e.rIon;
    }
}
