package dev.emergence.core.sim;

import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Seebeck EMF and Peltier heat in conductors. */
class ThermoelectricTest {
    static final Material CU = MaterialDatabase.standard().get("copper"), CON = MaterialDatabase.standard().get("constantan");

    /** A copper–constantan junction 100 K hotter than the open ends gives the type-T EMF (S_Cu − S_con)·ΔT ≈ 4.07 mV. */
    @Test
    void thermocoupleEmf() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node ja = n.node(Domain.ELECTRICAL, "A"), jj = n.node(Domain.ELECTRICAL, "J");
        Node cold = n.node(Domain.THERMAL, "cold", 300), hot = n.node(Domain.THERMAL, "hot", 400);
        n.add(new FixedTemperature(cold, 300));
        n.add(new FixedTemperature(hot, 400));
        // copper from A (cold) to the junction (hot); constantan from the junction back to ground (cold)
        n.add(new Conductor(ja, jj, CU, 0.1, 1e-6, cold, 300).withSeebeck(CU.get(MaterialProperty.SEEBECK_COEFFICIENT, 0),
                new Node[]{cold}, new double[]{1}, new Node[]{hot}, new double[]{1}));
        n.add(new Conductor(jj, gnd, CON, 0.1, 1e-6, hot, 400).withSeebeck(CON.get(MaterialProperty.SEEBECK_COEFFICIENT, 0),
                new Node[]{hot}, new double[]{1}, new Node[]{cold}, new double[]{1}));
        n.add(new Resistor(ja, gnd, 1e7)); // a voltmeter
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-3);
        double expected = (1.83e-6 + 38.9e-6) * 100;
        System.out.printf("type-T thermocouple at ΔT = 100 K: %.4f mV (tables: 4.28 mV over 0–100 °C)%n", 1e3 * s.x(ja));
        assertEquals(expected, s.x(ja), 1e-3 * expected, "Seebeck EMF");
    }

    /**
     * A current I through a copper–constantan junction delivers Peltier heat Π·I = (S_Cu − S_con)·T·I to the junction
     * (the Joule heat goes to the wires' own sinks); reversed, the same current cools it.
     */
    @Test
    void peltierHeatAtAJunction() {
        double t0 = 300, c = 1.0;
        double sc = CON.get(MaterialProperty.SEEBECK_COEFFICIENT, 0), su = CU.get(MaterialProperty.SEEBECK_COEFFICIENT, 0);
        double[] end = new double[2];
        for (int k = 0; k < 2; k++) {
            double current = k == 0 ? 2 : -2;
            Network n = new Network();
            Node gnd = n.reference(Domain.ELECTRICAL);
            Node e1 = n.node(Domain.ELECTRICAL, "1"), ej = n.node(Domain.ELECTRICAL, "j");
            Node sink = n.node(Domain.THERMAL, "sink", t0), jn = n.node(Domain.THERMAL, "junction", t0);
            n.add(new FixedTemperature(sink, t0));
            n.add(new HeatCapacity(jn, c));
            n.add(new FlowSource(gnd, e1, current));
            n.add(new Conductor(e1, ej, CU, 0.01, 1e-5, sink, t0).withSeebeck(su, new Node[]{sink}, new double[]{1}, new Node[]{jn}, new double[]{1}));
            n.add(new Conductor(ej, gnd, CON, 0.01, 1e-5, sink, t0).withSeebeck(sc, new Node[]{jn}, new double[]{1}, new Node[]{sink}, new double[]{1}));
            TransientSolver s = new TransientSolver(n);
            s.advance(0.5);
            end[k] = s.x(jn);
            assertEquals(0, s.energyError(), 1e-9 + 1e-6 * Math.abs(s.externalEnergyIn()), "energy closes");
        }
        double rate = (su - sc) * t0 * 2 / c;
        System.out.printf("Peltier: +2 A warms the junction by %.5f K, −2 A cools it by %.5f K in 0.5 s (Π·I/C·t = %.5f K)%n",
                end[0] - t0, t0 - end[1], rate * 0.5);
        assertEquals(t0 + rate * 0.5, end[0], 1e-3 * rate * 0.5, "heated by Π·I");
        assertEquals(t0 - rate * 0.5, end[1], 1e-3 * rate * 0.5, "cooled by Π·I");
    }
}
