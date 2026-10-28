package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

/**
 * A rechargeable battery (a manufactured component): {@code cells} cells in series of one {@link Chemistry}. Its charge
 * is counted by Faraday (the state of charge integrates the current); its EMF follows from what that charge has done to
 * the electrodes and electrolyte; its internal resistance warms it.
 * <ul>
 *   <li><b>Lead–acid</b>, PbO₂ | H₂SO₄ | Pb. Discharging turns both plates and the acid into lead sulfate and water,
 *   Pb + PbO₂ + 2 H₂SO₄ → 2 PbSO₄ + 2 H₂O, so the acid thins as charge is drawn. The Nernst equation for the cell over
 *   the acid concentrations of a working cell is well summarised by Vinal's rule, E ≈ SG + 0.845 V for specific gravity
 *   SG 1.10–1.30; the acid goes from SG 1.28 charged to 1.10 flat, linearly in the charge drawn: 2.125 V → 1.945 V a
 *   cell. Internal resistance 0.1 Ω·Ah ÷ capacity a cell (a 7 Ah cell ≈ 14 mΩ).</li>
 *   <li><b>Nickel–metal hydride</b>, NiOOH | KOH | MH. Discharging reduces the positive's nickel oxyhydroxide,
 *   NiOOH + H₂O + e⁻ → Ni(OH)₂ + OH⁻, while the alloy gives up its hydrogen, MH + OH⁻ → M + H₂O + e⁻; the alkaline
 *   electrolyte is not consumed. The voltage is set by the positive's redox couple, whose oxidised share is the state of
 *   charge s: the Nernst equation, E = E½ + (RT/F)·ln(s/(1 − s)), E½ = 1.30 V at half charge and 298 K — 1.27 V at
 *   20 %, 1.35 V at 90 % (a pinch of 2 % at each end keeps it finite: 1.40 V full). Four cells make a 5 V supply.
 *   Internal resistance 0.05 Ω·Ah ÷ capacity a cell (a 2 Ah AA cell ≈ 25 mΩ).</li>
 *   <li><b>Exhaustion and overcharge</b> (both): below a few percent of charge the active material is used up and the
 *   voltage falls away (a knee 0.3 V deep over the last 3 %); above full, charging current goes into side reactions
 *   (water splitting, oxygen evolution) and the voltage climbs 10 mV a cell per percent overcharged. The charge is not
 *   clamped: the knees hold it in range. The resistance is 60 % higher flat, and steeply higher in the knee.</li>
 * </ul>
 * <b>Energy</b>: the chemical free energy is an external reservoir delivering N·E·i; N·i²·r is Joule heat in the
 * battery (its thermal node, or an untracked loss without one). One element may stand for a string of identical
 * batteries in series (see AssemblyCompiler's battery strings): then its heat is shared equally among their thermal
 * nodes — exact, since identical cells carrying one current go through identical states. Not modelled: the reversible (entropic) heat, Peukert
 * rate dependence of capacity beyond the resistance, temperature dependence (the Nernst term is taken at 298 K),
 * self-discharge, ageing.
 */
public final class Battery implements Element {
    /** What the cells are made of. */
    public enum Chemistry {
        LEAD_ACID(0.1), NICKEL_METAL_HYDRIDE(0.05);

        /** Internal resistance of a cell times its capacity [Ω·A·h]. */
        final double resistanceAh;

        Chemistry(double resistanceAh) {
            this.resistanceAh = resistanceAh;
        }

        /** By the part's "chemistry" number: 0 lead–acid, 1 nickel–metal hydride. */
        public static Chemistry of(double code) {
            return (int) Math.round(code) == 1 ? NICKEL_METAL_HYDRIDE : LEAD_ACID;
        }
    }

    /** Specific gravity of the acid charged and flat, and Vinal's offset [V]. */
    static final double SG_FULL = 1.28, SG_FLAT = 1.10, VINAL = 0.845;
    /** The nickel electrode's potential at half charge [V], the Nernst slope RT/F at 298 K [V], and its end pinch. */
    static final double NI_HALF = 1.30, NERNST = PhysicalConstants.GAS_CONSTANT * 298.15 / PhysicalConstants.FARADAY, NI_PINCH = 0.02;

    private final Node pos, neg;
    /** Where its Joule heat goes, in equal shares (none: an untracked loss). */
    private final Node[] thermals;
    private final Chemistry chemistry;
    private final int cells;
    private final double capacity, soc0;
    private int jIdx, qIdx, slot;

    /**
     * @param capacityAh capacity [A·h]
     * @param stateOfCharge charge held at the start (1: full)
     */
    public Battery(Chemistry chemistry, Node pos, Node neg, int cells, double capacityAh, double stateOfCharge, Node thermal) {
        this(chemistry, pos, neg, cells, capacityAh, stateOfCharge, thermal == null ? new Node[0] : new Node[]{thermal});
    }

    /** A string of batteries as one: its cells in all, and each battery's thermal node (their heat in equal shares). */
    public Battery(Chemistry chemistry, Node pos, Node neg, int cells, double capacityAh, double stateOfCharge, Node[] thermals) {
        this.chemistry = chemistry;
        this.pos = pos;
        this.neg = neg;
        this.cells = Math.max(1, cells);
        this.capacity = Math.max(1e-3, capacityAh) * 3600;
        this.soc0 = stateOfCharge;
        this.thermals = java.util.Arrays.stream(thermals).filter(java.util.Objects::nonNull).toArray(Node[]::new);
    }

    @Override
    public String label() {
        return "Battery(" + chemistry + ", " + cells + " cells, " + capacity / 3600 + " Ah)";
    }

    @Override
    public void setup(SetupContext ctx) {
        jIdx = ctx.allocUnknown("I(battery)", 1e-9);
        qIdx = ctx.allocUnknown("Q(battery)", 1e-6);
        slot = ctx.allocState(1e-3);
    }

    /** {EMF per cell [V], its slope with the state of charge}. */
    public static double[] emf(Chemistry chemistry, double soc) {
        double e, de;
        if (chemistry == Chemistry.NICKEL_METAL_HYDRIDE) {
            // Nernst over the nickel electrode's oxidised share, pinched at the ends; continued straight past them
            double s = Math.max(0, Math.min(1, soc));
            e = NI_HALF + NERNST * Math.log((s + NI_PINCH) / (1 - s + NI_PINCH));
            de = NERNST * (1 / (s + NI_PINCH) + 1 / (1 - s + NI_PINCH));
            e += de * (soc - s);
        } else {
            e = SG_FLAT + (SG_FULL - SG_FLAT) * soc + VINAL;
            de = SG_FULL - SG_FLAT;
        }
        // exhaustion: the voltage falls away over the last few percent
        double ex = Math.exp(-soc / 0.03);
        e -= 0.3 * ex;
        de += 0.3 * ex / 0.03;
        // overcharge: side reactions past full, the voltage climbing 10 mV a cell per percent overcharged (smooth: a
        // softplus knee 3 % past full, so a full battery reads its chemistry's voltage)
        double u = (soc - 1.03) / 0.01, sp = u > 30 ? u : Math.log1p(Math.exp(u)), dsp = 1 / (1 + Math.exp(-u));
        e += 0.01 * sp;
        de += 0.01 * dsp / 0.01;
        return new double[]{e, de};
    }

    /** {internal resistance per cell [Ω], its slope with the state of charge}. */
    double[] resistance(double soc) {
        double r0 = chemistry.resistanceAh / (capacity / 3600);
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
        double[] e = emf(chemistry, s), r = resistance(s);
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
        // Joule heat N j² r into the battery (shared among a string's batteries)
        double share = 1.0 / Math.max(1, thermals.length);
        for (Node thermal : thermals) {
            ctx.addF(thermal.index(), -share * cells * j * j * r[0]);
            ctx.addJ(thermal.index(), jIdx, -share * 2 * cells * j * r[0]);
            ctx.addJ(thermal.index(), qIdx, share * cells * j * j * r[1] / capacity);
        }
    }

    public Chemistry chemistry() {
        return chemistry;
    }

    /** Current delivered out of the + terminal [A] (negative: charging). */
    public double current(StateView s) {
        return -s.x(jIdx);
    }

    public double voltage(StateView s) {
        return s.across(pos, neg);
    }

    /** Charge delivered since the island started [C]: the time integral of its current, exactly. */
    public double chargeDelivered(StateView s) {
        return s.x(qIdx);
    }

    /** Charge held, as a fraction of the capacity. */
    public double stateOfCharge(StateView s) {
        return soc(s.x(qIdx));
    }

    /** Open-circuit voltage now [V]. */
    public double openCircuitVoltage(StateView s) {
        return cells * emf(chemistry, stateOfCharge(s))[0];
    }

    @Override
    public double externalPower(StateView s) {
        // the chemistry gives N·E·i
        return -cells * emf(chemistry, stateOfCharge(s))[0] * s.x(jIdx);
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermals.length > 0) return 0;
        double j = s.x(jIdx);
        return cells * j * j * resistance(stateOfCharge(s))[0];
    }
}
