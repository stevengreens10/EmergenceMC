package dev.emergence.core.sim;

import dev.emergence.core.chem.Chemistry;
import dev.emergence.core.chem.Species;
import dev.emergence.core.sim.elements.GasReactor;
import dev.emergence.core.sim.elements.MixtureBranch;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Flows of gas mixtures between volumes and reservoirs. */
class MixtureFlowTest {
    static final Chemistry CH = Chemistry.standard();
    static final double R = PhysicalConstants.GAS_CONSTANT;
    static final List<Species> AIR = List.of(CH.species("N2"), CH.species("O2"), CH.species("Ar"));
    static final double[] X_AIR = {0.7809, 0.2095, 0.0096};

    static GasReactor volume(Network n, String name, double v, double p, double t) {
        Node th = n.node(Domain.THERMAL, name + "T", t), pn = n.node(Domain.FLUID, name + "p", p);
        double nt = p * v / (R * t);
        double[] moles = new double[3];
        for (int i = 0; i < 3; i++) moles[i] = nt * X_AIR[i];
        return n.add(new GasReactor(AIR, List.of(), v, th, moles).withPressureNode(pn));
    }

    /**
     * Filling an evacuated rigid tank from a reservoir: the incoming gas's flow work is converted into internal
     * energy, so the tank ends hotter than the supply — T_f solves n_f·u(T_f) = n_in·h(T₀) + n₀·u(T₀) (≈ γ·T₀ for
     * a nearly empty tank).
     */
    @Test
    void adiabaticFillingHeatsTheGas() {
        double v = 0.01, t0 = 300, p0 = 2e5;
        Network n = new Network();
        GasReactor tank = volume(n, "tank", v, 500, t0);
        MixtureBranch pipe = n.add(new MixtureBranch(AIR, MixtureBranch.End.reservoir(p0, t0, X_AIR), MixtureBranch.End.of(tank),
                1e-5, 3.6e-3, 0.05, 1.5).withTag("fill"));
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
        double e0 = s.storedEnergy();
        double nInit = 500 * v / (R * t0);
        s.advance(30);
        double pf = tank.pressure(s), tf = tank.temperature(s);
        assertEquals(p0, pf, 1e-3 * p0, "filled to the supply pressure");
        double nf = 0;
        for (int i = 0; i < 3; i++) nf += tank.moles(s, i);
        assertEquals(pf * v / (R * tf), nf, 1e-6 * nf, "ideal gas");
        // energy balance: the tank's U rose by the enthalpy brought in
        double hIn = 0, u0 = 0;
        for (int i = 0; i < 3; i++) {
            hIn += X_AIR[i] * AIR.get(i).enthalpy(t0);
            u0 += X_AIR[i] * AIR.get(i).internalEnergy(t0);
        }
        double target = (nf - nInit) * hIn + nInit * u0;
        double lo = t0, hi = 1000;
        for (int k = 0; k < 80; k++) {
            double mid = 0.5 * (lo + hi), u = 0;
            for (int i = 0; i < 3; i++) u += X_AIR[i] * AIR.get(i).internalEnergy(mid);
            // n_f at that temperature (the pressure is the supply's)
            double nAt = p0 * v / (R * mid);
            if (nAt * u < target - (nAt - nf) * hIn) lo = mid;
            else hi = mid;
        }
        System.out.printf("adiabatic filling: %.1f K (energy balance %.1f K, γT0 = %.1f K)%n", tf, lo, 1.4 * t0);
        assertEquals(lo, tf, 1.5, "filling temperature from the energy balance");
        assertTrue(tf > 400 && tf < 425, "≈ γ·T0");
        assertEquals(0, s.energyError(), 1e-6 * Math.abs(e0) + 1e-3, "energy ledger closes (enthalpy in = ΔU)");
        assertTrue(Math.abs(pipe.molarFlow(s)) < 1e-6, "flow has stopped");
    }

    /** Two volumes at different pressures equalise; moles, oxygen and energy are conserved. */
    @Test
    void volumesEqualise() {
        Network n = new Network();
        GasReactor a = volume(n, "a", 0.002, 3e5, 350), b = volume(n, "b", 0.006, 1e5, 290);
        n.add(new MixtureBranch(AIR, MixtureBranch.End.of(a), MixtureBranch.End.of(b), 2e-5, 5e-3, 0.1, 2).withTag("ab"));
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
        double e0 = s.storedEnergy(), n0 = 0, o0 = a.elementMoles(s, "O") + b.elementMoles(s, "O");
        for (int i = 0; i < 3; i++) n0 += a.moles(s, i) + b.moles(s, i);
        s.advance(20);
        assertEquals(a.pressure(s), b.pressure(s), 10, "pressures equal");
        double n1 = 0;
        for (int i = 0; i < 3; i++) n1 += a.moles(s, i) + b.moles(s, i);
        assertEquals(n0, n1, 1e-9 * n0, "moles conserved");
        assertEquals(o0, a.elementMoles(s, "O") + b.elementMoles(s, "O"), 1e-9 * o0, "oxygen conserved");
        assertEquals(e0, s.storedEnergy(), 1e-7 * Math.abs(e0), "energy conserved");
        System.out.printf("equalised at %.0f Pa: a %.1f K, b %.1f K%n", a.pressure(s), a.temperature(s), b.temperature(s));
    }

    /** A 10 bar supply through an orifice to 1 bar is choked: the isentropic critical mass flow, independent of p_down. */
    @Test
    void chokedOrificeFlow() {
        double t = 300, p = 10e5, a = 1e-6;
        double[] flows = new double[2];
        double[] down = {1e5, 2e5};
        for (int k = 0; k < 2; k++) {
            Network n = new Network();
            Node th = n.node(Domain.THERMAL, "T", t), pn = n.node(Domain.FLUID, "p", down[k]);
            double v = 1e3; // a huge receiver: its pressure stays put
            double nt = down[k] * v / (R * t);
            GasReactor rx = n.add(new GasReactor(AIR, List.of(), v, th, new double[]{nt * X_AIR[0], nt * X_AIR[1], nt * X_AIR[2]})
                    .withPressureNode(pn));
            MixtureBranch b = n.add(new MixtureBranch(AIR, MixtureBranch.End.reservoir(p, t, X_AIR), MixtureBranch.End.of(rx),
                    a, 1.13e-3, 1e-3, 2.0).withTag("orifice"));
            TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
            s.advance(0.05);
            flows[k] = b.massFlow(s);
        }
        double mAir = 0, cp = 0;
        for (int i = 0; i < 3; i++) {
            mAir += X_AIR[i] * AIR.get(i).molarMass();
            cp += X_AIR[i] * AIR.get(i).cp(t);
        }
        double g = cp / (cp - R), rs = R / mAir;
        double ideal = a * p * Math.sqrt(g / (rs * t)) * Math.pow(2 / (g + 1), (g + 1) / (2 * (g - 1)));
        System.out.printf("choked orifice: %.4g kg/s (to 1 bar), %.4g kg/s (to 2 bar), isentropic %.4g kg/s%n", flows[0], flows[1], ideal);
        assertEquals(ideal, flows[0], 0.03 * ideal, "choked mass flow");
        assertEquals(flows[0], flows[1], 1e-6 * flows[0], "independent of the downstream pressure while choked");
    }

    /**
     * Natural draft: a vertical passage holding hot gas (its column lighter than the cold air outside) between two
     * reservoirs of cold air at hydrostatic pressures draws flow upward at Δp = g·h·(ρ_out − ρ_in).
     */
    @Test
    void hotColumnDraws() {
        double h = 1.0, tIn = 600, tOut = 300, p0 = 101325;
        Network n = new Network();
        // a hot chamber at the bottom (held at 600 K) open to cold air below and to a chimney above
        Node th = n.node(Domain.THERMAL, "T", tIn), pn = n.node(Domain.FLUID, "p", p0);
        double v = 0.01, nt = p0 * v / (R * tIn);
        GasReactor box = n.add(new GasReactor(AIR, List.of(), v, th, new double[]{nt * X_AIR[0], nt * X_AIR[1], nt * X_AIR[2]})
                .withPressureNode(pn));
        n.add(new dev.emergence.core.sim.elements.FixedTemperature(th, tIn));
        double rhoOut = p0 * 0.02897 / (R * tOut), g = 9.80665;
        MixtureBranch inlet = n.add(new MixtureBranch(AIR, MixtureBranch.End.reservoir(p0, tOut, X_AIR), MixtureBranch.End.of(box),
                1e-3, 0.036, 0.05, 1.5).withTag("in"));
        // the outlet reservoir sits h above, where the outside air pressure is lower by ρ_out·g·h
        MixtureBranch chimney = n.add(new MixtureBranch(AIR, MixtureBranch.End.of(box),
                MixtureBranch.End.reservoir(p0 - rhoOut * g * h, tOut, X_AIR), 1e-3, 0.036, h, 1.5).withRise(h).withTag("up"));
        TransientSolver s = new TransientSolver(n).method(IntegrationMethod.BDF2);
        s.advance(5);
        double up = chimney.massFlow(s);
        System.out.printf("natural draft: %.4g kg/s up the chimney, %.4g in at the bottom%n", up, inlet.massFlow(s));
        assertTrue(up > 1e-4, "hot gas rises up the chimney: " + up);
        assertEquals(up, inlet.massFlow(s), 1e-3 * up, "drawing the same mass of cold air in at the bottom");
    }
}
