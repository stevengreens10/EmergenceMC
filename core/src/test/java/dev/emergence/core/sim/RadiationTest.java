package dev.emergence.core.sim;

import dev.emergence.core.nuclear.NuclearData;
import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Radioactive decay, Bateman chains and the Geiger–Müller counter. */
class RadiationTest {
    static final NuclearData ND = NuclearData.standard();

    /** A GM tube on 500 V through 4.7 MΩ, 1 kΩ cathode resistor; a source coupled with the given weight. */
    static Object[] counter(Network n, RadioactiveSource src, double weight, double background, long seed) {
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node hv = n.node(Domain.ELECTRICAL, "hv"), an = n.node(Domain.ELECTRICAL, "anode", 500), ca = n.node(Domain.ELECTRICAL, "cathode");
        n.add(new VoltageSource(hv, gnd, 500));
        n.add(new Resistor(hv, an, 4.7e6));
        n.add(new Resistor(ca, gnd, 10_000));
        n.add(new Capacitor(an, gnd, 100e-12)); // anode capacitor: the charge each discharge passes
        n.add(new Capacitor(an, ca, 2e-12));
        GeigerTube tube = n.add(new GeigerTube(an, ca, 350, 300, 1e5, background, seed, null));
        if (src != null) tube.couple(src, 0, weight);
        return new Object[]{tube, ca};
    }

    /** The counts in a minute follow the expected rate within Poisson scatter; each pulse reaches logic level. */
    @Test
    void countsFollowTheRate() {
        Network n = new Network();
        var cs = ND.chain("Cs137");
        double a0 = 3.7e5; // 10 µCi
        double[] atoms = {a0 / cs.get(0).lambda(), 0.944 * a0 / cs.get(1).lambda()}; // Ba-137m in equilibrium
        RadioactiveSource src = n.add(new RadioactiveSource(cs, atoms, null));
        Object[] c = counter(n, src, 0, 0.3, 42);
        GeigerTube tube = (GeigerTube) c[0];
        // couple the Ba-137m gammas: 3 cm² at 15 cm, 1 % efficiency at 662 keV
        tube.couple(src, 1, 0.8999 * 3e-4 / (4 * Math.PI * 0.15 * 0.15) * ND.gmEfficiency(661.66));
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
        double[] pk = {0};
        Node cath = (Node) c[1];
        s.addStepListener(sv -> pk[0] = Math.max(pk[0], sv.x(cath)));
        s.advance(60);
        double peak = pk[0];
        double expected = tube.hitRate(s) * 60;
        System.out.printf("GM counter: %d counts in 60 s (expected %.1f ± %.1f), %d lost, pulse peak %.2f V%n",
                tube.discharges(), expected, Math.sqrt(expected), tube.lost(), peak);
        assertEquals(expected, tube.discharges(), 4 * Math.sqrt(expected), "Poisson counting");
        assertTrue(peak > 2.0, "each discharge gives a logic-level pulse on the cathode resistor: " + peak);
        assertEquals(a0 * 0.944, src.activity(s, 1), 0.001 * a0, "Ba-137m in secular equilibrium with Cs-137");
    }

    /** At a very high rate the tube cannot recover between hits: counts saturate and hits are lost (dead time). */
    @Test
    void deadTimeLosesCounts() {
        Network n = new Network();
        Object[] c = counter(n, null, 0, 2e4, 7);
        GeigerTube tube = (GeigerTube) c[0];
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
        s.advance(0.05);
        double counted = tube.discharges() / 0.05;
        System.out.printf("dead time: %.0f counts/s for 20000 interactions/s, %d lost%n", counted, tube.lost());
        assertTrue(tube.lost() > 0 && counted < 0.9 * 2e4, "losses at a high rate");
    }

    /** A Ba-137m sample alone decays with its 2.55 min half-life — measured from the counts in successive minutes. */
    @Test
    void barium137mHalfLifeFromCounts() {
        Network n = new Network();
        var ba = ND.chain("Ba137m");
        double a0 = 2e5;
        RadioactiveSource src = n.add(new RadioactiveSource(ba, new double[]{a0 / ba.get(0).lambda()}, null));
        Object[] c = counter(n, src, 2e-4, 0.3, 11);
        GeigerTube tube = (GeigerTube) c[0];
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
        long[] perMinute = new long[6];
        long prev = 0;
        for (int m = 0; m < 6; m++) {
            for (int i = 0; i < 600; i++) s.advance(0.1);
            perMinute[m] = tube.discharges() - prev;
            prev = tube.discharges();
        }
        // least-squares slope of ln(counts) against time → half-life
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int m = 0; m < 6; m++) {
            double x = 60 * m + 30, y = Math.log(perMinute[m] - 0.3 * 60);
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double slope = (6 * sxy - sx * sy) / (6 * sxx - sx * sx), half = -Math.log(2) / slope;
        System.out.printf("Ba-137m counts per minute %s → half-life %.1f s (153.1 s)%n", java.util.Arrays.toString(perMinute), half);
        assertEquals(153.12, half, 10, "half-life from the counts");
        assertEquals(a0 * Math.exp(-ba.get(0).lambda() * 360), src.activity(s), 1e-3 * a0, "activity decays exponentially");
    }
}
