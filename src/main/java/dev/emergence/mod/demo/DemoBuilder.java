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

    /**
     * Plots are 16 blocks wide (exhibits are block-scale builds) with an aisle that keeps neighbouring exhibits
     * beyond each other's interaction reach (gears reach up to 6 blocks), so each stays its own machine.
     */
    public static final int PLOT_SPACING = 20;
    /** Depth of a plot (z) and the row (relative to the plot origin) where the signs and the walkway are. */
    public static final int PLOT_DEPTH = 16, SIGN_Z = 16;

    /** Builds all exhibits with the first plot at {@code origin} (the block above the gallery floor). */
    public static BlockPos build(ServerLevel level, BlockPos origin) {
        List<Exhibit> exhibits = Exhibits.all();
        int width = plotOffset(exhibits, exhibits.size());
        // gallery floor (a polished border between plots) and clear air above it
        for (int x = -3; x <= width + 1; x++) {
            for (int z = -3; z <= SIGN_Z + 6; z++) {
                BlockPos f = origin.offset(x, -1, z);
                boolean between = false;
                for (int i = 1; i <= exhibits.size(); i++) between |= x == plotOffset(exhibits, i) - 1;
                boolean edge = x == -3 || x == width + 1 || z == -3 || z == SIGN_Z + 6 || between || z == SIGN_Z - 1;
                level.setBlock(f, (edge ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState(), 2);
                for (int y = 0; y < 18; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        sign(level, origin.offset(-2, 0, SIGN_Z + 1), List.of("EMERGENCE", "Engineering from", "first principles", "exhibits ->"));
        for (int i = 0; i < exhibits.size(); i++) {
            Exhibit e = exhibits.get(i);
            BlockPos plot = origin.offset(plotOffset(exhibits, i), 0, 0);
            place(level, plot, e);
            sign(level, plot.offset(8, 0, SIGN_Z), e.sign);
        }
        EmergenceMod.LOG.info("Built {} demo exhibits at {}", exhibits.size(), origin);
        return origin.offset(8, 0, SIGN_Z + 3);
    }

    /**
     * Places one exhibit's parts at block scale: each part (a single grid cell) becomes one block of the
     * assembly at {@code plot} + its cell coordinates. A shaft and the part mounted on it share a block.
     */
    public static void place(ServerLevel level, BlockPos plot, Exhibit e) {
        if (e.miniature) {
            placeMiniature(level, plot.offset(MINIATURE_AT), e);
            return;
        }
        double ambient = PhysicalConstants.T_REF;
        Map<BlockPos, AssemblyBlockEntity> bes = new HashMap<>();
        for (Exhibit.PartDef d : e.parts) {
            BlockPos bp = plot.offset(d.x0(), d.y0(), d.z0());
            AssemblyBlockEntity be = bes.computeIfAbsent(bp, p -> {
                level.setBlock(p, ModRegistries.ASSEMBLY.get().defaultBlockState(), 3);
                AssemblyBlockEntity n = (AssemblyBlockEntity) level.getBlockEntity(p);
                n.setResolution(AssemblyBlockEntity.BLOCK_SCALE);
                return n;
            });
            PartSpec spec = new PartSpec(d.material(), 1, 1, d.params());
            long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            be.addPart(new PlacedPart(id, d.kind(), spec, 0, 0, 0, 0, 0, 0, d.axis(), e.latched.contains(d.index())));
            Exhibit.Initial in = e.initial.get(d.index());
            if (in != null) {
                double t = Double.isNaN(in.temperature()) ? ambient : in.temperature();
                be.savedState().put(id, new double[]{t, in.omega(), in.angle(), 0, in.capacitorVoltage()});
            }
        }
    }

    /** Adds one block-scale part to the world at plot-relative cell (x, y, z) — a load placed on an exhibit. */
    public static void addPart(ServerLevel level, BlockPos plot, int x, int y, int z, dev.emergence.core.assembly.PartKind kind,
                               String material, dev.emergence.core.assembly.Dir axis, Object... params) {
        BlockPos bp = plot.offset(x, y, z);
        if (!(level.getBlockEntity(bp) instanceof AssemblyBlockEntity)) level.setBlock(bp, ModRegistries.ASSEMBLY.get().defaultBlockState(), 3);
        AssemblyBlockEntity be = (AssemblyBlockEntity) level.getBlockEntity(bp);
        be.setResolution(AssemblyBlockEntity.BLOCK_SCALE);
        be.addPart(new PlacedPart(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), kind, PartSpec.of(material, 1, 1, params),
                0, 0, 0, 0, 0, 0, axis, false));
    }

    /** x offset of exhibit i's plot from the gallery origin (wide exhibits take several plots). */
    public static int plotOffset(List<Exhibit> exhibits, int i) {
        int x = 0;
        for (int k = 0; k < i; k++) x += exhibits.get(k).plots * PLOT_SPACING;
        return x;
    }

    /** Where in its plot a miniature exhibit's single block goes. */
    public static final BlockPos MINIATURE_AT = new BlockPos(8, 0, 8);

    /** Places a miniature exhibit at true scale: all its cells inside the one block {@code at}. */
    private static void placeMiniature(ServerLevel level, BlockPos at, Exhibit e) {
        level.setBlock(at, ModRegistries.ASSEMBLY.get().defaultBlockState(), 3);
        AssemblyBlockEntity be = (AssemblyBlockEntity) level.getBlockEntity(at);
        be.setResolution(AssemblyBlockEntity.MINIATURE);
        for (Exhibit.PartDef d : e.parts) {
            int a = d.axis().axis();
            int len = 1 + (a == 0 ? d.x1() - d.x0() : a == 1 ? d.y1() - d.y0() : d.z1() - d.z0());
            int cross = 1 + Math.max(a == 0 ? d.y1() - d.y0() : d.x1() - d.x0(), a == 2 ? d.y1() - d.y0() : d.z1() - d.z0());
            long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            be.addPart(new PlacedPart(id, d.kind(), new PartSpec(d.material(), len, cross, d.params()),
                    d.x0(), d.y0(), d.z0(), d.x1(), d.y1(), d.z1(), d.axis(), false));
        }
    }

    /** The block holding the named part of an exhibit built at {@code plot}. */
    public static BlockPos partBlock(BlockPos plot, Exhibit e, String name) {
        if (e.miniature) return plot.offset(MINIATURE_AT);
        Exhibit.PartDef d = e.parts.get(e.named.get(name));
        return plot.offset(d.x0(), d.y0(), d.z0());
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
