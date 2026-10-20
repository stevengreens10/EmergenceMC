package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

/**
 * A lead–acid battery (a manufactured component): {@code cells} cells in series, each lead dioxide and lead plates in
 * sulfuric acid. Discharging turns both plates and the acid into lead sulfate and water,
 * Pb + PbO₂ + 2 H₂SO₄ → 2 PbSO₄ + 2 H₂O, so the acid thins as charge is drawn; charging runs it back.
 * <ul>
 *   <li><b>EMF per cell</b> from the acid's strength: the Nernst equation for the cell over the acid concentrations of a
 *   working cell is well summarised by Vinal's rule, E ≈ SG + 0.845 V for specific gravity SG 1.10–1.30. The acid
 *   goes from SG 1.28 charged to 1.10 flat, linearly in the charge drawn: 2.125 V → 1.945 V a cell.</li>
 *   <li><b>Exhaustion and overcharge</b>: below a few percent of charge the plates' active material is used up and
 *   the voltage falls away (a knee 0.3 V deep over the last 3 %); above full, charging current splits water
 *   (gassing) and the voltage climbs towards 2.4 V a cell. The charge is not clamped: the knees hold it in range.</li>
 *   <li><b>Internal resistance</b> per cell r = 0.1 Ω·Ah ÷ capacity (a 7 Ah cell ≈ 14 mΩ), 60 % higher flat
 *   (thinner acid, sulfated plates), and steeply higher in the exhaustion knee.</li>
 * </ul>
 * <b>Energy</b>: the chemical free energy is an external reservoir delivering N·E·i; N·i²·r is Joule heat in the
 * battery (its thermal node, or an untracked loss without one). Not modelled: the reversible (entropic) heat, Peukert
 * rate dependence of capacity beyond the resistance, temperature dependence, self-discharge, sulfation over time.
 */
public final class LeadAcidBattery implements Element {
    /** Specific gravity of the acid charged and flat, and Vinal's offset [V]. */
    static final double SG_FULL = 1.28, SG_FLAT = 1.10, VINAL = 0.845;

    private final Node pos, neg, thermal;
    private final int cells;
    private final double capacity, soc0;
    private int jIdx, qIdx, slot;

    /**
     * @param capacityAh capacity [A·h]
     * @param stateOfCharge charge held at the start (1: full)
     */
    public LeadAcidBattery(Node pos, Node neg, int cells, double capacityAh, double stateOfCharge, Node thermal) {
        this.pos = pos;
        this.neg = neg;
        this.cells = Math.max(1, cells);
        this.capacity = Math.max(1e-3, capacityAh) * 3600;
        this.soc0 = stateOfCharge;
        this.thermal = thermal;
    }

    @Override
    public String label() {
        return "LeadAcidBattery(" + cells + " cells, " + capacity / 3600 + " Ah)";
    }

    @Override
    public void setup(SetupContext ctx) {
        jIdx = ctx.allocUnknown("I(battery)", 1e-9);
        qIdx = ctx.allocUnknown("Q(battery)", 1e-6);
        slot = ctx.allocState(1e-3);
    }

    /** {EMF per cell [V], its slope with the state of charge}. */
    static double[] emf(double soc) {
        double e = SG_FLAT + (SG_FULL - SG_FLAT) * soc + VINAL, de = SG_FULL - SG_FLAT;
        // exhaustion: the voltage falls away over the last few percent
        double ex = Math.exp(-soc / 0.03);
        e -= 0.3 * ex;
        de += 0.3 * ex / 0.03;
        // overcharge: gassing past full, the voltage climbing 10 mV a cell per percent overcharged (smooth: a softplus
        // knee 3 % past full, so a full battery reads its acid's voltage)
        double u = (soc - 1.03) / 0.01, sp = u > 30 ? u : Math.log1p(Math.exp(u)), dsp = 1 / (1 + Math.exp(-u));
        e += 0.01 * sp;
        de += 0.01 * dsp / 0.01;
        return new double[]{e, de};
    }

    /** {internal resistance per cell [Ω], its slope with the state of charge}. */
    double[] resistance(double soc) {
        double r0 = 0.1 / (capacity / 3600);
        double s = Math.max(soc, 1e-3), knee = 1 + 0.02 / s, dknee = soc > 1e-3 ? -0.02 / (s * s) : 0;
        double base = 1 + 0.6 * (1 - Math.min(soc, 1)), dbase = soc < 1 ? -0.6 : 0;
        return new double[]{r0 * base * knee, r0 * (dbase * knee + base * dknee)};
    }

    private double soc(double q) {
        return soc0 - q / capacity;
    }

    @Override
    public void load(LoadContext ctx) {
        // j: current from the network into the + terminal, through the battery to −; discharge current i = −j
        double j = ctx.x(jIdx), q = ctx.x(qIdx), s = soc(q);
        double[] e = emf(s), r = resistance(s);
        ctx.addF(pos.index(), j);
        ctx.addF(neg.index(), -j);
        ctx.addJ(pos.index(), jIdx, 1);
        ctx.addJ(neg.index(), jIdx, -1);
        // v+ − v− = N (E − i r) = N (E + j r)
        ctx.addF(jIdx, ctx.x(pos.index()) - ctx.x(neg.index()) - cells * (e[0] + j * r[0]));
        ctx.addJ(jIdx, pos.index(), 1);
        ctx.addJ(jIdx, neg.index(), -1);
        ctx.addJ(jIdx, jIdx, -cells * r[0]);
        ctx.addJ(jIdx, qIdx, cells * (e[1] + j * r[1]) / capacity); // ds/dq = −1/C
        // charge drawn: dq/dt = i = −j
        ctx.addF(qIdx, ctx.ddt(slot, q) + j);
        ctx.addJ(qIdx, qIdx, ctx.alpha());
        ctx.addJ(qIdx, jIdx, 1);
        if (thermal != null) {
            // Joule heat N j² r into the battery
            ctx.addF(thermal.index(), -cells * j * j * r[0]);
            ctx.addJ(thermal.index(), jIdx, -2 * cells * j * r[0]);
            ctx.addJ(thermal.index(), qIdx, cells * j * j * r[1] / capacity);
        }
    }

    /** Current delivered out of the + terminal [A] (negative: charging). */
    public double current(StateView s) {
        return -s.x(jIdx);
    }

    public double voltage(StateView s) {
        return s.across(pos, neg);
    }

    /** Charge held, as a fraction of the capacity. */
    public double stateOfCharge(StateView s) {
        return soc(s.x(qIdx));
    }

    /** Open-circuit voltage now [V]. */
    public double openCircuitVoltage(StateView s) {
        return cells * emf(stateOfCharge(s))[0];
    }

    @Override
    public double externalPower(StateView s) {
        // the chemistry gives N·E·i
        return -cells * emf(stateOfCharge(s))[0] * s.x(jIdx);
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double j = s.x(jIdx);
        return cells * j * j * resistance(stateOfCharge(s))[0];
    }
}
