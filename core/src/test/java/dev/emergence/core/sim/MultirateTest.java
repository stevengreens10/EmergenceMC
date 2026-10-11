package dev.emergence.core.sim;

import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.core.sim.elements.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Multirate stepping (the thermal network apart) against single-rate stepping of the same systems. */
class MultirateTest {
    /** A tungsten filament on 6 V heating from cold: R(T) couples its current to its temperature strongly. */
    static double[] filament(boolean multirate) {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL), amb = n.node(Domain.THERMAL, "ambient", 293.15);
        Node a = n.node(Domain.ELECTRICAL, "a"), th = n.node(Domain.THERMAL, "filament", 293.15);
        n.add(new FixedTemperature(amb, 293.15));
        n.add(new VoltageSource(a, gnd, 6.0));
        n.add(new Conductor(a, gnd, MaterialDatabase.standard().get("tungsten"), 0.03, Math.PI * 4e-5 * 4e-5, th, 293.15));
        n.add(new HeatCapacity(th, 2e-4));
        n.add(new ThermalConductance(th, amb, 2e-3));
        n.add(new Radiation(th, amb, 0.35, 2 * Math.PI * 4e-5 * 0.03));
        TransientSolver s = new TransientSolver(n).multirate(multirate);
        for (int k = 0; k < 40; k++) s.advance(0.05);
        return new double[]{s.x(th), s.energyError(), s.externalEnergyIn(), s.acceptedSteps()};
    }

    @Test
    void filamentHeatsTheSameWay() {
        double[] one = filament(false), two = filament(true);
        System.out.printf("filament: single rate %.2f K (%d steps, energy error %.3g J), multirate %.2f K (%d steps); energy error %.3g J of %.3g J%n",
                one[0], (long) one[3], one[1], two[0], (long) two[3], two[1], two[2]);
        assertEquals(one[0], two[0], 0.005 * one[0], "the same temperature");
        assertTrue(Math.abs(two[1]) < 1e-3 * two[2], "energy balances");
    }

    /**
     * An LC tank ringing at 5 kHz beside a resistor heated by a DC current: under multirate the thermal network is
     * stepped far less often than the tank, and the resistor ends at the same temperature.
     */
    static double[] tank(boolean multirate) {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL), amb = n.node(Domain.THERMAL, "ambient", 293.15);
        Node a = n.node(Domain.ELECTRICAL, "a"), b = n.node(Domain.ELECTRICAL, "b");
        Node th = n.node(Domain.THERMAL, "resistor", 293.15);
        n.add(new FixedTemperature(amb, 293.15));
        Capacitor cap = n.add(new Capacitor(a, gnd, 1e-6));
        n.add(new Inductor(a, gnd, 1e-3));
        n.add(new VoltageSource(b, gnd, 10.0));
        n.add(new Resistor(b, gnd, 100, th));
        n.add(new HeatCapacity(th, 0.5));
        n.add(new ThermalConductance(th, amb, 0.01));
        n.compile();
        TransientSolver s = new TransientSolver(n).multirate(multirate);
        s.setState(a.index(), 5.0);
        s.resyncHistory();
        for (int k = 0; k < 10; k++) s.advance(0.01);
        return new double[]{s.x(th), s.energyError(), s.externalEnergyIn(), s.acceptedSteps(), s.slowSteps()};
    }

    @Test
    void ringingCircuitBesideASlowHeater() {
        double[] one = tank(false), two = tank(true);
        System.out.printf("tank + heater: single rate %.4f K, multirate %.4f K (%d fast steps, %d thermal steps); energy error %.3g J of %.3g J%n",
                one[0], two[0], (long) two[3], (long) two[4], two[1], two[2]);
        assertEquals(one[0], two[0], 1e-3, "the same temperature");
        assertTrue(two[4] < two[3] / 10, "the thermal network takes far fewer steps");
        assertTrue(Math.abs(two[1]) < 1e-3 * two[2], "energy balances");
    }
}
