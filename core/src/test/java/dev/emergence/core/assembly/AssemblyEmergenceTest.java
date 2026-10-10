package dev.emergence.core.assembly;

import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds devices from primitive grid parts exactly as a player would, then checks the compiled
 * physics. No part of the compiler or solver knows what a "generator" is.
 */
class AssemblyEmergenceTest {
    static final MaterialDatabase DB = MaterialDatabase.standard();
    static final Material STEEL = DB.get("steel_1045"), ALU = DB.get("aluminium_6061"), CU = DB.get("copper"),
            NDFEB = DB.get("ndfeb_n42"), ALUMINA = DB.get("alumina");

    long nextId = 1;
    final Assembly asm = new Assembly();
    final Set<Long> anchors = new HashSet<>();

    Part add(PartKind k, Material m, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Map<String, Double> params) {
        Part p = new Part(nextId++, k, m, x0, y0, z0, x1, y1, z1, axis, params);
        asm.add(p);
        return p;
    }

    Part add(PartKind k, Material m, int x, int y, int z, Dir axis) {
        return add(k, m, x, y, z, x, y, z, axis, Map.of());
    }

    Part frame(int x0, int y0, int z0, int x1, int y1, int z1) {
        Part p = add(PartKind.BLOCK, STEEL, x0, y0, z0, x1, y1, z1, Dir.UP, Map.of());
        anchors.add(p.id());
        return p;
    }

    /** Hand-cranked axial-flux generator with a resistive load, returns {crank, resistor} parts. */
    Part[] buildGenerator(double loadOhms) {
        return buildGenerator(loadOhms, 0.6);
    }

    Part[] buildGenerator(double loadOhms, double diskFill) {
        // static frame: bottom bearing mounts and top plates
        frame(1, 1, 0, 1, 1, 0);
        frame(-1, 1, 0, -1, 1, 0);
        frame(1, 7, -1, 3, 7, 1);
        frame(-3, 7, -1, -1, 7, 1);
        // shaft through two ball bearings
        add(PartKind.SHAFT, STEEL, 0, -1, 0, 0, 7, 0, Dir.UP, Map.of("diameter", 0.02));
        add(PartKind.BEARING, STEEL, 0, 1, 0, Dir.UP);
        add(PartKind.BEARING, STEEL, 0, 7, 0, Dir.UP);
        Part crank = add(PartKind.CRANK, STEEL, 0, -2, 0, Dir.UP);
        // rotor disk and two magnets of opposite polarity
        add(PartKind.BLOCK, ALU, -2, 3, -2, 2, 3, 2, Dir.UP, Map.of("fill", diskFill));
        add(PartKind.MAGNET, NDFEB, 2, 4, 0, Dir.UP);
        add(PartKind.MAGNET, NDFEB, -2, 4, 0, Dir.DOWN);
        // stator coils hanging under the top plates (top leads contact the steel frame)
        Map<String, Double> coilParams = Map.of("turns", 300.0, "wire_diameter", 0.8e-3);
        add(PartKind.COIL, CU, 2, 5, 0, 2, 6, 0, Dir.UP, coilParams);
        add(PartKind.COIL, CU, -2, 5, 0, -2, 6, 0, Dir.UP, coilParams);
        // load circuit from coil A bottom lead to coil B bottom lead
        int[][] path = {{3, 5, 0}, {3, 5, 1}, {3, 5, 2}, {2, 5, 2}, {1, 5, 2}, {-1, 5, 2}, {-2, 5, 2}, {-3, 5, 2}, {-3, 5, 1}, {-3, 5, 0}};
        for (int[] c : path) add(PartKind.INSULATED_WIRE, CU, c[0], c[1], c[2], Dir.UP);
        Map<String, Double> rp = new HashMap<>();
        rp.put("resistance", loadOhms);
        Part res = add(PartKind.RESISTOR, ALUMINA, 0, 5, 2, 0, 5, 2, Dir.EAST, rp);
        asm.anchoredWhen(p -> anchors.contains(p.id()));
        return new Part[]{crank, res};
    }

    @Test
    void compilerFindsRotorBearingsAndCircuit() {
        buildGenerator(20);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        assertEquals(2, c.bodies().size(), "frame + one rotor; warnings=" + c.warnings());
        CompiledAssembly.Body rotor = c.bodies().get(1);
        assertFalse(rotor.isStatic());
        assertEquals(1.0, rotor.axis().y(), 1e-12);
        // rotor inertia dominated by the aluminium disk: 5×5 cells (31 cm) × 1 cell, fill 0.6 → m≈9.9 kg, J=m(a²+b²)/12≈0.16
        assertTrue(rotor.inertia() > 0.1 && rotor.inertia() < 0.3, "J=" + rotor.inertia()); // ≈10 kg aluminium plate, 31 cm square
        assertNotNull(c.magnetics());
        assertEquals(2, c.magnetics().coils().size());
    }

    @Test
    void handCrankedGeneratorLightsUpTheLoad() {
        Part[] pr = buildGenerator(20);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        c.setCrankTorque(pr[0].id(), 1.0); // ~a person turning a crank
        double maxI = 0;
        for (int tick = 0; tick < 60; tick++) { // 3 s of game time
            c.step(0.05);
            maxI = Math.max(maxI, Math.abs(c.probe(pr[1].id()).current()));
        }
        Probe crank = c.probe(pr[0].id());
        assertTrue(crank.angularVelocity() > 5, "rotor turning, ω=" + crank.angularVelocity());
        assertTrue(maxI > 1e-3, "load current induced, peak I=" + maxI);
        Probe load = c.probe(pr[1].id());
        assertTrue(load.temperature() > c.assembly().ambientTemperature(), "load warmed by Joule heating");
        // conservation: crank work = kinetic + magnetic + thermal energy change (+ reservoir exchange)
        double work = c.solver().externalEnergyIn();
        assertTrue(Math.abs(c.energyError()) < 0.02 * Math.abs(work) + 1e-3, "energy error " + c.energyError() + " of " + work);
    }

    @Test
    void openCircuitSpinsFreelyButLoadedCircuitBrakes() {
        // open circuit: remove resistor (leave circuit broken)
        // a light rotor (thin disk) so it reaches speeds where the EMF is substantial
        Part[] pr = buildGenerator(1e9, 0.04);
        CompiledAssembly open = AssemblyCompiler.compile(asm);
        open.setCrankTorque(pr[0].id(), 0.5);
        for (int i = 0; i < 40; i++) open.step(0.05);
        double wOpen = open.probe(pr[0].id()).angularVelocity();

        AssemblyEmergenceTest other = new AssemblyEmergenceTest();
        Part[] pr2 = other.buildGenerator(2.0, 0.04);
        CompiledAssembly loaded = AssemblyCompiler.compile(other.asm);
        loaded.setCrankTorque(pr2[0].id(), 0.5);
        for (int i = 0; i < 40; i++) loaded.step(0.05);
        double wLoaded = loaded.probe(pr2[0].id()).angularVelocity();
        // An air-core machine with one magnet pass per coil is weak (k ≈ 0.05 V·s/rad here), so braking at
        // ~22 rad/s is ~1–3 %: small but strictly from the electrical load.
        assertTrue(wLoaded < 0.995 * wOpen, "electrical load must brake the rotor: open " + wOpen + " vs loaded " + wLoaded);
        // Energy balance: crank work W = τ·θ goes to kinetic energy, bearing friction and (when loaded)
        // electrical heat. Bearing friction is tiny for ball bearings, so the electrical heat is
        // Q ≈ τ·(θ_loaded − θ_open) + (KE_open − KE_loaded).
        double j = loaded.bodies().get(1).inertia();
        double thO = open.probe(pr[0].id()).angle(), thL = loaded.probe(pr2[0].id()).angle();
        double qElec = 0.5 * (thL - thO) + 0.5 * j * (wOpen * wOpen - wLoaded * wLoaded);
        double heat = 0;
        for (long id : loaded.partIds()) {
            Part p = loaded.assembly().get(id);
            // the circuit's parts (the frame's steel also warms, from eddy currents the passing magnets induce in it)
            boolean circuit = p.kind() == PartKind.COIL || p.kind() == PartKind.WIRE || p.kind() == PartKind.INSULATED_WIRE
                    || p.kind() == PartKind.RESISTOR;
            if (loaded.bodyOf(id).isStatic() && circuit) {
                heat += (loaded.temperature(id) - loaded.assembly().ambientTemperature()) * PartGeometry.mass(p)
                        * p.material().get(dev.emergence.core.materials.MaterialProperty.SPECIFIC_HEAT, 0);
            }
        }
        assertEquals(qElec, heat, 0.1 * qElec, "electrical energy extracted should appear as circuit heat");
    }

    @Test
    void stateSurvivesRecompilation() {
        Part[] pr = buildGenerator(20);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        c.setCrankTorque(pr[0].id(), 1.0);
        for (int i = 0; i < 20; i++) c.step(0.05);
        double w = c.probe(pr[0].id()).angularVelocity();
        CompiledAssembly c2 = CompiledAssembly.recompile(asm, c);
        assertEquals(w, c2.probe(pr[0].id()).angularVelocity(), 1e-9);
        assertEquals(c.temperature(pr[1].id()), c2.temperature(pr[1].id()), 1e-9);
    }
}
