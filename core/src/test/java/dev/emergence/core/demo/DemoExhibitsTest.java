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

    /**
     * A turn of a body by hand, as a player gives a motor at rest (a two-segment commutator can stop with its brushes in
     * a gap, where no current flows) or a flywheel.
     */
    static void startByHand(CompiledAssembly c, long part) {
        c.setTurnEffort(part, 1, false);
        run(c, 0.3);
        c.setTurnEffort(part, 0, false);
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

    /**
     * Bridge rectifier: cranked, the generator's alternating EMF charges the capacitor one way only and the LED lights
     * steadily (a small ripple); let go, the capacitor keeps it lit a while.
     */
    @Test
    void rectifierLightsTheLedSteadily() {
        Exhibit e = Exhibits.rectifier();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long cap = b.id(e, "cap"), led = b.id(e, "led"), d1 = b.id(e, "d1");
        double vMin = 1e9, vMax = -1e9, iMin = 1e9, d1Rev = 0;
        c.setCrankEffort(b.id(e, "crank"), 1, false);
        for (int i = 0; i < 160; i++) {
            var broken = c.step(0.05);
            assertTrue(broken.isEmpty(), "nothing burns out: " + broken);
            if (i >= 100) {
                double v = c.probe(cap).voltage(), il = Math.abs(c.probe(led).current());
                vMin = Math.min(vMin, v);
                vMax = Math.max(vMax, v);
                iMin = Math.min(iMin, il);
                d1Rev = Math.min(d1Rev, c.probe(d1).current());
            }
        }
        double w = c.probe(b.id(e, "crank")).angularVelocity();
        c.setCrankEffort(b.id(e, "crank"), 0, false);
        run(c, 2);
        double after = Math.abs(c.probe(led).current());
        System.out.printf("rectifier: crank %.1f rad/s; capacitor %.2f–%.2f V; LED ≥ %.2f mA; D1 reverse ≥ %.2g A; 2 s after letting go %.2f mA%n",
                w, Math.abs(vMin), Math.abs(vMax), 1e3 * iMin, d1Rev, 1e3 * after);
        assertTrue(Math.min(Math.abs(vMin), Math.abs(vMax)) > 2.2, "a steady DC voltage across the capacitor");
        assertTrue(Math.signum(vMin) == Math.signum(vMax), "it never reverses");
        assertTrue(iMin > 1e-3, "the LED never goes out while cranked: " + iMin);
        assertTrue(after > 0.5e-3, "the capacitor keeps it lit after letting go: " + after);
    }

    /**
     * Boost converter: switching, the coil lifts the output above the 5 V supply until the four series LEDs (≈ 7.6 V)
     * light; with the switch held off, the output only follows the supply through the coil and diode and they stay dark.
     */
    @Test
    void boostConverterLightsLedsAboveItsSupply() {
        double[] vout = new double[2], led = new double[2];
        for (int m = 0; m < 2; m++) {
            Exhibit e = Exhibits.boost(m == 1);
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = compile(e, b);
            run(c, m == 1 ? 3 : 1);
            vout[m] = Math.abs(c.probe(b.id(e, "cout")).voltage());
            led[m] = Math.abs(c.probe(b.id(e, "led1")).current());
        }
        System.out.printf("boost: switch off %.2f V, LEDs %.3f mA; switching %.2f V, LEDs %.2f mA%n", vout[0], 1e3 * led[0], vout[1], 1e3 * led[1]);
        assertTrue(vout[0] < 5.3 && led[0] < 1e-4, "without switching the LEDs stay dark");
        assertTrue(vout[1] > 7.3, "switching lifts the output above the supply: " + vout[1]);
        assertTrue(led[1] > 2e-3, "and the LEDs light: " + led[1]);
    }

    /**
     * H-bridge: FORWARD spins the motor up one way; with both switches open the bridge shorts it and its own back-EMF
     * brakes it (decaying with the same time constant J·R/K² it spun up with); REVERSE then spins it the other way.
     */
    @Test
    void hBridgeReversesTheMotor() {
        Exhibit e = ControlExhibits.hBridge();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long rotor = b.id(e, "rotor");
        c.toggleSwitch(b.id(e, "forward"));
        run(c, 6);
        double wf = c.probe(rotor).angularVelocity(), amps = Math.abs(c.probe(b.id(e, "battery")).current());
        c.toggleSwitch(b.id(e, "forward"));
        run(c, 6);
        double braked = c.probe(rotor).angularVelocity();
        c.toggleSwitch(b.id(e, "reverse"));
        run(c, 10);
        double wr = c.probe(rotor).angularVelocity();
        System.out.printf("H-bridge: forward %.2f rad/s (%.2f A); 6 s shorted %.2f rad/s; then reverse %.2f rad/s%n", wf, amps, braked, wr);
        assertTrue(Math.abs(wf) > 5, "forward turns it: " + wf);
        assertTrue(braked * wf >= 0 && Math.abs(braked) < 0.9 * Math.abs(wf), "both open: shorted, it brakes: " + braked);
        assertTrue(wr * wf < 0 && Math.abs(wr) > 2, "reverse turns it the other way: " + wf + " / " + wr);
    }

    /**
     * PWM speed control: the knob turned up widens the pulses, and the motor, from rest, runs several times faster than
     * with the knob turned down (the same way round).
     */
    @Test
    void pwmKnobSetsTheMotorSpeed() {
        double[] w = new double[2];
        for (int m = 0; m < 2; m++) {
            Exhibit e = ControlExhibits.pwmMotor(m == 0 ? -1.2 : 1.2);
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = compile(e, b);
            run(c, 4);
            w[m] = c.probe(b.id(e, "rotor")).angularVelocity();
        }
        System.out.printf("PWM: knob down %.2f rad/s, knob up %.2f rad/s after 4 s%n", w[0], w[1]);
        assertTrue(w[0] * w[1] > 0, "the same way round");
        assertTrue(Math.abs(w[0]) > 0.3, "narrow pulses still turn it: " + w[0]);
        assertTrue(Math.abs(w[1]) > 2.5 * Math.abs(w[0]), "wide pulses turn it several times faster: " + w[0] + " / " + w[1]);
    }

    /**
     * Inverter: the bridge alternates the 9.5 V supply across the ring's 200-turn primary; the 1000-turn secondary swings
     * about five times as far both ways, and each LED lights in its own half-cycle.
     */
    @Test
    void inverterSteppedUpAcLightsBothLeds() {
        Exhibit e = ControlExhibits.inverter();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        double vmin = 0, vmax = 0, g = 0, r = 0;
        for (int i = 0; i < 800; i++) {
            c.step(0.0025);
            double v = c.probe(b.id(e, "secondary")).voltage();
            vmin = Math.min(vmin, v);
            vmax = Math.max(vmax, v);
            g = Math.max(g, Math.abs(c.probe(b.id(e, "green")).current()));
            r = Math.max(r, Math.abs(c.probe(b.id(e, "red")).current()));
        }
        System.out.printf("inverter: secondary %.1f … %.1f V; LEDs green %.1f mA, red %.1f mA%n", vmin, vmax, 1e3 * g, 1e3 * r);
        assertTrue(vmax > 35 && vmin < -35, "about five times the 9.5 V supply, both ways");
        assertTrue(g > 5e-3 && r > 5e-3, "each LED lights in its half-cycle");
    }

    /**
     * Propeller cart: the spun propeller throws air back along the rail and the cart, touched by nothing else, runs the
     * other way; the thrust falls as it gathers speed (and the flywheel slows).
     */
    @Test
    void propellerDrivesTheCartAlongItsRail() {
        Exhibit e = AeroExhibits.propellerCart();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertTrue(c.warnings().stream().noneMatch(w -> w.contains("Blade")), "the propeller's blades are modelled: " + c.warnings());
        var cart = c.bodyOf(b.id(e, "cart"));
        var af = c.aerofoils(c.bodyOf(b.id(e, "shaft")));
        // spun up by its crank (at rest, as a player finds it), then let go
        double x0 = c.angle(cart);
        for (int k = 0; k < 80; k++) {
            c.setCrankEffort(b.id(e, "crank"), 1, true);
            c.step(0.05);
        }
        c.setCrankEffort(b.id(e, "crank"), 0, false);
        double thrust0 = af.thrust(c.solverView());
        System.out.printf("propeller cart: cranked 4 s, the propeller at %.1f rad/s, the cart %.3f m along%n",
                c.angularVelocity(c.bodyOf(b.id(e, "shaft"))), c.angle(cart) - x0);
        run(c, 5);
        double x = c.angle(cart) - x0, v = c.angularVelocity(cart), thrust = af.thrust(c.solverView());
        System.out.printf("propeller cart: thrust on the air %.3f N at rest; after 5 s the cart %.3f m along at %.3f m/s, thrust %.3f N%n",
                thrust0, x, v, thrust);
        assertTrue(x * thrust0 < 0 && Math.abs(x) > 0.1, "the cart runs the opposite way to the air it throws: " + x);
        assertTrue(Math.abs(thrust) < Math.abs(thrust0), "the thrust falls as it goes");
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
        // at rest, as a player finds them: each spun up by hand for a few seconds, then let go
        for (String s : new String[]{"ball", "plain"}) c.setTurnEffort(b.id(e, s), 1, true);
        run(c, 4);
        for (String s : new String[]{"ball", "plain"}) c.setTurnEffort(b.id(e, s), 0, false);
        double ball0 = c.probe(b.id(e, "ball")).angularVelocity(), plain0 = c.probe(b.id(e, "plain")).angularVelocity();
        run(c, 60);
        double ball = c.probe(b.id(e, "ball")).angularVelocity();
        double plain = c.probe(b.id(e, "plain")).angularVelocity();
        System.out.printf("flywheels: spun by hand to %.1f / %.1f rad/s; a minute later %.1f / %.1f rad/s%n", ball0, plain0, ball, plain);
        assertTrue(ball0 > 5 && plain0 > 5, "a hand spins them up: " + ball0 + ", " + plain0);
        assertTrue(ball > 0.8 * ball0, "ball bearing barely slows: " + ball);
        assertTrue(plain < 0.1 * plain0, "bushing nearly stopped: " + plain);
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
        for (int i = 0; i < 400; i++) { // from rest
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

    /**
     * Condensing engine: the same engine exhausting into a sealed 40 °C condenser carries twice the atmospheric engine's
     * brake at nearly its speed (the atmospheric one runs ≈ 110–125 rad/s over the same seconds); the spent steam stays
     * in the condenser, which stays near water's vapour pressure.
     */
    @Test
    void condensingEngineCarriesTwiceTheLoad() {
        Exhibit e = Exhibits.condensingEngine();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long shaft = b.id(e, "shaft"), cond = b.id(e, "condenser");
        double sumW = 0, vent = 0;
        int n = 0;
        for (int i = 0; i < 500; i++) {
            c.step(0.05);
            if (i >= 300) {
                sumW += c.probe(shaft).angularVelocity();
                n++;
            }
            for (var v : c.vents()) vent += Math.max(0, c.ventOutflow(v)) * 0.05;
        }
        double mean = sumW / n, pc = c.fluid(cond)[0];
        System.out.printf("condensing engine: mean %.1f rad/s under a 16 N brake; condenser %.1f kPa %.1f °C; vented %.2f g%n",
                mean, pc / 1e3, c.fluid(cond)[1] - 273.15, vent * 1e3);
        assertTrue(mean > 80, "twice the brake at nearly the atmospheric engine's speed: " + mean);
        assertTrue(pc < 12e3, "the condenser holds a vacuum: " + pc);
        assertTrue(vent < 1e-4, "no steam leaves: " + vent);
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
        assertEquals(servoDemand(k), a, 0.01, "integral action: no droop, though holding the arm up takes 1.8 A");
        assertTrue(worst < 0.25, "the arm keeps up with the knob: " + worst);
        // and back down part way
        for (int i = 0; i < 60; i++) {
            c.setCrankEffort(crank, 1, false);
            c.step(0.05);
        }
        c.setCrankEffort(crank, 0, false);
        run(c, 3.0);
        assertEquals(servoDemand(c.probe(knob).angle()), c.probe(arm).angle(), 0.02, "follows the knob back");
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
        thermalExplosion(false);
    }

    /** The same in noon sunshine (as in the world): the sun on its wires and leads must not burn anything out. */
    @Test
    void thermalExplosionIgnitesInSunshine() {
        thermalExplosion(true);
    }

    static void thermalExplosion(boolean sun) {
        Exhibit e = ChemistryExhibits.thermalExplosion();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        if (sun) c.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> true);
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
        // as the tour lights it: igniter and gas together, the igniter off after a minute
        c.toggleSwitch(b.id(e, "switch"));
        c.toggleSwitch(b.id(e, "valve"));
        double litAt = -1;
        for (int i = 0; i < 3600; i++) {
            if (i == 1200) c.toggleSwitch(b.id(e, "switch"));
            var broken = c.step(0.05);
            assertTrue(broken.isEmpty(), "no part fails: " + broken);
            if (litAt < 0 && c.gas(chamber).temperature(c.solverView()) > 1300) litAt = 0.05 * (i + 1);
            if (i == 1100) {
                for (var el : c.network().elements()) if (el instanceof dev.emergence.core.sim.elements.MixtureBranch mb)
                    System.out.printf("burner flow %s: %.3g mol/s%n", mb.label(), mb.molarFlow(c.solverView()));
                for (var en : java.util.List.of("chamber", "chimney", "fuel")) {
                    var gg = c.gas(b.id(e, en));
                    System.out.printf("burner gas %s: %.0f K, p %.1f Pa%n", en, gg.temperature(c.solverView()), gg.pressure(c.solverView()));
                }
            }
            if (i % 600 == 0 || i == 3599) {
                var g = c.gas(chamber);
                var s = c.solverView();
                System.out.printf("burner t=%.0f s chamber %.0f K wall %.0f K, CH4 %.2f%% O2 %.1f%% CO2 %.1f%%; chimney top %.0f K CO2 %.1f%%, fuel %.1f bar%n",
                        0.05 * (i + 1), g.temperature(s), c.probe(chamber).temperature(),
                        100 * g.moleFraction(s, g.indexOf(ch.species("CH4"))), 100 * g.moleFraction(s, g.indexOf(ch.species("O2"))),
                        100 * g.moleFraction(s, g.indexOf(ch.species("CO2"))), c.gas(top).temperature(s),
                        100 * c.gas(top).moleFraction(s, g.indexOf(ch.species("CO2"))), c.gas(b.id(e, "fuel")).pressure(s) / 1e5);
            }
        }
        System.out.printf("burner lit at %.1f s%n", litAt);
        assertTrue(litAt > 0 && litAt < 60, "lights within the minute the tour waits: " + litAt);
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
        // left alone (as the gallery leaves it until someone comes), it must not run itself down: the pump's own weight
        // must not lift the loaded ram
        run(c, 30);
        double p0 = c.probe(pump).angle(), r0 = c.probe(ram).angle();
        System.out.printf("hydraulic press: left alone 30 s, the pump at %+.1f mm, the ram at %+.2f mm%n", 1000 * p0, 1000 * r0);
        assertTrue(p0 > -0.01, "the pump has not sunk under its own weight: " + p0);
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
    /**
     * The slide valve: shut as built (the rod pushed in, its plug seated) — the receiver holds its air and the rotor
     * stands; the rod drawn back by hand opens it and the rotor spins up on the air; pushed back in, the flow stops and
     * the receiver holds what is left (its pressure rising a little as its walls warm the air the expansion cooled).
     */
    @Test
    void slideValveThrottlesWithItsRod() {
        Exhibit e = MechanicsExhibits.slideValve();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long knob = b.id(e, "knob"), receiver = b.id(e, "receiver"), rod = b.id(e, "rod"), shaft = b.id(e, "shaft");
        // the flow through the seat: the valve's passage that its plug works
        var seat = c.branchesOf(b.id(e, "valve")).stream().filter(dev.emergence.core.sim.elements.FluidBranch::hasCam).findFirst().orElseThrow();
        java.util.function.DoubleSupplier through = () -> Math.abs(seat.massFlow(c.solverView()));
        run(c, 2);
        double p0 = c.fluid(receiver)[0], w0 = Math.abs(c.probe(shaft).angularVelocity());
        // draw the rod back
        for (int i = 0; i < 20; i++) {
            c.setPushEffort(knob, 1);
            c.step(0.05);
        }
        c.setPushEffort(knob, 0);
        double out = c.probe(rod).angle();
        c.step(0.05);
        double q0 = through.getAsDouble(); // fully open, the receiver still near 10 bar
        run(c, 2);
        double p1 = c.fluid(receiver)[0], w1 = Math.abs(c.probe(shaft).angularVelocity()), q1 = through.getAsDouble();
        // and push it back in
        for (int i = 0; i < 20; i++) {
            c.setPushEffort(knob, -1);
            c.step(0.05);
        }
        c.setPushEffort(knob, 0);
        double in = c.probe(rod).angle();
        run(c, 0.5);
        double p2 = c.fluid(receiver)[0], q2 = through.getAsDouble();
        run(c, 3);
        double p3 = c.fluid(receiver)[0], q3 = through.getAsDouble();
        System.out.printf("slide valve: shut %.0f rad/s at %.2f bar; rod out %.1f mm → %.0f rad/s, %.2f bar; rod in %.1f mm → %.3f bar → %.3f bar%n",
                w0, p0 / 1e5, 1e3 * out, w1, p1 / 1e5, 1e3 * in, p2 / 1e5, p3 / 1e5);
        assertTrue(Math.abs(out) > 0.025, "the rod drawn back to its collar: " + out);
        assertTrue(Math.abs(in) < 0.002, "the rod pushed back in: " + in);
        assertTrue(w0 < 1 && p0 > 9.9e5, "shut as built: the rotor stands, the receiver full");
        assertTrue(w1 > 50 && p1 < p0 - 1e4, "open: the air spins the rotor up as the receiver empties: " + w1 + " rad/s");
        // (its pressure rises as the walls warm the air its expansion cooled; nothing passes the seat)
        System.out.printf("slide valve: through the seat %.3g kg/s just opened, %.3g as the receiver ran out; %.3g and %.3g kg/s shut again (rod at %.2f mm)%n",
                q0, q1, q2, q3, 1e3 * c.probe(rod).angle());
        assertTrue(q2 < 1e-3 * q0 && q3 < 1e-3 * q0, "shut again: next to nothing passes the seat");
    }

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

    /**
     * The jack in the noon sun, pumped as the tour pumps it: the sun warms its pipes unevenly and a trickle of water
     * (10⁻⁷ kg/s) spills from its brim-full standpipe into the open air. That once failed Newton within a minute or so
     * (the open air's intake held to a finer tolerance than the vent's own flow), as it did in the world.
     */
    @Test
    void hydraulicJackPumpsInTheNoonSun() {
        Exhibit e = MechanicsExhibits.hydraulicJack();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pump = b.id(e, "pumpRod"), ram = b.id(e, "ramRod");
        double r0 = c.probe(ram).angle();
        for (int tick = 0; tick < 20 * 90; tick++) {
            c.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> true);
            int phase = tick < 100 ? -1 : ((tick - 100) / 12) % 2; // from 5 s on: 0.6 s down, 0.6 s up
            c.setPushEffort(pump, phase < 0 ? 0 : phase == 0 ? -1 : 1);
            c.step(0.05);
        }
        assertTrue(c.probe(ram).angle() - r0 > 0.02, "pumped, the ram rose: " + (c.probe(ram).angle() - r0));
    }

    /** Rack and pinion: the rack travels r_p per radian of the crank; let go, the weight runs it back. */
    @Test
    void rackAndPinionLiftsAndRunsBack() {
        Exhibit e = MechanicsExhibits.rackAndPinion();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertTrue(c.warnings().stream().noneMatch(w -> w.contains("ack")), c.warnings().toString());
        long crank = b.id(e, "crank"), rack = b.id(e, "rack");
        assertTrue(c.bodyOf(rack) != null && c.bodyOf(rack).slider(), "the rack is on a slide");
        run(c, 0.5);
        double th0 = c.probe(crank).angle(), x0 = c.probe(rack).angle();
        c.setCrankEffort(crank, 1, false);
        for (int i = 0; i < 100; i++) c.step(0.01);
        double th1 = c.probe(crank).angle(), x1 = c.probe(rack).angle();
        double rp = 0.5 * dev.emergence.core.assembly.Part.CELL;
        System.out.printf("rack and pinion: crank %.3f rad, rack %.2f mm (r_p·Δθ = %.2f mm)%n", th1 - th0, 1000 * (x1 - x0), 1000 * rp * (th1 - th0));
        assertTrue(x1 - x0 > 0.01, "cranking lifts the rack: " + (x1 - x0));
        assertEquals(rp * Math.abs(th1 - th0), Math.abs(x1 - x0), 1e-4, "it travels the pitch radius per radian");
        c.setCrankEffort(crank, 0, false);
        run(c, 1.0);
        assertTrue(c.probe(rack).angle() < x1 - 0.01, "let go: the weight runs it back down");
    }

    /** Lead screws: the nut moves a lead per turn; the fine thread holds its load, the steep one runs back. */
    @Test
    void fineScrewHoldsSteepScrewRunsBack() {
        Exhibit e = MechanicsExhibits.screwJacks();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertTrue(c.warnings().stream().noneMatch(w -> w.contains("Nut")), c.warnings().toString());
        run(c, 0.5);
        String[] names = {"fine", "steep"};
        double[] leads = {0.004, 0.04};
        for (int k = 0; k < 2; k++) {
            long crank = b.id(e, names[k] + "Crank"), rod = b.id(e, names[k] + "Rod");
            double th0 = c.probe(crank).angle(), x0 = c.probe(rod).angle();
            // right-hand threads: clockwise seen from above (negative about +y) screws the nut up
            c.setCrankEffort(crank, -1, false);
            for (int i = 0; i < 100; i++) c.step(0.01);
            double th1 = c.probe(crank).angle(), x1 = c.probe(rod).angle();
            c.setCrankEffort(crank, 0, false);
            System.out.printf("%s screw: crank %.2f turns, carriage %.2f mm (lead × turns = %.2f mm)%n", names[k],
                    (th1 - th0) / (2 * Math.PI), 1000 * (x1 - x0), 1000 * leads[k] * Math.abs(th1 - th0) / (2 * Math.PI));
            assertTrue(x1 - x0 > 0.002, names[k] + ": cranking lifts the carriage");
            assertEquals(leads[k] * Math.abs(th1 - th0) / (2 * Math.PI), x1 - x0, 1e-3 * leads[k] + 1e-5, "one lead per turn");
            run(c, 0.5); // the spinning screw coasts to a stop
            double settled = c.probe(rod).angle();
            run(c, 1.0);
            double fell = settled - c.probe(rod).angle();
            System.out.printf("  let go: coasted %.3g mm, then fell %.3g mm in 1 s%n", 1000 * (settled - x1), 1000 * fell);
            if (k == 0) assertTrue(fell < 2e-4, "the fine thread holds the load");
            else assertTrue(fell > 0.005, "the steep thread runs back");
        }
    }

    /**
     * Motion restored that the build no longer allows (here a fine-screw carriage saved falling while its
     * self-locking screw stands still) cannot be reconciled without an impulse: the first step starts the bodies from
     * rest where they stand instead of failing.
     */
    @Test
    void inconsistentRestoredMotionStartsFromRest() {
        Exhibit e = MechanicsExhibits.screwJacks();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        long rod = b.id(e, "fineRod");
        CompiledAssembly c0 = compile(e, b);
        var st = c0.exportState();
        for (long id : c0.bodyOf(rod).parts()) st.bodyMotion().put(id, new double[]{0.5, -0.1});
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), st);
        run(c, 0.5);
        assertTrue(c.motionReset(), "started from rest");
        assertEquals(-0.1, c.probe(rod).angle(), 1e-3, "where it stood");
    }

    /**
     * Burst test: the thin copper box bursts when its wall stress — flat walls bending under the steam pressure,
     * Roark's fixed-edge plate — reaches copper's strength, at the saturation pressure of its water; the steel box holds.
     */
    @Test
    void thinCopperVesselBurstsThickSteelHolds() {
        Exhibit e = ThermoExhibits.burstTest();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long thin = b.id(e, "thin"), thick = b.id(e, "thick");
        run(c, 20);
        assertTrue(c.pressure(thin) - c.assembly().ambientPressure() < 1e3, "nothing happens until the switch is closed");
        c.toggleSwitch(b.id(e, "switch"));
        double pAmb = c.assembly().ambientPressure(), pBefore = 0, tBefore = 0, tBurst = -1;
        for (int i = 0; i < 12000 && tBurst < 0; i++) {
            pBefore = c.pressure(thin);
            tBefore = c.fluid(thin)[1];
            var failed = c.step(0.05);
            if (failed.contains(thin)) tBurst = 0.05 * (i + 1);
            assertFalse(failed.contains(thick), "the steel box holds");
        }
        var copper = dev.emergence.core.materials.MaterialDatabase.standard().get("copper");
        double k = CompiledAssembly.wallStressPerPascal(b.assembly().get(thin));
        double expected = copper.get(dev.emergence.core.materials.MaterialProperty.ULTIMATE_TENSILE_STRENGTH, 0) / k;
        double tSat = dev.emergence.core.fluids.WaterSteam.tSat(pBefore);
        System.out.printf("burst test: copper box burst after %.0f s at %.3f bar gauge (Roark: %.3f bar), water %.1f K "
                + "(saturation at that pressure %.1f K); steel box at %.3f bar, utilisation %.3f%n", tBurst, (pBefore - pAmb) / 1e5,
                expected / 1e5, tBefore, tSat, (c.pressure(thick) - pAmb) / 1e5, c.burstUtilisation(thick));
        assertTrue(tBurst > 0, "the thin copper box bursts");
        assertEquals(expected, pBefore - pAmb, 0.03 * expected, "at the pressure where its wall stress reaches copper's strength");
        assertEquals(tSat, tBefore, 3, "the water is boiling: on the saturation curve");
        assertTrue(c.burstUtilisation(thick) < 0.2, "the steel box is far from bursting");
    }

    /**
     * Faraday's ring: closing the switch induces a pulse in the secondary of about N2/N1 times the primary coil's
     * voltage (the iron couples them, k close to 1) that lights one LED; opening it, a reverse pulse lights the other.
     */
    @Test
    void faradayRingInducesPulsesOnMakeAndBreak() {
        Exhibit e = Exhibits.faradayRing();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pri = b.id(e, "primary"), sec = b.id(e, "secondary"), green = b.id(e, "green"), red = b.id(e, "red");
        var ms = c.magnetics();
        double[][] l = ms.constantInductances();
        double k = l[0][1] / Math.sqrt(l[0][0] * l[1][1]);
        System.out.printf("Faraday ring: L1 %.3g H, L2 %.3g H, M %.3g H, k = %.3f; open-circuit EMF ratio M/L1 = %.2f (N2/N1 = 5)%n",
                l[0][0], l[1][1], l[0][1], k, Math.abs(l[0][1] / l[0][0]));
        assertEquals(5, Math.abs(l[0][1] / l[0][0]), 0.5, "secondary EMF per primary volt: the turns ratio, less leakage");
        assertTrue(Math.abs(k) > 0.9, "the iron couples the coils: " + k);
        run(c, 1);
        assertTrue(c.lightOutput(green) < 1e-6 && c.lightOutput(red) < 1e-6, "dark at rest");
        c.toggleSwitch(b.id(e, "switch"));
        double peakGreen = 0, peakRed = 0;
        for (int i = 0; i < 500; i++) { // 1 s: several L1/R time constants
            c.step(0.002);
            peakGreen = Math.max(peakGreen, c.lightOutput(green));
            peakRed = Math.max(peakRed, c.lightOutput(red));
        }
        System.out.printf("make: peak light green %.3g W, red %.3g W; after 1 s %.3g W%n", peakGreen, peakRed, c.lightOutput(green));
        assertTrue(peakGreen > 1e-4 || peakRed > 1e-4, "one LED flashes on make");
        boolean makeIsGreen = peakGreen > peakRed;
        assertTrue(Math.max(c.lightOutput(green), c.lightOutput(red)) < 1e-6, "dark once the current is steady");
        System.out.printf("before break: primary %.3f A, secondary %.4f A%n", c.probe(pri).current(), c.probe(sec).current());
        c.toggleSwitch(b.id(e, "switch"));
        double peakOther = 0;
        for (int i = 0; i < 200; i++) {
            c.step(0.002);
            peakOther = Math.max(peakOther, c.lightOutput(makeIsGreen ? red : green));
        }
        System.out.printf("break: the other LED peaks at %.3g W%n", peakOther);
        assertTrue(peakOther > 1e-4, "the other LED flashes on break");
    }

    /**
     * Faraday's ring saturates: its core mode's flux linkage levels off where the iron through the primary reaches
     * electrical steel's 2.0 T — N₁·B_sat·A_iron for the primary — and at the bank's 3 A the core is driven past that,
     * so the primary's linkage stays near it rather than the linear model's far larger μ·m.
     */
    @Test
    void faradayRingCoreSaturatesAtTheSteelsSaturationFluxDensity() {
        Exhibit e = Exhibits.faradayRing();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var ms = c.magnetics();
        double[] mode = ms.saturationModes().stream().max(java.util.Comparator.comparingDouble(m -> m[1])).orElseThrow();
        var pri = b.assembly().get(b.id(e, "primary"));
        var g = dev.emergence.core.assembly.PartGeometry.coil(pri);
        double rIn = g.meanRadius() - 0.5 * g.thickness(), aIron = Math.PI * rIn * rIn;
        double expect = 200 * 2.0 * aIron, got = mode[0] * Math.abs(mode[2]);
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 2);
        var s = c.solverView();
        double i1 = c.probe(b.id(e, "primary")).current(), lambda = Math.abs(ms.fluxLinkage(ms.coils().get(0), s));
        double air = ms.coils().get(0).geom().selfInductance(ms.coils().get(0).wireDiameter()) * Math.abs(i1);
        double linear = mode[1] * Math.abs(mode[2] * i1) * Math.abs(mode[2]);
        System.out.printf("ring saturation: primary level-off %.4g Wb (N1·Bsat·A_iron %.4g); at %.2f A the linkage is %.4g Wb (air %.3g, iron unsaturated would add %.3g)%n",
                got, expect, Math.abs(i1), lambda, air, linear);
        assertEquals(expect, got, 0.2 * expect, "the core saturates at the steel's flux density through the coil's iron");
        assertTrue(linear > 2 * got, "3 A drives the core well past saturation");
        // (the primary's linkage also holds the iron's smaller leakage modes, here unsaturated)
        assertTrue(lambda > 0.9 * got && lambda < 0.5 * (air + linear), "so the linkage levels off near N₁·B_sat·A, far below μ·m");
        System.out.printf("ring saturation: energy error %.3g J of the bank's 360 J%n", c.energyError());
        assertTrue(Math.abs(c.energyError()) < 1e-3 * 360, "energy conserved through the saturating core");
    }

    /**
     * Hysteresis in Faraday's ring: switched on and off again, the core keeps a remanent flux — its state stays a
     * coercive width w from zero, so the linkage ψ(w) remains with no current flowing — and the iron took heat.
     */
    @Test
    void faradayRingKeepsRemanentFlux() {
        Exhibit e = Exhibits.faradayRing();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var ms = c.magnetics();
        double[] w = ms.coerciveWidths();
        int core = 0;
        for (int k = 1; k < w.length; k++) if (ms.saturationModes().get(k)[1] > ms.saturationModes().get(core)[1]) core = k;
        assertTrue(w[core] > 0, "electrical steel's coercivity gives the core a coercive width");
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 1);
        c.toggleSwitch(b.id(e, "switch"));
        double heat = 0;
        for (int i = 0; i < 40; i++) {
            c.step(0.05);
            heat += ms.hysteresisLoss(c.solverView()) * 0.05;
        }
        double[] st = ms.modeState(core, c.solverView());
        double[] mode = ms.saturationModes().get(core);
        double expect = mode[0] * Math.tanh(mode[1] * w[core] / mode[0]);
        System.out.printf("remanence: coercive width %.4g A, after switching off m = %.3g A, p = %.4g A, ψ %.4g Wb (ψ(w) %.4g); hysteresis heat %.3g J; energy error %.3g J%n",
                w[core], st[0], st[1], st[2], expect, heat, c.energyError());
        assertEquals(0, st[0], 1e-3 * w[core] + 1e-6, "no current flows");
        assertEquals(expect, Math.abs(st[2]), 0.05 * expect, "the flux of p = ±w stays: remanence");
        assertTrue(Math.abs(c.energyError()) < 1e-3 * 360, "energy conserved, the loss in the iron");
    }

    /**
     * Hysteresis loss on alternating current: driven round its loop by the inverter, the ring's core dissipates per
     * cycle the loop's area, 2w·Δψ (a parallelogram of width 2w over the flux swing Δψ).
     */
    @Test
    void inverterCoreLosesTheLoopAreaEachCycle() {
        Exhibit e = ControlExhibits.inverter();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var ms = c.magnetics();
        double[] w = ms.coerciveWidths();
        int core = 0;
        for (int k = 1; k < w.length; k++) if (ms.saturationModes().get(k)[1] > ms.saturationModes().get(core)[1]) core = k;
        run(c, 1); // past the inrush
        double heat = 0, psiMin = 1e9, psiMax = -1e9, t = 0;
        int crossings = 0;
        double prevP = ms.modeState(core, c.solverView())[1];
        double[] prev = ms.modeState(core, c.solverView());
        double work = 0, maxLag = 0;
        // the loss the solver put into the iron, integrated over its own steps (it comes in bursts)
        double[] acc = {0, c.solverView().time(), 0};
        final int coreMode = core;
        c.solverView().addStepListener(sv -> {
            acc[0] += ms.modeLoss(coreMode) * (sv.time() - acc[1]);
            acc[2] += ms.hysteresisLoss(sv) * (sv.time() - acc[1]);
            acc[1] = sv.time();
        });
        double dt = 0.0025;
        for (int i = 0; i < 1600; i++) {
            c.step(dt);
            t += dt;
            double[] st = ms.modeState(core, c.solverView());
            work += 0.5 * ((st[0] - st[1]) + (prev[0] - prev[1])) * (st[2] - prev[2]);
            maxLag = Math.max(maxLag, Math.abs(st[0] - st[1]));
            prev = st;
            psiMin = Math.min(psiMin, st[2]);
            psiMax = Math.max(psiMax, st[2]);
            if (Math.signum(st[1]) != Math.signum(prevP) && st[1] != 0) crossings++;
            prevP = st[1];
        }
        heat = acc[0];
        double cycles = crossings / 2.0, loop = 2 * w[core] * (psiMax - psiMin);
        System.out.printf("loop loss: %.1f cycles in %.0f s, flux %.4g … %.4g Wb, w %.4g A: %.4g J per cycle (loop area 2w·Δψ %.4g J; ∮(m−p)dψ %.4g J; max lag %.3g A); all modes %.4g J per cycle%n",
                cycles, t, psiMin, psiMax, w[core], heat / cycles, loop, work / cycles, maxLag, acc[2] / cycles);
        assertTrue(cycles >= 8, "it alternates: " + cycles);
        assertEquals(loop, heat / cycles, 0.1 * loop, "each cycle dissipates the loop's area");
    }

    /** Buckling: the loaded short strip stands below its Euler load; the same lead block on the tall strip buckles it. */
    @Test
    void tallStripBucklesUnderTheBlockTheShortOneCarries() {
        Exhibit e = Exhibits.buckling();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        var r = compile(e, b).structure();
        System.out.printf("buckling: as built %.3f of the critical load (Euler, fixed-free, 5 cells: 27.2/51.3 = 0.53)%n", r.buckling());
        assertTrue(r.failed().isEmpty(), "everything stands as built");
        assertEquals(27.2 / 51.3, r.buckling(), 0.05, "the short strip at about half its Euler load");
        assertTrue(r.maxUtilisation() < 0.1, "far below its strength: " + r.maxUtilisation());
        e.add(dev.emergence.core.assembly.PartKind.BLOCK, "lead", 10, 9, 8, Dir.UP);
        Exhibits.Built b2 = Exhibits.build(e, AMBIENT);
        var r2 = compile(e, b2).structure();
        System.out.printf("buckling: with a block on the tall strip %.3f (27.2/15.8 = 1.72), failed %s%n", r2.buckling(), r2.failed());
        assertEquals(27.2 / 15.8, r2.buckling(), 0.1, "the tall strip at 1.7 times its Euler load");
        assertFalse(r2.failed().isEmpty(), "it buckles");
        for (long id : r2.failed()) {
            var p = b2.assembly().get(id);
            assertEquals(10, p.minX(), "the tall strip (and its load) fail, not the short one");
        }
    }

    /**
     * The gas engine, standing as a player finds it, starts by hand: the fuel opened and a few seconds at the starting
     * handle turn it over, the cam-timed valves draw in methane and air, the glow plug's hot wall lights the squeezed
     * charge, and once it fires it runs away from the handle. Then the brake goes on and it carries the load.
     */
    @Test
    void gasEngineStartsByHandAndCarriesTheBrake() {
        Exhibit e = EngineExhibits.gasEngine();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var ch = dev.emergence.core.chem.Chemistry.standard();
        long crank = b.id(e, "crankshaft"), cyl = b.id(e, "cylinder"), handle = b.id(e, "handle"), brake = b.id(e, "brake");
        assertEquals(0, c.probe(crank).angularVelocity(), 1e-9, "at rest");
        // it stands for ten minutes before anyone comes: the glow plug keeps its wall hot, the shut fuel keeps the box lean
        run(c, 600);
        var mg = c.gas(b.id(e, "mixer"));
        System.out.printf("gas engine: after 10 min standing, head %.0f K, glow plug %.0f K, mixing box %.1f %% methane%n",
                c.temperature(b.id(e, "head")), c.temperature(b.id(e, "glow")), 100 * mg.moleFraction(c.solverView(), mg.indexOf(ch.species("CH4"))));
        assertTrue(Math.abs(c.probe(crank).angularVelocity()) < 0.5, "it does not start itself");
        // the fuel opened, and the engine swung at the handle for ten seconds
        c.toggleSwitch(b.id(e, "needle"));
        double wHand = 0, pPeak = 0, co2 = 0;
        for (int i = 0; i < 200; i++) {
            c.setCrankEffort(handle, 1, false);
            c.step(0.05);
            wHand = Math.max(wHand, c.probe(crank).angularVelocity());
        }
        c.setCrankEffort(handle, 0, false);
        double w0 = c.probe(crank).angularVelocity(), wMin = Double.MAX_VALUE;
        for (int i = 0; i < 2000; i++) {
            c.step(0.005);
            var g = c.gas(cyl);
            pPeak = Math.max(pPeak, g.pressure(c.solverView()));
            co2 = Math.max(co2, g.moleFraction(c.solverView(), g.indexOf(ch.species("CO2"))));
            if (i >= 1000) wMin = Math.min(wMin, c.probe(crank).angularVelocity());
        }
        // a hand cannot turn the 4 cm handle faster than this
        double handTop = dev.emergence.core.assembly.HumanEffort.MAX_HAND_SPEED / 0.04;
        System.out.printf("  cranked by hand to %.0f rad/s (let go at %.0f); 10 s on its own: %.0f rad/s (no less than %.0f in the last 5 s; "
                + "a hand tops out at %.0f), peak cylinder pressure %.1f MPa, CO2 up to %.1f %%%n", wHand, w0, c.probe(crank).angularVelocity(),
                wMin, handTop, pPeak / 1e6, 100 * co2);
        assertTrue(pPeak > 2e6, "it fires: combustion pressure well above compression alone");
        assertTrue(co2 > 0.02, "methane burns to CO2");
        assertTrue(wMin > handTop, "it runs on its own, faster than a hand can turn it: " + wMin);
        // the brake on: it carries the load
        c.toggleSwitch(brake);
        double wLoad = Double.MAX_VALUE, work = 0;
        for (int i = 0; i < 2000; i++) {
            c.step(0.005);
            if (i >= 1000) {
                wLoad = Math.min(wLoad, c.probe(crank).angularVelocity());
                work += c.brake(brake)[1] * 0.005;
            }
        }
        System.out.printf("  the brake on: no less than %.0f rad/s over the last 5 s, absorbing %.1f W%n", wLoad, work / 5);
        assertTrue(wLoad > 20, "it keeps running under the brake: " + wLoad);
        assertTrue(work / 5 > 5, "it does work against the brake: " + work / 5 + " W");
    }

    /** Periscope: two 45° mirrors carry the LED's beam over the wall to the photodiode; without one, nothing arrives. */
    @Test
    void periscopeCarriesTheBeamOverTheWall() {
        Exhibit e = OpticsExhibits.periscope();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pd = b.id(e, "pd"), led = b.id(e, "led"), lit = b.id(e, "lit");
        run(c, 0.3);
        double out = c.lightOutput(led), in = c.lightInput(pd);
        // a 30-beam LED's axial intensity (m + 1)/2π, over the folded path (the traced beam's length), on 1 cm², two
        // 92 % mirrors
        var axis = c.beams(led).get(0);
        double path = 0;
        for (int k = 1; k < axis.size(); k++) path += axis.get(k).sub(axis.get(k - 1)).length();
        double expect = out * 31 / (2 * Math.PI) * 1e-4 / (path * path) * 0.92 * 0.92;
        System.out.printf("periscope: LED %.2f mW, photodiode %.3f µW (on-axis estimate %.3f µW), green LED %.2f mA%n",
                1e3 * out, 1e6 * in, 1e6 * expect, 1e3 * c.probe(lit).current());
        assertEquals(expect, in, 0.03 * expect, "the folded beam's irradiance");
        assertTrue(c.probe(lit).current() > 2e-3, "the green LED lights");
        // the beam drawn for it: east, up, east over the wall onto the photodiode's face
        var rays = c.beams(led);
        assertEquals(7, rays.size(), "the axis and a ring of six");
        var chief = rays.get(0);
        var pdPart = b.assembly().get(pd);
        var end = chief.get(chief.size() - 1);
        System.out.println("  beam axis: " + chief);
        assertEquals(4, chief.size(), "LED, two mirrors, photodiode");
        assertEquals(pdPart.center().y(), end.y(), 1e-9);
        assertEquals(pdPart.center().x() - 0.5 / 16, end.x(), 1e-9, "on the photodiode's west face");
        // taken away while it runs, as in the world (the circuit carrying on from its running state), at noon: the
        // photodiode looks sideways, and the sun overhead does not light it
        b.assembly().remove(b.id(e, "mirrorB"));
        CompiledAssembly c2 = AssemblyCompiler.compile(b.assembly(), c.exportState());
        c2.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> true);
        run(c2, 0.3);
        System.out.printf("  without the second mirror: %.3g µW, green LED %.3g mA%n", 1e6 * c2.lightInput(pd), 1e3 * c2.probe(lit).current());
        assertTrue(c2.lightInput(pd) < 0.05 * in, "the beam goes on into the sky (the sideways photodiode catches a little daylight): "
                + c2.lightInput(pd) / in);
        assertTrue(c2.probe(lit).current() < 1e-4, "dark");
    }

    /** Lens: the LED's image falls on the photodiode, which gathers far more light than without the lens. */
    @Test
    void lensFocusesTheLedOntoThePhotodiode() {
        Exhibit e = OpticsExhibits.lens();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pd = b.id(e, "pd"), led = b.id(e, "led"), lit = b.id(e, "lit");
        run(c, 0.3);
        double out = c.lightOutput(led), in = c.lightInput(pd);
        System.out.printf("lens: LED %.2f mW, photodiode %.3f µW (%.3g of the LED's light), green LED %.2f mA%n",
                1e3 * out, 1e6 * in, in / out, 1e3 * c.probe(lit).current());
        assertTrue(c.probe(lit).current() > 2e-3, "the green LED lights");
        // taken away while it runs, as in the world: the circuit carries on from its running state
        b.assembly().remove(b.id(e, "lens"));
        CompiledAssembly c2 = AssemblyCompiler.compile(b.assembly(), c.exportState());
        run(c2, 0.3);
        double bare = c2.lightInput(pd);
        // without the lens: the axial intensity on the 7.5 mm² junction 0.638 m from the LED
        double d = (12 - 1.8) / 16, direct = out * 31 / (2 * Math.PI) * 7.5e-6 / (d * d);
        System.out.printf("  without the lens: %.4f µW (direct estimate %.4f µW): the lens gathers %.0f times as much; green LED %.3g mA%n",
                1e6 * bare, 1e6 * direct, in / bare, 1e3 * c2.probe(lit).current());
        assertEquals(direct, bare, 0.1 * direct);
        assertTrue(in > 30 * bare, "the lens gathers much more light");
        assertTrue(c2.probe(lit).current() < 1e-4, "dark without the lens");
    }

    /** Solar furnace: the dish focuses noon sunlight onto the plate, which heats red-hot; the plate is cool in the shade. */
    @Test
    void solarFurnaceHeatsThePlateRedHot() {
        Exhibit e = OpticsExhibits.solarFurnace();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long target = b.id(e, "target"), dish = b.id(e, "dish");
        c.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> true);
        double onPlate = c.sunlightAbsorbed(target), dishHeat = c.sunlightAbsorbed(dish);
        double area = Math.pow(13.0 / 16, 2), dishHeatAll = 0;
        for (var p : b.assembly().parts()) if (p.kind() == dev.emergence.core.assembly.PartKind.MIRROR) dishHeatAll += c.sunlightAbsorbed(p.id());
        dishHeat = dishHeatAll;
        System.out.printf("solar furnace: all parts absorb %.1f W%n", c.sunlightAbsorbed());
        System.out.printf("solar furnace: plate absorbs %.1f W, dish %.1f W, of %.1f W on the dish; traced in %.1f ms%n",
                onPlate, dishHeat, 1000 * area, c.sunTraceCost()[1]);
        // aluminium reflects ~92 % at 700 nm; steel absorbs 1 − R ≈ 40 %; the plate's shadow and the dish's rim lose a little
        assertTrue(onPlate > 0.3 * 0.92 * 1000 * area && onPlate < 0.45 * 1000 * area, "the plate gets the dish's focused light");
        assertEquals((1 - 0.924) * 1000 * area, dishHeat, 0.25 * (1 - 0.924) * 1000 * area, "the dish absorbs what it does not reflect");
        for (int k = 1; k <= 6; k++) {
            run(c, 30);
            System.out.printf("  plate after %d s: %.0f K%n", 30 * k, c.temperature(target));
        }
        double hot = c.temperature(target);
        assertTrue(hot > 798, "red-hot (past Draper's 798 K)");
        // a low sun misses the plate
        c.setDaylight(new dev.emergence.core.math.Vec3(Math.sin(Math.toRadians(20)), -Math.cos(Math.toRadians(20)), 0), 1000, id -> true);
        System.out.printf("  sun 20 degrees off the axis: plate absorbs %.1f W%n", c.sunlightAbsorbed(target));
        assertTrue(c.sunlightAbsorbed(target) < 0.1 * onPlate, "the spot moves off the plate");
    }

    /** Cost of tracing noon sunlight through every exhibit (done in-world when the sun moves half a degree). */
    @Test
    void sunlightTracingCostPerExhibit() {
        double worst = 0, total = 0;
        StringBuilder log = new StringBuilder();
        for (Exhibit e : Exhibits.all()) {
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
            c.setDaylight(new dev.emergence.core.math.Vec3(0.3, -0.95, 0.1), 1000, id -> true);
            double ms = c.sunTraceCost()[1];
            worst = Math.max(worst, ms);
            total += ms;
            log.append(String.format(" %s %.0f", e.id, ms));
        }
        System.out.printf("sun tracing [ms]:%s; total %.0f, worst %.0f%n", log, total, worst);
        assertTrue(worst < 1000, "a trace stays under a second: " + worst);
    }

    /** Incandescent lamp: the filament glows white-hot and its Planck radiation lights the photodiode's receiver. */
    @Test
    void lampFilamentLightsThePhotodiode() {
        Exhibit e = OpticsExhibits.lamp();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pd = b.id(e, "pd"), lit = b.id(e, "lit"), fil = b.id(e, "filament");
        run(c, 0.5);
        assertTrue(Math.abs(c.probe(pd).current()) < 1e-9, "no photocurrent while the lamp is off (warm bodies' infrared lies beyond silicon's band)");
        assertTrue(c.probe(lit).current() < 1e-4, "the green LED off");
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 3);
        double t = c.temperature(fil), i = c.probe(fil).current(), in = c.lightInput(pd);
        // the filament at T seen from the window 3.5 cells away: g = ε·(πdL/4)·A/(πr²), a quarter of its surface its
        // mean projected area
        double r = 3.5 / 16, g = 0.30 * Math.PI * 1.2e-4 / 16 / 4 * 7.5e-6 / (Math.PI * r * r);
        double expect = g * dev.emergence.core.units.PhysicalConstants.STEFAN_BOLTZMANN * (Math.pow(t, 4) - Math.pow(AMBIENT, 4));
        System.out.printf("lamp: filament %.0f K, %.3f A; photodiode %.3f µW (estimate %.3f µW), R_b(T) %.3f A/W; green LED %.2f mA%n",
                t, i, 1e6 * in, 1e6 * expect, dev.emergence.core.sim.elements.Photodiode.blackbodyResponsivity(t), 1e3 * c.probe(lit).current());
        assertTrue(t > 2300 && t < 3300, "white-hot: " + t);
        assertEquals(expect, in, 0.02 * expect, "Lambertian emission of its surface onto the window");
        assertTrue(c.probe(lit).current() > 2e-3, "the green LED lights");
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 3);
        System.out.printf("  switched off: filament %.0f K, green LED %.3g mA%n", c.temperature(fil), 1e3 * c.probe(lit).current());
        assertTrue(c.probe(lit).current() < 1e-4, "dark again as the filament cools");
    }

    /** Metal fatigue: at the same rotating-bending stress the aluminium shaft cracks after its Basquin life; steel runs on. */
    @Test
    void aluminiumShaftFatiguesSteelRunsOn() {
        Exhibit e = Exhibits.fatigue();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long alRoot = b.id(e, "aluminiumRoot"), stRoot = b.id(e, "steelRoot");
        double[] al = c.fatigue(alRoot), st = c.fatigue(stRoot);
        // the overhung load's moment at the outer bearing: Σ m·g·(distance beyond it), as 32M/(πd³)
        var asm = b.assembly();
        var bearingCell = asm.get(alRoot);
        double m = 0;
        for (long id : c.bodyOf(alRoot).parts()) {
            var p = asm.get(id);
            double arm = p.center().x() - bearingCell.center().x();
            if (arm > 0) m += dev.emergence.core.assembly.PartGeometry.mass(p) * dev.emergence.core.assembly.PartGeometry.gravity() * arm;
        }
        double sigma = 32 * m / (Math.PI * Math.pow(0.0084, 3));
        System.out.printf("fatigue: %.1f N·m at the bearing → %.1f MPa (analysed %.1f MPa aluminium, %.1f steel); lives %.0f and %.3g cycles%n",
                m, sigma / 1e6, al[0] / 1e6, st[0] / 1e6, al[2], st[2]);
        assertEquals(sigma, al[0], 0.01 * sigma, "rotating-bending amplitude at the bearing");
        assertTrue(Double.isInfinite(st[2]), "steel below its endurance limit lasts for ever");
        assertTrue(al[2] > 1e3 && al[2] < 1e4, "aluminium has a finite life");
        for (String k : new String[]{"aluminium", "steel"}) {
            double[] f = c.fatigue(b.id(e, k + "Countershaft")); // (null: nothing overhung to bend it)
            assertTrue(f == null || Double.isInfinite(f[2]), k + ": the drive's countershaft lasts");
        }
        // both at rest: each cranked up by hand through its gears in turn, then left to run; the aluminium shaft's
        // revolutions counted from its angle until it cracks
        long alBody = alRoot;
        double[] turned = {0}, last = {c.probe(alBody).angle()};
        boolean broke = false;
        java.util.function.Supplier<java.util.List<Long>> step = () -> {
            var gone = c.step(0.05);
            if (!gone.contains(alRoot)) {
                double th = c.probe(alBody).angle();
                turned[0] += Math.abs(th - last[0]) / (2 * Math.PI);
                last[0] = th;
            }
            return gone;
        };
        for (String k : new String[]{"aluminium", "steel"}) {
            long crank = b.id(e, k + "Crank");
            for (int i = 0; i < 300; i++) {
                c.setCrankEffort(crank, 1, false);
                assertFalse(step.get().contains(alRoot), "no crack while still spinning up");
            }
            c.setCrankEffort(crank, 0, false);
            double w = Math.abs(c.probe(b.id(e, k + "Root")).angularVelocity());
            System.out.printf("  %s cranked for 15 s: %.0f rad/s%n", k, w);
            assertTrue(w > 100, k + ": a hand spins it up through its gears");
        }
        for (int i = 0; i < 4000 && !broke; i++) {
            var gone = step.get();
            if (gone.contains(alRoot)) {
                broke = true;
                System.out.printf("  aluminium cracked after %.0f turns (%.1f s; Miner's sum of the Basquin life %.0f); its disc falls: %s%n",
                        turned[0], c.time(), al[2], gone.contains(b.id(e, "aluminiumDisc")));
                assertEquals(al[2], turned[0], 0.05 * al[2], "at its Basquin life");
                assertTrue(gone.contains(b.id(e, "aluminiumDisc")), "the disc beyond the crack falls");
                assertFalse(gone.contains(stRoot), "the steel shaft is intact");
            }
        }
        assertTrue(broke, "the aluminium shaft cracks within 200 s");
        System.out.printf("  steel: still turning at %.0f rad/s, damage %.3g%n", c.probe(stRoot).angularVelocity(), c.fatigue(stRoot)[1]);
        assertEquals(0, c.fatigue(stRoot)[1], 0, "no damage below the endurance limit");
    }

    /** Only the fatigue exhibit's aluminium shaft has a finite life: the gallery's other machines run indefinitely. */
    @Test
    void galleryShaftsOutliveTheirUse() {
        StringBuilder log = new StringBuilder();
        for (Exhibit e : Exhibits.all()) {
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
            double worst = 0, life = Double.POSITIVE_INFINITY;
            for (var p : b.assembly().parts()) {
                double[] f = c.fatigue(p.id());
                if (f == null) continue;
                worst = Math.max(worst, f[0]);
                life = Math.min(life, f[2]);
            }
            if (worst > 0) log.append(String.format(" %s %.0f MPa (life %.3g)", e.id, worst / 1e6, life));
            if (!e.id.equals("fatigue")) assertTrue(Double.isInfinite(life) || life > 1e9, e.id + ": a shaft would fatigue in " + life + " turns");
        }
        System.out.println("rotating-bending amplitudes:" + log);
    }

    /** Eddy-current brake: the aluminium disc slows and warms by the kinetic energy it loses; the nylon one coasts. */
    @Test
    void eddyCurrentsBrakeTheAluminiumDisc() {
        Exhibit e = Exhibits.eddyBrake();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long al = b.id(e, "aluminiumShaft"), ny = b.id(e, "nylonShaft");
        // at rest, as a player finds them: the nylon disc cranked up, then the aluminium one, each let go at speed
        for (String s : new String[]{"nylonCrank", "aluminiumCrank"}) {
            for (int i = 0; i < 200; i++) {
                c.setCrankEffort(b.id(e, s), 1, true);
                c.step(0.05);
            }
            c.setCrankEffort(b.id(e, s), 0, false);
        }
        double j = c.bodyOf(al).inertia(), w0 = c.probe(al).angularVelocity(), wn0 = c.probe(ny).angularVelocity();
        // the heat in the aluminium disc (the hand's work while cranking against the magnets is already there: counted from now)
        java.util.function.DoubleSupplier discHeat = () -> {
            double h = 0;
            for (long id : c.bodyOf(al).parts()) {
                var p = b.assembly().get(id);
                if (!p.material().id().equals("aluminium_6061")) continue;
                h += (c.temperature(id) - AMBIENT) * dev.emergence.core.assembly.PartGeometry.mass(p)
                        * p.material().get(dev.emergence.core.materials.MaterialProperty.SPECIFIC_HEAT, 0);
            }
            return h;
        };
        double heat0 = discHeat.getAsDouble();
        StringBuilder log = new StringBuilder();
        for (int s = 0; s < 2; s++) { // stopped within some 4 s; judged then, before the hot disc sheds much heat to the air
            run(c, 2);
            log.append(String.format(" %.0f s: %.1f / %.1f rad/s;", c.time(), c.probe(al).angularVelocity(), c.probe(ny).angularVelocity()));
        }
        double wAl = c.probe(al).angularVelocity(), wNy = c.probe(ny).angularVelocity();
        // the heat the disc gained against the kinetic energy it lost since it was let go (its bearings take a little)
        double heat = discHeat.getAsDouble() - heat0;
        double lost = 0.5 * j * (w0 * w0 - wAl * wAl);
        System.out.printf("eddy brake:%s kinetic energy lost %.1f J, heat in the disc %.1f J%n", log, lost, heat);
        assertTrue(wAl < 0.3 * w0, "the aluminium disc is braked: " + wAl);
        assertTrue(wNy > 0.9 * wn0, "the nylon disc coasts: " + wNy);
        assertEquals(lost, heat, 0.1 * lost, "the kinetic energy becomes heat in the disc");
    }

    /** Prism: each colour's beam is turned by δ = i + e − A, Snell at both faces with the glass's index at its colour. */
    @Test
    void prismSpreadsTheColours() {
        Exhibit e = OpticsExhibits.prism();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        String[] col = {"red", "green", "blue"};
        int[] nm = {630, 525, 465};
        double a = Math.toRadians(45), i = Math.toRadians(22.5); // a beam parallel to the base meets the face at A/2
        double[] turn = new double[3];
        StringBuilder log = new StringBuilder();
        for (int k = 0; k < 3; k++) {
            var rays = c.beams(b.id(e, col[k]));
            assertNotNull(rays, col[k] + " beam");
            var chief = rays.get(0);
            assertEquals(4, chief.size(), "LED, into the prism, out of it, onward");
            var out = chief.get(3).sub(chief.get(2)).normalize();
            turn[k] = Math.acos(out.x());
            double n = dev.emergence.core.optics.Optics.cauchy(1.78472, 25.68, nm[k]);
            double r1 = Math.asin(Math.sin(i) / n), ex = Math.asin(n * Math.sin(a - r1));
            double expect = i + ex - a;
            log.append(String.format(" %s n %.4f: %.3f° (δ = i + e − A: %.3f°);", col[k], n, Math.toDegrees(turn[k]), Math.toDegrees(expect)));
            assertEquals(expect, turn[k], 1e-6, col[k]);
            assertTrue(out.z() > 0, "bent towards the base (south)");
        }
        System.out.println("prism:" + log);
        assertTrue(turn[2] > turn[1] && turn[1] > turn[0], "blue turned most, red least");
        assertTrue(Math.toDegrees(turn[2] - turn[0]) > 3, "a fan several degrees wide");
    }

    /**
     * Relay memory: START pulls the relay in and its own contacts then feed its coil, so it holds with START let go;
     * STOP breaks the hold, and closed again leaves it out.
     */
    @Test
    void relayLatchHoldsItselfInUntilStopped() {
        Exhibit e = Exhibits.relayLatch();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        long slide = b.id(e, "slide"), lamp = b.id(e, "lamp"), start = b.id(e, "start"), stop = b.id(e, "stop");
        run(c, 0.5);
        double down = -0.0015, up = 0.0015;
        assertEquals(down, c.probe(slide).angle(), 1e-4, "at rest, out");
        c.toggleSwitch(start); // START closed: it pulls in
        run(c, 0.6);
        double pulled = c.probe(slide).angle(), lit = c.probe(lamp).current();
        c.toggleSwitch(start); // START opened again: it holds itself in through its own contacts
        run(c, 1.0);
        double held = c.probe(slide).angle(), stillLit = c.probe(lamp).current();
        c.toggleSwitch(stop); // STOP opened: the hold is broken
        run(c, 0.6);
        double dropped = c.probe(slide).angle();
        c.toggleSwitch(stop); // STOP closed again: it stays out
        run(c, 1.0);
        double after = c.probe(slide).angle(), dark = c.probe(lamp).current();
        System.out.printf("relay memory: START → %.2f mm (LED %.1f mA); released START → %.2f mm (LED %.1f mA); STOP → %.2f mm; "
                + "STOP closed again → %.2f mm (LED %.3g mA)%n", 1e3 * pulled, 1e3 * lit, 1e3 * held, 1e3 * stillLit, 1e3 * dropped, 1e3 * after, 1e3 * dark);
        assertEquals(up, pulled, 1e-4, "START pulls it in");
        assertTrue(lit > 2e-3, "the LED shows it energised: " + lit);
        assertEquals(up, held, 1e-4, "START let go, it holds itself in");
        assertTrue(stillLit > 2e-3, "still energised through its own contacts: " + stillLit);
        assertEquals(down, dropped, 1e-4, "STOP breaks the hold");
        assertEquals(down, after, 1e-4, "STOP closed again, it stays out");
        assertTrue(dark < 1e-5, "and dark: " + dark);
    }

    /** Relay: the energised coil pulls the armature across its gap, closing the lamp circuit; released, it drops open. */
    @Test
    void relayCoilPullsTheArmatureAndClosesTheContacts() {
        Exhibit e = Exhibits.relay();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long slide = b.id(e, "slide"), lamp = b.id(e, "lamp"), coil = b.id(e, "coil");
        run(c, 0.5);
        double rest = c.probe(slide).angle();
        System.out.printf("relay: at rest the armature at %.2f mm, LED %.3g mA%n", 1e3 * rest, 1e3 * c.probe(lamp).current());
        assertEquals(-0.0015, rest, 1e-4, "it rests on its lower stop");
        assertTrue(c.probe(lamp).current() < 1e-5, "the contacts are open");
        c.toggleSwitch(b.id(e, "switch"));
        double e0 = c.energyError();
        for (int k = 0; k < 10; k++) {
            run(c, 0.05);
            System.out.printf("  t+%.2f s: coil %.3f A, armature %.2f mm, LED %.2f mA%n", 0.05 * (k + 1), c.probe(coil).current(),
                    1e3 * c.probe(slide).angle(), 1e3 * c.probe(lamp).current());
        }
        run(c, 0.5);
        assertEquals(0.0015, c.probe(slide).angle(), 1e-4, "pulled up against the core");
        assertTrue(c.probe(lamp).current() > 5e-3, "the LED lights: " + c.probe(lamp).current());
        System.out.printf("  energy error over the pull-in %.3g J%n", c.energyError() - e0);
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 0.5);
        System.out.printf("  released: armature %.2f mm, LED %.3g mA%n", 1e3 * c.probe(slide).angle(), 1e3 * c.probe(lamp).current());
        assertEquals(-0.0015, c.probe(slide).angle(), 1e-4, "it drops back");
        assertTrue(c.probe(lamp).current() < 1e-5, "the light goes out");
    }

    /** Trembler: a relay feeding its coil through its own normally-closed contacts oscillates. */
    @Test
    void tremblerOscillates() {
        Exhibit e = Exhibits.trembler();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long slide = b.id(e, "slide"), coil = b.id(e, "coil");
        run(c, 0.3);
        assertEquals(-0.0015, c.probe(slide).angle(), 1e-4, "rests on the closed contacts");
        c.toggleSwitch(b.id(e, "switch"));
        int ups = 0;
        boolean up = false;
        double peak = 0;
        StringBuilder log = new StringBuilder();
        for (int k = 0; k < 400; k++) {
            c.step(0.01);
            double x = c.probe(slide).angle();
            peak = Math.max(peak, Math.abs(c.probe(coil).current()));
            if (!up && x > 0.001) {
                up = true;
                ups++;
                log.append(String.format(" %.2f", c.time()));
            } else if (up && x < -0.001) up = false;
        }
        System.out.printf("trembler: pulled up %d times in 4 s, at%s s; peak coil current %.2f A; energy error %.3g J%n", ups, log, peak, c.energyError());
        assertTrue(ups >= 4, "it keeps cycling: " + ups);
        // it buzzes: the armature's tone, at the rate it cycles
        var sounds = c.sounds();
        assertFalse(sounds.isEmpty(), "the armature sounds");
        System.out.printf("  trembler sounds at %.2f Hz, %.1f dB at a metre%n", sounds.get(0).tone().frequency(), sounds.get(0).tone().level(1, 1));
        assertEquals(ups / 4.0, sounds.get(0).tone().frequency(), 1.0, "at its cycling rate");
        assertTrue(c.probe(b.id(e, "diode")).temperature() < 400, "the flyback diode copes");
    }

    /**
     * Speed governor: braked, the governed motor recovers its speed (within 5 %); the same motor on a fixed supply
     * that ran it at that speed unloaded slows by a quarter and more.
     */
    @Test
    void governorHoldsItsSpeedUnderLoad() {
        double[] sag = new double[2];
        for (int m = 0; m < 2; m++) {
            Exhibit e = m == 0 ? ControlExhibits.governor() : ControlExhibits.governor(9.5);
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            CompiledAssembly c = compile(e, b);
            if (m == 1) c.toggleSwitch(b.id(e, "switch"));
            long rotor = b.id(e, "rotor");
            startByHand(c, rotor); // at rest, as a player finds it
            run(c, 20);
            double before = 0, after = 0;
            for (int k = 0; k < 6; k++) {
                run(c, 0.5);
                before += c.probe(rotor).angularVelocity() / 6;
            }
            c.toggleSwitch(b.id(e, "brake"));
            run(c, 10);
            for (int k = 0; k < 6; k++) {
                run(c, 0.5);
                after += c.probe(rotor).angularVelocity() / 6;
            }
            sag[m] = 1 - after / before;
            System.out.printf("governor (%s): %.2f rad/s free, %.2f rad/s braked (%.1f %% slower)%n", m == 0 ? "governed" : "fixed 9.5 V",
                    before, after, 100 * sag[m]);
        }
        assertEquals(0, sag[0], 0.05, "the governor restores the speed");
        assertTrue(sag[1] > 0.25, "without it the brake drags the motor down: " + sag[1]);
    }

    /**
     * Contact governor: the weight lifts off its contact near √(F₀/(m r)) and the speed then holds — braked, the motor
     * draws several times the current and keeps its speed within 2 %.
     */
    @Test
    void contactGovernorHoldsItsSpeed() {
        Exhibit e = ControlExhibits.contactGovernor();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long rotor = b.id(e, "rotor"), weight = b.id(e, "weight"), bat = b.id(e, "battery");
        c.toggleSwitch(b.id(e, "switch"));
        startByHand(c, rotor); // at rest, as a player finds it
        run(c, 30);
        double[] w = new double[2], amps = new double[2];
        for (int m = 0; m < 2; m++) {
            if (m == 1) {
                c.toggleSwitch(b.id(e, "brake"));
                run(c, 8);
            }
            int n = 100;
            for (int k = 0; k < n; k++) {
                c.step(0.05);
                w[m] += c.probe(rotor).angularVelocity() / n;
                amps[m] += Math.abs(c.probe(bat).current()) / n;
            }
        }
        System.out.printf("contact governor: %.2f rad/s at %.2f A free, %.2f rad/s at %.2f A braked; weight off its stop by %.3f mm%n",
                w[0], amps[0], w[1], amps[1], 1e3 * c.probe(weight).angle());
        assertTrue(w[0] > 33 && w[0] < 40, "it governs near 37 rad/s: " + w[0]);
        assertEquals(w[0], w[1], 0.02 * w[0], "braked, it keeps its speed");
        assertTrue(amps[1] > 3 * amps[0], "by taking more current through the contact: " + amps[0] + " → " + amps[1]);
    }

    /**
     * Flyball governor: the weights fly out, their bars draw the sleeve down and the push rod turns the rheostat in;
     * braked, the speed sags a little, the weights sink in, the rheostat backs off and the motor takes more current.
     */
    @Test
    void flyballGovernorReinsTheMotorIn() {
        Exhibit e = ControlExhibits.flyball();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long rotor = b.id(e, "rotor"), bat = b.id(e, "battery"), crank = b.id(e, "crankShaft"), rod = b.id(e, "rodA");
        assertFalse(Double.isNaN(c.linkTension(b.id(e, "barA"))), "the weights' bars work");
        assertFalse(Double.isNaN(c.linkTension(b.id(e, "pushRod"))), "the push rod works");
        c.toggleSwitch(b.id(e, "switch"));
        startByHand(c, rotor); // at rest, as a player finds it
        double[] w = new double[2], amps = new double[2], turn = new double[2], out = new double[2];
        for (int m = 0; m < 2; m++) {
            if (m == 1) c.toggleSwitch(b.id(e, "brake"));
            // its steady state, not its first seconds: with too small a rheostat it crept past its range over a minute
            run(c, m == 0 ? 40 : 20);
            int n = 40;
            for (int k = 0; k < n; k++) {
                c.step(0.05);
                w[m] += c.probe(rotor).angularVelocity() / n;
                amps[m] += Math.abs(c.probe(bat).current()) / n;
            }
            turn[m] = c.probe(crank).angle();
            out[m] = -c.probe(rod).angle();
        }
        System.out.printf("flyball: %.2f rad/s at %.2f A, weights out %.1f mm, rheostat turned %.1f°; braked %.2f rad/s at %.2f A, "
                        + "out %.1f mm, %.1f°; sleeve %.2f mm; energy error %.3g J%n", w[0], amps[0], 1e3 * out[0], Math.toDegrees(turn[0]),
                w[1], amps[1], 1e3 * out[1], Math.toDegrees(turn[1]), 1e3 * c.probe(b.id(e, "sleeve")).angle(), c.energyError());
        assertTrue(out[0] > 0.003 && out[0] < 0.027, "the weights ride out between their stops: " + out[0]);
        assertTrue(turn[0] > Math.toRadians(2), "the push rod has turned the rheostat in: " + turn[0]);
        // a spring-loaded centrifugal governor without reset holds its speed only proportionally: some droop under load
        assertTrue(Math.abs(w[1] - w[0]) < 0.10 * w[0], "braked, it keeps its speed within a tenth: " + w[0] + " → " + w[1]);
        assertTrue(turn[1] < turn[0] && out[1] < out[0], "the weights sink in and the rheostat backs off");
        assertTrue(amps[1] > 1.08 * amps[0], "so the motor takes more current: " + amps[0] + " → " + amps[1]);
    }

    /**
     * Every exhibit stays on its plot: in front of the walkway (z < 16, where the signs stand) and clear of the 4-block
     * aisle that keeps neighbouring exhibits out of each other's interaction reach (x < 20·plots − 4).
     */
    @Test
    void exhibitsStayOnTheirPlots() {
        StringBuilder bad = new StringBuilder();
        for (Exhibit e : Exhibits.all()) {
            int maxX = 20 * e.plots - 4;
            for (Exhibit.PartDef d : e.parts) {
                if (d.x0() < 0 || d.z0() < 0 || d.y0() < 0 || d.x1() >= maxX || d.z1() >= 16) {
                    bad.append(String.format("%n  %s: %s at (%d,%d,%d)-(%d,%d,%d)", e.id, d.kind(), d.x0(), d.y0(), d.z0(), d.x1(), d.y1(), d.z1()));
                    break;
                }
            }
        }
        assertTrue(bad.isEmpty(), "parts off their plots:" + bad);
    }

    /**
     * Spectrometer: the prism spreads the filament's light so the blue detector sees the shortest wavelengths and the
     * infrared one the longest; heating the filament lights the infrared channel first, then the red, then the blue.
     */
    @Test
    void spectrometerSeesTheGlowShiftToBlue() {
        Exhibit e = OpticsExhibits.spectrometer();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        String[] ch = {"blue", "red", "infrared"};
        double last = 0;
        for (String k : ch) {
            double[] g = c.spectralCoupling(b.id(e, "pd_" + k));
            double sum = 0, mean = 0;
            for (int j = 0; j < g.length; j++) {
                sum += g[j];
                mean += g[j] * dev.emergence.core.sim.elements.Photodiode.bandCentre(j);
            }
            mean /= sum;
            System.out.printf("spectrometer: %s detector sees %.0f nm on average%n", k, mean);
            assertTrue(sum > 0 && mean > last, k + " lies further along the spectrum");
            last = mean;
        }
        run(c, 0.5);
        String[] sw = {"switch", "warmer", "hotter"};
        boolean[][] want = {{false, false, true}, {false, true, true}, {true, true, true}};
        for (int s = 0; s < 3; s++) {
            c.toggleSwitch(b.id(e, sw[s]));
            run(c, 2);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 3; k++) {
                double led = c.probe(b.id(e, "lit_" + ch[k])).current();
                sb.append(String.format(" %s %s;", ch[k], led > 1e-3 ? "lit" : "dark"));
                assertEquals(want[s][k], led > 1e-3, sw[s] + ": the " + ch[k] + " channel");
            }
            System.out.printf("  %s: filament %.0f K:%s%n", sw[s], c.probe(b.id(e, "filament")).temperature(), sb);
        }
    }

    /** Loudspeaker: silent, the microphone's LED is dark; singing, the sound reaches the microphone and lights it. */
    @Test
    void loudspeakerIsHeardByTheMicrophone() {
        Exhibit e = AcousticsExhibits.loudspeaker();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long mic = b.id(e, "micRod"), lit = b.id(e, "lit");
        run(c, 0.15); // the amplifier settles from power-on
        assertTrue(c.probe(lit).current() < 1e-6, "silence: the LED is dark");
        c.toggleSwitch(b.id(e, "switch"));
        double micPeak = 0, spkPeak = 0;
        for (int k = 0; k < 600; k++) {
            c.step(2e-4);
            micPeak = Math.max(micPeak, Math.abs(c.probe(mic).angularVelocity()));
            spkPeak = Math.max(spkPeak, Math.abs(c.probe(b.id(e, "spkRod")).angularVelocity()));
        }
        System.out.printf("loudspeaker: plate %.3g m/s, microphone plate %.3g m/s, hold %.2f V, LED %.2f mA%n", spkPeak, micPeak,
                c.probe(b.id(e, "hold")).voltage(), 1e3 * c.probe(lit).current());
        assertTrue(micPeak > 1e-5, "the air sets the microphone's plate moving: " + micPeak);
        assertTrue(c.probe(lit).current() > 5e-3, "the microphone hears it: the LED lights");
        var sounds = c.sounds();
        var loudest = sounds.stream().max(java.util.Comparator.comparingDouble(s -> s.tone().rmsPressure())).orElseThrow();
        System.out.printf("  the loudspeaker sounds at %.1f Hz, %.1f dB at a metre%n", loudest.tone().frequency(), loudest.tone().level(1, 1));
        assertEquals(250, loudest.tone().frequency(), 15, "its note");
    }

    /** A magnet holds its armature and weight at contact, but cannot lift the same from 5 cm away. */
    @Test
    void magnetHoldsAtContactButCannotLiftFromAfar() {
        Exhibit e = Exhibits.magnetPull();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long near = b.id(e, "near"), far = b.id(e, "far");
        run(c, 1.0);
        double xn = c.probe(near).angle(), xf = c.probe(far).angle();
        System.out.printf("magnet pull: near armature at %.1f mm, far armature at %.1f mm; energy error %.3g J%n", 1e3 * xn, 1e3 * xf, c.energyError());
        assertTrue(xn > -0.001, "the near one is held against its magnet: " + xn);
        assertTrue(xf < -0.1, "the far one falls to its stop: " + xf);
    }

    /** Still: boiling 10 % ethanol gives a distillate several times richer, and leaves the pot weaker. */
    @Test
    void stillConcentratesEthanol() {
        Exhibit e = ChemistryExhibits.still();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var pot = c.gas(b.id(e, "pot"));
        var rec = c.gas(b.id(e, "receiver"));
        int iE = -1, iW = -1;
        for (int i = 0; i < pot.species().size(); i++) {
            if (pot.species().get(i).id().equals("C2H5OH")) iE = i;
            if (pot.species().get(i).id().equals("H2O")) iW = i;
        }
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 300);
        var s = c.solverView();
        double pe = pot.liquidMoles(s, iE), pw = pot.liquidMoles(s, iW), re = rec.liquidMoles(s, iE), rw = rec.liquidMoles(s, iW);
        System.out.printf("still: pot %.1f °C, %.3f mol at %.3f ethanol; distillate %.4f mol at %.3f ethanol%n", pot.temperature(s) - 273.15,
                pe + pw, pe / (pe + pw), re + rw, re / (re + rw));
        assertTrue(re + rw > 0.03, "a distillate collects: " + (re + rw));
        assertTrue(re / (re + rw) > 0.3, "several times richer in ethanol than the 10 % charge");
        assertTrue(pe / (pe + pw) < 0.095, "the pot loses its ethanol faster than its water");
    }

    /**
     * The still idle in noon sunshine, as in the world before anyone switches it on: no vessel's gas or liquid goes
     * below empty, and the solver never fails. (Gas and liquid passages used to carry the blend of both ends' contents
     * through zero flow: a trickle out of a pipe holding no vapour or liquid took its neighbour's, the pipe's moles went
     * negative, and after a minute its energy did too — 30-second server ticks, then a failed island.)
     */
    @Test
    void stillIdlesInSunshineWithoutDrainingAPipeBelowEmpty() {
        Exhibit e = ChemistryExhibits.still();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        c.setDaylight(new dev.emergence.core.math.Vec3(0, -1, 0), 1000, id -> true);
        double least = Double.POSITIVE_INFINITY;
        String where = "";
        for (int k = 0; k < 24; k++) {
            run(c, 5);
            var s = c.solverView();
            for (var part : b.assembly().parts()) {
                var g = c.gas(part.id());
                if (g == null) continue;
                for (int i = 0; i < g.species().size(); i++) {
                    // (a liquid pool only for the condensables)
                    double gas = g.moles(s, g.species().get(i)), liq = g.liquidIndex(i) >= 0 ? g.liquidMoles(s, i) : Double.POSITIVE_INFINITY;
                    if (Math.min(gas, liq) < least) {
                        least = Math.min(gas, liq);
                        where = part.kind() + "#" + part.id() + " " + g.species().get(i).id() + (gas < liq ? " gas" : " liquid") + " at t = " + s.time();
                    }
                }
            }
        }
        System.out.printf("still idle in the sun: least moles anywhere %.3g (%s), %d Newton failures%n", least, where, c.solver().newtonFailures());
        assertTrue(least > -1e-9, "nothing below empty: " + least + " mol, " + where);
        assertEquals(0, c.solver().newtonFailures(), "Newton never fails");
    }

    /**
     * Reflux column: after six minutes of boiling, each tray up is richer in ethanol, and the condensate is enriched over
     * the pot far beyond one still stage (separation factor (x_D/(1 − x_D))/(x_W/(1 − x_W)) ≈ 11 for the still).
     */
    @Test
    void columnSeparatesBeyondOneStage() {
        Exhibit e = ChemistryExhibits.column();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        var pot = c.gas(b.id(e, "pot"));
        int iE = -1, iW = -1;
        for (int i = 0; i < pot.species().size(); i++) {
            if (pot.species().get(i).id().equals("C2H5OH")) iE = i;
            if (pot.species().get(i).id().equals("H2O")) iW = i;
        }
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 360);
        var s = c.solverView();
        String[] names = {"pot", "tray1", "tray2", "tray3", "condenser"};
        double[] x = new double[names.length];
        StringBuilder sb = new StringBuilder("column (liquid ethanol fractions):");
        for (int k = 0; k < names.length; k++) {
            var g = c.gas(b.id(e, names[k]));
            double le = g.liquidMoles(s, iE), lw = g.liquidMoles(s, iW);
            x[k] = le / (le + lw);
            sb.append(String.format(" %s %.3f (%.0f °C)", names[k], x[k], g.temperature(s) - 273.15));
        }
        double sep = (x[4] / (1 - x[4])) / (x[0] / (1 - x[0]));
        System.out.println(sb + String.format("; separation factor %.1f", sep));
        assertTrue(x[3] > x[2] && x[2] > x[0], "richer up the column");
        assertTrue(x[4] > 0.45, "the condensate is about half ethanol: " + x[4]);
        assertTrue(sep > 14, "beyond one still stage (≈ 11): " + sep);
    }

    /**
     * Fan cooling: at rest the two warm plates cool alike (no draft through the idle duct); cranked, the fan's jet
     * carries off several times the heater's power and the plate in it falls well below its twin.
     */
    @Test
    void fanJetCoolsItsPlate() {
        Exhibit e = ThermoExhibits.fanCooling();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long cooled = b.id(e, "cooled"), still = b.id(e, "still");
        run(c, 20);
        double idle = c.temperature(cooled) - c.temperature(still);
        double jetIdle = jetHeat(c);
        c.setCrankEffort(b.id(e, "crank"), 1, false);
        run(c, 60);
        double gap = c.temperature(still) - c.temperature(cooled), jet = jetHeat(c);
        double h = jet / (c.temperature(cooled) - AMBIENT);
        System.out.printf("fan cooling: idle gap %.3f K (jet %.3f W); cranked 60 s: fan %.0f rad/s, %.1f g/s, plates %.1f / %.1f °C, jet %.1f W (%.3f W/K)%n",
                idle, jetIdle, c.probe(b.id(e, "fanShaft")).angularVelocity(), 1e3 * c.vents().stream().mapToDouble(c::ventOutflow).max().orElse(0),
                c.temperature(cooled) - 273.15, c.temperature(still) - 273.15, jet, h);
        assertEquals(0, idle, 0.05, "an idle duct draws no draft: the plates cool alike");
        assertTrue(jetIdle < 0.01, "no jet without the fan");
        assertTrue(gap > 4, "the plate in the jet falls well below its twin within a minute: " + gap);
        assertTrue(jet > 2 * 4.5, "the jet carries off more than twice the heater's power: " + jet);
        assertEquals(0.36, h, 0.05, "h·A: ~30 W/(m²·K) over the plate's 0.012 m² (Churchill–Bernstein at Re ≈ 19 000)");
    }

    static double jetHeat(CompiledAssembly c) {
        double q = 0;
        for (var el : c.network().elements())
            if (el instanceof dev.emergence.core.sim.elements.JetConvection j) q += j.heatFlow(c.solverView());
        return q;
    }

    /**
     * Unbalanced rotor: coasting down from well above the platform's natural frequency, the wheel shakes it little at
     * first, hardest as its speed passes √(k/M), and little again below — with no machine-specific code: the shaking is
     * the carried rotor's inertial coupling, the resonance the springs and the platform's mass.
     */
    @Test
    void unbalancedRotorShakesItsPlatformHardestAtResonance() {
        Exhibit e = MechanicsExhibits.unbalance();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        CompiledAssembly.Body platform = c.bodyOf(b.id(e, "rod")), wheel = c.bodyOf(b.id(e, "shaft"));
        assertSame(platform, wheel.carrier(), "the wheel rides on the platform");
        double k = dev.emergence.core.assembly.PartGeometry.springRate(b.assembly().get(b.id(e, "springA")))
                + dev.emergence.core.assembly.PartGeometry.springRate(b.assembly().get(b.id(e, "springB")));
        double wn = Math.sqrt(k / (platform.mass() + wheel.mass()));
        // at rest as a player finds it: cranked up well past resonance, then let go
        for (int i = 0; i < 2000 && Math.abs(c.angularVelocity(wheel)) < 28; i++) {
            c.setCrankEffort(b.id(e, "crank"), 1, true);
            c.step(0.05);
        }
        c.setCrankEffort(b.id(e, "crank"), 0, false);
        double w0 = c.angularVelocity(wheel), e0 = 0.5 * wheel.inertia() * w0 * w0, first = 0, peak = 0, peakSpeed = 0, last = 0;
        for (int w = 0; w < 20; w++) {
            double lo = 1e9, hi = -1e9, speed = 0;
            for (int j = 0; j < 40; j++) {
                c.step(0.05);
                lo = Math.min(lo, c.angle(platform));
                hi = Math.max(hi, c.angle(platform));
                speed += c.angularVelocity(wheel) / 40;
            }
            double amp = (hi - lo) / 2;
            if (w == 1) first = amp;
            if (amp > peak) {
                peak = amp;
                peakSpeed = speed;
            }
            last = amp;
        }
        System.out.printf("unbalance: ω_n %.1f rad/s; platform ±%.1f mm at speed, ±%.1f mm peak (wheel %.1f rad/s), ±%.2f mm after; energy error %.2g of %.2f J%n",
                wn, 1e3 * first, 1e3 * peak, peakSpeed, 1e3 * last, c.energyError(), e0);
        assertTrue(peak > 2.5 * first, "the shaking peaks well above its level at speed");
        // a slowing sweep: the response builds over ~1/(ζω_n) ≈ 3 s, so it peaks just after the resonance is passed
        // (and the wheel is braked hardest there, the platform's damping taking its energy); steady forced amplitudes
        // are checked against the analytical solution in CarriedRotorTest
        assertTrue(peakSpeed > 0.6 * wn && peakSpeed < 1.1 * wn, "it peaks as the wheel passes the natural frequency √(k/M)");
        assertTrue(last < 0.2 * peak, "below resonance it calms again");
        assertTrue(Math.abs(c.energyError()) < 0.01 * e0, "energy ledger closes (to 1 % of the wheel's energy)");
    }

    /**
     * Four-bar linkage: the coupler keeps its length; the rocker swings between the angles where crank and coupler lie
     * in line (the triangle frame–rocker–(coupler ± crank)), and the flywheel's speed ripples as it swings it.
     */
    @Test
    void fourBarRockerSwingsBetweenTheToggleAngles() {
        Exhibit e = MechanicsExhibits.fourBar();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long coupler = b.id(e, "coupler"), rocker = b.id(e, "rocker"), crank = b.id(e, "crankShaft");
        assertFalse(Double.isNaN(c.linkTension(coupler)), "the coupler is a working link");
        double cell = dev.emergence.core.assembly.Part.CELL;
        // pins: crank (4.5, 4.5) → (5, 5.5); rocker (9.5, 2.5) → (9, 5.5), in cells
        double a = Math.hypot(0.5, 1) * cell, bb = 4 * cell, cc = Math.hypot(0.5, 3) * cell, d = Math.hypot(5, 2) * cell;
        double far = Math.acos((d * d + cc * cc - (bb + a) * (bb + a)) / (2 * d * cc)),
                near = Math.acos((d * d + cc * cc - (bb - a) * (bb - a)) / (2 * d * cc));
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, wLo = Double.MAX_VALUE, wHi = 0, ke0 = 0;
        for (int i = 0; i < 160; i++) {
            c.step(0.02);
            double q = c.probe(rocker).angle(), w = c.probe(crank).angularVelocity();
            if (i == 0) ke0 = 0.5 * c.bodyOf(crank).inertia() * w * w;
            lo = Math.min(lo, q);
            hi = Math.max(hi, q);
            wLo = Math.min(wLo, w);
            wHi = Math.max(wHi, w);
        }
        double[] pins = c.linkPins(coupler);
        System.out.printf("four-bar: rocker swings %.1f° (toggle positions %.1f°); flywheel %.2f–%.2f rad/s; crank turned %.1f turns; "
                        + "tension now %.1f N; energy error %.2g J of %.2f J%n", Math.toDegrees(hi - lo), Math.toDegrees(far - near),
                wLo, wHi, c.probe(crank).angle() / (2 * Math.PI), c.linkTension(coupler), c.energyError(), ke0);
        assertEquals(far - near, hi - lo, Math.toRadians(1), "the rocker swings between the toggle angles");
        assertTrue(c.probe(crank).angle() > 2 * 2 * Math.PI, "the crank turns right round");
        assertTrue(wHi - wLo > 0.05 && wLo > 3, "the flywheel's speed ripples as it swings the rocker: " + wLo + " – " + wHi);
        assertTrue(pins != null && pins.length == 6, "the coupler's pieces know where they are");
        assertTrue(Math.abs(c.energyError()) < 0.02 * ke0, "energy ledger closes: " + c.energyError());
    }

    /**
     * Spinning weights: cranked up to speed, each weight slides out along its arm until its spring supplies the
     * centripetal force its circle needs, k·x = m·ω²·r — from nothing but the carried slide's dynamics.
     */
    @Test
    void spinningWeightsGoOutUntilTheirSpringsHoldThem() {
        Exhibit e = MechanicsExhibits.spinningWeights();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        CompiledAssembly.Body rotor = c.bodyOf(b.id(e, "shaft")), a = c.bodyOf(b.id(e, "rodA")), bb = c.bodyOf(b.id(e, "rodB"));
        assertTrue(a.slider() && a.carrier() == rotor && bb.carrier() == rotor, "the weights' rods are slides on the turning beam");
        double k = dev.emergence.core.assembly.PartGeometry.springRate(b.assembly().get(b.id(e, "springA")));
        for (int i = 0; i < 200; i++) {
            c.setCrankEffort(b.id(e, "crank"), 1, false);
            c.step(0.05);
        }
        double w = c.angularVelocity(rotor), xa = c.angle(a), xb = c.angle(bb);
        dev.emergence.core.sim.elements.CarriedSlide sa = null;
        for (var el : c.network().elements())
            if (el instanceof dev.emergence.core.sim.elements.CarriedSlide cs && sa == null) sa = cs;
        double r = Math.sqrt(sa.radiusSq(xa)), centripetal = a.mass() * w * w * r;
        double ke = 0.5 * (rotor.inertia() + 2 * sa.inertia(xa)) * w * w;
        System.out.printf("weights: ω %.2f rad/s; out %.1f / %.1f mm; spring %.1f N vs m·ω²·r %.1f N (r %.3f m); energy error %.2g of %.1f J%n",
                w, -1e3 * xa, 1e3 * xb, k * Math.abs(xa), centripetal, r, c.energyError(), ke);
        assertTrue(w > 10, "cranked up to speed: " + w);
        assertTrue(Math.abs(xa) > 0.02 && Math.abs(xb) > 0.02, "both weights have gone out");
        assertEquals(-xa, xb, 1e-4, "symmetrically");
        assertEquals(centripetal, k * Math.abs(xa), 0.02 * centripetal, "the spring supplies the centripetal force");
        assertTrue(Math.abs(c.energyError()) < 1e-3 * ke, "energy ledger closes");
    }

    /**
     * Wind turbine: in a steady 8 m/s wind the blades' lift turns the rotor up to where the generator's braking balances
     * it; it takes a fraction of the wind's power through its disc, below the Betz limit, and delivers it to the load.
     * In 5 m/s it makes several times less (power ∝ V³); in still air it does not turn.
     */
    @Test
    void windTurbineTurnsInTheWindAndPowersItsLoad() {
        double[] power = new double[3], cp = new double[3];
        double[] winds = {8, 5, 0};
        for (int k = 0; k < 3; k++) {
            Exhibit e = AeroExhibits.windTurbine();
            Exhibits.Built b = Exhibits.build(e, AMBIENT);
            // started near its running speed (a minute's spin-up skipped; still air: from rest)
            double w0 = new double[]{140, 80, 0}[k];
            b.state().bodyMotion().put(b.id(e, "shaft"), new double[]{w0, 0});
            CompiledAssembly c = compile(e, b);
            CompiledAssembly.Body rotor = c.bodyOf(b.id(e, "shaft"));
            c.setWind(new dev.emergence.core.math.Vec3(winds[k], 0, 0));
            var af = c.aerofoils(rotor);
            assertNotNull(af, "the blades are aerofoils on the rotor");
            run(c, winds[k] > 0 ? 25 : 2);
            double load = 0, aero = 0;
            for (int i = 0; i < 200; i++) {
                c.step(0.05);
                load += c.probe(b.id(e, "load")).power() / 200;
                aero += af.generalisedForce(c.solverView()) * c.angularVelocity(rotor) / 200;
            }
            power[k] = load;
            cp[k] = winds[k] > 0 ? aero / (0.5 * 1.2 * af.discArea() * Math.pow(winds[k], 3)) : 0;
            System.out.printf("wind turbine at %.0f m/s: ω %.1f rad/s, from the wind %.2f W (C_p %.3f), load %.2f W; energy error %.2g of %.3g J in%n",
                    winds[k], c.angularVelocity(rotor), aero, cp[k], load, c.energyError(), c.solver().externalEnergyIn());
            // (the ledger integrates the wind's power at step ends: 2.5·10⁻³ over the spin-up in 50 ms chunks)
            if (winds[k] > 0) assertTrue(Math.abs(c.energyError()) < 1e-2 * c.solver().externalEnergyIn(), "energy ledger closes");
        }
        assertTrue(power[0] > 3, "a few watts in a fresh breeze: " + power[0]);
        assertTrue(cp[0] > 0.05 && cp[0] < 16.0 / 27, "a fraction of the wind's power, below Betz: " + cp[0]);
        assertTrue(power[0] > 2.5 * power[1], "much less in a light wind (∝ V³): " + power[0] + " vs " + power[1]);
        assertTrue(power[2] < 1e-6, "nothing in still air");
    }

    /**
     * Optocoupler: with the switch open both LEDs are dark; closed, the infrared LED's light makes a photocurrent at the
     * phototransistor's collector–base junction which, multiplied by its gain, lights the green LED — across circuits
     * that share no conductor.
     */
    @Test
    void optocouplerCarriesTheSignalAsLight() {
        Exhibit e = OpticsExhibits.optocoupler();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long ir = b.id(e, "ir"), green = b.id(e, "green"), photo = b.id(e, "photo");
        run(c, 0.5);
        assertTrue(c.lightOutput(green) < 1e-6, "dark with the switch open");
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 0.5);
        var pd = c.receiver(photo);
        double light = c.lightInput(photo), iph = Math.abs(pd.current(c.solverView())), ic = Math.abs(c.probe(photo).current());
        System.out.printf("optocoupler: IR LED %.2f mW, %.3f mW at the window, photocurrent %.1f µA, collector %.2f mA (×%.0f), green LED %.3g W%n",
                1e3 * c.lightOutput(ir), 1e3 * light, 1e6 * iph, 1e3 * ic, ic / iph, c.lightOutput(green));
        assertTrue(light > 0, "the infrared light reaches the window");
        assertTrue(ic > 30 * iph, "the transistor amplifies its photocurrent");
        assertTrue(c.lightOutput(green) > 1e-4, "the green LED lights");
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 0.5);
        assertTrue(c.lightOutput(green) < 1e-6, "and goes out with the switch");
    }

    /**
     * Colour filters: the same filament, the same geometry both ways; only the glass differs. Red-hot, the red glass
     * passes ~70× more light than the blue-green one and only the red LED lights; white-hot, both do.
     */
    @Test
    void colourFiltersSortTheFilamentsLightByColour() {
        Exhibit e = OpticsExhibits.colourFilters();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        long pdR = b.id(e, "pdRed"), pdB = b.id(e, "pdBlue"), litR = b.id(e, "litRed"), litB = b.id(e, "litBlue");
        c.toggleSwitch(b.id(e, "switch"));
        run(c, 1.5);
        double warmR = c.lightInput(pdR), warmB = c.lightInput(pdB), tWarm = c.temperature(b.id(e, "filament"));
        boolean warmRedLit = c.lightOutput(litR) > 1e-4, warmBlueLit = c.lightOutput(litB) > 1e-4;
        c.toggleSwitch(b.id(e, "hotter"));
        run(c, 1);
        double hotR = c.lightInput(pdR), hotB = c.lightInput(pdB), tHot = c.temperature(b.id(e, "filament"));
        System.out.printf("filters: %.0f K → red side %.3g W, blue-green %.3g W (×%.0f); %.0f K → %.3g W, %.3g W (×%.1f)%n",
                tWarm, warmR, warmB, warmR / warmB, tHot, hotR, hotB, hotR / hotB);
        assertTrue(warmR > 20 * warmB, "red-hot: the red glass passes far more");
        assertTrue(warmRedLit && !warmBlueLit, "only the red LED lights");
        assertTrue(hotR / hotB < 0.5 * warmR / warmB, "white-hot: relatively more blue-green light");
        assertTrue(c.lightOutput(litR) > 1e-4 && c.lightOutput(litB) > 1e-4, "both LEDs light");
    }

    /**
     * Memory chip: words written by hand read back at their addresses and nowhere else, and survive the build being
     * recompiled (the stored bits travel with its state).
     */
    @Test
    void memoryChipStoresWordsByAddress() {
        Exhibit e = ComputerExhibit.memory();
        Exhibits.Built b = Exhibits.build(e, AMBIENT);
        CompiledAssembly c = compile(e, b);
        run(c, 0.05);
        java.util.function.IntConsumer setAddress = w -> {
            for (int j = 0; j < 2; j++) setSwitch(c, b.id(e, "a" + j), ((w >> j) & 1) == 1);
        };
        java.util.function.BiConsumer<Integer, Integer> write = (w, data) -> {
            setAddress.accept(w);
            for (int k = 0; k < 4; k++) setSwitch(c, b.id(e, "d" + k), ((data >> k) & 1) == 1);
            run(c, 0.05);
            setSwitch(c, b.id(e, "write"), true);
            run(c, 0.05);
            setSwitch(c, b.id(e, "write"), false);
            run(c, 0.05);
        };
        write.accept(1, 0b1010);
        write.accept(2, 0b0101);
        write.accept(3, 0b1111);
        int[] expect = {0, 0b1010, 0b0101, 0b1111};
        for (int w = 0; w < 4; w++) {
            setAddress.accept(w);
            run(c, 0.05);
            int got = word(c, b, e);
            System.out.printf("memory: word %d reads %s (wrote %s)%n", w, Integer.toBinaryString(got), Integer.toBinaryString(expect[w]));
            assertEquals(expect[w], got, "word " + w);
        }
        // recompiled from its saved state, it still holds them
        CompiledAssembly again = AssemblyCompiler.compile(b.assembly(), c.exportState());
        run(again, 0.05);
        assertEquals(0b1111, word(again, b, e), "word 3 after recompiling");
    }

    static void setSwitch(CompiledAssembly c, long id, boolean on) {
        if (b(c, id) != on) c.toggleSwitch(id);
    }

    static boolean b(CompiledAssembly c, long id) {
        return c.assembly().get(id).switchClosed();
    }

    /** The word the LEDs show (bit k lit: LED q_k emitting). */
    static int word(CompiledAssembly c, Exhibits.Built b, Exhibit e) {
        int w = 0;
        for (int k = 0; k < 4; k++) if (c.lightOutput(b.id(e, "q" + k)) > 1e-4) w |= 1 << k;
        return w;
    }
}
