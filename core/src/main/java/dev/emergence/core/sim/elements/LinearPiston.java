package dev.emergence.core.sim.elements;

/**
 * A piston on a straight rod that is part of a slide: s(x) = s₀ + sign·x for the slide's displacement x. Near the
 * cylinder head the volume is kept from vanishing (s ≥ −0.9·c, smoothly) — in practice the slide's own stops end the
 * travel before that.
 *
 * @param area      piston area [m²]
 * @param clearance clearance length c [m]
 * @param s0        piston distance from the head when the slide is at x = 0 [m]
 * @param sign      +1 if the slide moving along its axis draws the piston away from the head, −1 if towards it
 */
public record LinearPiston(double area, double clearance, double s0, double sign) implements PistonKinematics {
    @Override
    public double displacement(double x) {
        double s = s0 + sign * x, floor = -0.9 * clearance, w = 0.05 * clearance;
        // soft max(s, floor)
        return floor + 0.5 * ((s - floor) + Math.sqrt((s - floor) * (s - floor) + w * w));
    }

    @Override
    public double dDisplacement(double x) {
        double s = s0 + sign * x, floor = -0.9 * clearance, w = 0.05 * clearance;
        return sign * 0.5 * (1 + (s - floor) / Math.sqrt((s - floor) * (s - floor) + w * w));
    }
}
