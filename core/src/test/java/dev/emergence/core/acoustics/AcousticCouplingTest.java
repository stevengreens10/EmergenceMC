package dev.emergence.core.acoustics;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.Damper;
import dev.emergence.core.sim.elements.FlowSource;
import dev.emergence.core.sim.elements.Inertia;
import dev.emergence.core.sim.elements.Spring;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A plate driven to and fro at 400 Hz makes a second plate, tuned to that frequency, half a metre away along its axis
 * vibrate: the amplitude the solver produces matches the driven oscillator's response to the field's force,
 * Û_B = ρM_B·iω·û_x/Z_B with û_x = M_A·Û_A·e^{−ikr}(2/r³ + 2ik/r² − k²/r)/(4π).
 */
class AcousticCouplingTest {
    @Test
    void aPlateHearsAnother() {
        double f = 400, w = 2 * Math.PI * f, r = 0.5;
        double mA = 0.05, mB = 0.02, zeta = 0.01;
        double kA = mA * Math.pow(2 * Math.PI * 300, 2), kB = mB * w * w, cA = 2 * zeta * Math.sqrt(kA * mA), cB = 2 * zeta * Math.sqrt(kB * mB);
        double moment = DipoleRadiator.plateMoment(0.03, 0);
        Network n = new Network();
        Node ref = n.reference(Domain.TRANSLATIONAL);
        Node a = n.node(Domain.TRANSLATIONAL, "A"), b = n.node(Domain.TRANSLATIONAL, "B");
        n.add(new Inertia(a, mA));
        n.add(new Spring(a, ref, kA));
        n.add(new Damper(a, ref, cA, null));
        n.add(new Inertia(b, mB));
        n.add(new Spring(b, ref, kB));
        n.add(new Damper(b, ref, cB, null));
        n.add(new FlowSource(ref, a, t -> 2.0 * Math.sin(w * t)));
        SoundField air = SoundField.air(293.15, 101_325);
        var ra = new DipoleRadiator(Vec3.ZERO, new Vec3(1, 0, 0), moment);
        var rb = new DipoleRadiator(new Vec3(r, 0, 0), new Vec3(1, 0, 0), moment);
        n.add(new AcousticCoupling(air, java.util.List.of(ra, rb), java.util.List.of(a, b)));
        TransientSolver s = new TransientSolver(n).maxStep(1 / (f * 40));
        s.advance(1.5); // B's ring-up time 1/(ζω) = 40 ms; A's transient dies the same way
        // phasors over 20 periods
        int per = 20, sub = 80;
        double[] ca = new double[2], cb = new double[2];
        double t0 = s.time();
        for (int k = 0; k < per * sub; k++) {
            s.advance(1 / (f * sub));
            double t = s.time() - t0, cs = Math.cos(w * t), sn = Math.sin(w * t);
            ca[0] += s.x(a) * cs;
            ca[1] += s.x(a) * sn;
            cb[0] += s.x(b) * cs;
            cb[1] += s.x(b) * sn;
        }
        double norm = 2.0 / (per * sub);
        // complex amplitudes X such that x(t) = Re(X e^{iωt}) = Re(X)cos − Im(X)sin
        double[] ua = {ca[0] * norm, -ca[1] * norm}, ub = {cb[0] * norm, -cb[1] * norm};
        double c = air.soundSpeed(), kw = w / c;
        // û_x = M·Û_A·e^{−ikr}·(2/r³ + 2ik/r² − k²/r)/(4π)
        double[] g = {2 / (r * r * r) - kw * kw / r, 2 * kw / (r * r)};
        double[] e = {Math.cos(kw * r), -Math.sin(kw * r)};
        double[] ge = {g[0] * e[0] - g[1] * e[1], g[0] * e[1] + g[1] * e[0]};
        double[] ux = {moment * (ua[0] * ge[0] - ua[1] * ge[1]) / (4 * Math.PI), moment * (ua[0] * ge[1] + ua[1] * ge[0]) / (4 * Math.PI)};
        // F = ρ M iω û_x; at resonance Z_B = c_B
        double[] force = {-air.density() * moment * w * ux[1], air.density() * moment * w * ux[0]};
        double expect = Math.hypot(force[0], force[1]) / cB, got = Math.hypot(ub[0], ub[1]);
        System.out.printf("acoustic coupling: source %.4g m/s, receiver %.4g m/s (expected %.4g); energy error %.3g J of %.3g J in%n",
                Math.hypot(ua[0], ua[1]), got, expect, s.energyError(), s.externalEnergyIn());
        assertEquals(expect, got, 0.05 * expect);
        assertTrue(Math.abs(s.energyError()) < 1e-2 * Math.abs(s.externalEnergyIn()), "energy balances (to the integration error of 40 steps a period)");
    }
}
