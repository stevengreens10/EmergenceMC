package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Probe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Every demo exhibit must actually show what its sign claims — verified from the physics. */
class DemoExhibitsTest {
    static final double AMBIENT = 293.15;

    static CompiledAssembly compile(Exhibit e, Exhibits.Built b) {
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), e.id + " warnings: " + c.warnings());
        return c;
    }

    static void run(CompiledAssembly c, double seconds) {
        for (int i = 0; i < Math.round(seconds / 0.05); i++) c.step(0.05);
    }

    @Test
    void generatorProducesCurrentWhenCranked() {
        Exhibit e = Exhibits.generator();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertEquals(2, c.bodies().size());
        double peak = 0;
        for (int i = 0; i < 60; i++) {
            c.setCrankTorque(b.id(e, "crank"), 2.0);
            c.step(0.05);
            peak = Math.max(peak, Math.abs(c.probe(b.id(e, "load")).current()));
        }
        assertTrue(peak > 5e-3, "peak current " + peak);
    }

    @Test
    void gearTrainReproducesToothRatios() {
        Exhibit e = Exhibits.gearTrain();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertEquals(4, c.bodies().size(), "frame + three gear shafts");
        for (int i = 0; i < 40; i++) {
            c.setCrankTorque(b.id(e, "crank"), 3.0);
            c.step(0.05);
        }
        double wIn = c.probe(b.id(e, "input")).angularVelocity();
        double wMid = c.probe(b.id(e, "middle")).angularVelocity();
        double wOut = c.probe(b.id(e, "output")).angularVelocity();
        assertTrue(wIn > 1, "input turning: " + wIn);
        assertEquals(-wIn / 3, wMid, 1e-3 * Math.abs(wIn));
        assertEquals(wIn / 5, wOut, 1e-3 * Math.abs(wIn));
    }

    @Test
    void capacitorDischargeMakesWireGlowThenCool() {
        Exhibit e = Exhibits.capacitorGlow();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long wire = b.id(e, "wire");
        Probe cap = c.probe(b.id(e, "capacitor"));
        assertEquals(75, Math.abs(cap.voltage()), 0.5, "pre-charged");
        run(c, 0.5);
        assertEquals(75, Math.abs(c.probe(b.id(e, "capacitor")).voltage()), 0.5, "holds charge with switch open");
        c.toggleSwitch(b.id(e, "switch"));
        double peak = 0;
        for (int i = 0; i < 10; i++) {
            c.step(0.05);
            peak = Math.max(peak, c.temperature(wire));
        }
        assertTrue(peak > 900 && peak < 1600, "should glow but not melt, peak " + peak + " K");
        assertTrue(Math.abs(c.probe(b.id(e, "capacitor")).voltage()) < 1, "capacitor discharged");
        run(c, 30);
        assertTrue(c.temperature(wire) < 500, "cooled by radiation/convection: " + c.temperature(wire));
    }

    @Test
    void thinWireBlowsLikeAFuse() {
        Exhibit e = Exhibits.fuse();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        c.toggleSwitch(b.id(e, "switch"));
        boolean blown = false;
        for (int i = 0; i < 40 && !blown; i++) blown = c.step(0.05).contains(b.id(e, "wire"));
        assertTrue(blown, "0.1 mm nichrome should exceed its melting point");
    }

    @Test
    void copperConductsHeatFarFasterThanStainless() {
        Exhibit e = Exhibits.conductionRace();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 240);
        double cu = c.temperature(b.id(e, "copperEnd")) - AMBIENT;
        double ss = c.temperature(b.id(e, "steelEnd")) - AMBIENT;
        assertTrue(cu > 20, "copper far end should warm noticeably within 4 min: ΔT=" + cu);
        assertTrue(cu > 10 * ss, "copper ΔT " + cu + " vs stainless ΔT " + ss);
    }

    @Test
    void ballBearingFlywheelOutlastsBushing() {
        Exhibit e = Exhibits.flywheels();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertEquals(60, c.probe(b.id(e, "ball")).angularVelocity(), 1e-9);
        run(c, 60);
        double ball = c.probe(b.id(e, "ball")).angularVelocity();
        double plain = c.probe(b.id(e, "plain")).angularVelocity();
        assertTrue(ball > 50, "ball bearing barely slows: " + ball);
        assertTrue(plain < 5, "bushing nearly stopped: " + plain);
        assertTrue(c.temperature(b.id(e, "plainBearing")) > AMBIENT + 0.5, "bushing warmed by friction");
    }
}
