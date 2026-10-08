package dev.emergence.core.sim;

/**
 * Implicit integration formulas applied to every stored quantity q (charge, flux, momentum, ...).
 * <ul>
 *   <li>{@link #BACKWARD_EULER} — 1st order, L-stable, strongly dissipative. Used to restart after
 *       discontinuities.</li>
 *   <li>{@link #TRAPEZOIDAL} — 2nd order, A-stable, exactly conserves the energy of linear lossless
 *       oscillators (it is symplectic for linear systems) but can ring on very stiff modes.</li>
 *   <li>{@link #BDF2} — 2nd order, L-stable (variable-step Gear-2). Damps stiff parasitic modes;
 *       introduces numerical dissipation of order (ωh)^4 per cycle for oscillators.</li>
 * </ul>
 */
public enum IntegrationMethod {
    BACKWARD_EULER(1, 0.5),
    TRAPEZOIDAL(2, 1.0 / 12.0),
    BDF2(2, 2.0 / 9.0);

    public final int order;
    /** Leading coefficient of the local truncation error, LTE = C·h^(p+1)·q^(p+1). */
    public final double errorConstant;

    IntegrationMethod(int order, double errorConstant) {
        this.order = order;
        this.errorConstant = errorConstant;
    }
}
