package dev.emergence.mod.gametest;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.StationExhibit;
import dev.emergence.mod.demo.DemoBuilder;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The power station's world's own check (run when that world is generated: {@code ./gradlew generateStationWorld}),
 * on the station as the world built it, part by part as a player would: its fire burns, its boiler holds steam and its
 * engine turns. (The gallery's in-world tests run with the gallery's build.)
 */
@GameTestHolder("emergence_station")
@PrefixGameTestTemplate(false)
public final class StationGameTests {
    private StationGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 1200)
    public static void stationRunsInItsWorld(GameTestHelper h) {
        BlockPos origin = DemoBuilder.LAST_ORIGIN;
        h.assertTrue(origin != null, "the station has been built");
        Exhibit e = StationExhibit.station();
        SimulationManager sim = SimulationManager.get(h.getLevel());
        BlockPos shaft = DemoBuilder.partBlock(origin, e, "shaft"), boiler = DemoBuilder.partBlock(origin, e, "boiler"),
                box = DemoBuilder.partBlock(origin, e, "firebox");
        h.runAtTickTime(1000, () -> {
            var c = sim.compiledAt(shaft);
            h.assertTrue(c != null, "the station is simulated");
            var at = c.assembly();
            long shaftId = -1, boilerId = -1, boxId = -1;
            for (var p : at.parts()) {
                if (shaftId < 0 && p.kind() == dev.emergence.core.assembly.PartKind.SHAFT && contains(origin, p, shaft)) shaftId = p.id();
                if (boilerId < 0 && p.kind() == dev.emergence.core.assembly.PartKind.TANK && contains(origin, p, boiler)) boilerId = p.id();
                if (boxId < 0 && p.kind() == dev.emergence.core.assembly.PartKind.TANK && contains(origin, p, box)) boxId = p.id();
            }
            h.assertTrue(shaftId >= 0 && boilerId >= 0 && boxId >= 0, "the crankshaft, boiler and firebox are in its island");
            double w = Math.abs(c.probe(shaftId).angularVelocity()), p = c.pressure(boilerId);
            var g = c.gas(boxId);
            var ch = dev.emergence.core.chem.Chemistry.standard();
            double co2 = g.moleFraction(c.solverView(), g.indexOf(ch.species("CO2")));
            h.assertTrue(co2 > 0.03, String.format("the firebox burns (%.1f %% CO2)", 100 * co2));
            h.assertTrue(p > 1.5e5, String.format("the boiler holds steam (%.2f bar)", p / 1e5));
            h.assertTrue(w > 8, String.format("the engine turns (%.1f rad/s)", w));
            h.succeed();
        });
    }

    /** Whether part p (world-island coordinates are the world's block coordinates) lies in the block {@code at}. */
    private static boolean contains(BlockPos origin, dev.emergence.core.assembly.Part p, BlockPos at) {
        return p.minX() <= at.getX() && at.getX() <= p.maxX() && p.minY() <= at.getY() && at.getY() <= p.maxY()
                && p.minZ() <= at.getZ() && at.getZ() <= p.maxZ();
    }
}
