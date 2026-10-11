package dev.emergence.core.chem;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.FixedTemperature;
import dev.emergence.core.sim.elements.FlowSource;
import dev.emergence.core.sim.elements.GasReactor;
import dev.emergence.core.sim.elements.ThermalConductance;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Liquids in a gas vessel: evaporation to equilibrium, latent heat, conservation. */
class GasLiquidTest {
    static final Chemistry CHEM = Chemistry.standard();
    static final double R = PhysicalConstants.GAS_CONSTANT;

    /** A litre of air over 9 g of water, held at 20 °C: the vapour's partial pressure settles at p_sat = 2.34 kPa. */
    @Test
    void waterEvaporatesToItsVapourPressure() {
        Network net = new Network();
        Node amb = net.node(Domain.THERMAL, "ambient", 293.15), t = net.node(Domain.THERMAL, "gas", 293.15);
        Node p = net.node(Domain.FLUID, "p", 101_325);
        net.add(new FixedTemperature(amb, 293.15));
        net.add(new ThermalConductance(t, amb, 1.0));
        var species = List.of(CHEM.species("N2"), CHEM.species("O2"), CHEM.species("H2O"));
        double v = 1e-3, nAir = 101_325 * v / (R * 293.15);
        GasReactor g = net.add(new GasReactor(species, List.of(), v, t, new double[]{0.79 * nAir, 0.21 * nAir, 0})
                .withPressureNode(p).withLiquid(new double[]{0, 0, 0.5}, 0.01));
        TransientSolver s = new TransientSolver(net);
        double h0 = g.elementMoles(s, "H");
        s.advance(300); // evaporation cools it; it warms back over C/G ≈ 40 s
        double pw = s.x(g.molesIndex(2)) * R * s.x(t) / (v - g.liquidMoles(s, 2) * 18.07e-6);
        System.out.printf("water vapour %.1f Pa (p_sat %.1f Pa at %.3f K: %.1f Pa), liquid %.4f mol, total %.0f Pa%n", pw, VaporLiquid.of("H2O").psat(293.15),
                s.x(t), VaporLiquid.of("H2O").psat(s.x(t)), g.liquidMoles(s, 2), s.x(p));
        assertEquals(VaporLiquid.of("H2O").psat(293.15), pw, 0.01 * pw);
        assertEquals(h0, g.elementMoles(s, "H"), 1e-9 * h0, "hydrogen conserved across the phases");
    }

    /** Heated in an insulated vessel, the water warms and evaporates; the energy put in is all there, sensible and latent. */
    @Test
    void heatGoesIntoWarmingAndEvaporating() {
        Network net = new Network();
        Node t = net.node(Domain.THERMAL, "gas", 293.15), p = net.node(Domain.FLUID, "p", 101_325);
        var species = List.of(CHEM.species("N2"), CHEM.species("O2"), CHEM.species("H2O"));
        double v = 1e-3, nAir = 101_325 * v / (R * 293.15);
        double pw0 = VaporLiquid.of("H2O").psat(293.15), nw = pw0 * v / (R * 293.15);
        GasReactor g = net.add(new GasReactor(species, List.of(), v, t, new double[]{0.79 * nAir, 0.21 * nAir, nw})
                .withPressureNode(p).withLiquid(new double[]{0, 0, 0.5}, 0.01));
        net.add(new FlowSource(net.reference(Domain.THERMAL), t, 10.0));
        TransientSolver s = new TransientSolver(net);
        double l0 = g.liquidMoles(s, 2);
        for (int k = 0; k < 10; k++) s.advance(10);
        double evaporated = l0 - g.liquidMoles(s, 2);
        System.out.printf("after 1 kJ: %.2f K, %.4f mol evaporated, %.0f Pa; energy error %.3g J of %.3g J%n", s.x(t), evaporated, s.x(p),
                s.energyError(), s.externalEnergyIn());
        // sensible heat: liquid water c_v ≈ 75 J/(mol·K) and the vapour and air; the rest went into evaporating
        double c = 0.5 * 75.3 + 0.0416 * 20.8 + 0.001 * 25;
        double expect = 293.15 + (1000 - evaporated * VaporLiquid.of("H2O").latentHeat(305)) / c;
        assertEquals(expect, s.x(t), 1.5, "the temperature the heat left after evaporating buys");
        assertTrue(evaporated > 0.002, "some water evaporates");
        assertTrue(Math.abs(s.energyError()) < 1e-3 * s.externalEnergyIn(), "energy is conserved");
    }
}
