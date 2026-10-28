package dev.emergence.core.digital;

import java.util.Map;

/**
 * The transistors a fabrication process made, and what they make of a chip: its speed and leakage against the
 * 74HC-class process the ready-made chips are made in, or that it does not work at all.
 * <p>
 * A process is its gate oxide's thickness t_ox, its source and drain junctions' depth x_j, the drawn gate length L
 * (the mask's) and the carriers that conduct the channel (electrons with phosphorus-doped sources and drains,
 * holes with boron). The models, long-channel MOS (as the square law in {@link DigitalDomain#delayFactor}):
 * <ul>
 *   <li>Threshold V_T = V_FB + 2φ_F + √(2qε_Si·N_A·2φ_F)/C_ox, C_ox = ε_ox/t_ox, with the channel doped
 *       N_A = 10¹⁶ cm⁻³ and the flat-band voltage set so that the reference process (50 nm) has the 0.75 V the
 *       circuits' timing assumes. A thicker oxide raises it (0.71 V for every 50 nm), a thinner lowers it.</li>
 *   <li>Effective channel L_eff = L − 2·0.8·x_j: the sources and drains diffuse sideways about 0.8 of their depth.
 *       Shorter than 1 µm and the drain's depletion reaches the source (punch-through): the gate no longer shuts the
 *       channel. Shallower than 0.1 µm and the aluminium contacts spike through the junctions.</li>
 *   <li>Delay ∝ C·V/I. The drive current I ∝ μ·(ε_ox/t_ox)·(W/L_eff)·(V − V_T)²; half the load is the next gates'
 *       capacitance (∝ W·L_eff/t_ox), half the wiring and junctions' (∝ W). So delay scales as
 *       (μ_ref/μ)·((V − V_T,ref)/(V − V_T))²·(L_eff/L_ref)·[½·L_eff/L_ref + ½·t_ox/t_ox,ref], at 5 V.</li>
 *   <li>Leakage (subthreshold) scales as exp((V_T,ref − V_T)/(n·kT/q)), n = 1.5 at 300 K.</li>
 *   <li>It works while 0.3 V ≤ V_T ≤ 4.5 V (below, its transistors do not turn off; above, a 5 V supply barely
 *       turns them on), 0.1 µm ≤ x_j and L_eff ≥ 1 µm.</li>
 * </ul>
 * Hole mobility is about a third of electrons' (480 against 1350 cm²/V·s in lightly doped silicon), so a p-channel
 * chip is that much slower. The chip's logic stays as its design says (one process makes all its transistors).
 */
public record ChipProcess(double oxide, double junction, double gateLength, boolean pChannel) {
    /** The reference process (the ready-made chips'): 50 nm oxide, 0.5 µm junctions, 3 µm gates, n-channel. */
    public static final double REF_OXIDE = 50e-9, REF_JUNCTION = 0.5e-6, REF_GATE = 3e-6;
    public static final ChipProcess REFERENCE = new ChipProcess(REF_OXIDE, REF_JUNCTION, REF_GATE, false);
    /** Chip parameters carrying the process: oxide [m], junction depth [m], gate length [m], channel (0 n, 1 p). */
    public static final String OXIDE = "fab_oxide", JUNCTION = "fab_junction", GATE = "fab_gate", CHANNEL = "fab_channel";

    private static final double Q = 1.602176634e-19, EPS0 = 8.8541878128e-12;
    private static final double EPS_SI = 11.7 * EPS0, EPS_OX = 3.9 * EPS0;
    /** Channel doping [m⁻³] and 2φ_F at 300 K for it (n_i = 10¹⁰ cm⁻³). */
    private static final double N_A = 1e22, TWO_PHI_F = 2 * 0.025852 * Math.log(1e16 / 1e10);
    private static final double V = 5, N_KT_Q = 1.5 * 0.025852;
    private static final double MOBILITY_RATIO = 1350.0 / 480;
    /** Lateral diffusion of a junction as a share of its depth; the shortest channel that still shuts [m]. */
    private static final double LATERAL = 0.8, MIN_CHANNEL = 1e-6, MIN_JUNCTION = 0.1e-6;
    /** Flat-band voltage: what gives the reference process its 0.75 V threshold. */
    private static final double V_FB = DigitalDomain.V_T - TWO_PHI_F - depletionCharge() / (EPS_OX / REF_OXIDE);

    private static double depletionCharge() {
        return Math.sqrt(2 * Q * EPS_SI * N_A * TWO_PHI_F);
    }

    /** The process a chip part's parameters say, the reference where they say nothing. */
    public static ChipProcess of(Map<String, Double> params) {
        return new ChipProcess(params.getOrDefault(OXIDE, REF_OXIDE), params.getOrDefault(JUNCTION, REF_JUNCTION),
                params.getOrDefault(GATE, REF_GATE), params.getOrDefault(CHANNEL, 0.0) > 0.5);
    }

    /** Threshold voltage [V]. */
    public double threshold() {
        return V_FB + TWO_PHI_F + depletionCharge() * oxide / EPS_OX;
    }

    /** The channel left between source and drain [m]. */
    public double effectiveLength() {
        return gateLength - 2 * LATERAL * junction;
    }

    /** Null if it works, else why not. */
    public String fault() {
        double vt = threshold();
        if (!(oxide > 0) || vt < 0.3) return String.format("its transistors do not turn off (threshold %.2f V: the oxide is too thin)", vt);
        if (vt > V - 0.5) return String.format("a 5 V supply barely turns its transistors on (threshold %.2f V: the oxide is too thick)", vt);
        if (junction < MIN_JUNCTION) return String.format("its junctions are too shallow (%.0f nm): the contacts spike through them", junction * 1e9);
        if (effectiveLength() < MIN_CHANNEL)
            return String.format("its sources and drains have diffused into one another (%.1f µm of %.1f µm gate left): punch-through",
                    Math.max(0, effectiveLength()) * 1e6, gateLength * 1e6);
        return null;
    }

    public boolean works() {
        return fault() == null;
    }

    /** How much slower than the reference its logic switches (1: the ready-made chips'). */
    public double delayScale() {
        double l = effectiveLength() / REFERENCE.effectiveLength();
        double drive = (V - DigitalDomain.V_T) / (V - threshold());
        return (pChannel ? MOBILITY_RATIO : 1) * drive * drive * l * (0.5 * l + 0.5 * oxide / REF_OXIDE);
    }

    /** How much more it leaks than the reference (one whose transistors do not turn off: as at 0.3 V). */
    public double leakageScale() {
        return Math.exp((DigitalDomain.V_T - Math.max(0.3, threshold())) / N_KT_Q);
    }
}
