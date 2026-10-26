package dev.emergence.core.assembly;

import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fluid machines assembled from pipes, tanks, valves and rotors: behaviour follows from the parts alone. */
class FluidAssemblyTest {
    static final double P0 = 101_325;
    long id = 1;
    final Assembly asm = new Assembly()
            .anchoredWhen(p -> p.minY() == 0 && p.kind().structural && p.kind() != PartKind.SHAFT);

    Part add(PartKind k, String m, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Object... kv) {
        Map<String, Double> pr = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) pr.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        Part p = new Part(id++, k, MaterialDatabase.standard().get(m), x0, y0, z0, x1, y1, z1, axis, pr);
        asm.add(p);
        return p;
    }

    Part add(PartKind k, String m, int x, int y, int z, Dir axis, Object... kv) {
        return add(k, m, x, y, z, x, y, z, axis, kv);
    }

    static void run(CompiledAssembly c, double seconds) {
        for (int i = 0; i < Math.round(seconds / 0.05); i++) c.step(0.05);
    }

    @Test
    void valveHoldsThenReleasesACompressedTank() {
        Part tank = add(PartKind.TANK, "steel_1045", 0, 0, 0, 1, 1, 1, Dir.UP, "pressure", 6e5);
        Part valve = add(PartKind.VALVE, "brass", 2, 0, 0, Dir.EAST);
        add(PartKind.PIPE, "copper", 3, 0, 0, Dir.EAST);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        double m0 = c.fluid(tank.id())[2];
        run(c, 2);
        assertEquals(6e5, c.fluid(tank.id())[0], 1e3, "valve shut: pressure held");
        assertEquals(1, c.vents().size(), "the pipe's free end is the only opening");
        c.toggleSwitch(valve.id());
        double outflow = 0;
        // the blowdown takes a fraction of a second: integrate the vent flow finely (trapezoid)
        double q0 = c.ventOutflow(c.vents().get(0));
        for (int i = 0; i < 4000; i++) {
            c.step(0.001);
            double q1 = c.ventOutflow(c.vents().get(0));
            outflow += 0.5 * (q0 + q1) * 0.001;
            q0 = q1;
        }
        double[] f = c.fluid(tank.id());
        assertTrue(f[0] < 1.2 * P0, "blown down: " + f[0]);
        // the valve and pipe hold almost nothing, so the vented mass is the tank's loss
        assertEquals(m0 - f[2], outflow, 0.03 * (m0 - f[2]), "vented mass equals the tank's loss");
    }

    @Test
    void heatingASealedTankRaisesItsPressure() {
        Part tank = add(PartKind.TANK, "aluminium_6061", 0, 0, 0, Dir.UP);
        Part hot = add(PartKind.BLOCK, "copper", 1, 0, 0, Dir.UP);
        CompiledAssembly c = AssemblyCompiler.compile(asm, stateWithTemperature(hot.id(), 700));
        double t0 = c.fluid(tank.id())[1];
        run(c, 120);
        double[] f = c.fluid(tank.id());
        assertTrue(f[1] > t0 + 20, "gas heated through the wall: " + t0 + " -> " + f[1]);
        assertEquals(P0 * f[1] / t0, f[0], 0.002 * f[0], "sealed ideal gas: p/T constant");
    }

    /**
     * Two vessels side by side holding water under its vapour, both less than half full, one nearly empty: the water
     * finds its level through the face between them. (The face passes water low and vapour high at once; drawn at
     * mid-height alone, above both water lines, it passed only vapour and the levels stayed apart.)
     */
    @Test
    void waterFindsItsLevelBetweenVesselsSideBySide() {
        Part a = add(PartKind.TANK, "steel_1045", 0, 0, 0, Dir.UP, "fluid", 7, "fill", 0.45);
        Part b = add(PartKind.TANK, "steel_1045", 1, 0, 0, Dir.UP, "fluid", 7, "fill", 0.05);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        java.util.function.ToDoubleFunction<Part> water = t -> c.fluid(t.id())[2] * (1 - c.fluid(t.id())[4]);
        double wa = water.applyAsDouble(a), wb = water.applyAsDouble(b), m0 = c.fluid(a.id())[2] + c.fluid(b.id())[2];
        run(c, 30);
        double wa1 = water.applyAsDouble(a), wb1 = water.applyAsDouble(b);
        assertTrue(wb1 > wb + 0.6 * (wa - wb) / 2, String.format("the water runs across: %.1f g, %.1f g → %.1f g, %.1f g", 1e3 * wa,
                1e3 * wb, 1e3 * wa1, 1e3 * wb1));
        assertEquals(m0, c.fluid(a.id())[2] + c.fluid(b.id())[2], 1e-6 * m0, "mass conserved");
    }

    /**
     * A lying pipe with water under its vapour, below its axis, beside an empty vessel: the water runs out along the
     * bottom of the bore. (Drawn at mid-bore alone the face passed only vapour, and a level steam line kept each of
     * its cells half full of condensate.)
     */
    @Test
    void waterUnderTheAxisOfALyingPipeDrainsSideways() {
        add(PartKind.BLOCK, "steel_1045", -1, 0, 0, Dir.UP); // the pipe's far end, capped
        Part pipe = add(PartKind.PIPE, "copper", 0, 0, 0, Dir.EAST, "fluid", 7, "fill", 0.4, "bore", 0.02);
        Part tank = add(PartKind.TANK, "steel_1045", 1, 0, 0, Dir.UP, "fluid", 7, "fill", 0.02);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        assertTrue(c.vents().isEmpty(), "sealed");
        java.util.function.ToDoubleFunction<Part> water = t -> c.fluid(t.id())[2] * (1 - c.fluid(t.id())[4]);
        double w0 = water.applyAsDouble(pipe), m0 = c.fluid(pipe.id())[2] + c.fluid(tank.id())[2];
        run(c, 30);
        double w1 = water.applyAsDouble(pipe);
        assertTrue(w1 < 0.75 * w0, String.format("the pipe drains: %.2f g → %.2f g", 1e3 * w0, 1e3 * w1));
        assertEquals(m0, c.fluid(pipe.id())[2] + c.fluid(tank.id())[2], 1e-6 * m0, "mass conserved");
    }

    static AssemblyState stateWithTemperature(long id, double t) {
        AssemblyState s = AssemblyState.empty();
        s.temperatures().put(id, t);
        return s;
    }

    /** Tank → valve → pipe → rotor on a shaft → pipe with a side outlet; the shaft leaves through a gland. */
    Part[] turbine(double pitch) {
        add(PartKind.TANK, "steel_1045", 0, 0, 0, 2, 2, 2, Dir.UP, "pressure", 8e5);
        Part valve = add(PartKind.VALVE, "brass", 3, 1, 1, Dir.EAST);
        add(PartKind.PIPE, "steel_1045", 4, 1, 1, Dir.EAST, "bore", 0.04);
        Part rotor = add(PartKind.ROTOR, "aluminium_6061", 5, 1, 1, Dir.EAST, "pitch", pitch);
        add(PartKind.PIPE, "steel_1045", 6, 1, 1, Dir.EAST, "bore", 0.04);
        add(PartKind.PIPE, "steel_1045", 6, 2, 1, Dir.UP, "bore", 0.04);
        Part shaft = add(PartKind.SHAFT, "steel_1045", 5, 1, 1, 8, 1, 1, Dir.EAST, "diameter", 0.012);
        add(PartKind.BEARING, "steel_1045", 7, 1, 1, Dir.EAST);
        add(PartKind.BEARING, "steel_1045", 8, 1, 1, Dir.EAST);
        add(PartKind.BLOCK, "steel_1045", 7, 0, 1, Dir.UP);
        add(PartKind.BLOCK, "steel_1045", 8, 0, 1, Dir.UP);
        return new Part[]{valve, rotor, shaft};
    }

    @Test
    void compressedAirDrivesATurbineWhoseDirectionFollowsBladePitch() {
        double[] omega = new double[2];
        for (int k = 0; k < 2; k++) {
            FluidAssemblyTest t = new FluidAssemblyTest();
            Part[] parts = t.turbine(k == 0 ? 1 : -1);
            CompiledAssembly c = AssemblyCompiler.compile(t.asm);
            assertTrue(c.warnings().isEmpty(), c.warnings().toString());
            assertEquals(2, c.bodies().size(), "frame + turbine rotor (the shaft runs in a gland, not welded)");
            run(c, 1);
            assertEquals(0, c.probe(parts[2].id()).angularVelocity(), 1e-6, "valve shut: no flow, no rotation");
            c.toggleSwitch(parts[0].id());
            // the blowdown lasts about a second: follow the peak flow and rotor speed
            double peakFlow = 0;
            for (int i = 0; i < 150; i++) {
                c.step(0.02);
                peakFlow = Math.max(peakFlow, c.fluid(parts[1].id())[3]);
                double w = c.probe(parts[2].id()).angularVelocity();
                if (Math.abs(w) > Math.abs(omega[k])) omega[k] = w;
            }
            assertTrue(peakFlow > 0.01, "air flows through the rotor: " + peakFlow);
            assertTrue(Math.abs(c.energyError()) < 1e-3 * Math.abs(c.storedEnergy()), "energy ledger: " + c.energyError());
        }
        assertTrue(Math.abs(omega[0]) > 20, "turbine spins: " + omega[0]);
        assertEquals(-omega[0], omega[1], 0.05 * Math.abs(omega[0]), "opposite pitch, opposite rotation");
    }

    @Test
    void crankedFanBlowsAirThroughADuct() {
        add(PartKind.PIPE, "pvc", 2, 1, 1, Dir.EAST, "bore", 0.05);
        Part fan = add(PartKind.ROTOR, "aluminium_6061", 3, 1, 1, Dir.EAST);
        add(PartKind.PIPE, "pvc", 4, 1, 1, Dir.EAST, "bore", 0.05);
        add(PartKind.PIPE, "pvc", 4, 2, 1, Dir.UP, "bore", 0.05);
        add(PartKind.SHAFT, "steel_1045", 3, 1, 1, 6, 1, 1, Dir.EAST, "diameter", 0.012);
        add(PartKind.BEARING, "steel_1045", 5, 1, 1, Dir.EAST);
        add(PartKind.BLOCK, "steel_1045", 5, 0, 1, Dir.UP);
        Part crank = add(PartKind.CRANK, "steel_1045", 7, 1, 1, Dir.EAST, "radius", 0.15);
        add(PartKind.BLOCK, "steel_1045", 2, 0, 1, Dir.UP);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        assertEquals(2, c.vents().size(), "inlet and outlet open to the air");
        for (int i = 0; i < 60; i++) {
            c.setCrankEffort(crank.id(), 1, true);
            c.step(0.05);
        }
        double flow = c.fluid(fan.id())[3];
        // turned at hand speed (no step-up gearing) the fan moves a little air: ~0.4 g/s (until the vents saw the
        // outside air's column, a spurious 1.5 g/s draft ran backwards through this duct and hid how little)
        assertTrue(Math.abs(flow) > 2e-4, "the fan moves air: " + flow + " kg/s");
        for (var v : c.vents())
            assertEquals(v.face() == Dir.WEST ? -1 : 1, Math.signum(c.ventOutflow(v)), "drawn in at the inlet, blown out of the riser");
        double in = 0, out = 0;
        for (var v : c.vents()) {
            double q = c.ventOutflow(v);
            if (q > 0) out += q;
            else in -= q;
        }
        assertEquals(in, out, 0.02 * out, "steady: intake equals discharge");
    }

    /**
     * An idle duct open at two heights holds still: the outside air's column presses on the lower opening as the
     * inside air's does (before each vent saw the same pressure, and a cold duct drew a draft of 1.6 g/s forever).
     */
    @Test
    void idleDuctOpenAtTwoHeightsDrawsNoDraft() {
        add(PartKind.PIPE, "pvc", 2, 1, 1, 4, 1, 1, Dir.EAST, "bore", 0.05);
        add(PartKind.BLOCK, "steel_1045", 5, 1, 1, Dir.UP); // caps the run's far end
        add(PartKind.PIPE, "pvc", 4, 2, 1, 4, 5, 1, Dir.UP, "bore", 0.05);
        add(PartKind.BLOCK, "steel_1045", 2, 0, 1, Dir.UP);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        assertEquals(2, c.vents().size());
        run(c, 10);
        for (var v : c.vents()) assertEquals(0, c.ventOutflow(v), 1e-5, "no draft through a duct at ambient temperature");
    }
}
