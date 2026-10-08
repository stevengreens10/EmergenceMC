package dev.emergence.mod.demo;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.core.units.PhysicalConstants;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PartSpec;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.PrimaryLevelData;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the demo-world showcase: a stone gallery with one plot per {@link Exhibit}, each assembled
 * from primitive parts exactly as a player would, with an explanatory sign in front. Used by the
 * {@code /emergence demo} command and by the automated demo-world generator.
 */
public final class DemoBuilder {
    private DemoBuilder() {}

    public static final int PLOT_SPACING = 4;

    /** Builds all exhibits with the first plot at {@code origin} (the block above the gallery floor). */
    public static BlockPos build(ServerLevel level, BlockPos origin) {
        List<Exhibit> exhibits = Exhibits.all();
        int width = 0;
        for (Exhibit e : exhibits) width += Math.max(e.widthBlocks, 1) * PLOT_SPACING;
        // gallery floor and clear air above it
        for (int x = -3; x <= width + 1; x++) {
            for (int z = -3; z <= 5; z++) {
                BlockPos f = origin.offset(x, -1, z);
                boolean edge = x == -3 || x == width + 1 || z == -3 || z == 5;
                level.setBlock(f, (edge ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState(), 2);
                for (int y = 0; y < 5; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        sign(level, origin.offset(-2, 0, 3), List.of("EMERGENCE", "Engineering from", "first principles", "→ exhibits →"));
        int x = 0;
        for (Exhibit e : exhibits) {
            BlockPos plot = origin.offset(x, 0, 0);
            place(level, plot, e);
            sign(level, plot.offset(0, 0, 2), e.sign);
            x += Math.max(e.widthBlocks, 1) * PLOT_SPACING;
        }
        EmergenceMod.LOG.info("Built {} demo exhibits at {}", exhibits.size(), origin);
        return origin.offset(width / 2, 0, 4);
    }

    /** Places one exhibit's parts (grid cells relative to {@code plot}) into assembly blocks. */
    public static void place(ServerLevel level, BlockPos plot, Exhibit e) {
        double ambient = PhysicalConstants.T_REF;
        Map<BlockPos, AssemblyBlockEntity> bes = new HashMap<>();
        for (Exhibit.PartDef d : e.parts) {
            BlockPos bp = plot.offset(Math.floorDiv(d.x0(), 16), Math.floorDiv(d.y0(), 16), Math.floorDiv(d.z0(), 16));
            AssemblyBlockEntity be = bes.computeIfAbsent(bp, p -> {
                level.setBlock(p, ModRegistries.ASSEMBLY.get().defaultBlockState(), 3);
                return (AssemblyBlockEntity) level.getBlockEntity(p);
            });
            int lx = Math.floorMod(d.x0(), 16), ly = Math.floorMod(d.y0(), 16), lz = Math.floorMod(d.z0(), 16);
            int a = d.axis().axis();
            int len = 1 + (a == 0 ? d.x1() - d.x0() : a == 1 ? d.y1() - d.y0() : d.z1() - d.z0());
            int cross = 1 + Math.max(a == 0 ? d.y1() - d.y0() : d.x1() - d.x0(), a == 2 ? d.y1() - d.y0() : d.z1() - d.z0());
            PartSpec spec = new PartSpec(d.material(), len, cross, d.params());
            long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            be.addPart(new PlacedPart(id, d.kind(), spec, lx, ly, lz, lx + d.x1() - d.x0(), ly + d.y1() - d.y0(),
                    lz + d.z1() - d.z0(), d.axis(), false));
            Exhibit.Initial in = e.initial.get(d.index());
            if (in != null) {
                double t = Double.isNaN(in.temperature()) ? ambient : in.temperature();
                be.savedState().put(id, new double[]{t, in.omega(), in.angle(), 0, in.capacitorVoltage()});
            }
        }
    }

    private static void sign(ServerLevel level, BlockPos pos, List<String> lines) {
        BlockState s = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0);
        level.setBlock(pos, s, 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText t = new SignText();
            for (int i = 0; i < Math.min(4, lines.size()); i++) t = t.setMessage(i, Component.literal(lines.get(i)));
            sign.setText(t, true);
            sign.setWaxed(true);
        }
    }

    /**
     * Automated demo-world generation (system property {@code emergence.buildDemo=true}): builds the
     * gallery at the world spawn, moves the spawn in front of it, freezes daylight at noon and names
     * the world "Emergence Demo".
     */
    public static void generateWorld(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(0, 0, 0));
        BlockPos spawn = build(level, top);
        level.setDefaultSpawnPos(spawn, 180f);
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        if (server.getWorldData() instanceof PrimaryLevelData data) {
            try {
                Field f = PrimaryLevelData.class.getDeclaredField("settings");
                f.setAccessible(true);
                LevelSettings old = (LevelSettings) f.get(data);
                f.set(data, new LevelSettings("Emergence Demo", old.gameType(), old.hardcore(), old.difficulty(),
                        old.allowCommands(), old.gameRules(), old.getDataConfiguration()));
            } catch (ReflectiveOperationException e) {
                EmergenceMod.LOG.warn("Could not rename demo world", e);
            }
        }
        server.saveEverything(false, true, true);
        EmergenceMod.LOG.info("Demo world generated; spawn at {}", spawn);
    }
}
