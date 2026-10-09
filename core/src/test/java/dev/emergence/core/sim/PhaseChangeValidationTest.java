package dev.emergence.core.sim;

import dev.emergence.core.fluids.Fluid;
import dev.emergence.core.fluids.FluidDatabase;
import dev.emergence.core.fluids.WaterSteam;
import dev.emergence.core.sim.elements.*;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Validation of liquid–vapour phase change: steam-table properties, boiling, condensation, conservation. */
class PhaseChangeValidationTest {
    static final double P0 = PhysicalConstants.STANDARD_ATMOSPHERE;
    final WaterSteam steam = (WaterSteam) FluidDatabase.standard().get("water_steam");
    final Fluid air = FluidDatabase.standard().get("air");

    @Test
    void saturationPropertiesMatchSteamTables() {
        // IAPWS-IF97 / IAPWS-95 steam tables
        assertEquals(101_418, WaterSteam.pSat(373.15), 100);
        assertEquals(198_670, WaterSteam.pSat(393.15), 200);
        assertEquals(1.0028e6, WaterSteam.pSat(453.15), 1e3);
        assertEquals(611.66, WaterSteam.pSat(273.16), 0.5);
        assertEquals(373.124, WaterSteam.tSat(P0), 0.01);
        assertEquals(958.35, WaterSteam.rhoLiquidSat(373.15), 0.3);
        assertEquals(0.5976, WaterSteam.rhoVapourSat(373.15), 0.003);
        assertEquals(419.17e3, WaterSteam.hLiquidSat(373.15), 500);
        assertEquals(2675.6e3, WaterSteam.hVapourSat(373.15), 1000);
        assertEquals(2256.4e3, WaterSteam.latentHeat(373.15), 1500);
        assertEquals(2014.2e3, WaterSteam.latentHeat(453.15), 3000);
        assertEquals(0, WaterSteam.uLiquidSat(273.16), 50, "IAPWS reference: u' = 0 at the triple point");
        assertEquals(58.91e-3, WaterSteam.surfaceTension(373.15), 0.05e-3);
        assertEquals(4217, steam.cp(373.15), 40);
        assertEquals(0.679, steam.liquidConductivity(373.15), 0.005);
        // Clapeyron: h_fg = T (v'' − v') dp/dT
        double t = 400;
        assertEquals(WaterSteam.latentHeat(t),
                t * (1 / WaterSteam.rhoVapourSat(t) - 1 / WaterSteam.rhoLiquidSat(t)) * WaterSteam.dpSatDt(t),
                1e-6 * WaterSteam.latentHeat(t));
    }

    @Test
    void stateIsContinuousAcrossPhaseBoundaries() {
        double[] a = new double[9], b = new double[9];
        for (double s0 : new double[]{0, 1}) {
            WaterSteam.state(400, s0 - 1e-9, a);
            WaterSteam.state(400, s0 + 1e-9, b);
            for (int k : new int[]{WaterSteam.P, WaterSteam.RHO, WaterSteam.RHO_U}) {
                assertEquals(a[k], b[k], 1e-5 * Math.abs(a[k]) + 1e-6, "quantity " + k + " at s = " + s0);
            }
        }
        // phaseOf inverts the single-phase branches
        double s0 = WaterSteam.phaseOf(WaterSteam.pSat(350) + 3e6, 350);
        WaterSteam.state(350, s0, a);
        assertEquals(WaterSteam.pSat(350) + 3e6, a[WaterSteam.P], 1e-2);
        double[] st = new double[9];
        WaterSteam.state(450, 1.4, st);
        assertEquals(1.4, WaterSteam.phaseOf(st[WaterSteam.P], 450), 1e-6);
    }

    /** Temperature at which a rigid vessel of volume v holding mass m has internal energy u (bisection). */
    static double isochoricTemperature(double m, double v, double energy) {
        double lo = 280, hi = 600;
        double[] st = new double[9];
        for (int i = 0; i < 100; i++) {
            double t = 0.5 * (lo + hi), rho = m / v;
            double rl = WaterSteam.rhoLiquidSat(t), rv = WaterSteam.rhoVapourSat(t);
            double s = Math.log(rl / rho) / Math.log(rl / rv);
            WaterSteam.state(t, s, st);
            if (st[WaterSteam.RHO_U] * v < energy) lo = t;
            else hi = t;
        }
        return 0.5 * (lo + hi);
    }

    @Test
    void heatedSealedVesselFollowsTheSaturationCurve() {
        double v = 1e-3, t0 = 293.15, q = 400, dt = 600;
        Network n = new Network();
        Node pn = n.node(Domain.FLUID, "p", WaterSteam.pSat(t0)), tn = n.node(Domain.THERMAL, "T", t0);
        FluidVolume vol = n.add(new FluidVolume(pn, tn, steam, v).withLiquidFraction(0.5, t0));
        n.add(new FlowSource(n.reference(Domain.THERMAL), tn, q));
        TransientSolver s = new TransientSolver(n);
        double m0 = vol.mass(s), e0 = s.storedEnergy();
        s.advance(dt);
        double t = s.x(tn);
        assertEquals(m0, vol.mass(s), 1e-9 * m0, "sealed: mass conserved");
        assertEquals(q * dt, s.storedEnergy() - e0, 1e-4 * q * dt, "first law");
        assertEquals(WaterSteam.pSat(t), s.x(pn), 1e-4 * s.x(pn), "two-phase: pressure on the saturation curve");
        assertEquals(isochoricTemperature(m0, v, e0 + q * dt), t, 0.05, "state from (m, V, U)");
        assertTrue(t > 390 && s.x(pn) > 1.6e5, "heated well past 100 °C: " + t + " K, " + s.x(pn) + " Pa");
        // the liquid expanded and some evaporated: vapour volume fraction fell slightly (liquid expansion wins)
        assertTrue(vol.vapourFraction(s) < 0.5 && vol.vapourFraction(s) > 0.4, "vapour fraction " + vol.vapourFraction(s));
    }

    @Test
    void openKettleBoilsAtOneHundredDegreesWithLatentHeatRate() {
        double v = 1e-3, q = 1000;
        Network n = new Network();
        double tStart = 372.0;
        Node pn = n.node(Domain.FLUID, "p", WaterSteam.pSat(tStart)), tn = n.node(Domain.THERMAL, "T", tStart);
        FluidVolume vol = n.add(new FluidVolume(pn, tn, steam, v).withLiquidFraction(0.5, tStart));
        Node ap = n.node(Domain.FLUID, "atm.p", P0), at = n.node(Domain.THERMAL, "atm.T", 374);
        FluidReservoir atm = n.add(new FluidReservoir(ap, at, P0, 374));
        FluidBranch spout = n.add(new FluidBranch(pn, tn, ap, at, steam, 0.05, 0.01, 0, 1.0, 0.05)
                .withPorts(vol.port(1.0), null));
        n.add(new FlowSource(n.reference(Domain.THERMAL), tn, q));
        TransientSolver s = new TransientSolver(n);
        double e0 = s.storedEnergy();
        s.advance(60);
        double t = s.x(tn), mdot = spout.massFlow(s);
        double tSat = WaterSteam.tSat(P0);
        assertEquals(tSat, t, 0.1, "boils at the saturation temperature for 1 atm");
        // steady boiling: Q = ṁ (h'' − h') / (1 − ρ''/ρ') — the vapour that leaves also vacates liquid volume
        double expect = q * (1 - WaterSteam.rhoVapourSat(t) / WaterSteam.rhoLiquidSat(t)) / WaterSteam.latentHeat(t);
        assertEquals(expect, mdot, 0.01 * expect, "boil-off rate Q/h_fg");
        // the steam leaving is vapour, not liquid
        assertTrue(vol.vapourFraction(s) > 0.5, "level falls as water boils away");
        // ledger: heat in = stored + enthalpy carried to the atmosphere
        assertEquals(s.externalEnergyIn(), s.storedEnergy() - e0, Math.abs(1e-3 * s.externalEnergyIn()) + 5,
                "energy ledger closes");
        assertTrue(atm.massAbsorbed(s) > 0);
    }

    @Test
    void steamCondensesOnAColdWall() {
        double v = 1e-3, t0 = 393.15, tw = 303.15;
        Network n = new Network();
        Node pn = n.node(Domain.FLUID, "p", WaterSteam.pSat(t0)), tn = n.node(Domain.THERMAL, "T", t0);
        FluidVolume vol = n.add(new FluidVolume(pn, tn, steam, v).withLiquidFraction(0.001, t0));
        Node wall = n.node(Domain.THERMAL, "wall", tw);
        FixedTemperature cold = n.add(new FixedTemperature(wall, tw));
        FluidWallConvection conv = n.add(new FluidWallConvection(tn, wall, steam, 0.06, 0.1, 0.01, java.util.List.of())
                .withTwoPhase(vol, 0.1));
        TransientSolver s = new TransientSolver(n);
        double m0 = vol.mass(s), e0 = s.storedEnergy();
        // initial condensation rate vs the Nusselt film (ΔT = 90 K, L = 0.1 m)
        double ts = t0, dtc = ts - tw;
        double rl = WaterSteam.rhoLiquidSat(ts), rv = WaterSteam.rhoVapourSat(ts), hfg = WaterSteam.latentHeat(ts);
        double kl = steam.liquidConductivity(t0), mul = steam.liquidViscosity(t0), cpl = steam.cp(t0);
        double hfgP = hfg + 0.68 * cpl * dtc;
        double h = 0.943 * Math.pow(rl * (rl - rv) * 9.80665 * hfgP * kl * kl * kl / (mul * 0.1 * dtc), 0.25);
        s.advance(1e-4);
        assertEquals(h * dtc * 0.06 * 0.999, cold.heatAbsorbed(s), 0.03 * h * dtc * 0.06, "Nusselt film condensation");
        s.advance(300);
        assertEquals(m0, vol.mass(s), 1e-9 * m0, "sealed: mass conserved");
        assertEquals(WaterSteam.pSat(tw), s.x(pn), 0.05 * WaterSteam.pSat(tw), "pressure falls to p_sat(T_wall)");
        assertTrue(vol.vapourFraction(s) < 0.999, "liquid formed");
        assertEquals(-s.externalEnergyIn(), e0 - s.storedEnergy(), 1e-4 * (e0 - s.storedEnergy()), "heat to the wall");
    }

    @Test
    void nucleateBoilingAndCriticalHeatFluxMatchIncropera() {
        // Incropera Ex. 10.1: water at 1 atm on polished copper, ΔT_e = 18 K → q'' ≈ 836 kW/m² (Rohsenow)
        double tSat = WaterSteam.tSat(P0), area = 1e-2;
        Network n = new Network();
        Node pn = n.node(Domain.FLUID, "p", P0), tn = n.node(Domain.THERMAL, "T", tSat);
        FluidVolume vol = n.add(new FluidVolume(pn, tn, steam, 1e-3).withPhase(1e-6));
        Node wall = n.node(Domain.THERMAL, "wall", tSat + 18);
        FixedTemperature hot = n.add(new FixedTemperature(wall, tSat + 18));
        n.add(new FluidWallConvection(tn, wall, steam, area, 0.1, 0.01, java.util.List.of()).withTwoPhase(vol, 0.1));
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-5);
        double flux = -hot.heatAbsorbed(s) / area;
        assertEquals(836e3, flux, 0.12 * 836e3, "Rohsenow nucleate boiling (property data ±10 %)");
        // Zuber: q''_max = 0.149 h_fg ρ_v (σ g (ρ_l − ρ_v)/ρ_v²)^¼ ≈ 1.26 MW/m² at 1 atm
        Network n2 = new Network();
        Node p2 = n2.node(Domain.FLUID, "p", P0), t2 = n2.node(Domain.THERMAL, "T", tSat);
        FluidVolume v2 = n2.add(new FluidVolume(p2, t2, steam, 1e-3).withPhase(1e-6));
        Node w2 = n2.node(Domain.THERMAL, "wall", tSat + 120);
        FixedTemperature hot2 = n2.add(new FixedTemperature(w2, tSat + 120));
        n2.add(new FluidWallConvection(t2, w2, steam, area, 0.1, 0.01, java.util.List.of()).withTwoPhase(v2, 0.1));
        TransientSolver s2 = new TransientSolver(n2);
        s2.advance(1e-6);
        assertEquals(1.26e6, -hot2.heatAbsorbed(s2) / area, 0.05 * 1.26e6, "capped at the critical heat flux");
    }

    @Test
    void checkValveOpensOnlyForwardAboveItsCrackingPressure() {
        double t = 293.15;
        double[] flows = new double[3];
        double[] dps = {-2e4, 5e3, 3e4};
        for (int k = 0; k < 3; k++) {
            Network n = new Network();
            Node pa = n.node(Domain.FLUID, "a", P0 + dps[k]), ta = n.node(Domain.THERMAL, "Ta", t);
            Node pb = n.node(Domain.FLUID, "b", P0), tb = n.node(Domain.THERMAL, "Tb", t);
            n.add(new FluidReservoir(pa, ta, P0 + dps[k], t));
            n.add(new FluidReservoir(pb, tb, P0, t));
            FluidBranch b = n.add(new FluidBranch(pa, ta, pb, tb, air, 0.01, 0.01, 0, 0, 0)
                    .withOrifice(7.85e-5, 0.6).withCheck(1e4, 1e4));
            TransientSolver s = new TransientSolver(n);
            s.advance(0.01);
            flows[k] = b.massFlow(s);
        }
        assertEquals(0, flows[0], 1e-7, "reverse flow blocked");
        assertEquals(0, flows[1], 1e-7, "closed below cracking pressure");
        assertTrue(flows[2] > 1e-3, "open above cracking pressure: " + flows[2]);
    }
}
