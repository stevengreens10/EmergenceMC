package dev.emergence.core.acoustics;

import dev.emergence.core.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Linear acoustics in free air from compact dipole sources ({@link DipoleRadiator}), retarded: the field at r and t is
 * made by each source's motion at t − r/c. With the velocity potential φ (u = ∇φ, p = −ρ ∂φ/∂t), a source of strength
 * m(t) = M·U(t) along ŝ, at distance r in direction r̂ (τ = t − r/c):
 * <pre>
 *   φ = −(ŝ·r̂)·A/(4π),   A = m(τ)/r² + ṁ(τ)/(c r)
 *   p = ρ (ŝ·r̂)·[ṁ(τ)/r² + m̈(τ)/(c r)]/(4π)
 *   u = −[(ŝ − (ŝ·r̂) r̂)·A/r + (ŝ·r̂) r̂·∂A/∂r]/(4π),   ∂A/∂r = −2m/r³ − 2ṁ/(c r²) − m̈/(c² r)
 * </pre>
 * — the near field (1/r³: the potential flow round a moving body, carried along with no delay to speak of), the
 * induction field (1/r²) and the radiated far field (1/r: p = ρ cos θ·m̈/(4π c r), u = p/(ρc) along r̂). A dipole
 * radiates P = ρ M²·Ü²/(12π c³) (Ü the second derivative of the source's velocity).
 * <p>
 * A receiver — another rigid body of strength M_r along ŝ_r, small against the wavelength — feels the force the
 * accelerating air exerts on it, F = ρ M_r·(∂u/∂t · ŝ_r) (the Froude–Krylov force plus the added-mass reaction, for a
 * body held still in an unsteady potential flow); by the symmetry of the Green's function, the force A's motion puts on
 * B equals the force B's would put on A.
 */
public final class SoundField {
    private final double rho, c;
    private final List<DipoleRadiator> sources = new ArrayList<>();

    /** Air of density ρ [kg/m³] and sound speed c [m/s]. */
    public SoundField(double rho, double c) {
        this.rho = rho;
        this.c = c;
    }

    /** Dry air at temperature T [K] and pressure p [Pa]: ρ = pM/(RT), c = √(γRT/M), γ = 1.4, M = 28.96 g/mol. */
    public static SoundField air(double temperature, double pressure) {
        double m = 0.028_964, r = 8.314_462_618;
        return new SoundField(pressure * m / (r * temperature), Math.sqrt(1.4 * r * temperature / m));
    }

    public double density() {
        return rho;
    }

    public double soundSpeed() {
        return c;
    }

    public void add(DipoleRadiator s) {
        sources.add(s);
    }

    public List<DipoleRadiator> sources() {
        return sources;
    }

    /** Sound pressure [Pa] at x and time t from every source but {@code skip}. */
    public double pressure(Vec3 x, double t, DipoleRadiator skip) {
        double p = 0;
        for (DipoleRadiator s : sources) {
            if (s == skip) continue;
            Vec3 d = x.sub(s.centre());
            double r = d.length();
            if (r < 1e-9) continue;
            Vec3 rh = d.mul(1 / r);
            double[] u = s.derivatives(t - r / c);
            double m = s.moment();
            p += rho * s.axis().dot(rh) * (m * u[1] / (r * r) + m * u[2] / (c * r)) / (4 * Math.PI);
        }
        return p;
    }

    /** Particle velocity [m/s] at x and time t (order 0) or its time derivative (order 1), from every source but {@code skip}. */
    public Vec3 velocity(Vec3 x, double t, DipoleRadiator skip, int order) {
        Vec3 sum = Vec3.ZERO;
        for (DipoleRadiator s : sources) {
            if (s == skip) continue;
            Vec3 d = x.sub(s.centre());
            double r = d.length();
            if (r < 1e-9) continue;
            Vec3 rh = d.mul(1 / r), sh = s.axis();
            double[] u = s.derivatives(t - r / c);
            double mm = s.moment();
            // m, ṁ, m̈ (or their time derivatives, for ∂u/∂t)
            double m0 = mm * u[order], m1 = mm * u[order + 1], m2 = mm * u[order + 2];
            double a = m0 / (r * r) + m1 / (c * r);
            double dadr = -2 * m0 / (r * r * r) - 2 * m1 / (c * r * r) - m2 / (c * c * r);
            double cs = sh.dot(rh);
            Vec3 tangential = sh.sub(rh.mul(cs)).mul(a / r);
            Vec3 radial = rh.mul(cs * dadr);
            sum = sum.add(tangential.add(radial).mul(-1 / (4 * Math.PI)));
        }
        return sum;
    }

    /** The force [N] the air's motion puts on receiver {@code rx} (its own field excluded): ρ·M_r·(∂u/∂t·ŝ_r). */
    public double force(DipoleRadiator rx, double t) {
        return rho * rx.moment() * velocity(rx.centre(), t, rx, 1).dot(rx.axis());
    }

    /** Power a dipole source radiates [W] at time t (from its velocity's second derivative). */
    public double radiatedPower(DipoleRadiator s, double t) {
        double ddu = s.derivatives(t)[2], m = s.moment();
        return rho * m * m * ddu * ddu / (12 * Math.PI * c * c * c);
    }
}
