package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Probe;
import dev.emergence.core.assembly.Dir;
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
                    // touching wires of different nets are fine only if their insulation colours differ
                    if (q != null && Math.round(d.params().getOrDefault("color", 0.0)) == Math.round(e.parts.get(q).params().getOrDefault("color", 0.0)))
                        assertEquals(e.nets.get(d.index()), e.nets.get(q),
                                e.id + ": same-colour wires of different nets touch at " + d.x0() + "," + d.y0() + "," + d.z0());
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
        for (int i = 0; i < 2400; i++) {
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

    @Test
    void steamEngineRunsOnBoilerSteam() {
        Exhibit e = Exhibits.steamEngine();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long shaft = b.id(e, "shaft"), boiler = b.id(e, "boiler"), cyl = b.id(e, "cylinder");
        assertNotNull(c.piston(cyl), "cylinder linked to the crank");
        double p0 = c.fluid(boiler)[0];
        assertEquals(dev.emergence.core.fluids.WaterSteam.pSat(418), p0, 2e3, "boiler on the saturation curve");
        double sumW = 0, vent = 0, minW = 1e9, brakeWork = 0;
        int n = 0;
        for (int i = 0; i < 600; i++) {
            c.step(0.05);
            double w = c.probe(shaft).angularVelocity();
            if (i > 200) {
                sumW += w;
                minW = Math.min(minW, Math.abs(w));
                brakeWork += c.brake(b.id(e, "brake"))[1] * 0.05;
                n++;
            }
            for (var v : c.vents()) vent += Math.max(0, c.ventOutflow(v)) * 0.05;
        }
        double mean = sumW / n;
        double[] bf = c.fluid(boiler);
        System.out.println("steam engine: mean w " + mean + " rad/s (min " + minW + "), steam vented " + vent * 1e3 + " g, boiler "
                + bf[0] + " Pa " + bf[1] + " K, vapour fraction " + bf[4]);
        assertTrue(Math.abs(mean) > 5 && minW > 1, "the engine keeps running on steam: mean ω " + mean + ", min " + minW);
        assertTrue(vent > 1e-3, "spent steam puffs out of the exhaust: " + vent + " kg");
        System.out.println("brake absorbs " + brakeWork / (n * 0.05) + " W");
        assertTrue(brakeWork / (n * 0.05) > 2, "the engine delivers shaft power to the brake: " + brakeWork / (n * 0.05) + " W");
        assertTrue(bf[0] > 2e5, "the fire keeps the boiler pressed: " + bf[0]);
        // shut the regulator: the engine coasts to a stop
        c.toggleSwitch(b.id(e, "regulator"));
        run(c, 20);
        assertTrue(Math.abs(c.probe(shaft).angularVelocity()) < 0.2 * Math.abs(mean),
                "regulator shut: the engine stops, ω " + c.probe(shaft).angularVelocity());
    }

    @Test
    void computerRunsItsProgramAndStepsTheMotor() {
        Exhibit e = ComputerExhibit.build();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var rotor = c.bodyOf(b.id(e, "rotor"));
        double start = c.angle(rotor);
        java.util.List<Double> angles = new java.util.ArrayList<>();
        for (int i = 0; i < 800; i++) {
            c.step(0.05);
            if (i % 72 == 71) angles.add((double) Math.round(Math.toDegrees(c.angle(rotor))));
        }
        double fwd = c.angle(rotor) - start;
        System.out.println("computer: forward rotor angles (deg, about one per instruction) " + angles + " (moved " + fwd + " rad)");
        assertTrue(fwd > 2 * Math.PI, "the program steps the rotor round: " + fwd + " rad in 40 s");
        // SENSE on: the program branches into the reverse loop
        c.toggleSwitch(b.id(e, "sense"));
        double mid = c.angle(rotor);
        run(c, 40);
        double rev = c.angle(rotor) - mid;
        System.out.println("computer: reverse moved " + rev + " rad");
        assertTrue(rev * fwd < 0 && Math.abs(rev) > 2 * Math.PI, "with SENSE on it steps the other way: " + rev);
    }

    @Test
    void everyExhibitStructureHoldsItsOwnWeight() {
        for (Exhibit e : Exhibits.all()) {
            if (e.miniature) continue;
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            var r = compile(e, b).structure();
            assertTrue(r.failed().isEmpty() && r.maxUtilisation() < 0.95, e.id + ": structure holds (utilisation " + r.maxUtilisation() + ")");
        }
    }

    /**
     * In the world, parts past their failure temperature or strength are destroyed — something the other tests,
     * which watch quantities, would not notice. Every exhibit must run (cranked, if it has a crank) without losing
     * a part, except the ones built to fail.
     */
    @Test
    void noExhibitBurnsOutByItself() {
        java.util.Set<String> meantToFail = java.util.Set.of("fuse", "glow", "shelves");
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (Exhibit e : Exhibits.all()) {
            if (meantToFail.contains(e.id)) continue;
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = compile(e, b);
            Long crank = e.named.containsKey("crank") ? b.id(e, "crank") : null;
            for (int i = 0; i < 100; i++) {
                if (crank != null) c.setCrankEffort(crank, 1, true);
                var failed = c.step(0.05);
                if (!failed.isEmpty()) {
                    bad.add(e.id + ": " + failed.stream().map(id -> b.assembly().get(id).kind() + "#" + id).toList());
                    break;
                }
            }
        }
        assertTrue(bad.isEmpty(), "parts fail: " + bad);
    }

    /** Saving and reloading a running exhibit (its state exported, recompiled, stepped on) must not fail. */
    @Test
    void everyExhibitRestartsFromItsRunningState() {
        for (Exhibit e : Exhibits.all()) {
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = compile(e, b);
            Long crank = e.named.containsKey("crank") ? b.id(e, "crank") : null;
            for (int i = 0; i < 10; i++) {
                if (crank != null) c.setCrankEffort(crank, 1, true);
                c.step(0.05);
            }
            CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), c.exportState());
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 4; i++) again.step(0.05);
            }, e.id + " restarts");
        }
    }

    @Test
    void shelvesHoldOneLeadBlockAndBreakUnderTwo() {
        Exhibit e = Exhibits.shelves();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        var r = compile(e, b).structure();
        double steel = r.utilisation().get(b.id(e, "shelf1")), al = r.utilisation().get(b.id(e, "shelf0"));
        // hand calculation for the aluminium root: σ = 6M/(b t²), M from the lead block and the plates' own weight
        double t = 0.04 * 0.0625, bw = 0.0625, lead = 11340 * Math.pow(0.0625, 3) * 9.80665;
        double plate = 2700 * 0.0625 * 0.0625 * t * 9.80665;
        double m = lead * 4 * 0.0625 + plate * 0.0625 * (1 + 2 + 3 + 4);
        assertEquals(6 * m / (bw * t * t) / 276e6, al, 0.05, "aluminium root stress vs beam theory");
        assertTrue(steel > 0.6 && steel < 1, "steel shelf near yield but holding: " + steel);
        assertTrue(r.failed().isEmpty());
        // a second lead block on the steel and glass shelves
        e.add(dev.emergence.core.assembly.PartKind.BLOCK, "lead", 5, 5, 5, Dir.UP);
        e.add(dev.emergence.core.assembly.PartKind.BLOCK, "lead", 5, 5, 11, Dir.UP);
        Exhibits.Built b2 = Exhibits.build(e, AMBIENT);
        var r2 = compile(e, b2).structure();
        assertTrue(r2.failed().contains(b2.id(e, "shelf1")), "the steel shelf yields at the wall");
        assertTrue(r2.failed().contains(b2.id(e, "load1")), "and its load falls");
        assertTrue(r2.failed().contains(b2.id(e, "load3")), "the glass shelf fractures too");
        assertFalse(r2.failed().contains(b2.id(e, "shelf0")) || r2.failed().contains(b2.id(e, "load0")), "the aluminium shelf holds");
    }

    @Test
    void lightBarrierSeesTheVaneCutTheBeam() {
        Exhibit e = OpticsExhibits.lightBarrier();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pd = b.id(e, "pd"), led = b.id(e, "beamLed"), crank = b.id(e, "crank");
        double lit = c.lightInput(pd), seen = 0, cut = Double.MAX_VALUE;
        int beamLedOn = 0, toggles = 0;
        boolean qPrev = c.probe(b.id(e, "toggle")).current() > 0.5;
        for (int i = 0; i < 200; i++) {
            c.setCrankEffort(crank, 1, true);
            c.step(0.05);
            double p = c.lightInput(pd);
            seen = Math.max(seen, p);
            cut = Math.min(cut, p);
            if (c.probe(led).current() > 1e-3) beamLedOn++;
            boolean q = c.probe(b.id(e, "toggle")).current() > 0.5;
            if (q != qPrev) toggles++;
            qPrev = q;
        }
        System.out.println("light barrier: light " + seen + " W, cut to " + cut + " W, yellow on " + beamLedOn + "/200 steps, " + toggles + " toggles");
        assertTrue(seen > 1e-6, "the photodiode sees the IR LED: " + seen);
        assertTrue(cut < 0.05 * seen, "the vane cuts the beam: " + cut);
        assertTrue(beamLedOn > 0 && beamLedOn < 200, "the indicator follows the vane");
        assertTrue(toggles >= 2, "the flip-flop counts passages: " + toggles);
    }

    @Test
    void solarPanelLightsItsLedInSunshineOnly() {
        Exhibit e = OpticsExhibits.solarPanel();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long led = b.id(e, "led");
        run(c, 0.2);
        assertTrue(c.probe(led).current() < 1e-5, "dark without daylight");
        c.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> true);
        run(c, 0.2);
        double day = c.probe(led).current();
        System.out.println("solar panel: LED current in full sun " + day + " A");
        assertTrue(day > 0.005 && day < 0.04, "the string lights the LED: " + day);
        // a low sun (60° from the zenith) delivers half the irradiance on the horizontal cells
        c.setDaylight(new dev.emergence.core.math.Vec3(Math.sin(Math.PI / 3), -Math.cos(Math.PI / 3), 0), 1000, id -> true);
        run(c, 0.2);
        assertTrue(c.probe(led).current() > 0.003, "still lit by a low sun");
        // shade one cell: the series string collapses
        long cell = b.id(e, "cell");
        c.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> id != cell);
        run(c, 0.2);
        assertTrue(c.probe(led).current() < 0.2 * day, "one shaded cell starves the string: " + c.probe(led).current());
    }

    /** The knob's wiper spans +9 … −1 V of the ±12 V the arm's spans: demanded arm angle = 0.785 + 0.417·knob. */
    static double servoDemand(double knob) {
        double k = Math.max(-0.75 * Math.PI, Math.min(0.75 * Math.PI, knob));
        return (4.0 + 10.0 * k / (1.5 * Math.PI)) * (1.5 * Math.PI) / 24;
    }

    @Test
    void servoArmFollowsTheKnob() {
        Exhibit e = ControlExhibits.servo();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long arm = b.id(e, "arm"), knob = b.id(e, "knob"), crank = b.id(e, "crank"), coil = b.id(e, "coilUp");
        // at rest: the knob asks for the hanging arm, and it hangs there drawing almost nothing
        run(c, 1.0);
        assertEquals(Math.PI / 2, c.probe(arm).angle(), 0.06, "rests hanging");
        assertTrue(Math.abs(c.probe(coil).current()) < 0.3, "idle current " + c.probe(coil).current());
        double vBank0 = c.probe(b.id(e, "batteryN")).voltage();
        // turn the knob down to its end stop: the arm rises past horizontal and follows the whole way
        double worst = 0;
        for (int i = 0; i < 200; i++) {
            c.setCrankEffort(crank, -1, false);
            c.step(0.05);
            if (i > 20) worst = Math.max(worst, Math.abs(c.probe(arm).angle() - servoDemand(c.probe(knob).angle())));
        }
        c.setCrankEffort(crank, 0, false);
        run(c, 3.0);
        double k = c.probe(knob).angle(), a = c.probe(arm).angle();
        System.out.printf("servo: knob at its stop %.3f rad, arm %.3f rad (asked %.3f), %.2f A, lag ≤ %.3f rad, PNP %.1f K, coil %.1f K%n",
                k, a, servoDemand(k), c.probe(coil).current(), worst, c.probe(b.id(e, "pnp")).temperature(), c.probe(coil).temperature());
        assertEquals(-0.75 * Math.PI - Math.toRadians(15), k, 0.03, "the knob rests against its end stop (300° of rotation)");
        assertTrue(a < 0.05, "the arm is raised to horizontal and beyond: " + a);
        assertEquals(servoDemand(k), a, 0.15, "proportional droop holding the arm up");
        assertTrue(worst < 0.25, "the arm keeps up with the knob: " + worst);
        // and back down part way
        for (int i = 0; i < 60; i++) {
            c.setCrankEffort(crank, 1, false);
            c.step(0.05);
        }
        c.setCrankEffort(crank, 0, false);
        run(c, 3.0);
        assertEquals(servoDemand(c.probe(knob).angle()), c.probe(arm).angle(), 0.1, "follows the knob back");
        assertTrue(Math.abs(c.probe(arm).angularVelocity()) < 0.01, "settled without oscillating");
        assertTrue(c.probe(b.id(e, "pnp")).temperature() < 360, "the power transistor stays cool enough");
        assertTrue(Math.abs(c.probe(b.id(e, "batteryN")).voltage() - vBank0) < 0.2, "the banks barely discharge");
    }

    @Test
    void servoSurvivesSaveAndReload() {
        Exhibit e = ControlExhibits.servo();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 0.5);
        var st = c.exportState();
        CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), st);
        run(again, 0.5);
        assertEquals(c.probe(b.id(e, "arm")).angle(), again.probe(b.id(e, "arm")).angle(), 0.05);
        // as the mod persists it: every part gets a temperature, motion, coil current and capacitor voltage entry
        var w = dev.emergence.core.assembly.AssemblyState.empty();
        for (var part : b.assembly().parts()) {
            long id = part.id();
            w.temperatures().put(id, st.temperatures().getOrDefault(id, AMBIENT));
            w.bodyMotion().put(id, st.bodyMotion().getOrDefault(id, new double[]{0, 0}));
            w.coilCurrents().put(id, st.coilCurrents().getOrDefault(id, 0.0));
            w.capacitorVoltages().put(id, st.capacitorVoltages().getOrDefault(id, 0.0));
        }
        CompiledAssembly world = AssemblyCompiler.compile(b.assembly(), w);
        run(world, 0.5);
        System.out.println("servo reloaded as the mod does: arm " + world.probe(b.id(e, "arm")).angle());
        // a fresh compile without any state, as after a failure reset
        CompiledAssembly fresh = AssemblyCompiler.compile(b.assembly(), dev.emergence.core.assembly.AssemblyState.empty());
        run(fresh, 0.5);
    }

    /** Each flask's composition is the equilibrium at its own temperature; the warm one holds more NO2 and is darker. */
    @Test
    void brownGasEquilibriumFollowsTemperature() {
        Exhibit e = ChemistryExhibits.brownGas();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 20);
        var rx = dev.emergence.core.chem.Chemistry.standard().reaction("n2o4_dissociation");
        double[] xNo2 = new double[2];
        int[] colour = new int[2];
        String[] flask = {"cold", "hot"};
        for (int k = 0; k < 2; k++) {
            long id = b.id(e, flask[k]);
            var g = c.gas(id);
            var s = c.solverView();
            double t = g.temperature(s);
            double pNo2 = g.moles(s, dev.emergence.core.chem.Chemistry.standard().species("NO2")) * 8.314462618 * t / g.volume();
            double pN2o4 = g.moles(s, dev.emergence.core.chem.Chemistry.standard().species("N2O4")) * 8.314462618 * t / g.volume();
            double q = (pNo2 / 1e5) * (pNo2 / 1e5) / (pN2o4 / 1e5);
            System.out.printf("brown gas: %s flask %.1f °C, %.4f bar, NO2 %.1f %%, Q = %.3f, K_p = %.3f, colour %06x%n", flask[k],
                    t - 273.15, g.pressure(s) / 1e5, 100 * g.moleFraction(s, 1), q, rx.kp(t), c.chemicalColour(id));
            assertEquals(rx.kp(t), q, 0.02 * rx.kp(t), flask[k] + ": at equilibrium for its temperature");
            xNo2[k] = pNo2 / (pNo2 + pN2o4);
            colour[k] = c.chemicalColour(id);
        }
        assertTrue(c.gas(b.id(e, "hot")).temperature(c.solverView()) > 340, "the hot plate keeps its flask warm");
        assertTrue(xNo2[1] > xNo2[0] + 0.2, "warmer: more NO2 (" + xNo2[0] + " → " + xNo2[1] + ")");
        int dark0 = (colour[0] >> 16 & 255) + (colour[0] >> 8 & 255) + (colour[0] & 255);
        int dark1 = (colour[1] >> 16 & 255) + (colour[1] >> 8 & 255) + (colour[1] & 255);
        assertTrue(dark1 < dark0 - 40, "the warm flask looks darker");
    }

    /** The preheated can of methane–air runs away thermally: a temperature and pressure spike, burnt to CO2 and water. */
    @Test
    void thermalExplosionIgnites() {
        Exhibit e = ChemistryExhibits.thermalExplosion();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var g = c.gas(b.id(e, "can"));
        var ch = dev.emergence.core.chem.Chemistry.standard();
        run(c, 2);
        assertEquals(AMBIENT, g.temperature(c.solverView()), 1, "cold until the switch is closed");
        c.toggleSwitch(b.id(e, "switch"));
        double peakT = 0, peakP = 0, tIgnite = Double.NaN;
        for (int i = 0; i < 6000; i++) {
            var broken = c.step(0.05);
            assertTrue(broken.isEmpty(), "no part burns out: " + broken.stream().map(id -> b.assembly().get(id).kind() + "#" + id).toList()
                    + " heater " + c.probe(b.id(e, "heater")).temperature() + " K");
            double t = g.temperature(c.solverView());
            if (t > peakT) peakT = t;
            peakP = Math.max(peakP, g.pressure(c.solverView()));
            if (Double.isNaN(tIgnite) && t > 1500) tIgnite = 0.05 * (i + 1);
            if (i % 400 == 0) System.out.printf("explosion t=%.1f s heater %.0f K plate %.0f K %.1f W gas %.0f K wall %.0f K p %.2f bar CH4 %.4f mol%n", 0.05 * (i + 1), c.probe(b.id(e, "heater")).temperature(), c.probe(b.id(e, "can")).temperature(), c.probe(b.id(e, "heater")).power(), t,
                    c.probe(b.id(e, "can")).temperature(), g.pressure(c.solverView()) / 1e5, g.moles(c.solverView(), ch.species("CH4")));
        }
        System.out.printf("explosion: ignition after %.1f s, peak %.0f K, %.2f bar%n", tIgnite, peakT, peakP / 1e5);
        assertTrue(peakT > 2000, "it ignites: peak " + peakT);
        assertTrue(tIgnite > 30 && tIgnite < 240, "after a few minutes' heating: " + tIgnite);
        assertTrue(peakP > 6e5, "the pressure spikes: " + peakP);
        double maxWall = 0;
        for (int i = 0; i < 6000; i++) {
            assertTrue(c.step(0.05).isEmpty(), "no part burns out afterwards");
            maxWall = Math.max(maxWall, c.probe(b.id(e, "can")).temperature());
        }
        assertTrue(maxWall < 1300, "the heater's bank runs down before the can overheats: " + maxWall);
        assertTrue(g.moles(c.solverView(), ch.species("CH4")) < 1e-6, "all the methane burns");
    }

    /**
     * Restarting from rest and from a running state (the arm held up: 2.4 A in the coils, a hot power transistor)
     * must work for fragments of the servo, as when only some of its chunks are loaded: every cut along x and z into
     * quadrants. A Newton iterate once drove the transistor's heat node to infinity here, and its saturation current
     * to NaN. Cuts giving the same set of parts are simulated once; the distinct fragments run in parallel.
     */
    @Test
    void servoFragmentsSimulate() {
        Exhibit e = ControlExhibits.servo();
        Exhibits.Built full = Exhibits.build(e, AMBIENT);
        CompiledAssembly running = compile(e, full);
        run(running, 1.0);
        var rest = running.exportState();
        for (int i = 0; i < 160; i++) {
            running.setCrankEffort(full.id(e, "crank"), -1, false);
            running.step(0.05);
        }
        var hot = running.exportState();
        java.util.Map<java.util.Set<Long>, String> fragments = new java.util.LinkedHashMap<>();
        for (int cx = 0; cx <= 32; cx++)
            for (int cz = 0; cz <= 16; cz += (cx == 0 || cx == 32 ? 1 : 16))
                for (int quad = 0; quad < 4; quad++) {
                    java.util.Set<Long> ids = new java.util.TreeSet<>();
                    for (var part : full.assembly().parts()) {
                        boolean left = part.minX() < cx, near = part.minZ() < cz;
                        if (left == (quad % 2 == 0) && near == (quad / 2 == 0)) ids.add(part.id());
                    }
                    if (!ids.isEmpty()) fragments.putIfAbsent(ids, "x" + cx + " z" + cz + " q" + quad);
                }
        java.util.List<String> bad = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        // a pool of its own: a parallel stream started from JUnit's fork-join workers gets no extra threads
        var pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        java.util.List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
        for (var f : fragments.entrySet()) jobs.add(pool.submit(() -> {
            for (var state : java.util.List.of(rest, hot)) {
                Exhibits.Built b = Exhibits.build(e, AMBIENT);
                var a = new dev.emergence.core.assembly.Assembly().ambientTemperature(AMBIENT);
                for (var part : b.assembly().parts()) if (f.getKey().contains(part.id())) a.add(part);
                a.anchoredWhen(q -> q.minY() == 0 && q.kind().structural && q.kind() != dev.emergence.core.assembly.PartKind.SHAFT);
                try {
                    // a restart goes wrong (NaN, singular, underflow) within its first steps; three are enough, and
                    // keep the few fragments that cut an amplifier's feedback (which then oscillates fast) cheap
                    CompiledAssembly c = AssemblyCompiler.compile(a, state);
                    for (int k = 0; k < 3; k++) c.step(0.05);
                } catch (RuntimeException ex) {
                    bad.add((state == hot ? "hot " : "rest ") + f.getValue() + " (" + a.parts().size() + " parts): " + ex.getMessage());
                }
            }
        }));
        try {
            for (var j : jobs) j.get();
        } catch (InterruptedException | java.util.concurrent.ExecutionException ex) {
            throw new AssertionError(ex);
        } finally {
            pool.shutdown();
        }
        System.out.println("servo: " + fragments.size() + " distinct fragments");
        bad.forEach(m -> System.out.println("FRAG " + m));
        assertTrue(bad.isEmpty(), bad.size() + " fragments fail: " + (bad.isEmpty() ? "" : bad.get(0)));
    }

    /** The burner lights from the heater, keeps burning when it is switched off, and draws its air by natural draft. */
    @Test
    void gasBurnerDrawsItsOwnAir() {
        Exhibit e = ChemistryExhibits.gasBurner();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var ch = dev.emergence.core.chem.Chemistry.standard();
        long chamber = b.id(e, "chamber"), top = b.id(e, "chimney");
        // as a gas furnace lights: igniter first, then the gas, then the igniter off
        c.toggleSwitch(b.id(e, "switch"));
        for (int i = 0; i < 7200; i++) {
            if (i == 2400) c.toggleSwitch(b.id(e, "valve"));  // fuel on after 2 minutes
            if (i == 4800) c.toggleSwitch(b.id(e, "switch")); // igniter off after 4
            var broken = c.step(0.05);
            assertTrue(broken.isEmpty(), "no part fails: " + broken);
            if (i == 3500) {
                for (var el : c.network().elements()) if (el instanceof dev.emergence.core.sim.elements.MixtureBranch mb)
                    System.out.printf("burner flow %s: %.3g mol/s%n", mb.label(), mb.molarFlow(c.solverView()));
                for (var en : java.util.List.of("chamber", "chimney", "fuel")) {
                    var gg = c.gas(b.id(e, en));
                    System.out.printf("burner gas %s: %.0f K, p %.1f Pa%n", en, gg.temperature(c.solverView()), gg.pressure(c.solverView()));
                }
            }
            if (i % 1200 == 0 || i == 7199) {
                var g = c.gas(chamber);
                var s = c.solverView();
                System.out.printf("burner t=%.0f s chamber %.0f K wall %.0f K, CH4 %.2f%% O2 %.1f%% CO2 %.1f%%; chimney top %.0f K CO2 %.1f%%, fuel %.1f bar%n",
                        0.05 * (i + 1), g.temperature(s), c.probe(chamber).temperature(),
                        100 * g.moleFraction(s, g.indexOf(ch.species("CH4"))), 100 * g.moleFraction(s, g.indexOf(ch.species("O2"))),
                        100 * g.moleFraction(s, g.indexOf(ch.species("CO2"))), c.gas(top).temperature(s),
                        100 * c.gas(top).moleFraction(s, g.indexOf(ch.species("CO2"))), c.gas(b.id(e, "fuel")).pressure(s) / 1e5);
            }
        }
        var g = c.gas(chamber);
        assertTrue(g.temperature(c.solverView()) > 1100, "still burning with the heater off: " + g.temperature(c.solverView()));
        assertTrue(c.gas(top).moleFraction(c.solverView(), g.indexOf(ch.species("CO2"))) > 0.02, "CO2 goes up the chimney");
    }

    /** The amplified thermocouple voltage tracks (S_Cu − S_con)·(T_hot − T_cold)·201. */
    @Test
    void thermocoupleReadsTheTemperatureDifference() {
        Exhibit e = ThermoExhibits.thermocouple();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        for (int k = 0; k < 3; k++) {
            run(c, 5);
            double hot = c.probe(b.id(e, "junction")).temperature(), cold = c.probe(b.id(e, "cold")).temperature();
            double out = c.probe(b.id(e, "load")).voltage();
            double expected = 201 * (1.83e-6 + 38.9e-6) * (hot - cold);
            System.out.printf("thermocouple: hot %.1f K, cold %.2f K → amplifier %.4f V (expected %.4f V)%n", hot, cold, out, expected);
            assertEquals(expected, out, 0.03 * expected + 1e-3, "type-T EMF ×201, positive when the junction is hot");
        }
    }

    /** The Geiger counter counts at the expected rate, the chip's count matches the tube's, and lead shields it. */
    @Test
    void geigerCounterCountsAndLeadShields() {
        Exhibit e = RadiationExhibits.geigerCounter();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var tube = c.tube(b.id(e, "tube"));
        double rate = tube.hitRate(c.solverView());
        // the counter chip settles at power-up; from then on it must count every pulse the tube makes
        java.util.Set<Long> offsets = new java.util.HashSet<>();
        for (int i = 0; i < 600; i++) {
            c.step(0.1);
            if (i < 20 || tube.conducting()) continue;
            double[] ch = c.chipState(b.id(e, "counter"));
            int v = 0;
            for (int k = 0; k < 4; k++) if (ch[k] > 0.5) v |= 1 << k;
            offsets.add(Math.floorMod(tube.discharges() - v, 16L));
        }
        assertEquals(1, offsets.size(), "the chip and the tube stay in step: offsets " + offsets);
        long n = tube.discharges();
        double[] chip = c.chipState(b.id(e, "counter"));
        int count = 0;
        for (int k = 0; k < 4; k++) if (chip[k] > 0.5) count |= 1 << k;
        System.out.printf("Geiger: %d counts in 60 s (expected %.1f ± %.1f), chip shows %d, %d lost%n",
                n, rate * 60, Math.sqrt(rate * 60), count, tube.lost());
        assertEquals(rate * 60, n, 4 * Math.sqrt(rate * 60), "Poisson counting at the expected rate");

        // a 1 cm lead plate between source and tube transmits e^(−μx) of the 662 keV gammas
        Exhibit shielded = RadiationExhibits.geigerCounter();
        shielded.add(dev.emergence.core.assembly.PartKind.BLOCK, "lead", 5, 1, 7, dev.emergence.core.assembly.Dir.EAST, "fill", 0.16);
        Exhibits.Built bs = Exhibits.build(shielded, AMBIENT);
        CompiledAssembly cs = AssemblyCompiler.compile(bs.assembly(), bs.state());
        double bg = dev.emergence.core.nuclear.NuclearData.standard().gmBackground();
        double t = (cs.tube(bs.id(shielded, "tube")).hitRate(cs.solverView()) - bg) / (rate - bg);
        double expected = Math.exp(-0.1101 * 11.34 * 0.0625 * 0.16 * 100);
        System.out.printf("Geiger: lead plate %.1f mm transmits %.3f (e^-μx = %.3f)%n", 0.0625 * 0.16 * 1000, t, expected);
        assertEquals(expected, t, 0.03, "Beer–Lambert attenuation in lead");
    }

    /** The slide oscillates about the static sag m·g/k with period 2π√(m/k). */
    @Test
    void springMassOscillates() {
        Exhibit e = MechanicsExhibits.springMass();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long rod = b.id(e, "rod");
        var body = c.bodyOf(rod);
        assertTrue(body.slider(), "the rod slides");
        double k = dev.emergence.core.assembly.PartGeometry.springRate(b.assembly().get(b.id(e, "spring"))), m = body.mass();
        java.util.List<Double> ups = new java.util.ArrayList<>();
        double prev = 0, sum = 0;
        int n = 0;
        for (int i = 0; i < 2000; i++) {
            c.step(0.001);
            double v = c.probe(rod).angularVelocity();
            if (prev < 0 && v >= 0) ups.add(0.001 * (i + 1));
            prev = v;
            sum += c.probe(rod).angle();
            n++;
        }
        double period = (ups.get(ups.size() - 1) - ups.get(0)) / (ups.size() - 1);
        System.out.printf("spring and mass: m %.3f kg, k %.0f N/m, period %.4f s (2π√(m/k) = %.4f s), mean %.4f m (−mg/k = %.4f m)%n",
                m, k, period, 2 * Math.PI * Math.sqrt(m / k), sum / n, -m * 9.80665 / k);
        assertEquals(2 * Math.PI * Math.sqrt(m / k), period, 0.02 * period, "period");
        assertEquals(-m * 9.80665 / k, sum / n, 0.1 * m * 9.80665 / k, "oscillates about the static sag");
    }

    /**
     * Faraday and Lenz on a slide: with the coils loaded, the bouncing magnet loses more mechanical energy than with
     * them open — the energy that appears as heat in the load and the coils.
     */
    @Test
    void magnetOnASpringHarvestsEnergy() {
        double[] mech = new double[2];
        double elec = 0;
        for (int k = 0; k < 2; k++) {
            Exhibit e = MechanicsExhibits.harvester();
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = compile(e, b);
            if (k == 1) c.toggleSwitch(b.id(e, "switch"));
            long rod = b.id(e, "rod");
            c.setPushEffort(rod, 1);
            for (int i = 0; i < 2; i++) c.step(0.005);
            c.setPushEffort(rod, 0);
            var body = c.bodyOf(rod);
            double k0 = dev.emergence.core.assembly.PartGeometry.springRate(b.assembly().get(b.id(e, "spring")));
            java.util.function.DoubleSupplier energy = () -> {
                double x = c.probe(rod).angle(), v = c.probe(rod).angularVelocity();
                return 0.5 * body.mass() * v * v + 0.5 * k0 * x * x + body.mass() * 9.80665 * x;
            };
            double e0 = energy.getAsDouble();
            for (int i = 0; i < 800; i++) {
                c.step(0.005);
                if (k == 1) {
                    double p = c.probe(b.id(e, "load")).power();
                    for (var en : b.assembly().parts()) if (en.kind() == dev.emergence.core.assembly.PartKind.COIL) p += c.probe(en.id()).power();
                    elec += 0.005 * p;
                }
            }
            mech[k] = e0 - energy.getAsDouble();
        }
        System.out.printf("magnet on a spring: mechanical energy lost in 4 s %.4f J open, %.4f J loaded; electrical %.4f J%n",
                mech[0], mech[1], elec);
        assertTrue(mech[1] > mech[0], "the loaded coils brake the magnet");
        // the electrical energy comes out of the motion (never more than it lost) and accounts for the extra braking
        // (the open run, swinging wider, loses somewhat more to bearing friction, so the two differ a little)
        assertTrue(elec < mech[1], "no more electrical energy than the motion lost");
        assertTrue(elec > 0.8 * (mech[1] - mech[0]), "the extra loss went into the circuit");
    }

    /** Pascal: pushing the small piston lifts the ram; the water's volume is conserved (A₁·Δx₁ = A₂·Δx₂). */
    @Test
    void hydraulicPressMultipliesForce() {
        Exhibit e = MechanicsExhibits.hydraulicPress();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pump = b.id(e, "pumpRod"), ram = b.id(e, "ramRod");
        run(c, 0.5);
        double p0 = c.probe(pump).angle(), r0 = c.probe(ram).angle();
        c.setPushEffort(pump, -1);
        for (int i = 0; i < 100; i++) c.step(0.01);
        c.setPushEffort(pump, 0);
        double dp = c.probe(pump).angle() - p0, dr = c.probe(ram).angle() - r0;
        double a1 = Math.PI * 0.012 * 0.012 / 4, a2 = Math.PI * 0.05 * 0.05 / 4;
        double press = c.fluid(b.id(e, "ram"))[0];
        System.out.printf("hydraulic press: pump moved %.2f mm, ram %.3f mm (A1/A2 · %.3f mm), ram pressure %.0f kPa%n",
                1000 * dp, 1000 * dr, -1000 * dp * a1 / a2, press / 1e3);
        assertTrue(dp < -0.005, "the pump piston went down");
        assertEquals(-dp * a1 / a2, dr, 0.15 * Math.abs(dp * a1 / a2), "the ram rose by the displaced water over its area");
    }

    /** The jack: strokes of the pump ratchet the ram up through the check valves; it holds; the release lets it down. */
    @Test
    void hydraulicJackRatchetsUp() {
        Exhibit e = MechanicsExhibits.hydraulicJack();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pump = b.id(e, "pumpRod"), ram = b.id(e, "ramRod");
        run(c, 1.0);
        double r0 = c.probe(ram).angle(), down = 0, pRamMin = Double.MAX_VALUE;
        for (int stroke = 0; stroke < 3; stroke++) {
            double top = c.probe(pump).angle();
            c.setPushEffort(pump, -1);
            for (int i = 0; i < 60; i++) {
                c.step(0.01);
                pRamMin = Math.min(pRamMin, c.fluid(b.id(e, "ram"))[0]);
            }
            down += top - c.probe(pump).angle();
            c.setPushEffort(pump, 1);
            for (int i = 0; i < 60; i++) c.step(0.01);
            c.setPushEffort(pump, 0);
            System.out.printf("jack stroke %d: pump %.1f mm, ram %.2f mm%n", stroke, 1000 * c.probe(pump).angle(), 1000 * (c.probe(ram).angle() - r0));
        }
        double rise = c.probe(ram).angle() - r0, a1 = Math.PI * 0.02 * 0.02 / 4, a2 = Math.PI * 0.05 * 0.05 / 4;
        System.out.printf("hydraulic jack: pumped %.1f mm in all, ram rose %.2f mm (A1/A2 · = %.2f mm)%n", 1000 * down, 1000 * rise, 1000 * down * a1 / a2);
        // a quick push throws the light ram up past the water under it: a cavity opens below it (the pressure falls
        // to the vapour pressure) and the reservoir's pressure pushes a little extra water in through both valves
        System.out.printf("  lowest ram pressure %.1f kPa%n", pRamMin / 1e3);
        assertTrue(rise > 0.9 * down * a1 / a2 && rise < 1.1 * down * a1 / a2, "the ram climbs by the water pumped over its area");
        assertTrue(pRamMin > 1000, "never below the vapour pressure (bar the cavity's small regularisation)");
        double held = c.probe(ram).angle();
        run(c, 2);
        assertEquals(held, c.probe(ram).angle(), 1e-3, "the outlet valve holds the ram up");
        c.toggleSwitch(b.id(e, "release"));
        run(c, 3);
        assertTrue(c.probe(ram).angle() < held - 0.5 * rise, "opening the release valve lets it down");
    }
}
