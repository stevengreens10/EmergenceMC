package dev.emergence.core.sim.elements;

import dev.emergence.core.chem.VaporLiquid;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * Liquid flowing through a passage between two gas vessels' liquid pools ({@link GasReactor#withLiquid}): a drain, an
 * overflow, the pipe joining communicating vessels, the downcomer of a still's column.
 * <p>
 * Each vessel's liquid stands at a level h = floor + V_l/A over its floor (A its horizontal section); the passage
 * leaves it at a port at height z. The liquid moves by its weight: the depth above each port, ρg·max(h − z, 0), and
 * its fall (or climb) between the ports, z_a − z_b,
 * <pre>Δ = ρ_a g H_a − ρ_b g H_b + ρg(z_a − z_b)</pre>
 * The gases' pressure difference is not counted: the gas passage sharing the way equalises it (gas and liquid pass
 * independently, so a pressurised vessel vents its gas rather than pushing its liquid — and counting it would
 * couple the liquid to the gas's millisecond pressure swings in small pipes, stiffly, for no gain).
 * through the passage as through an orifice, Q = C_d·A_p·√(2|Δ|/ρ)·sgn Δ, joined smoothly to a laminar Q ∝ Δ below
 * Δ₀ = 30 Pa. It can only carry liquid that is there: the liquid reaches the port by spilling over its lip, which
 * passes at most the weir flow Q_w = ⅔·C_d·b·√(2g)·H^{3/2} (Francis; b = 2√(πA_p) the port's rim, H the upstream depth
 * above the port, smoothly floored), joined to the orifice flow as Q·Q_w/(|Q| + Q_w) — a pool just below its outlet
 * keeps its liquid, as a column's tray holds its pool below the weir — and scaled by V_l/(V_l + 10 µL) (a dry vessel
 * passes nothing). The liquid carries
 * its upstream pool's composition (each species' moles per volume) and internal energy — elements and energy are
 * conserved; the liquid's weight falling between ports is neither stored nor dissipated (ρgΔz is 10⁻⁵ of its
 * internal energy per metre). Quasi-steady: the liquid in the passage stores nothing; gas and liquid pass a shared
 * passage independently (a pipe full of liquid still lets gas through).
 */
public final class LiquidDrain implements Element {
    private static final double G = PhysicalConstants.STANDARD_GRAVITY, DP0 = 30.0, FLOOR = 1e-5, VOL0 = 1e-8;

    private final GasReactor a, b;
    private final double floorA, sectionA, portA, floorB, sectionB, portB, area, cd;
    private final int[] species;     // species indices with a liquid pool on both sides
    private final double[] vMol, mMol;
    private int[] la, lb;
    private MixtureBranch gate;

    /**
     * @param floorA,sectionA the bottom height [m] and horizontal section [m²] of a's liquid; portA the passage's bottom
     *                        height where it leaves a [m] (likewise for b)
     * @param area            the passage's open area [m²]
     */
    public LiquidDrain(GasReactor a, double floorA, double sectionA, double portA, GasReactor b, double floorB, double sectionB,
                       double portB, double area) {
        this.a = a;
        this.b = b;
        this.floorA = floorA;
        this.sectionA = Math.max(sectionA, 1e-8);
        this.portA = portA;
        this.floorB = floorB;
        this.sectionB = Math.max(sectionB, 1e-8);
        this.portB = portB;
        this.area = area;
        this.cd = 0.6;
        int n = 0;
        int[] tmp = new int[a.species().size()];
        for (int i = 0; i < a.species().size(); i++) {
            if (VaporLiquid.of(a.species().get(i).id()) != null) tmp[n++] = i;
        }
        species = java.util.Arrays.copyOf(tmp, n);
        vMol = new double[n];
        mMol = new double[n];
        for (int k = 0; k < n; k++) {
            var sp = a.species().get(species[k]);
            vMol[k] = VaporLiquid.of(sp.id()).liquidVolume();
            mMol[k] = sp.molarMass();
        }
    }

    /** Opens and shuts with the gas passage sharing its way (a valve shuts both). */
    public LiquidDrain gatedBy(MixtureBranch b) {
        this.gate = b;
        return this;
    }

    @Override
    public void setup(dev.emergence.core.sim.SetupContext ctx) {
        // (the vessels' liquid unknowns exist once they are set up: looked up at load)
    }

    private void indices() {
        if (la != null) return;
        la = new int[species.length];
        lb = new int[species.length];
        for (int k = 0; k < species.length; k++) {
            la[k] = a.liquidIndex(species[k]);
            lb[k] = b.liquidIndex(species[k]);
        }
    }

    /** Pools' moles, smoothly floored at zero (½(l + √(l² + ε²)), ε = 10⁻⁹ mol). */
    private double[] posMoles(double[] l) {
        double[] out = l == null ? null : new double[l.length];
        for (int k = 0; k < l.length; k++) out[k] = 0.5 * (l[k] + Math.sqrt(l[k] * l[k] + 1e-18));
        return out;
    }

    /** Smooth max(x, 0). */
    private static double pos(double x) {
        return 0.5 * (x + Math.sqrt(x * x + FLOOR * FLOOR));
    }

    /**
     * Flows out of a (into b) for the given state: [0..n) each species' moles/s, [n] energy/s, [n+1] volume/s.
     */
    void flows(double[] lA, double[] lB, double pA, double pB, double tA, double tB, double[] out) {
        int n = species.length;
        // (smooth everywhere: a dry pipe's pools hover about zero, and a kink there costs Newton dearly)
        double[] pa = posMoles(lA), pb = posMoles(lB);
        double vA = 0, vB = 0, mA = 0, mB = 0;
        for (int k = 0; k < n; k++) {
            vA += pa[k] * vMol[k];
            vB += pb[k] * vMol[k];
            mA += pa[k] * mMol[k];
            mB += pb[k] * mMol[k];
        }
        double rhoA = (mA + 1000 * VOL0) / (vA + VOL0), rhoB = (mB + 1000 * VOL0) / (vB + VOL0);
        double hA = pos(floorA + vA / sectionA - portA), hB = pos(floorB + vB / sectionB - portB);
        // what a side can give: liquid at all (a dry vessel gives nothing, whatever the smoothing of its depth)
        double phiA = vA / (vA + VOL0), phiB = vB / (vB + VOL0);
        double up = 0.5 * (1 + Math.tanh((rhoA * G * hA - rhoB * G * hB + 0.5 * (rhoA + rhoB) * G * (portA - portB)) / DP0));
        double rho = up * rhoA + (1 - up) * rhoB;
        double drive = rhoA * G * hA - rhoB * G * hB + rho * G * (portA - portB);
        double open = gate == null ? 1 : Math.max(gate.opening(), 1e-6);
        double q = cd * area * open * Math.sqrt(2 / rho) * drive / Math.pow(drive * drive + DP0 * DP0, 0.25);
        // over the lip: the upstream depth above the port limits it (weir flow, ∝ H^{3/2})
        double hUp = up * hA + (1 - up) * hB;
        double qw = 2.0 / 3 * cd * 2 * Math.sqrt(Math.PI * area) * open * Math.sqrt(2 * G) * hUp * Math.sqrt(hUp);
        q = q * qw / (Math.abs(q) + qw);
        q *= up * phiA + (1 - up) * phiB;
        double e = 0;
        for (int k = 0; k < n; k++) {
            double cA = pa[k] / (vA + 1e-12), cB = pb[k] / (vB + 1e-12);
            double nk = q * (up * cA + (1 - up) * cB);
            out[k] = nk;
            e += nk * (up * a.liquidEnergy(species[k], tA) + (1 - up) * b.liquidEnergy(species[k], tB));
        }
        out[n] = e;
        out[n + 1] = q;
    }

    @Override
    public void load(LoadContext ctx) {
        indices();
        int n = species.length;
        double[] lA = new double[n], lB = new double[n];
        for (int k = 0; k < n; k++) {
            lA[k] = ctx.x(la[k]);
            lB[k] = ctx.x(lb[k]);
        }
        int pa = a.pressureNode().index(), pb = b.pressureNode().index(), ta = a.temperatureNode().index(), tb = b.temperatureNode().index();
        double pA = ctx.x(pa), pB = ctx.x(pb), tA = ctx.x(ta), tB = ctx.x(tb);
        double[] f0 = new double[n + 2], f1 = new double[n + 2];
        flows(lA, lB, pA, pB, tA, tB, f0);
        for (int k = 0; k < n; k++) {
            ctx.addF(la[k], f0[k]);
            ctx.addF(lb[k], -f0[k]);
        }
        ctx.addF(ta, f0[n]);
        ctx.addF(tb, -f0[n]);
        // slopes by finite differences: each liquid pool, and both temperatures (the pressures move nothing)
        for (int j = 0; j < 2 * n + 4; j++) {
            if (j == 2 * n || j == 2 * n + 1) continue;
            double h;
            int col;
            if (j < n) {
                h = Math.max(1e-9, 1e-7 * Math.abs(lA[j]));
                col = la[j];
                lA[j] += h;
                flows(lA, lB, pA, pB, tA, tB, f1);
                lA[j] -= h;
            } else if (j < 2 * n) {
                int k = j - n;
                h = Math.max(1e-9, 1e-7 * Math.abs(lB[k]));
                col = lb[k];
                lB[k] += h;
                flows(lA, lB, pA, pB, tA, tB, f1);
                lB[k] -= h;
            } else {
                int w = j - 2 * n;
                double[] v = {pA, pB, tA, tB};
                h = w < 2 ? 1e-3 : 1e-4 * Math.max(1, v[w]);
                v[w] += h;
                col = new int[]{pa, pb, ta, tb}[w];
                flows(lA, lB, v[0], v[1], v[2], v[3], f1);
            }
            for (int k = 0; k < n; k++) {
                double d = (f1[k] - f0[k]) / h;
                if (d == 0) continue;
                ctx.addJ(la[k], col, d);
                ctx.addJ(lb[k], col, -d);
            }
            double de = (f1[n] - f0[n]) / h;
            if (de != 0) {
                ctx.addJ(ta, col, de);
                ctx.addJ(tb, col, -de);
            }
        }
    }

    /** Liquid volume flow from a to b [m³/s]. */
    public double volumeFlow(StateView s) {
        indices();
        int n = species.length;
        double[] lA = new double[n], lB = new double[n], out = new double[n + 2];
        for (int k = 0; k < n; k++) {
            lA[k] = s.x(la[k]);
            lB[k] = s.x(lb[k]);
        }
        flows(lA, lB, s.x(a.pressureNode().index()), s.x(b.pressureNode().index()), s.x(a.temperatureNode().index()),
                s.x(b.temperatureNode().index()), out);
        return out[n + 1];
    }

    @Override
    public String label() {
        return "liquid drain";
    }
}
