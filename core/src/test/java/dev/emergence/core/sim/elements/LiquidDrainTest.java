package dev.emergence.core.sim.elements;

import dev.emergence.core.chem.Chemistry;
import dev.emergence.core.chem.VaporLiquid;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Liquid between gas vessels: communicating vessels level out; an upper vessel drains into a lower one. */
class LiquidDrainTest {
    static final Chemistry CHEM = Chemistry.standard();
    static final double R = PhysicalConstants.GAS_CONSTANT, T = 293.15, V = 1e-3, SECTION = 0.01;
    static final List<dev.emergence.core.chem.Species> SP = List.of(CHEM.species("N2"), CHEM.species("O2"), CHEM.species("H2O"),
            CHEM.species("C2H5OH"));

    /** A litre vessel of air (at the liquid's vapour pressure) holding the given liquid moles of water and ethanol. */
    static GasReactor vessel(Network net, Node amb, String name, double water, double ethanol) {
        Node t = net.node(Domain.THERMAL, name + ".T", T), p = net.node(Domain.FLUID, name + ".p", 101_325);
        net.add(new ThermalConductance(t, amb, 1.0));
        double nAir = 101_325 * V / (R * T);
        double pw = water > 0 ? VaporLiquid.of("H2O").psat(T) : 0, pe = ethanol > 0 ? VaporLiquid.of("C2H5OH").psat(T) : 0;
        return net.add(new GasReactor(SP, List.of(), V, t, new double[]{0.79 * nAir, 0.21 * nAir, pw * V / (R * T), pe * V / (R * T)})
                .withPressureNode(p).withLiquid(new double[]{0, 0, water, ethanol}, SECTION));
    }

    static double volume(GasReactor g, TransientSolver s) {
        return g.liquidMoles(s, 2) * VaporLiquid.of("H2O").liquidVolume() + g.liquidMoles(s, 3) * VaporLiquid.of("C2H5OH").liquidVolume();
    }

    @Test
    void communicatingVesselsLevelOut() {
        Network net = new Network();
        Node amb = net.node(Domain.THERMAL, "amb", T);
        net.add(new FixedTemperature(amb, T));
        GasReactor a = vessel(net, amb, "a", 20, 0), b = vessel(net, amb, "b", 0, 0);
        // joined at the floor by a 1 cm pipe; their gas spaces by another, so the gas pressures stay equal
        LiquidDrain drain = net.add(new LiquidDrain(a, 0, SECTION, 0, b, 0, SECTION, 0, 7.85e-5));
        net.add(new MixtureBranch(SP, MixtureBranch.End.of(a), MixtureBranch.End.of(b), 7.85e-5, 0.01, 0.1, 1.5));
        TransientSolver s = new TransientSolver(net);
        double h0 = a.elementMoles(s, "H") + b.elementMoles(s, "H");
        for (int i = 0; i < 300; i++) s.advance(0.1);
        double la = volume(a, s) / SECTION, lb = volume(b, s) / SECTION;
        System.out.printf("communicating vessels: levels %.2f / %.2f mm (from 36 / 0), flow now %.3g m³/s, H %.6f → %.6f mol, energy error %.3g J%n",
                1e3 * la, 1e3 * lb, drain.volumeFlow(s), h0, a.elementMoles(s, "H") + b.elementMoles(s, "H"), s.energyError());
        assertEquals(la, lb, 0.002 * (la + lb), "the levels meet");
        assertEquals(h0, a.elementMoles(s, "H") + b.elementMoles(s, "H"), 1e-9 * h0, "hydrogen conserved");
        assertTrue(Math.abs(s.energyError()) < 1e-3 * 20 * 75 * T, "energy conserved");
    }

    @Test
    void upperVesselDrainsIntoTheLowerWithItsComposition() {
        Network net = new Network();
        Node amb = net.node(Domain.THERMAL, "amb", T);
        net.add(new FixedTemperature(amb, T));
        // a: floor at 0.3 m, its drain at its floor; b: floor at 0, the drain entering through its top (0.1 m)
        GasReactor a = vessel(net, amb, "a", 10, 2), b = vessel(net, amb, "b", 0, 0);
        net.add(new LiquidDrain(a, 0.3, SECTION, 0.3, b, 0, SECTION, 0.1, 7.85e-5));
        net.add(new MixtureBranch(SP, MixtureBranch.End.of(a), MixtureBranch.End.of(b), 7.85e-5, 0.01, 0.3, 1.5));
        TransientSolver s = new TransientSolver(net);
        for (int i = 0; i < 300; i++) s.advance(0.1);
        double wA = a.liquidMoles(s, 2), eA = a.liquidMoles(s, 3), wB = b.liquidMoles(s, 2), eB = b.liquidMoles(s, 3);
        System.out.printf("drain: upper keeps %.4f mol water, %.4f ethanol; lower holds %.3f water, %.3f ethanol (x_EtOH %.3f, poured 0.167)%n",
                wA, eA, wB, eB, eB / (wB + eB));
        assertTrue(wA + eA < 0.01 * 12, "the upper vessel empties");
        assertEquals(1.0 / 6, eB / (wB + eB), 0.01, "the liquid arrives as it left (a little evaporation aside)");
    }
}
