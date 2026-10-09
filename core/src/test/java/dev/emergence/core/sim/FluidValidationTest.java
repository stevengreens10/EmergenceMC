package dev.emergence.core.sim;

import dev.emergence.core.fluids.Fluid;
import dev.emergence.core.fluids.FluidDatabase;
import dev.emergence.core.fluids.IdealGas;
import dev.emergence.core.sim.elements.*;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Analytical validation of the compressible fluid network (control volumes, branches, rotors). */
class FluidValidationTest {
    static final double P0 = PhysicalConstants.STANDARD_ATMOSPHERE;
    final Fluid air = FluidDatabase.standard().get("air");
    final Fluid water = FluidDatabase.standard().get("water");

    /** A fluid port: pressure node and thermal node. */
    record Port(Node p, Node t) {}

    static Port port(Network n, String name, double p, double t) {
        return new Port(n.node(Domain.FLUID, name + ".p", p), n.node(Domain.THERMAL, name + ".T", t));
    }

    static FluidReservoir reservoir(Network n, Port port, double p, double t) {
        return n.add(new FluidReservoir(port.p, port.t, p, t));
    }

    static FluidVolume volume(Network n, Port port, Fluid f, double v) {
        return n.add(new FluidVolume(port.p, port.t, f, v));
    }

    static FluidBranch pipe(Network n, Port a, Port b, Fluid f, double l, double d, double dz) {
        return n.add(new FluidBranch(a.p, a.t, b.p, b.t, f, l, d, 0, 0, dz));
    }

    @Test
    void propertiesMatchReferenceTables() {
        // Incropera Table A.4 (air at 300 K, tabulated at 1 bar) and A.6 (saturated water, 300 K)
        assertEquals(1.1614, air.density(1e5, 300), 0.001);
        assertEquals(1007, air.cp(300), 3);
        assertEquals(1.846e-5, air.viscosity(300), 0.02e-5);
        assertEquals(0.0263, air.conductivity(300), 0.0008);
        assertEquals(1.4, ((IdealGas) air).gamma(300), 0.003);
        assertEquals(5.0 / 3, ((IdealGas) FluidDatabase.standard().get("helium")).gamma(300), 0.002);
        assertEquals(997, water.density(P0, 298.15), 0.5);
        assertEquals(855e-6, water.viscosity(300), 15e-6);
        // u is consistent with c_v: du/dT
        double du = (air.internalEnergy(P0, 801) - air.internalEnergy(P0, 799)) / 2;
        assertEquals(air.cv(800), du, 1e-3 * du);
    }

    @Test
    void laminarPipeFlowIsHagenPoiseuille() {
        double l = 1.0, d = 5e-3, dp = 100, t = 293.15;
        Network n = new Network();
        Port a = port(n, "a", P0 + dp, t), b = port(n, "b", P0, t);
        reservoir(n, a, P0 + dp, t);
        reservoir(n, b, P0, t);
        FluidBranch br = pipe(n, a, b, water, l, d, 0);
        TransientSolver s = new TransientSolver(n);
        s.advance(0.1);
        double rho = water.density(P0 + dp / 2, t), mu = water.viscosity(t);
        double q = Math.PI * Math.pow(d, 4) * dp / (128 * mu * l);
        assertEquals(rho * q, br.massFlow(s), 1e-4 * rho * q);
    }

    @Test
    void turbulentPipeFlowMatchesColebrook() {
        double l = 10, d = 0.02, eps = 4.5e-5, dp = 20_000, t = 293.15;
        Network n = new Network();
        Port a = port(n, "a", P0 + dp, t), b = port(n, "b", P0, t);
        reservoir(n, a, P0 + dp, t);
        reservoir(n, b, P0, t);
        FluidBranch br = n.add(new FluidBranch(a.p, a.t, b.p, b.t, water, l, d, eps, 0, 0));
        TransientSolver s = new TransientSolver(n);
        s.advance(0.1);
        double rho = water.density(P0 + dp / 2, t), mu = water.viscosity(t);
        // solve Δp = f L/D ρ v²/2 with the implicit Colebrook–White equation
        double v = 1, f = 0.02;
        for (int i = 0; i < 200; i++) {
            double re = rho * v * d / mu;
            for (int k = 0; k < 50; k++) f = Math.pow(-2 * Math.log10(eps / d / 3.7 + 2.51 / (re * Math.sqrt(f))), -2);
            v = Math.sqrt(2 * dp * d / (f * l * rho));
        }
        double expected = rho * v * Math.PI * d * d / 4;
        assertTrue(rho * v * d / mu > 1e4, "turbulent regime");
        assertEquals(expected, br.massFlow(s), 0.02 * expected);
    }

    @Test
    void hydrostaticColumnHasNoFlow() {
        double h = 3.0, t = 293.15;
        double rho = water.density(P0, t);
        Network n = new Network();
        // hydrostatic pressure with the (slightly compressible) column's mean density
        double pBottom = P0 + rho * PhysicalConstants.STANDARD_GRAVITY * h;
        for (int i = 0; i < 5; i++) pBottom = P0 + water.density((P0 + pBottom) / 2, t) * PhysicalConstants.STANDARD_GRAVITY * h;
        Port bot = port(n, "bottom", pBottom, t), top = port(n, "top", P0, t);
        reservoir(n, bot, pBottom, t);
        reservoir(n, top, P0, t);
        FluidBranch br = pipe(n, bot, top, water, h, 0.01, h);
        TransientSolver s = new TransientSolver(n);
        s.advance(0.1);
        assertEquals(0, br.massFlow(s), 1e-9);
    }

    @Test
    void heatingASealedVesselRaisesPressureIsochorically() {
        double v = 1e-3, t0 = 293.15, q = 10, dt = 10;
        Network n = new Network();
        Node ref = n.reference(Domain.THERMAL);
        Port g = port(n, "gas", P0, t0);
        FluidVolume vol = volume(n, g, air, v);
        n.add(new FlowSource(ref, g.t, q));
        TransientSolver s = new TransientSolver(n);
        double m = vol.mass(s), u0 = air.internalEnergy(P0, t0);
        s.advance(dt);
        assertEquals(m, vol.mass(s), 1e-9 * m, "sealed: mass conserved");
        // first law: m·(u(T) − u(T0)) = Q
        double t = s.x(g.t);
        assertEquals(q * dt, m * (air.internalEnergy(P0, t) - u0), 1e-4 * q * dt);
        assertEquals(P0 * t / t0, s.x(g.p), 1e-6 * P0, "Gay-Lussac p ∝ T");
        assertEquals(s.externalEnergyIn(), s.storedEnergy() - air.internalEnergy(P0, t0) * m, 1e-3);
    }

    @Test
    void connectedTanksEqualiseConservingMassAndEnergy() {
        double v1 = 1e-3, v2 = 2e-3, p1 = 5e5, t1 = 400, p2 = P0, t2 = 300;
        Network n = new Network();
        Port a = port(n, "a", p1, t1), b = port(n, "b", p2, t2);
        FluidVolume va = volume(n, a, air, v1), vb = volume(n, b, air, v2);
        pipe(n, a, b, air, 0.2, 4e-3, 0);
        TransientSolver s = new TransientSolver(n);
        double m0 = va.mass(s) + vb.mass(s), e0 = s.storedEnergy();
        s.advance(30);
        double m = va.mass(s) + vb.mass(s);
        assertEquals(m0, m, 1e-6 * m0, "mass conserved");
        assertEquals(e0, s.storedEnergy(), 1e-6 * Math.abs(e0), "adiabatic: internal energy conserved");
        // equilibrium: equal pressures; final state from m and U (temperatures equalise only through mixing
        // of the flowing gas; here no wall heat transfer, so check the mechanical equilibrium and totals)
        assertEquals(s.x(a.p), s.x(b.p), 1e-3 * s.x(b.p), "pressures equalised");
        double r = ((IdealGas) air).gasConstant();
        // p·V_total = R·Σ m_i T_i holds for any temperature split
        double mT = va.mass(s) * s.x(a.t) + vb.mass(s) * s.x(b.t);
        assertEquals(r * mT, s.x(a.p) * (v1 + v2), 1e-4 * s.x(a.p) * (v1 + v2));
        // the expanding gas left behind cools (adiabatic expansion), the receiving tank warms (flow work)
        assertTrue(s.x(a.t) < t1, "source tank cooled: " + s.x(a.t));
        assertTrue(s.x(b.t) > t2, "receiving tank warmed: " + s.x(b.t));
    }

    @Test
    void sourceTankFollowsIsentropicExpansion() {
        // an adiabatic tank discharging slowly: the gas remaining inside expands isentropically,
        // T/T0 = (p/p0)^((γ−1)/γ) (calorically perfect over the small range)
        double v = 5e-3, p1 = 3e5, t1 = 300;
        Network n = new Network();
        Port a = port(n, "tank", p1, t1), b = port(n, "atm", P0, t1);
        volume(n, a, air, v);
        reservoir(n, b, P0, t1);
        pipe(n, a, b, air, 0.5, 2e-3, 0);
        TransientSolver s = new TransientSolver(n);
        s.advance(5);
        double p = s.x(a.p), g = ((IdealGas) air).gamma(270);
        assertTrue(p < 0.9 * p1 && p > 1.05 * P0, "partially discharged: " + p);
        assertEquals(t1 * Math.pow(p / p1, (g - 1) / g), s.x(a.t), 0.5);
        assertTrue(Math.abs(s.energyError()) < 1e-3 * Math.abs(s.storedEnergy()), "ledger closes: " + s.energyError());
    }

    @Test
    void drivenRotorPumpsAndFreeRotorWindmillsAtZeroIncidence() {
        double t = 293.15, r = 0.02, beta = Math.toRadians(45), d = 0.05;
        // pump: torque drives the rotor; flow a → b against equal pressures, shaft power = ṁ·Δh0 + nothing lost
        {
            Network n = new Network();
            Node ref = n.reference(Domain.ROTATIONAL);
            Node w = n.node(Domain.ROTATIONAL, "rotor");
            n.add(new Inertia(w, 1e-4));
            n.add(new Damper(w, ref, 1e-5, null));
            n.add(new FlowSource(ref, w, 0.01));
            Port a = port(n, "a", P0, t), b = port(n, "b", P0, t);
            reservoir(n, a, P0, t);
            reservoir(n, b, P0, t);
            FluidBranch fan = n.add(new FluidBranch(a.p, a.t, b.p, b.t, air, 0.1, d, 0, 0.5, 0)
                    .withRotor(w, r, Math.PI * d * d / 4, beta, 1, 1, 1, 0.02, 0.8));
            TransientSolver s = new TransientSolver(n);
            s.advance(5);
            assertTrue(s.x(w) > 1, "spun up: " + s.x(w));
            assertTrue(fan.massFlow(s) > 0, "fan blows a → b: " + fan.massFlow(s));
            assertTrue(Math.abs(s.energyError()) < 1e-6 * Math.max(1, s.externalEnergyIn()), "power balance: " + s.energyError());
        }
        // turbine: a pressure difference drives flow; an unloaded rotor settles where it takes no swirl,
        // U = c_x·tan β (zero torque)
        {
            Network n = new Network();
            Node w = n.node(Domain.ROTATIONAL, "rotor");
            n.add(new Inertia(w, 1e-5));
            Port a = port(n, "a", P0 + 200, t), b = port(n, "b", P0, t);
            reservoir(n, a, P0 + 200, t);
            reservoir(n, b, P0, t);
            FluidBranch tur = n.add(new FluidBranch(a.p, a.t, b.p, b.t, air, 0.1, d, 0, 0.5, 0)
                    .withRotor(w, r, Math.PI * d * d / 4, beta, 1, 1, 1, 0.02, 0.8));
            TransientSolver s = new TransientSolver(n);
            s.advance(10);
            double rho = air.density(P0 + 100, t);
            double cx = tur.massFlow(s) / (rho * Math.PI * d * d / 4);
            assertTrue(cx > 0.5, "flow through the turbine: " + cx);
            // zero torque where the relative flow meets the blades almost along their chord (profile drag
            // shifts it by α ≈ C_D0·tan β / 2π)
            assertEquals(cx * Math.tan(beta), s.x(w) * r, 0.02 * cx, "free-running blade speed");
        }
    }

    @Test
    void orificeChokesAtTheCriticalPressureRatio() {
        double t = 293.15, p1 = 1e6, d = 2e-3, cd = 0.62;
        IdealGas g = (IdealGas) air;
        double gam = g.gamma(t), r = g.gasConstant(), area = Math.PI * d * d / 4;
        double choked = cd * area * p1 * Math.sqrt(gam / (r * t)) * Math.pow(2 / (gam + 1), (gam + 1) / (2 * (gam - 1)));
        double[] flows = new double[2];
        double[] downstream = {P0, 2 * P0};
        for (int k = 0; k < 2; k++) {
            Network n = new Network();
            Port a = port(n, "a", p1, t), b = port(n, "b", downstream[k], t);
            reservoir(n, a, p1, t);
            reservoir(n, b, downstream[k], t);
            FluidBranch br = n.add(new FluidBranch(a.p, a.t, b.p, b.t, air, 0.02, d, 0, 0, 0).withOrifice(area, cd));
            TransientSolver s = new TransientSolver(n);
            s.advance(0.01);
            flows[k] = br.massFlow(s);
        }
        assertEquals(choked, flows[0], 1e-3 * choked, "choked mass flow (sonic throat)");
        assertEquals(flows[0], flows[1], 1e-6 * choked, "below the critical ratio the downstream pressure does not matter");
        // subcritical: the full isentropic-nozzle relation
        Network n = new Network();
        double p2 = 0.8 * p1;
        Port a = port(n, "a", p1, t), b = port(n, "b", p2, t);
        reservoir(n, a, p1, t);
        reservoir(n, b, p2, t);
        FluidBranch br = n.add(new FluidBranch(a.p, a.t, b.p, b.t, air, 0.02, d, 0, 0, 0).withOrifice(area, cd));
        TransientSolver s = new TransientSolver(n);
        s.advance(0.01);
        double ratio = p2 / p1;
        double expected = cd * area * p1 * Math.sqrt(2 * gam / ((gam - 1) * r * t) * (Math.pow(ratio, 2 / gam) - Math.pow(ratio, (gam + 1) / gam)));
        assertEquals(expected, br.massFlow(s), 1e-3 * expected);
    }
}
