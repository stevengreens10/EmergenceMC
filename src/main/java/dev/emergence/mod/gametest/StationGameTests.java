package dev.emergence.mod.gametest;

import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.StationExhibit;
import dev.emergence.mod.demo.DemoBuilder;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * The power station's world's own check (run when that world is generated: {@code ./gradlew generateStationWorld}),
 * on the station as the world built it, part by part as a player would, over three minutes: its fire burns, its boiler
 * holds steam, its engine turns, it exhausts into a vacuum, its generator charges its battery, its counter counts; then
 * the brake goes on and the governor holds the engine's speed. The island must run the whole time without failing (a
 * failure restarts it). The same checks as the station's tour, which needs a display; the
 * gallery's in-world tests run with the gallery's build.
 */
@GameTestHolder("emergence_station")
@PrefixGameTestTemplate(false)
public final class StationGameTests {
    private StationGameTests() {}

    /** Ticks (at 20 a second, the island kept up with by the test server's unlimited budget). */
    private static final int FIRST = 1000, BEFORE = 2400, BRAKE = 3000, SETTLED = 3400, END = 3800;

    @GameTest(template = "arena", timeoutTicks = END + 400)
    public static void stationRunsInItsWorld(GameTestHelper h) {
        BlockPos origin = DemoBuilder.LAST_ORIGIN;
        h.assertTrue(origin != null, "the station has been built");
        Exhibit e = StationExhibit.station();
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos shaft = DemoBuilder.partBlock(origin, e, "shaft"), boiler = DemoBuilder.partBlock(origin, e, "boiler"),
                box = DemoBuilder.partBlock(origin, e, "firebox"), cnd = DemoBuilder.partBlock(origin, e, "condenser"),
                bat = DemoBuilder.partBlock(origin, e, "battery"), brake = DemoBuilder.partBlock(origin, e, "brake");
        BlockPos[] bitLed = new BlockPos[3];
        for (int k = 0; k < 3; k++) bitLed[k] = DemoBuilder.partBlock(origin, e, "bitLed" + k);
        int failures0 = sim.failures;
        double[] w0 = {0, 0}, w1 = {0, 0}, q = {Double.NaN, Double.NaN}, t = {Double.NaN, Double.NaN};
        Set<String> counts = new HashSet<>();
        // the regulator over the battery's window, once a second: its output at most, the battery at most, the dump's current at most
        double[] regMax = {0}, vMax = {0}, dumpMax = {0};
        BlockPos regAt = DemoBuilder.partBlock(origin, e, "regulator"), dumpAt = DemoBuilder.partBlock(origin, e, "dump");
        for (int tick = 20; tick <= END; tick += 20) {
            int at = tick;
            h.runAtTickTime(at, () -> {
                CompiledAssembly c = sim.compiledAt(shaft);
                h.assertTrue(c != null, "the station is simulated (tick " + at + ")");
                h.assertTrue(sim.failures == failures0, String.format("the station's island ran without failing (it failed and restarted by tick %d)", at));
                double w = Math.abs(c.probe(id(c, shaft, PartKind.SHAFT)).angularVelocity());
                if (at > BEFORE && at <= BRAKE) {
                    w0[0] += w;
                    w0[1]++;
                }
                if (at > SETTLED) {
                    w1[0] += w;
                    w1[1]++;
                }
                if (at >= FIRST && at <= BRAKE) {
                    regMax[0] = Math.max(regMax[0], c.probe(id(c, regAt, PartKind.OPAMP)).voltage());
                    vMax[0] = Math.max(vMax[0], c.probe(id(c, bat, PartKind.BATTERY)).voltage());
                    dumpMax[0] = Math.max(dumpMax[0], Math.abs(c.probe(id(c, dumpAt, PartKind.RESISTOR)).current()));
                }
                StringBuilder sb = new StringBuilder();
                for (int k = 2; k >= 0; k--) sb.append(c.lightOutput(id(c, bitLed[k], PartKind.LED)) > 1e-4 ? '1' : '0');
                counts.add(sb.toString());
                if (at == FIRST) {
                    long boilerId = id(c, boiler, PartKind.TANK), boxId = id(c, box, PartKind.TANK);
                    var g = c.gas(boxId);
                    double co2 = g.moleFraction(c.solverView(), g.indexOf(dev.emergence.core.chem.Chemistry.standard().species("CO2")));
                    h.assertTrue(co2 > 0.03, String.format("the firebox burns (%.1f %% CO2)", 100 * co2));
                    h.assertTrue(c.pressure(boilerId) > 1.5e5, String.format("the boiler holds steam (%.2f bar)", c.pressure(boilerId) / 1e5));
                    h.assertTrue(w > 8, String.format("the engine turns (%.1f rad/s)", w));
                    double pc = c.pressure(id(c, cnd, PartKind.TANK));
                    h.assertTrue(pc < 80e3, String.format("it exhausts into a partial vacuum (condenser %.1f kPa)", pc / 1e3));
                    q[0] = c.batteryChargeDelivered(id(c, bat, PartKind.BATTERY));
                    t[0] = c.solverView().time();
                    // -Demergence.stationDump=true: the island as it stands, to replay in the core (IslandDumpReplayTest)
                    if (Boolean.getBoolean("emergence.stationDump")) sim.dumpIsland(shaft);
                }
                if (at == BRAKE) {
                    q[1] = c.batteryChargeDelivered(id(c, bat, PartKind.BATTERY));
                    t[1] = c.solverView().time();
                    double mAh = -(q[1] - q[0]) / 3.6;
                    // what decides the battery's charge: its voltage, the regulator's output and what it dumps, the screens
                    BlockPos reg = DemoBuilder.partBlock(origin, e, "regulator"), lamp = DemoBuilder.partBlock(origin, e, "lamp"),
                            shunt = DemoBuilder.partBlock(origin, e, "shunt");
                    double screens = 0;
                    for (long pid : c.assembly().parts().stream().map(dev.emergence.core.assembly.Part::id).toList()) {
                        var chip = c.digitalChip(pid);
                        if (chip != null && chip.component() instanceof dev.emergence.core.digital.DisplayPanel dp)
                            screens += dp.leakage(c.probe(id(c, bat, PartKind.BATTERY)).voltage());
                    }
                    dev.emergence.mod.EmergenceMod.LOG.info("Station battery: {} mA·h over {} s ({} mA); battery {} V; regulator output {} V; lamp {} mA; shunt MOSFET {} mA; screens {} mA; regulator {} K",
                            String.format("%.3f", mAh), String.format("%.0f", t[1] - t[0]), String.format("%.2f", 3600 * mAh / (t[1] - t[0])),
                            String.format("%.4f", c.probe(id(c, bat, PartKind.BATTERY)).voltage()),
                            String.format("%.3f", c.probe(id(c, reg, PartKind.OPAMP)).voltage()),
                            String.format("%.2f", 1e3 * Math.abs(c.probe(id(c, lamp, PartKind.LED)).current())),
                            String.format("%.2f", 1e3 * Math.abs(c.probe(id(c, shunt, PartKind.TRANSISTOR)).current())),
                            String.format("%.2f", 1e3 * screens / 6), String.format("%.1f", c.temperature(id(c, reg, PartKind.OPAMP))));
                    dev.emergence.mod.EmergenceMod.LOG.info("Station battery window, sampled each second: regulator output at most {} V; battery at most {} V; dump load at most {} mA",
                            String.format("%.3f", regMax[0]), String.format("%.4f", vMax[0]), String.format("%.1f", 1e3 * dumpMax[0]));
                    h.assertTrue(mAh > 0.001, String.format("its generator charges its battery (%.3f mA·h taken in over %.0f s)", mAh, t[1] - t[0]));
                    h.assertTrue(counts.size() >= 2, "its counter counts the engine's turns (states seen: " + counts + ")");
                    sim.toggleSwitch(brake, id(c, brake, PartKind.BRAKE)); // a load put on it by hand
                }
                if (at == END) {
                    double before = w0[0] / w0[1], after = w1[0] / w1[1];
                    sim.toggleSwitch(brake, id(c, brake, PartKind.BRAKE));
                    h.assertTrue(Math.abs(after / before - 1) <= 0.10, String.format(
                            "braked, the governor holds its speed (mean %.2f → %.2f rad/s, %+.1f %%; expected within ±10 %%)", before, after,
                            100 * (after / before - 1)));
                    dev.emergence.mod.EmergenceMod.LOG.info("Station check: {} s simulated; engine {} → {} rad/s braked; {} mA·h charged; counter states {}",
                            String.format("%.0f", c.solverView().time()), String.format("%.2f", before), String.format("%.2f", after),
                            String.format("%.3f", -(q[1] - q[0]) / 3.6), counts);
                    h.succeed();
                }
            });
        }
    }

    /**
     * The id of the first part of that kind in the block {@code at}, from the block itself (an island joined across
     * scales holds its full-size parts shifted in its grid: island coordinates are not block coordinates).
     */
    private static long id(CompiledAssembly c, BlockPos at, PartKind kind) {
        var level = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().overworld();
        if (level.getBlockEntity(at) instanceof dev.emergence.mod.block.AssemblyBlockEntity be)
            for (var p : be.parts()) if (p.kind == kind && c.assembly().get(p.id) != null) return p.id;
        throw new net.minecraft.gametest.framework.GameTestAssertException("no " + kind + " in the station's island at " + at.toShortString());
    }
}
