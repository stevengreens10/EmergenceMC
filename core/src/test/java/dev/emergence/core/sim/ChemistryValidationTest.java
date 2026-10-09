package dev.emergence.core.sim;

import dev.emergence.core.chem.Chemistry;
import dev.emergence.core.chem.Reaction;
import dev.emergence.core.chem.Species;
import dev.emergence.core.sim.elements.FixedTemperature;
import dev.emergence.core.sim.elements.GasReactor;
import dev.emergence.core.sim.elements.ThermalConductance;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Thermochemistry, equilibrium and reacting-gas validation. */
class ChemistryValidationTest {
    static final Chemistry CH = Chemistry.standard();
    static final double R = PhysicalConstants.GAS_CONSTANT;

    @Test
    void substanceDataAndThermodynamicConsistency() {
        assertEquals(16.043e-3, CH.species("CH4").molarMass(), 1e-6);
        assertEquals(44.009e-3, CH.species("CO2").molarMass(), 1e-6);
        Species co2 = CH.species("CO2");
        // the fit passes through the tabulated heat capacities
        assertEquals(37.13, co2.cp(298.15), 1e-9);
        assertEquals(54.31, co2.cp(1000), 1e-9);
        assertEquals(60.44, co2.cp(2000), 1e-9);
        // h and s are integrals of cp: dh/dT = cp, ds/dT = cp/T (also across the clamp)
        for (double t : new double[]{250, 600, 1500, 2900, 3500}) {
            double h = 1e-3;
            assertEquals(co2.cp(t), (co2.enthalpy(t + h) - co2.enthalpy(t - h)) / (2 * h), 1e-5);
            assertEquals(co2.cp(t) / t, (co2.entropy(t + h) - co2.entropy(t - h)) / (2 * h), 1e-8);
        }
        for (Reaction r : CH.reactions())
            for (String el : List.of("C", "H", "N", "O")) assertEquals(0, r.elementBalance(el), 1e-12, r.id() + " conserves " + el);
    }

    @Test
    void reactionEnthalpyAndEquilibriumConstants() {
        Reaction comb = CH.reaction("methane_combustion"), diss = CH.reaction("n2o4_dissociation");
        // lower heating value of methane: 802.3 kJ/mol (to water vapour)
        assertEquals(-802.3e3, comb.deltaH(298.15), 0.2e3);
        // N2O4 ⇌ 2 NO2: K_p(298 K) ≈ 0.148 (bar), ΔH ≈ +57 kJ/mol
        assertEquals(0.148, diss.kp(298.15), 0.004);
        assertEquals(57.1e3, diss.deltaH(298.15), 0.2e3);
        // van 't Hoff: d ln K_p / dT = ΔH/(RT²)
        for (double t : new double[]{300, 350, 400}) {
            double h = 1e-3;
            double dlnK = (Math.log(diss.kp(t + h)) - Math.log(diss.kp(t - h))) / (2 * h);
            assertEquals(diss.deltaH(t) / (R * t * t), dlnK, 1e-7);
        }
    }

    /** N2O4 at constant T and V relaxes to the analytical equilibrium extent 4ξ²/(1 − ξ) = K_p (for V = RT/P0 per mol). */
    @Test
    void n2o4SettlesAtEquilibrium() {
        for (double t : new double[]{298.15, 330}) {
            Network n = new Network();
            Node th = n.node(Domain.THERMAL, "gas", t);
            Species a = CH.species("N2O4"), b = CH.species("NO2");
            double v = R * t / Species.P0;
            GasReactor g = n.add(new GasReactor(List.of(a, b), List.of(CH.reaction("n2o4_dissociation")), v, th, new double[]{1, 0}));
            n.add(new FixedTemperature(th, t));
            TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
            s.advance(0.01);
            double kp = CH.reaction("n2o4_dissociation").kp(t);
            double xi = (-kp + Math.sqrt(kp * kp + 16 * kp)) / 8; // 4ξ² + Kξ − K = 0
            assertEquals(1 - xi, g.moles(s, a), 1e-5, "N2O4 at " + t + " K");
            assertEquals(2 * xi, g.moles(s, b), 1e-5, "NO2 at " + t + " K");
            assertEquals(2, g.elementMoles(s, "N"), 1e-9, "nitrogen conserved");
            assertEquals((1 + xi) * Species.P0, g.pressure(s), 1e-3 * Species.P0);
        }
    }

    /**
     * Stoichiometric methane–air in a closed adiabatic vessel, hot enough to self-ignite: it burns to completion,
     * the elements are conserved, and the final temperature is the one at which the products hold the reactants'
     * internal energy (constant-volume adiabatic combustion).
     */
    @Test
    void adiabaticConstantVolumeCombustion() {
        Species ch4 = CH.species("CH4"), o2 = CH.species("O2"), co2 = CH.species("CO2"), h2o = CH.species("H2O"), n2 = CH.species("N2");
        List<Species> list = List.of(ch4, o2, co2, h2o, n2);
        double[] n0 = {0.1, 0.2, 0, 0, 0.752};
        double t0 = 1100;
        Network n = new Network();
        Node th = n.node(Domain.THERMAL, "gas", t0);
        double v = 1.052 * R * t0 / 101325;
        GasReactor g = n.add(new GasReactor(list, CH.reactionsAmong(list), v, th, n0));
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
        double e0 = s.storedEnergy();
        s.advance(2.0);
        assertTrue(g.moles(s, ch4) < 1e-6, "burnt: " + g.moles(s, ch4));
        assertEquals(0.1, g.moles(s, co2), 1e-6);
        assertEquals(0.2, g.moles(s, h2o), 1e-6);
        for (String el : List.of("C", "H", "O", "N")) {
            double before = 0;
            for (int i = 0; i < list.size(); i++) before += n0[i] * list.get(i).atoms(el);
            assertEquals(before, g.elementMoles(s, el), 1e-9 * Math.max(1, before), el + " conserved");
        }
        assertEquals(e0, s.storedEnergy(), 1e-6 * Math.abs(e0), "internal energy conserved");
        // the temperature at which the products' U equals the reactants' U
        double u0 = 0;
        for (int i = 0; i < list.size(); i++) u0 += n0[i] * list.get(i).internalEnergy(t0);
        double lo = t0, hi = 6000;
        for (int k = 0; k < 100; k++) {
            double mid = 0.5 * (lo + hi);
            double u = 0.1 * co2.internalEnergy(mid) + 0.2 * h2o.internalEnergy(mid) + 0.752 * n2.internalEnergy(mid);
            if (u < u0) lo = mid;
            else hi = mid;
        }
        System.out.printf("constant-volume adiabatic combustion from %.0f K: %.0f K (expected %.0f K), %.2f bar%n", t0,
                g.temperature(s), lo, g.pressure(s) / 1e5);
        assertEquals(lo, g.temperature(s), 1.0);
    }

    /**
     * Semenov thermal explosion: methane–air held in a vessel whose wall is kept at T_w, with heat loss h·A·(T − T_w).
     * Just below a critical wall temperature the gas reacts slowly and stays near T_w; a little above it, the heat
     * release outruns the loss and the gas runs away (ignites).
     */
    @Test
    void semenovThermalExplosion() {
        double[] peak = new double[2];
        double[] walls = {800, 950};
        for (int k = 0; k < 2; k++) {
            Species ch4 = CH.species("CH4"), o2 = CH.species("O2"), co2 = CH.species("CO2"), h2o = CH.species("H2O"), n2 = CH.species("N2");
            List<Species> list = List.of(ch4, o2, co2, h2o, n2);
            double tw = walls[k], v = 1e-3; // a litre
            double nt = 101325 * v / (R * tw);
            double[] n0 = {0.095 * nt, 0.19 * nt, 0, 0, 0.715 * nt};
            Network n = new Network();
            Node th = n.node(Domain.THERMAL, "gas", tw), wall = n.node(Domain.THERMAL, "wall", tw);
            GasReactor g = n.add(new GasReactor(list, CH.reactionsAmong(list), v, th, n0));
            n.add(new FixedTemperature(wall, tw));
            n.add(new ThermalConductance(th, wall, 10 * 0.06)); // h ≈ 10 W/m²K over 0.06 m²
            TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
            for (int i = 0; i < 200; i++) {
                s.advance(0.05);
                peak[k] = Math.max(peak[k], g.temperature(s));
            }
        }
        System.out.printf("Semenov: wall 800 K → gas peaks at %.0f K; wall 950 K → %.0f K%n", peak[0], peak[1]);
        assertTrue(peak[0] < 830, "below the critical temperature it smoulders: " + peak[0]);
        assertTrue(peak[1] > 2000, "above it, it explodes: " + peak[1]);
    }
}
