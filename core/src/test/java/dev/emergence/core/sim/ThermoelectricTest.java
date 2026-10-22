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

    /**
     * A contact between a copper and a constantan part as one {@link ContactConductor} matches the two half-conductors
     * meeting at a contact node it replaces: the same current, the same temperatures (Joule heat in each half, Seebeck
     * EMF, Peltier heat at the contact), energy closing — driven hard enough (a 0.2 V source, unequal heat capacities,
     * one part 60 K hotter) that every term shows.
     */
    @Test
    void aContactConductorIsTheTwoHalvesItReplaces() {
        double sc = CON.get(MaterialProperty.SEEBECK_COEFFICIENT, 0), su = CU.get(MaterialProperty.SEEBECK_COEFFICIENT, 0);
        double lp = 0.3, gp = 0.004 / 2e-8, gq = 0.006 / 3e-8; // half lengths / areas
        double[][] got = new double[2][];
        for (int k = 0; k < 2; k++) {
            Network n = new Network();
            Node gnd = n.reference(Domain.ELECTRICAL);
            Node e1 = n.node(Domain.ELECTRICAL, "1"), e2 = n.node(Domain.ELECTRICAL, "2");
            Node tp = n.node(Domain.THERMAL, "p", 360), tq = n.node(Domain.THERMAL, "q", 300);
            n.add(new HeatCapacity(tp, 0.05));
            n.add(new HeatCapacity(tq, 0.2));
            n.add(new ThermalConductance(tp, tq, 0.01));
            n.add(new VoltageSource(e1, gnd, 0.2));
            n.add(new Resistor(e2, gnd, 0.05));
            if (k == 0) {
                n.add(new ContactConductor(e1, e2, CU, gp, tp, su, CON, gq, tq, sc, lp, 300));
            } else {
                Node mid = n.node(Domain.ELECTRICAL, "mid");
                Node[] contact = {tp, tq};
                double[] w = {lp, 1 - lp};
                n.add(new Conductor(e1, mid, CU, 0.004, 2e-8, tp, 300).withSeebeck(su, new Node[]{tp}, new double[]{1}, contact, w));
                n.add(new Conductor(mid, e2, CON, 0.006, 3e-8, tq, 300).withSeebeck(sc, contact, w, new Node[]{tq}, new double[]{1}));
            }
            TransientSolver s = new TransientSolver(n);
            s.advance(2);
            got[k] = new double[]{s.x(e2) / 0.05, s.x(tp), s.x(tq)};
            assertEquals(0, s.energyError(), 1e-9 + 1e-6 * Math.abs(s.externalEnergyIn()), "energy closes");
        }
        System.out.printf("contact conductor: I %.6f A, T_p %.4f K, T_q %.4f K; two halves: %.6f A, %.4f K, %.4f K%n",
                got[0][0], got[0][1], got[0][2], got[1][0], got[1][1], got[1][2]);
        double joule = got[0][0] * got[0][0] * CON.resistivity(300) * gq * 2 / 0.2;
        assertTrue(joule > 1, "the Joule heat shows: " + joule + " K in q");
        assertEquals(got[1][0], got[0][0], 1e-5 * Math.abs(got[1][0]), "current");
        assertEquals(got[1][1], got[0][1], 1e-3, "p's temperature");
        assertEquals(got[1][2], got[0][2], 1e-3, "q's temperature");
    }

    /** The contact conductor's analytic Jacobian matches finite differences of its residuals, temperatures free. */
    @Test
    void contactConductorJacobianIsExact() {
        double sc = CON.get(MaterialProperty.SEEBECK_COEFFICIENT, 0), su = CU.get(MaterialProperty.SEEBECK_COEFFICIENT, 0);
        Network n = new Network();
        Node e1 = n.node(Domain.ELECTRICAL, "1"), e2 = n.node(Domain.ELECTRICAL, "2");
        Node tp = n.node(Domain.THERMAL, "p", 360), tq = n.node(Domain.THERMAL, "q", 300);
        ContactConductor cc = n.add(new ContactConductor(e1, e2, CU, 2e5, tp, su, CON, 2e5, tq, sc, 0.3, 300));
        n.compile();
        int size = n.size();
        double[] x = new double[size];
        x[e1.index()] = 0.31;
        x[e2.index()] = 0.02;
        x[tp.index()] = 360;
        x[tq.index()] = 305;
        java.util.function.Function<double[], double[]> residual = xs -> {
            LoadContext ctx = new LoadContext();
            ctx.x = xs;
            ctx.f = new double[size];
            ctx.jac = new dev.emergence.core.math.DenseLinearSystem(size);
            cc.load(ctx);
            return ctx.f;
        };
        LoadContext ctx = new LoadContext();
        ctx.x = x;
        ctx.f = new double[size];
        var jac = new dev.emergence.core.math.DenseLinearSystem(size);
        ctx.jac = jac;
        cc.load(ctx);
        for (int col = 0; col < size; col++) {
            double h = 1e-6 * Math.max(1, Math.abs(x[col]));
            double[] xp = x.clone(), xm = x.clone();
            xp[col] += h;
            xm[col] -= h;
            double[] fp = residual.apply(xp), fm = residual.apply(xm);
            for (int row = 0; row < size; row++) {
                double fd = (fp[row] - fm[row]) / (2 * h), an = jac.get(row, col);
                assertEquals(fd, an, 1e-6 * Math.max(1, Math.abs(fd)), "∂F" + row + "/∂x" + col);
            }
        }
    }
}
