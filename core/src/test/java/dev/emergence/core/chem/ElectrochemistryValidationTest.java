package dev.emergence.core.chem;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.*;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Electrochemistry against textbook values (VALIDATION.md §Electrochemistry). */
class ElectrochemistryValidationTest {
    static final double T = 298.15;
    static final double F = PhysicalConstants.FARADAY;

    record Daniell(Network net, Node zn, Node cu, ElectrochemicalCell a, ElectrochemicalCell b,
                   ElectrochemicalCell.Electrode znE, ElectrochemicalCell.Electrode cuE) {}

    static Daniell daniell(double load) {
        Network n = new Network();
        Node zn = n.node(Domain.ELECTRICAL, "zn"), cu = n.node(Domain.ELECTRICAL, "cu");
        Node sa = n.node(Domain.ELECTRICAL, "solA"), sb = n.node(Domain.ELECTRICAL, "solB");
        Node ta = n.node(Domain.THERMAL, "Ta", T), tb = n.node(Domain.THERMAL, "Tb", T), amb = n.node(Domain.THERMAL, "amb", T);
        n.add(new HeatCapacity(ta, 4000));
        n.add(new HeatCapacity(tb, 4000));
        n.add(new FixedTemperature(amb, T));
        n.add(new ThermalConductance(ta, amb, 1));
        n.add(new ThermalConductance(tb, amb, 1));
        ElectrochemicalCell a = n.add(new ElectrochemicalCell(sa, ta, 1.0, Map.of("Zn2+", 1.0)));
        ElectrochemicalCell b = n.add(new ElectrochemicalCell(sb, tb, 1.0, Map.of("Cu2+", 1.0)));
        var znE = a.addElectrode(zn, null, "zinc", 0.004, 0.03, 4.5, 1.0, "zn");
        var cuE = b.addElectrode(cu, null, "copper", 0.004, 0.03, 4.5, 1.0, "cu");
        n.add(new Resistor(sa, sb, 5.0)); // salt bridge
        n.add(new Resistor(cu, zn, load));
        return new Daniell(n, zn, cu, a, b, znE, cuE);
    }

    @Test
    void daniellCellOpenCircuitVoltage() {
        Daniell d = daniell(1e9);
        TransientSolver s = new TransientSolver(d.net());
        s.advance(1.0);
        double ocv = s.x(d.cu()) - s.x(d.zn());
        // E° = 0.342 − (−0.762) = 1.104 V at unit activities
        assertEquals(1.104, ocv, 0.01, "Daniell OCV");
    }

    @Test
    void daniellCellUnderLoadFollowsFaradayAndConservesEnergy() {
        Daniell d = daniell(10.0);
        TransientSolver s = new TransientSolver(d.net());
        double t = 600;
        s.advance(t);
        double v = s.x(d.cu()) - s.x(d.zn());
        // internal resistance: salt bridge 5 Ω + two ionic paths d/(κA) = 0.03/(4.5·0.004) = 1.67 Ω each
        double rInt = 5 + 2 * 0.03 / (4.5 * 0.004);
        assertEquals(1.104 * 10 / (10 + rInt), v, 0.08, "terminal voltage = EMF divided by internal + load resistance, minus activation losses");
        double i = v / 10.0;
        // zinc consumed ≈ ∫I dt / 2F
        double znLost = d.a().initialMetal(d.znE()) - d.a().remainingMetal(d.znE(), s);
        assertEquals(i * t / (2 * F), znLost, 0.05 * i * t / (2 * F), "Faraday's law for the anode");
        double cuGain = d.b().remainingMetal(d.cuE(), s) - d.b().initialMetal(d.cuE());
        assertEquals(znLost, cuGain, 0.02 * znLost, "charge balance: Cu plated = Zn dissolved");
        assertEquals(1.0 + znLost, d.a().concentration("Zn2+", s), 1e-6, "Zn2+ produced in solution");
        // energy ledger: chemical energy in = load heat + interface/ionic heat + stored change
        assertTrue(Math.abs(s.energyError()) < 0.01 * s.externalEnergyIn(), "energy error " + s.energyError() + " of " + s.externalEnergyIn());
    }

    @Test
    void zincCorrodesInAcidWithHydrogenEvolution() {
        Network n = new Network();
        Node zn = n.node(Domain.ELECTRICAL, "zn"), sol = n.node(Domain.ELECTRICAL, "sol");
        Node th = n.node(Domain.THERMAL, "T", T);
        n.add(new HeatCapacity(th, 4000));
        ElectrochemicalCell c = n.add(new ElectrochemicalCell(sol, th, 0.5, Map.of("H+", 2.0)));
        var e = c.addElectrode(zn, null, "zinc", 0.004, 0.03, 37, 0.5, "zn");
        TransientSolver s = new TransientSolver(n);
        s.advance(3600);
        double dissolved = c.initialMetal(e) - c.remainingMetal(e, s);
        double hConsumed = 1.0 - c.moles("H+", s);
        assertTrue(dissolved > 0, "isolated zinc in acid corrodes (mixed potential)");
        assertEquals(2 * dissolved, hConsumed, 1e-3 * hConsumed + 1e-12, "Zn + 2H+ → Zn2+ + H2 stoichiometry");
        double ecorr = s.x(zn) - s.x(sol);
        double cZn = c.concentration("Zn2+", s), cH = c.concentration("H+", s);
        double rtf = PhysicalConstants.GAS_CONSTANT * T / F;
        double eZn = -0.762 + rtf / 2 * Math.log(cZn), eH = rtf * Math.log(cH);
        assertTrue(ecorr > eZn && ecorr < eH, "mixed (corrosion) potential lies between the Zn (" + eZn
                + " V) and H2 (" + eH + " V) equilibria: " + ecorr);
    }

    @Test
    void electroplatingDepositsFaradaysMass() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL);
        Node anode = n.node(Domain.ELECTRICAL, "anode"), cathode = n.node(Domain.ELECTRICAL, "cathode");
        Node sol = n.node(Domain.ELECTRICAL, "sol"), th = n.node(Domain.THERMAL, "T", T);
        n.add(new HeatCapacity(th, 4000));
        ElectrochemicalCell c = n.add(new ElectrochemicalCell(sol, th, 1.0, Map.of("Cu2+", 1.0)));
        var a = c.addElectrode(anode, null, "copper", 0.004, 0.03, 4.5, 1.0, "a");
        var k = c.addElectrode(cathode, null, "copper", 0.004, 0.03, 4.5, 1.0, "k");
        n.add(new FlowSource(gnd, anode, 0.5)); // 0.5 A constant current into the anode
        n.add(new Resistor(cathode, gnd, 0.01));
        TransientSolver s = new TransientSolver(n);
        s.advance(1000);
        double plated = c.remainingMetal(k, s) - c.initialMetal(k);
        assertEquals(0.5 * 1000 / (2 * F), plated, 1e-3 * plated, "m = I·t·M/(nF)");
        assertEquals(1.0, c.concentration("Cu2+", s), 1e-4, "copper electrolyte composition unchanged");
    }
}
