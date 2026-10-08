package dev.emergence.core.assembly;

/**
 * A measurement taken from the simulated state of one part (what an ideal multimeter, thermocouple or
 * tachometer attached to it would read). Quantities not applicable to the part are NaN.
 *
 * @param temperature     [K]
 * @param current         current through the part's leads / conductor [A]
 * @param voltage         voltage across the part's leads (positive-axis lead minus negative) or node potential [V]
 * @param power           power dissipated in the part [W]
 * @param angularVelocity angular velocity of the body carrying the part [rad/s]
 * @param angle           angle of that body [rad]
 * @param bodyIndex       index of the rigid body (0 = static frame)
 */
public record Probe(long partId, PartKind kind, double temperature, double current, double voltage, double power,
                    double angularVelocity, double angle, int bodyIndex) {
}
