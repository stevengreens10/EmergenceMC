package dev.emergence.mod.gametest;

import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.assembly.Probe;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PartSpec;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.registry.ModRegistries;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-world integration tests: devices are assembled from primitive parts inside real assembly blocks
 * and simulated by the live {@link SimulationManager}. Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(EmergenceMod.MODID)
@PrefixGameTestTemplate(false)
public final class EmergenceGameTests {
    private static final AtomicLong IDS = new AtomicLong(1_000_000);

    private static PlacedPart part(AssemblyBlockEntity be, PartKind k, PartSpec spec, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis) {
        PlacedPart p = new PlacedPart(IDS.incrementAndGet(), k, spec, x0, y0, z0, x1, y1, z1, axis, false);
        be.addPart(p);
        return p;
    }

    /** Hand-cranked axial-flux generator feeding a 20 Ω resistor, all inside one block space. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void handCrankedGeneratorHeatsResistor(GameTestHelper h) {
        BlockPos rel = new BlockPos(5, 2, 5); // structure layer 0 (rel y=1) is the stone floor
        h.setBlock(rel, ModRegistries.ASSEMBLY.get().defaultBlockState());
        AssemblyBlockEntity be = (AssemblyBlockEntity) h.getBlockEntity(rel);
        PartSpec steel = PartSpec.of("steel_1045", 1, 1);
        part(be, PartKind.BLOCK, steel, 9, 0, 8, 9, 2, 8, Dir.UP);          // pillar standing on the floor
        part(be, PartKind.BLOCK, steel, 7, 0, 8, 7, 2, 8, Dir.UP);
        part(be, PartKind.SHAFT, PartSpec.of("steel_1045", 9, 1, "diameter", 0.02), 8, 2, 8, 8, 10, 8, Dir.UP);
        part(be, PartKind.BEARING, steel, 8, 2, 8, 8, 2, 8, Dir.UP);
        part(be, PartKind.BEARING, steel, 8, 8, 8, 8, 8, 8, Dir.UP);
        part(be, PartKind.BLOCK, steel, 9, 8, 7, 11, 8, 12, Dir.UP);        // top plates hold the stator...
        part(be, PartKind.BLOCK, steel, 5, 8, 7, 7, 8, 12, Dir.UP);
        part(be, PartKind.BLOCK, steel, 11, 0, 12, 11, 7, 12, Dir.UP);      // ...on columns standing clear of the rotor
        part(be, PartKind.BLOCK, steel, 5, 0, 12, 5, 7, 12, Dir.UP);
        PlacedPart crank = part(be, PartKind.CRANK, PartSpec.of("steel_1045", 1, 1, "radius", 0.15), 8, 11, 8, 8, 11, 8, Dir.UP);
        part(be, PartKind.BLOCK, PartSpec.of("aluminium_6061", 1, 5, "fill", 0.04), 6, 4, 6, 10, 4, 10, Dir.UP);
        part(be, PartKind.MAGNET, PartSpec.of("ndfeb_n42", 1, 1), 10, 5, 8, 10, 5, 8, Dir.UP);
        part(be, PartKind.MAGNET, PartSpec.of("ndfeb_n42", 1, 1), 6, 5, 8, 6, 5, 8, Dir.DOWN);
        PartSpec coil = PartSpec.of("copper", 2, 1, "turns", 300, "wire_diameter", 0.8e-3);
        part(be, PartKind.COIL, coil, 10, 6, 8, 10, 7, 8, Dir.UP);
        part(be, PartKind.COIL, coil, 6, 6, 8, 6, 7, 8, Dir.UP);
        PartSpec wire = PartSpec.of("copper", 1, 1, "diameter", 1.5e-3);
        int[][] path = {{11, 6, 8}, {11, 6, 9}, {11, 6, 10}, {10, 6, 10}, {9, 6, 10}, {7, 6, 10}, {6, 6, 10}, {5, 6, 10}, {5, 6, 9}, {5, 6, 8}};
        for (int[] c : path) part(be, PartKind.INSULATED_WIRE, wire, c[0], c[1], c[2], c[0], c[1], c[2], Dir.UP);
        PlacedPart res = part(be, PartKind.RESISTOR, PartSpec.of("alumina", 1, 1, "resistance", 20.0), 8, 6, 10, 8, 6, 10, Dir.EAST);

        BlockPos abs = h.absolutePos(rel);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        double[] peakCurrent = {0};
        h.onEachTick(() -> {
            CompiledAssembly c = sim.compiledAt(abs);
            if (c == null) return;
            c.setCrankTorque(crank.id, 1.0);
            Probe pr = c.probe(res.id);
            if (pr != null && Double.isFinite(pr.current())) peakCurrent[0] = Math.max(peakCurrent[0], Math.abs(pr.current()));
        });
        h.runAtTickTime(120, () -> {
            CompiledAssembly c = sim.compiledAt(abs);
            h.assertTrue(c != null, "island compiled");
            StringBuilder diag = new StringBuilder();
            for (CompiledAssembly.Body b : c.bodies()) diag.append(" body").append(b.index()).append(b.isStatic() ? "(static)" : "").append("=").append(b.parts().size());
            h.assertTrue(c.bodies().size() == 2, "expected frame + rotor, got" + diag + " " + c.warnings());
            Probe pc = c.probe(crank.id);
            h.assertTrue(pc.angularVelocity() > 5, "rotor should spin, ω=" + pc.angularVelocity());
            h.assertTrue(peakCurrent[0] > 1e-3, "load current expected, peak=" + peakCurrent[0]);
            Probe pr = c.probe(res.id);
            h.assertTrue(pr.temperature() > c.assembly().ambientTemperature(), "resistor should warm up");
            h.succeed();
        });
    }

    /** The demo glow exhibit, placed through the real builder path, flash-heats its wire when switched. */
    @GameTest(template = "arena", timeoutTicks = 100)
    public static void demoCapacitorExhibitGlowsInWorld(GameTestHelper h) {
        // the block-scale exhibit spans cells x 3..8, z 8..10: it fits the 12³ arena from its corner
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.Exhibits.capacitorGlow();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos abs = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "switch");
        PlacedPart sw = only(h, abs, PartKind.SWITCH);
        BlockPos wireAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "wire");
        PlacedPart wire = only(h, wireAt, PartKind.WIRE);
        PlacedPart cap = only(h, dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "capacitor"), PartKind.CAPACITOR);
        double[] peak = {0};
        h.runAtTickTime(10, () -> {
            Probe pc = sim.probe(abs, cap.id);
            h.assertTrue(pc != null && Math.abs(pc.voltage()) > 70, "capacitor should be charged, probe=" + pc);
            sim.toggleSwitch(abs, sw.id);
        });
        h.onEachTick(() -> {
            Probe pw = sim.probe(abs, wire.id);
            if (pw != null) peak[0] = Math.max(peak[0], pw.temperature());
        });
        h.runAtTickTime(30, () -> {
            h.assertTrue(peak[0] > 900, "wire should flash-heat, peak " + peak[0] + " K");
            h.succeed();
        });
    }

    /**
     * Block-scale gear train: gears in blocks two and four blocks apart mesh through their pitch circles,
     * so the island must take in blocks across the air gaps between them.
     */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void blockScaleGearTrainMeshesAcrossGaps(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.Exhibits.gearTrain();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos crankAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "crank");
        BlockPos outAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "output");
        BlockPos inAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "input");
        PlacedPart crank = only(h, crankAt, PartKind.CRANK);
        PlacedPart in = only(h, inAt, PartKind.SHAFT);
        PlacedPart out = only(h, outAt, PartKind.SHAFT);
        h.onEachTick(() -> {
            var c = sim.compiledAt(crankAt);
            if (c != null) c.setCrankEffort(crank.id, 1, true);
        });
        h.runAtTickTime(60, () -> {
            Probe pi = sim.probe(inAt, in.id), po = sim.probe(outAt, out.id);
            h.assertTrue(pi != null && po != null, "gears in one island");
            h.assertTrue(Math.abs(pi.angularVelocity()) > 1, "input turning: " + pi.angularVelocity());
            double ratio = po.angularVelocity() / pi.angularVelocity();
            h.assertTrue(Math.abs(ratio - 0.2) < 0.01, "8:40 tooth ratio, got " + ratio);
            h.succeed();
        });
    }

    /** Fluids in the world: a vessel against a hot block gains pressure at constant p/T; its twin stays put. */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void heatedVesselPressureRisesInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.Exhibits.heatedVessel();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos hotAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "hotTank");
        BlockPos coldAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "coldTank");
        PlacedPart hot = only(h, hotAt, PartKind.TANK), cold = only(h, coldAt, PartKind.TANK);
        double[] start = new double[2];
        h.runAtTickTime(5, () -> {
            double[] f = sim.compiledAt(hotAt).fluid(hot.id);
            start[0] = f[0];
            start[1] = f[1];
            double amb = sim.compiledAt(hotAt).assembly().ambientPressure();
            h.assertTrue(Math.abs(f[0] - amb) < 1000, "vessel starts near the local air pressure " + amb + ", got " + f[0]);
        });
        h.runAtTickTime(300, () -> {
            double[] f = sim.compiledAt(hotAt).fluid(hot.id);
            double[] c = sim.compiledAt(coldAt).fluid(cold.id);
            h.assertTrue(f[0] > start[0] * 1.05, "heated vessel pressure rose: " + start[0] + " -> " + f[0]);
            h.assertTrue(Math.abs(f[0] / f[1] - start[0] / start[1]) < 0.01 * start[0] / start[1], "p/T constant");
            h.assertTrue(Math.abs(c[0] - start[0]) < 300, "cold vessel unchanged: " + c[0]);
            h.succeed();
        });
    }

    /** Phase change in the world: the steam exhibit's boiler sits on the saturation curve and its engine keeps turning. */
    @GameTest(template = "arena", timeoutTicks = 300, batch = "steam")
    public static void steamEngineRunsInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.Exhibits.steamEngine();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos shaftAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "shaft");
        BlockPos boilerAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "boiler");
        PlacedPart shaft = only(h, shaftAt, PartKind.SHAFT), boiler = only(h, boilerAt, PartKind.TANK);
        h.runAtTickTime(5, () -> {
            double[] f = sim.compiledAt(boilerAt).fluid(boiler.id);
            h.assertTrue(Math.abs(f[0] - dev.emergence.core.fluids.WaterSteam.pSat(f[1])) < 0.01 * f[0],
                    "boiler pressure on the saturation curve: " + f[0] + " Pa at " + f[1] + " K");
            h.assertTrue(f[4] > 0.2 && f[4] < 0.6, "boiler partly full of water: vapour fraction " + f[4]);
        });
        h.runAtTickTime(200, () -> {
            Probe ps = sim.probe(shaftAt, shaft.id);
            h.assertTrue(ps != null && Math.abs(ps.angularVelocity()) > 5, "the engine runs on steam: ω " + (ps == null ? "-" : ps.angularVelocity()));
            h.succeed();
        });
    }

    /** Phase 4 in the world: the EM-4 computer's chips run the ROM program (PC advances, ACC follows it). */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "computer")
    public static void computerRunsItsProgramInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.ComputerExhibit.build();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos pcAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "pc");
        BlockPos accAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "acc");
        PlacedPart pc = only(h, pcAt, PartKind.CHIP), acc = only(h, accAt, PartKind.CHIP);
        h.runAtTickTime(300, () -> {
            var c = sim.compiledAt(pcAt);
            h.assertTrue(c != null && c.warnings().isEmpty(), "computer compiles without warnings: " + (c == null ? "-" : c.warnings()));
            double[] q = c.chipState(pc.id), a = sim.compiledAt(accAt).chipState(acc.id);
            int pcv = 0, accv = 0;
            for (int k = 0; k < 4; k++) {
                if (q[k] > 0.5) pcv |= 1 << k;
                if (a[k] > 0.5) accv |= 1 << k;
            }
            h.assertTrue(pcv >= 1, "the program counter advanced: " + pcv);
            h.assertTrue(accv == java.util.List.of(0, 1, 2, 4, 8, 8, 1).get(Math.min(pcv, 6)) || pcv > 6,
                    "ACC follows the program: PC " + pcv + ", ACC " + accv);
            h.succeed();
        });
    }

    /** Structures in the world: the shelves hold their lead blocks; a second block breaks the steel shelf. */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "shelves")
    public static void overloadedShelfBreaksInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.Exhibits.shelves();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        BlockPos steelTip = plot.offset(5, 3, 5), alTip = plot.offset(2, 3, 2);
        h.runAtTickTime(40, () -> {
            h.assertTrue(h.getLevel().getBlockEntity(steelTip) instanceof AssemblyBlockEntity, "steel shelf holds one lead block");
            dev.emergence.mod.demo.DemoBuilder.addPart(h.getLevel(), plot, 5, 5, 5, PartKind.BLOCK, "lead", dev.emergence.core.assembly.Dir.UP);
        });
        h.runAtTickTime(120, () -> {
            h.assertTrue(!(h.getLevel().getBlockEntity(steelTip) instanceof AssemblyBlockEntity), "the overloaded steel shelf broke");
            h.assertTrue(h.getLevel().getBlockEntity(alTip) instanceof AssemblyBlockEntity, "the aluminium shelf still stands");
            h.succeed();
        });
    }

    /** Light in the world: the light barrier's photodiode receives the IR beam, and the cranked vane cuts it. */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "optics")
    public static void lightBarrierWorksInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.OpticsExhibits.lightBarrier();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos pdAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "pd");
        BlockPos crankAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "crank");
        PlacedPart pd = only(h, pdAt, PartKind.PHOTODIODE), crank = only(h, crankAt, PartKind.CRANK);
        double[] range = {Double.MAX_VALUE, 0};
        h.onEachTick(() -> {
            var c = sim.compiledAt(crankAt);
            if (c == null) return;
            c.setCrankEffort(crank.id, 1, true);
            double p = sim.compiledAt(pdAt).lightInput(pd.id);
            range[0] = Math.min(range[0], p);
            range[1] = Math.max(range[1], p);
        });
        h.runAtTickTime(160, () -> {
            h.assertTrue(range[1] > 1e-6, "the photodiode receives the IR beam: " + range[1] + " W");
            h.assertTrue(range[0] < 0.05 * range[1], "the turning vane cuts it: down to " + range[0] + " W");
            h.succeed();
        });
    }

    /** Feedback control in the world: turning the servo's knob raises its arm against gravity. */
    @GameTest(template = "arena", timeoutTicks = 640, batch = "servo")
    public static void servoArmFollowsKnobInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.ControlExhibits.servo();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos armAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "arm");
        BlockPos crankAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "crank");
        PlacedPart arm = only(h, armAt, PartKind.SHAFT), crank = only(h, crankAt, PartKind.CRANK);
        h.onEachTick(() -> {
            var c = sim.compiledAt(crankAt);
            if (c != null) c.setCrankEffort(crank.id, -1, false);
        });
        BlockPos knobAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "knob");
        PlacedPart knob = only(h, knobAt, PartKind.SHAFT);
        // however fast the world lets it run, the knob turns and the arm follows it: check the relation, not the pace
        h.runAtTickTime(600, () -> {
            var p = sim.probe(armAt, arm.id);
            var k = sim.probe(knobAt, knob.id);
            h.assertTrue(p != null && k != null && k.angle() < 1.885 - 0.3, "the knob was turned: " + (k == null ? "?" : k.angle()));
            double demand = 0.785 + 0.417 * k.angle();
            h.assertTrue(p.angle() < 1.5 && Math.abs(p.angle() - demand) < 0.3,
                    String.format("the arm rose with it: arm %.3f rad, asked %.3f rad", p.angle(), demand));
            h.succeed();
        });
    }

    /** Gas chemistry in the world: the warm flask of N2O4/NO2 holds more brown NO2 and is drawn darker. */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "chemistry")
    public static void brownGasDarkensWhenWarm(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.ChemistryExhibits.brownGas();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos hotAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "hot");
        BlockPos coldAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "cold");
        PlacedPart hot = only(h, hotAt, PartKind.TANK), cold = only(h, coldAt, PartKind.TANK);
        h.runAtTickTime(120, () -> {
            var c = sim.compiledAt(hotAt);
            h.assertTrue(c != null && c.gas(hot.id) != null, "the flask holds a reacting gas");
            int a = c.chemicalColour(hot.id), b = sim.compiledAt(coldAt).chemicalColour(cold.id);
            int da = (a >> 16 & 255) + (a >> 8 & 255) + (a & 255), db = (b >> 16 & 255) + (b >> 8 & 255) + (b & 255);
            h.assertTrue(a >= 0 && b >= 0 && da < db, String.format("warm flask %06x darker than cold %06x", a, b));
            h.succeed();
        });
    }

    /** The heated can of methane-air ignites in the world too (Semenov thermal explosion). */
    @GameTest(template = "arena", timeoutTicks = 3000, batch = "explosion")
    public static void canIgnitesInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.ChemistryExhibits.thermalExplosion();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos canAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "can");
        BlockPos swAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "switch");
        BlockPos heaterAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "heater");
        PlacedPart can = only(h, canAt, PartKind.TANK), sw = only(h, swAt, PartKind.SWITCH), heater = only(h, heaterAt, PartKind.RESISTOR);
        double[] peak = {0};
        h.runAtTickTime(20, () -> sim.toggleSwitch(swAt, sw.id));
        h.onEachTick(() -> {
            var c = sim.compiledAt(canAt);
            if (c == null || c.gas(can.id) == null) return;
            double t = c.gas(can.id).temperature(c.solverView());
            peak[0] = Math.max(peak[0], t);
            if (h.getTick() % 400 == 0 && c.probe(heater.id) != null) dev.emergence.mod.EmergenceMod.LOG.info("explosion GameTest tick {}: gas {} K, can {} K, heater {} A {} K",
                    h.getTick(), (int) t, (int) c.probe(can.id).temperature(), c.probe(heater.id).current(), (int) c.probe(heater.id).temperature());
            if (peak[0] > 1500) h.succeed();
        });
        h.runAtTickTime(2900, () -> h.fail("the can did not ignite: peak " + peak[0] + " K"));
    }

    /** Radiation in the world: the Geiger–Müller tube counts the check source's gammas, at random but at the expected rate. */
    @GameTest(template = "arena", timeoutTicks = 700, batch = "geiger")
    public static void geigerCounterCountsInWorld(GameTestHelper h) {
        BlockPos plot = h.absolutePos(new BlockPos(0, 2, 0));
        dev.emergence.core.demo.Exhibit ex = dev.emergence.core.demo.RadiationExhibits.geigerCounter();
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos tubeAt = dev.emergence.mod.demo.DemoBuilder.partBlock(plot, ex, "tube");
        PlacedPart tube = only(h, tubeAt, PartKind.GM_TUBE);
        h.runAtTickTime(640, () -> {
            var c = sim.compiledAt(tubeAt);
            var t = c == null ? null : c.tube(tube.id);
            h.assertTrue(t != null, "the tube is simulated");
            double simTime = c.solverView().time(), expected = t.hitRate(c.solverView()) * simTime;
            h.assertTrue(simTime > 5 && Math.abs(t.discharges() - expected) < 4 * Math.sqrt(expected) + 2,
                    String.format("%d counts in %.1f s (expected %.1f)", t.discharges(), simTime, expected));
            h.succeed();
        });
    }

    private static PlacedPart only(GameTestHelper h, BlockPos abs, PartKind kind) {
        AssemblyBlockEntity be = (AssemblyBlockEntity) h.getLevel().getBlockEntity(abs);
        return be.parts().stream().filter(p -> p.kind == kind).findFirst().orElseThrow();
    }

    /** A thin tungsten wire across a strong source glows (incandescence emerges from Joule heating + radiation). */
    @GameTest(template = "arena", timeoutTicks = 120)
    public static void partsSurviveSaveAndReload(GameTestHelper h) {
        BlockPos rel = new BlockPos(3, 2, 3);
        h.setBlock(rel, ModRegistries.ASSEMBLY.get().defaultBlockState());
        AssemblyBlockEntity be = (AssemblyBlockEntity) h.getBlockEntity(rel);
        part(be, PartKind.BLOCK, PartSpec.of("granite", 1, 1), 0, 0, 0, 0, 0, 0, Dir.UP);
        part(be, PartKind.MAGNET, PartSpec.of("ferrite_c8", 1, 1), 0, 1, 0, 0, 1, 0, Dir.UP);
        var tag = be.saveWithoutMetadata(h.getLevel().registryAccess());
        AssemblyBlockEntity copy = new AssemblyBlockEntity(h.absolutePos(rel), be.getBlockState());
        copy.loadWithComponents(tag, h.getLevel().registryAccess());
        h.assertTrue(copy.parts().size() == 2, "parts persisted");
        h.assertTrue(copy.parts().get(1).spec.material().equals("ferrite_c8"), "spec persisted");
        h.succeed();
    }
}
