package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The lead–acid battery: its voltage from its acid, its charge counted by Faraday, its losses as heat. */
class LeadAcidBatteryTest {
    @Test
    void fullSixCellBatteryReadsTwelvePointSevenFiveVolts() {
        Network net = new Network();
        Node p = net.node(Domain.ELECTRICAL, "+", 0), gnd = net.reference(Domain.ELECTRICAL);
        LeadAcidBattery b = net.add(new LeadAcidBattery(p, gnd, 6, 7, 1.0, null));
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
        LeadAcidBattery b = net.add(new LeadAcidBattery(p, gnd, 6, 0.5, 1.0, th));
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
        LeadAcidBattery b = net.add(new LeadAcidBattery(p, gnd, 6, 0.5, 0.5, null));
        net.add(new VoltageSource(q, gnd, 14.4));
        net.add(new Resistor(q, p, 2));
        TransientSolver s = new TransientSolver(net);
        for (int k = 0; k < 100; k++) s.advance(1);
        System.out.printf("battery: charging at 14.4 V through 2 Ω took it from 0.50 to %.3f in 100 s, at %.2f A%n", b.stateOfCharge(s), -b.current(s));
        assertTrue(b.stateOfCharge(s) > 0.52, "charge goes back in: " + b.stateOfCharge(s));
    }
}
