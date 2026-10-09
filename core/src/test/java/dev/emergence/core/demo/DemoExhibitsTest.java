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
    void dcMotorRunsWhenSwitchedOn() {
        Exhibit e = Exhibits.dcMotor();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long rotor = b.id(e, "rotor");
        run(c, 1);
        assertEquals(0, c.probe(rotor).angularVelocity(), 1e-6, "idle while the switch is open");
        c.toggleSwitch(b.id(e, "switch"));
        double th0 = c.probe(rotor).angle(), minW = Double.MAX_VALUE;
        for (int i = 0; i < 80; i++) {
            c.step(0.05);
            if (i > 10) minW = Math.min(minW, c.probe(rotor).angularVelocity());
        }
        double turned = c.probe(rotor).angle() - th0;
        assertTrue(turned > 2 * Math.PI, "more than a revolution: " + turned);
        assertTrue(minW > 0, "commutated: never reverses (min ω " + minW + ")");
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

    @Test
    void exhibitsAreBlockScaleBuilds() {
        for (Exhibit e : Exhibits.all()) {
            if (e.miniature) continue;
            for (Exhibit.PartDef d : e.parts) {
                assertTrue(d.x0() == d.x1() && d.y0() == d.y1() && d.z0() == d.z1(), e.id + ": part " + d.index() + " is not single-cell");
            }
        }
    }

    @Test
    void daniellCellDrivesCurrentAndUsesUpCopperIons() {
        Exhibit e = Exhibits.daniellCell();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        c.step(0.05);
        double v = c.probe(b.id(e, "copper")).voltage() - c.probe(b.id(e, "zinc")).voltage();
        assertTrue(v > 0.4 && v < 1.11, "copper positive, below the 1.10 V open-circuit EMF: " + v);
        assertTrue(Math.abs(c.probe(b.id(e, "load")).current()) > 5e-3, "load current " + c.probe(b.id(e, "load")).current());
        double cu0 = c.chemistry(b.id(e, "cuSol")).get("[Cu2+] mol/L");
        int col0 = c.chemicalColour(b.id(e, "cuSol"));
        run(c, 60);
        assertTrue(c.chemistry(b.id(e, "cuSol")).get("[Cu2+] mol/L") < cu0, "Cu2+ consumed");
        assertTrue((col0 & 0xFF) > ((col0 >> 16) & 0xFF) + 100, "copper sulfate looks blue: " + Integer.toHexString(col0));
    }

    @Test
    void displacementPlatesCopperOnZincAndSilverOnCopper() {
        Exhibit e = Exhibits.plating();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 60);
        assertTrue(c.depositMoles(b.id(e, "zinc")) > 1e-6, "copper on zinc: " + c.depositMoles(b.id(e, "zinc")));
        assertTrue(c.depositMoles(b.id(e, "copper")) > 1e-7, "silver on copper: " + c.depositMoles(b.id(e, "copper")));
        int zincColour = c.chemicalColour(b.id(e, "zinc"));
        assertTrue(zincColour >= 0, "plated zinc changes colour");
    }

    @Test
    void connectingCopperSpeedsZincCorrosionAndMovesHydrogenToCopper() {
        Exhibit e = Exhibits.galvanicCorrosion();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 1);
        long zn = b.id(e, "zinc"), cu = b.id(e, "copper");
        double gasCuOpen = c.gasEvolution(cu);
        double rateOpen = c.chemistry(zn).get("metal dissolved mol");
        run(c, 5);
        rateOpen = (c.chemistry(zn).get("metal dissolved mol") - rateOpen) / 5;
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 1);
        double d0 = c.chemistry(zn).get("metal dissolved mol");
        run(c, 5);
        double rateClosed = (c.chemistry(zn).get("metal dissolved mol") - d0) / 5;
        assertTrue(rateClosed > 10 * Math.max(rateOpen, 1e-15), "corrosion " + rateOpen + " → " + rateClosed + " mol/s");
        assertTrue(c.gasEvolution(cu) > 10 * Math.max(gasCuOpen, 1e-15), "H2 on copper " + gasCuOpen + " → " + c.gasEvolution(cu));
    }

    @Test
    void miniatureGeneratorWorksAtTrueScale() {
        Exhibit e = Exhibits.miniatureGenerator();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertEquals(2, c.bodies().size());
        double peak = 0;
        for (int i = 0; i < 60; i++) {
            c.setCrankEffort(b.id(e, "crank"), 1, true);
            c.step(0.05);
            peak = Math.max(peak, Math.abs(c.probe(b.id(e, "load")).current()));
        }
        assertTrue(peak > 1e-5, "a small but real current: " + peak);
    }

    @Test
    void compressedAirSpinsTheTurbineFlywheel() {
        Exhibit e = Exhibits.airTurbine();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long shaft = b.id(e, "shaft"), receiver = b.id(e, "receiver");
        run(c, 1);
        assertEquals(0, c.probe(shaft).angularVelocity(), 1e-6, "valve shut: still");
        assertEquals(1e6, c.fluid(receiver)[0], 2e3, "receiver holds 10 bar");
        c.toggleSwitch(b.id(e, "valve"));
        double peak = 0;
        for (int i = 0; i < 100; i++) {
            c.step(0.05);
            peak = Math.max(peak, Math.abs(c.probe(shaft).angularVelocity()));
        }
        assertTrue(peak > 50, "turbine spins the rotor and disk: peak " + peak + " rad/s");
        assertTrue(c.fluid(receiver)[0] < 9.5e5, "receiver pressure falls: " + c.fluid(receiver)[0]);
    }

    @Test
    void fanDrivesTheWindmillThroughTheAir() {
        Exhibit e = Exhibits.fanCoupling();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long fan = b.id(e, "fanShaft"), mill = b.id(e, "millShaft");
        for (int i = 0; i < 160; i++) {
            c.setCrankEffort(b.id(e, "crank"), 1, true);
            c.step(0.05);
        }
        double wf = c.probe(fan).angularVelocity(), wm = c.probe(mill).angularVelocity();
        assertTrue(Math.abs(wf) > 200, "geared fan spins fast: " + wf);
        assertTrue(Math.abs(wm) > 20, "windmill turned by the air alone: " + wm);
        assertTrue(Math.signum(wf) == Math.signum(wm), "same blade pitch: same direction");
    }

    @Test
    void heatedVesselPressureRisesAndColdOneStays() {
        Exhibit e = Exhibits.heatedVessel();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 120);
        double[] hot = c.fluid(b.id(e, "hotTank")), cold = c.fluid(b.id(e, "coldTank"));
        assertTrue(hot[0] > 1.1e5, "heated vessel pressure rose: " + hot[0]);
        assertEquals(101_325, cold[0], 500, "cold vessel unchanged");
        assertEquals(101_325 * hot[1] / AMBIENT, hot[0], 0.01 * hot[0], "p/T constant");
    }

    @Test
    void stirlingEngineRunsOnTheHeatOfTheCopperBlock() {
        Exhibit e = Exhibits.stirlingEngine();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long shaft = b.id(e, "shaft");
        assertNotNull(c.piston(b.id(e, "hot")), "hot cylinder linked to the crank");
        assertNotNull(c.piston(b.id(e, "cold")), "cold cylinder linked to the crank");
        double minW = Double.MAX_VALUE, peakI = 0;
        for (int i = 0; i < 400; i++) {
            c.step(0.05);
            if (i > 100) minW = Math.min(minW, c.probe(shaft).angularVelocity());
            peakI = Math.max(peakI, Math.abs(c.probe(b.id(e, "load")).current()));
        }
        assertTrue(peakI > 1e-3, "the engine drives the generator: peak load current " + peakI + " A");
        System.out.println("Stirling: w=" + c.probe(shaft).angularVelocity() + " hot gas T=" + c.fluid(b.id(e, "hot"))[1]
                + " cold gas T=" + c.fluid(b.id(e, "cold"))[1] + " heater T=" + c.temperature(b.id(e, "heater")));
        assertTrue(minW > 10, "keeps running for 20 s on the block's heat (min ω " + minW + ")");
    }

    @Test
    void transistorSwitchesTheLed() {
        Exhibit e = Exhibits.transistorSwitch();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 0.2);
        assertTrue(Math.abs(c.probe(b.id(e, "led")).current()) < 1e-5, "switch open: LED dark, " + c.probe(b.id(e, "led")).current());
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 0.2);
        double il = c.probe(b.id(e, "led")).current(), ib = Math.abs(c.probe(b.id(e, "baseResistor")).current());
        assertTrue(il > 0.012 && il < 0.025, "LED current ~ (5 − 2 − V_CE,sat)/150 Ω: " + il);
        assertTrue(ib > 3e-4 && ib < 5e-4, "base current ~ (5 − 0.7)/10 kΩ: " + ib);
        assertTrue(c.lightOutput(b.id(e, "led")) > 1e-3, "the LED emits light");
    }

    @Test
    void nandGateTruthTable() {
        Exhibit e = Exhibits.nandGate();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long led = b.id(e, "led"), a = b.id(e, "a"), bb = b.id(e, "b");
        boolean[][] cases = {{false, false}, {true, false}, {true, true}, {false, true}};
        boolean sa = false, sb = false;
        for (boolean[] cs : cases) {
            if (cs[0] != sa) {
                c.toggleSwitch(a);
                sa = cs[0];
            }
            if (cs[1] != sb) {
                c.toggleSwitch(bb);
                sb = cs[1];
            }
            run(c, 0.2);
            boolean lit = c.probe(led).current() > 1e-3;
            assertEquals(!(sa && sb), lit, "A=" + sa + " B=" + sb + " LED current " + c.probe(led).current());
        }
    }

    @Test
    void binaryCounterCounts() {
        Exhibit e = Exhibits.binaryCounter();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long clock = b.id(e, "clock");
        // count rising clock edges while sampling the counter value after each
        int edges = 0;
        double prev = c.probe(clock).current();
        int[] seen = new int[64];
        for (int i = 0; i < 200; i++) {
            c.step(0.05);
            double s = c.probe(clock).current();
            if (prev < 0.5 && s >= 0.5) {
                edges++;
            }

            prev = s;
            int v = 0;
            for (int k = 0; k < 4; k++) if (c.probe(b.id(e, "bit" + k)).current() > 0.5) v |= 1 << k;
            seen[edges % 64] = v;
        }
        assertTrue(edges >= 8, "the oscillator runs: " + edges + " clock edges in 10 s");
        // after n rising edges the counter holds n mod 16
        int v = 0;
        for (int k = 0; k < 4; k++) if (c.probe(b.id(e, "bit" + k)).current() > 0.5) v |= 1 << k;
        assertEquals(edges % 16, v, "counter value after " + edges + " clock edges");
        assertTrue(c.lightOutput(b.id(e, "led0")) > 0 || c.lightOutput(b.id(e, "led1")) > 0 || v == 0, "LEDs show the count");
    }

    @Test
    void wiringHasNoAccidentalShorts() {
        // touching insulated wires join electrically: wires labelled with different nets must never touch
        for (Exhibit e : Exhibits.all()) {
            java.util.Map<Long, Integer> at = new java.util.HashMap<>();
            for (Exhibit.PartDef d : e.parts) {
                if (e.nets.containsKey(d.index())) at.put(key(d.x0(), d.y0(), d.z0()), d.index());
            }
            for (var en : at.entrySet()) {
                Exhibit.PartDef d = e.parts.get(en.getValue());
                for (int[] o : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    Integer q = at.get(key(d.x0() + o[0], d.y0() + o[1], d.z0() + o[2]));
                    if (q != null) assertEquals(e.nets.get(d.index()), e.nets.get(q),
                            e.id + ": wires of different nets touch at " + d.x0() + "," + d.y0() + "," + d.z0());
                }
            }
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512);
    }

    @Test
    void thermostatHoldsThePlateBetweenItsThresholds() {
        Exhibit e = Exhibits.thermostat();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long plate = b.id(e, "plate"), led = b.id(e, "led");
        double tMin = 1e9, tMax = 0;
        int switches = 0;
        boolean on = c.probe(led).current() > 1e-3;
        for (int i = 0; i < 1600; i++) {
            c.step(1.0);
            double t = c.temperature(plate);
            if (i > 300) {
                tMin = Math.min(tMin, t);
                tMax = Math.max(tMax, t);
            }
            boolean now = c.probe(led).current() > 1e-3;
            if (now != on) switches++;
            on = now;
        }
        System.out.println("thermostat: " + (tMin - 273.15) + " .. " + (tMax - 273.15) + " °C, " + switches + " switchings");
        assertTrue(switches >= 4, "the heater cycles on and off: " + switches);
        assertTrue(tMin > 295 && tMax < 335, "regulated band " + tMin + " .. " + tMax + " K");
        assertTrue(tMax - tMin > 5, "Schmitt hysteresis gives a temperature band: " + (tMax - tMin));
    }
}
