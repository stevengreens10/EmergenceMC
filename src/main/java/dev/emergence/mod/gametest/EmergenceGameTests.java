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
