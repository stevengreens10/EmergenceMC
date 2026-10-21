package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Batteries: their voltage from their chemistry, their charge counted by Faraday, their losses as heat. */
class BatteryTest {
    @Test
    void fullSixCellBatteryReadsTwelvePointSevenFiveVolts() {
        Network net = new Network();
        Node p = net.node(Domain.ELECTRICAL, "+", 0), gnd = net.reference(Domain.ELECTRICAL);
        Battery b = net.add(new Battery(Battery.Chemistry.LEAD_ACID, p, gnd, 6, 7, 1.0, null));
        net.add(new Resistor(p, gnd, 1e6)); // a voltmeter
        TransientSolver s = new TransientSolver(net);
        s.advance(0.01);
        System.out.printf("battery: 6 cells full read %.3f V open circuit (Vinal: 6 × (1.28 + 0.845) = 12.75 V)%n", b.voltage(s));
        assertEquals(12.75, b.voltage(s), 0.02);
    }

    /** A small battery (0.5 Ah) run down through 12 Ω: Faraday's charge count, the sag, the ledger. */
    @Test
    void dischargeCountsTheChargeAndHeatsTheBattery() {
        Network net = new Network();
        Node p = net.node(Domain.ELECTRICAL, "+", 0), gnd = net.reference(Domain.ELECTRICAL);
        Node th = net.node(Domain.THERMAL, "T", 293.15), amb = net.reference(Domain.THERMAL);
        net.add(new HeatCapacity(th, 500));
        Battery b = net.add(new Battery(Battery.Chemistry.LEAD_ACID, p, gnd, 6, 0.5, 1.0, th));
        net.add(new Resistor(p, gnd, 12));
        TransientSolver s = new TransientSolver(net);
        double drawn = 0, v0 = Double.NaN;
        for (int k = 0; k < 300; k++) {
            double i0 = b.current(s);
            s.advance(1);
            drawn += 0.5 * (i0 + b.current(s)) * 1;
            if (k == 0) v0 = b.voltage(s);
        }
        double soc = b.stateOfCharge(s), expect = 1 - drawn / 1800;
        System.out.printf("battery: 300 s into 12 Ω drew %.2f C; state of charge %.3f (Faraday: %.3f); voltage %.2f → %.2f V; %.3f K warmer; energy error %.2g J%n",
                drawn, soc, expect, v0, b.voltage(s), s.x(th.index()) - 293.15, s.energyError());
        assertEquals(expect, soc, 1e-3, "the charge drawn is the charge counted");
        assertTrue(b.voltage(s) < v0 - 0.1, "the voltage sags as the acid thins: " + v0 + " → " + b.voltage(s));
        assertTrue(s.x(th.index()) > 293.15, "its internal resistance warms it");
        assertTrue(Math.abs(s.energyError()) < 1e-3 * drawn * 12, "energy ledger closes: " + s.energyError());
    }

    /** Charged from a supply above its voltage, the charge goes back in. */
    @Test
    void chargingRaisesTheStateOfCharge() {
        Network net = new Network();
        Node p = net.node(Domain.ELECTRICAL, "+", 0), q = net.node(Domain.ELECTRICAL, "s", 0), gnd = net.reference(Domain.ELECTRICAL);
        Battery b = net.add(new Battery(Battery.Chemistry.LEAD_ACID, p, gnd, 6, 0.5, 0.5, null));
        net.add(new VoltageSource(q, gnd, 14.4));
        net.add(new Resistor(q, p, 2));
        TransientSolver s = new TransientSolver(net);
        for (int k = 0; k < 100; k++) s.advance(1);
        System.out.printf("battery: charging at 14.4 V through 2 Ω took it from 0.50 to %.3f in 100 s, at %.2f A%n", b.stateOfCharge(s), -b.current(s));
        assertTrue(b.stateOfCharge(s) > 0.52, "charge goes back in: " + b.stateOfCharge(s));
    }

    /**
     * Nickel–metal hydride: four full cells read 5.6 V open circuit (the Nernst term at 2 % from full), sagging through
     * 5.2 V at half charge to the knee; the ledger closes.
     */
    @Test
    void nickelMetalHydrideFollowsTheNernstCurveOfItsNickelElectrode() {
        assertEquals(1.30, Battery.emf(Battery.Chemistry.NICKEL_METAL_HYDRIDE, 0.5)[0], 1e-3, "E½ at half charge");
        assertEquals(1.401, Battery.emf(Battery.Chemistry.NICKEL_METAL_HYDRIDE, 1.0)[0], 2e-3, "full");
        assertEquals(1.266, Battery.emf(Battery.Chemistry.NICKEL_METAL_HYDRIDE, 0.2)[0], 2e-3, "a fifth left");
        // the slope it reports is the curve's
        for (double s : new double[]{-0.02, 0.01, 0.2, 0.5, 0.9, 1.0, 1.05}) {
            double h = 1e-6, num = (Battery.emf(Battery.Chemistry.NICKEL_METAL_HYDRIDE, s + h)[0] - Battery.emf(Battery.Chemistry.NICKEL_METAL_HYDRIDE, s - h)[0]) / (2 * h);
            assertEquals(num, Battery.emf(Battery.Chemistry.NICKEL_METAL_HYDRIDE, s)[1], 1e-3 * Math.max(1, Math.abs(num)), "slope at " + s);
        }
        Network net = new Network();
        Node p = net.node(Domain.ELECTRICAL, "+", 0), gnd = net.reference(Domain.ELECTRICAL);
        Battery b = net.add(new Battery(Battery.Chemistry.NICKEL_METAL_HYDRIDE, p, gnd, 4, 1.0, 1.0, null));
        net.add(new Resistor(p, gnd, 20));
        TransientSolver s = new TransientSolver(net);
        s.advance(0.01);
        double v0 = b.voltage(s), drawn = 0;
        for (int k = 0; k < 1800; k++) {
            double i0 = b.current(s);
            s.advance(1);
            drawn += 0.5 * (i0 + b.current(s));
        }
        System.out.printf("NiMH: 4 cells, 1 Ah into 20 Ω: %.3f V at first, %.3f V after 30 min (state of charge %.3f, Faraday %.3f), energy error %.2g J%n",
                v0, b.voltage(s), b.stateOfCharge(s), 1 - drawn / 3600, s.energyError());
        assertEquals(5.55, v0, 0.05, "four full cells, less a little for their resistance");
        assertEquals(1 - drawn / 3600, b.stateOfCharge(s), 1e-3, "Faraday");
        assertTrue(b.voltage(s) < v0 - 0.1 && b.voltage(s) > 4.8, "it sags along the Nernst curve: " + b.voltage(s));
        assertTrue(Math.abs(s.energyError()) < 1e-3 * drawn * 5, "energy ledger closes: " + s.energyError());
    }
}
