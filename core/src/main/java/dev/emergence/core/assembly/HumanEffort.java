package dev.emergence.core.assembly;

/**
 * Torque a person can apply to a hand crank, from a Hill-type force–velocity relation:
 * the hand force falls linearly from F_max at rest to zero at the maximum hand speed v_max, and the
 * delivered power is capped at the sustainable mechanical output.
 * <pre>
 *   τ(ω) = min( F_max·r·(1 − |ω|·r / v_max),  P_max / |ω| ),   τ ≥ 0
 * </pre>
 * Typical adult values (arm cranking ergometry): F_max ≈ 200 N, v_max ≈ 3 m/s at the handle,
 * sustained P ≈ 100 W (≈ 250 W for short sprints).
 */
public final class HumanEffort {
    private HumanEffort() {}

    public static final double MAX_FORCE = 200.0;
    public static final double MAX_HAND_SPEED = 3.0;
    public static final double SUSTAINED_POWER = 100.0;
    public static final double SPRINT_POWER = 250.0;

    /** Magnitude of crank torque [N·m] at angular speed |ω| for crank radius r. */
    public static double crankTorque(double crankRadius, double omega, boolean sprint) {
        double w = Math.abs(omega);
        double f = MAX_FORCE * Math.max(0.0, 1.0 - w * crankRadius / MAX_HAND_SPEED);
        double tau = f * crankRadius;
        double p = sprint ? SPRINT_POWER : SUSTAINED_POWER;
        if (w > 1e-6) tau = Math.min(tau, p / w);
        return tau;
    }
}
