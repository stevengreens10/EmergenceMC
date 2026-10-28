package dev.emergence.core.digital;

import java.util.Map;

/**
 * The transistors a fabrication process made, and what they make of a chip: its speed and leakage against the
 * 74HC-class process the ready-made chips are made in, or that it does not work at all.
 * <p>
 * A process is its gate oxide's thickness t_ox, its source and drain junctions' depth x_j, the drawn gate length L
 * (the mask's) and the carriers that conduct the channel (electrons with phosphorus-doped sources and drains,
 * holes with boron). The models, MOS with the two short-channel effects that matter at these lengths:
 * <ul>
 *   <li>Effective channel L_eff = L − 2·0.8·x_j: the sources and drains diffuse sideways about 0.8 of their depth.
 *       Shorter than 1 µm and the drain's depletion reaches the source (punch-through): the gate no longer shuts the
 *       channel. Shallower than 0.1 µm and the aluminium contacts spike through the junctions.</li>
 *   <li>Threshold V_T = V_FB + 2φ_F + (Q_d/C_ox)·(1 − (x_j/L_eff)·(√(1 + 2W_d/x_j) − 1)), C_ox = ε_ox/t_ox,
 *       Q_d = √(2qε_Si·N_A·2φ_F). The channel is doped N_A = 10¹⁶ cm⁻³, so its depletion is W_d = 0.31 µm deep. The
 *       bracket is Yau's charge sharing: near a short channel's source and drain, their own depletion holds part of
 *       the charge under the gate, and the threshold falls. V_FB is set so that the reference process has the 0.75 V
 *       the circuits' timing assumes. A thicker oxide raises the threshold (0.63 V for every 50 nm at the reference's
 *       lengths); a short channel lowers it.</li>
 *   <li>Delay ∝ C·V/I. The drive current is I ∝ μ·(ε_ox/t_ox)·(W/L_eff)·(V − V_T)² / (1 + (V − V_T)/(E_sat·L_eff)). The
 *       denominator is velocity saturation: carriers stop speeding up near E_sat = 2v_sat/μ (electrons 4 V/µm,
 *       holes 9 V/µm), so a short channel gains less than the square law says. Half the load is the next gates'
 *       capacitance (∝ W·L_eff/t_ox), half the wiring and junctions' (∝ W). At 5 V, against the reference.</li>
 *   <li>Leakage (subthreshold) scales as exp((V_T,ref − V_T)/(n·kT/q)), n = 1.5 at 300 K.</li>
 *   <li>It works while 0.3 V ≤ V_T ≤ 4.5 V (below, its transistors do not turn off; above, a 5 V supply barely
 *       turns them on), 0.1 µm ≤ x_j and L_eff ≥ 1 µm.</li>
 * </ul>
 * Hole mobility is about a third of electrons' (480 against 1350 cm²/V·s in lightly doped silicon), so a p-channel
 * chip is slower. The chip's logic stays as its design says (one process makes all its transistors).
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
    /** The channel's depletion depth [m] and its charge per area [C/m²]. */
    private static final double W_D = Math.sqrt(2 * EPS_SI * TWO_PHI_F / (Q * N_A)), Q_D = Math.sqrt(2 * Q * EPS_SI * N_A * TWO_PHI_F);
    private static final double V = 5, N_KT_Q = 1.5 * 0.025852;
    /** Low-field mobilities [m²/V·s] and saturation velocities [m/s] of electrons and holes in the channel. */
    private static final double MU_N = 0.135, MU_P = 0.048, VSAT_N = 1e5, VSAT_P = 8e4;
    /** Surface mobility (the channel's, against the bulk's) for the saturation field: about 40 %. */
    private static final double SURFACE = 0.4;
    /** Lateral diffusion of a junction as a share of its depth; the shortest channel that still shuts [m]. */
    private static final double LATERAL = 0.8, MIN_CHANNEL = 1e-6, MIN_JUNCTION = 0.1e-6;
    /** Flat-band voltage: what gives the reference process its 0.75 V threshold. */
    private static final double V_FB = DigitalDomain.V_T - TWO_PHI_F - REFERENCE.depletionTerm();

    /** The process a chip part's parameters say, the reference where they say nothing. */
    public static ChipProcess of(Map<String, Double> params) {
        return new ChipProcess(params.getOrDefault(OXIDE, REF_OXIDE), params.getOrDefault(JUNCTION, REF_JUNCTION),
                params.getOrDefault(GATE, REF_GATE), params.getOrDefault(CHANNEL, 0.0) > 0.5);
    }

    /** The depletion charge's share of the threshold [V], less what a short channel's source and drain take of it. */
    private double depletionTerm() {
        double l = Math.max(1e-9, effectiveLength()), xj = Math.max(1e-9, junction);
        double shared = Math.min(1, xj / l * (Math.sqrt(1 + 2 * W_D / xj) - 1));
        return Q_D * oxide / EPS_OX * (1 - shared);
    }

    /** Threshold voltage [V]. */
    public double threshold() {
        return V_FB + TWO_PHI_F + depletionTerm();
    }

    /** The channel left between source and drain [m]. */
    public double effectiveLength() {
        return gateLength - 2 * LATERAL * junction;
    }

    /** Null if it works, else why not. */
    public String fault() {
        if (junction < MIN_JUNCTION) return String.format("its junctions are too shallow (%.0f nm): the contacts spike through them", junction * 1e9);
        if (effectiveLength() < MIN_CHANNEL)
            return String.format("its sources and drains have diffused into one another (%.1f µm of %.1f µm gate left): punch-through",
                    Math.max(0, effectiveLength()) * 1e6, gateLength * 1e6);
        double vt = threshold();
        if (!(oxide > 0) || vt < 0.3)
            return String.format("its transistors do not turn off (threshold %.2f V: the oxide too thin, or the channel too short)", vt);
        if (vt > V - 0.5) return String.format("a 5 V supply barely turns its transistors on (threshold %.2f V: the oxide is too thick)", vt);
        return null;
    }

    public boolean works() {
        return fault() == null;
    }

    /** A gate's delay, up to a constant: its load over its drive current at 5 V. */
    private double delay() {
        double l = effectiveLength(), mu = pChannel ? MU_P : MU_N, eSat = 2 * (pChannel ? VSAT_P : VSAT_N) / (SURFACE * mu);
        double vg = Math.max(1e-3, V - threshold());
        double current = mu / oxide / l * vg * vg / (1 + vg / (eSat * l));
        double load = 0.5 * l / oxide / ((REF_GATE - 2 * LATERAL * REF_JUNCTION) / REF_OXIDE) + 0.5;
        return load / current;
    }

    /** How much slower than the reference its logic switches (1: the ready-made chips'). */
    public double delayScale() {
        return delay() / REFERENCE.delay();
    }

    /** How much more it leaks than the reference (one whose transistors do not turn off: as at 0.3 V). */
    public double leakageScale() {
        return Math.exp((DigitalDomain.V_T - Math.max(0.3, threshold())) / N_KT_Q);
    }
}
