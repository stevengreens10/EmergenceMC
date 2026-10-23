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

    /**
     * Each exhibit as the world compiles it, against the same exhibit compiled from its definition: a part that came
     * out welded, locked or unjoined shows as a different set of bodies, or a warning. (Run once the gallery's blocks
     * have registered with the simulation, a tick or so after they are placed.)
     */
    public static List<String> verifyAgainstDefinitions(ServerLevel level, BlockPos origin) {
        List<Exhibit> exhibits = Exhibits.forWorld();
        List<String> problems = new java.util.ArrayList<>();
        for (int i = 0; i < exhibits.size(); i++) problems.addAll(verifyExhibit(level, origin, i));
        for (String pr : problems) EmergenceMod.LOG.error("Demo build: {}", pr);
        EmergenceMod.LOG.info("Demo build: {} exhibits compiled in the world as from their definitions ({} differences)",
                exhibits.size(), problems.size());
        return problems;
    }

    /** {@link #verifyAgainstDefinitions} for exhibit i alone (its chunks loaded); the differences found. */
    public static List<String> verifyExhibit(ServerLevel level, BlockPos origin, int i) {
        List<Exhibit> exhibits = Exhibits.forWorld();
        List<String> problems = new java.util.ArrayList<>();
        var sim = dev.emergence.mod.sim.SimulationManager.get(level);
        sim.awaitCompiles(); // (a slow machine may still be compiling off the server thread)
        {
            Exhibit e = exhibits.get(i);
            BlockPos plot = origin.offset(plotOffset(exhibits, i), 0, 0);
            // every island the exhibit's blocks belong to (one exhibit may be several machines)
            java.util.Map<dev.emergence.core.assembly.CompiledAssembly, BlockPos> islands = new java.util.IdentityHashMap<>();
            List<String> uncompiled = new java.util.ArrayList<>();
            for (Exhibit.PartDef d : e.parts) {
                // (a miniature's parts are all in its one block)
                BlockPos at = e.miniature ? plot.offset(MINIATURE_AT) : plot.offset(d.x0(), d.y0(), d.z0());
                var c = sim.compiledAt(at);
                if (c != null) islands.putIfAbsent(c, at);
                else if (uncompiled.size() < 3) uncompiled.add(d.kind() + " at (" + d.x0() + ", " + d.y0() + ", " + d.z0() + ")");
            }
            if (!uncompiled.isEmpty()) problems.add(e.id + ": not simulated in the world: " + uncompiled);
            var core = dev.emergence.core.assembly.AssemblyCompiler.compile(Exhibits.build(e, PhysicalConstants.T_REF).assembly(),
                    Exhibits.build(e, PhysicalConstants.T_REF).state());
            int wb = 1;
            long ws = 0;
            List<String> ww = new java.util.ArrayList<>();
            int x0 = plot.getX(), x1 = plot.getX() + e.plots * PLOT_SPACING;
            java.util.function.BiPredicate<dev.emergence.core.assembly.CompiledAssembly, dev.emergence.core.assembly.CompiledAssembly.Body> own = (c, b) -> {
                // a body is this exhibit's if a part of it stands on its plot (light can join a neighbour's machine
                // into the same island: the periscope's mirrors see the engine next door)
                for (long id : b.parts()) {
                    BlockPos p = sim.partBlock(islands.get(c), id);
                    if (p != null && p.getX() >= x0 && p.getX() < x1) return true;
                }
                return false;
            };
            for (var c : islands.keySet()) {
                for (var b : c.bodies()) {
                    if (b.isStatic() || !own.test(c, b)) continue;
                    wb++;
                    if (b.slider()) ws++;
                }
                ww.addAll(c.warnings());
            }
            long cs = core.bodies().stream().filter(b -> b.slider()).count();
            if (wb != core.bodies().size() || ws != cs) {
                problems.add(e.id + ": in the world it has " + wb + " bodies (" + ws + " slides) over " + islands.size() + " islands, its definition "
                        + core.bodies().size() + " (" + cs + ")");
            }
            // body by body: what moves, how heavy, how far a slide may travel (as a multiset: the order may differ)
            List<String> wd = new java.util.ArrayList<>(), cd = new java.util.ArrayList<>();
            for (var c : islands.keySet()) for (var b : c.bodies()) if (!b.isStatic() && own.test(c, b)) wd.add(describe(b));
            for (var b : core.bodies()) if (!b.isStatic()) cd.add(describe(b));
            java.util.Collections.sort(wd);
            java.util.Collections.sort(cd);
            if (!wd.equals(cd)) {
                List<String> onlyW = new java.util.ArrayList<>(wd), onlyC = new java.util.ArrayList<>(cd);
                for (String s : cd) onlyW.remove(s);
                for (String s : wd) onlyC.remove(s);
                problems.add(e.id + ": bodies differ: in the world " + onlyW + ", in its definition " + onlyC);
            }
            java.util.Collections.sort(ww);
            List<String> cw = new java.util.ArrayList<>(core.warnings());
            java.util.Collections.sort(cw);
            if (!ww.equals(cw)) problems.add(e.id + ": in the world it warns " + ww + ", its definition " + cw);
        }
        return problems;
    }

    /** A body as the comparison sees it: rotor or slide, its parts, mass and inertia, a slide's travel. */
    private static String describe(dev.emergence.core.assembly.CompiledAssembly.Body b) {
        return (b.slider() ? String.format("slide of %d parts, %.4g kg, travel %.4g…%.4g m", b.parts().size(), b.mass(), b.strokeLo(), b.strokeHi())
                : String.format("rotor of %d parts, %.4g kg·m²", b.parts().size(), b.inertia()));
    }

    /** What the last build could not do as a player would (see {@link PlayerBuilder}). */
    public static final List<String> LAST_PROBLEMS = new java.util.ArrayList<>();

    /** Builds all exhibits with the first plot at {@code origin} (the block above the gallery floor). */
    public static BlockPos build(ServerLevel level, BlockPos origin) {
        List<Exhibit> exhibits = Exhibits.forWorld();
        for (int x = floorFrom(exhibits, 0); x <= floorTo(exhibits, exhibits.size() - 1); x++) floorColumn(level, origin, exhibits, x);
        welcomeSign(level, origin);
        List<String> problems = new java.util.ArrayList<>();
        for (int i = 0; i < exhibits.size(); i++) {
            Exhibit e = exhibits.get(i);
            BlockPos plot = origin.offset(plotOffset(exhibits, i), 0, 0);
            scenery(level, plot, e);
            // as a player builds it: each part made in the part editor and placed with a click
            if (e.miniature) problems.addAll(PlayerBuilder.buildMiniature(level, plot.offset(MINIATURE_AT), e));
            else problems.addAll(PlayerBuilder.build(level, plot, e));
            sign(level, plot, e);
        }
        for (String pr : problems) EmergenceMod.LOG.error("Demo build: {}", pr);
        EmergenceMod.LOG.info("Built {} demo exhibits at {} by player placement ({} problems)", exhibits.size(), origin, problems.size());
        LAST_PROBLEMS.clear();
        LAST_PROBLEMS.addAll(problems);
        return origin.offset(8, 0, SIGN_Z + 3);
    }

    /** The gallery floor's columns (x from the origin) that go with exhibit i: its plots, and the ends' borders. */
    public static int floorFrom(List<Exhibit> exhibits, int i) {
        return i == 0 ? -3 : plotOffset(exhibits, i);
    }

    public static int floorTo(List<Exhibit> exhibits, int i) {
        return i == exhibits.size() - 1 ? plotOffset(exhibits, exhibits.size()) + 1 : plotOffset(exhibits, i + 1) - 1;
    }

    /** One column (x from the origin) of the gallery floor (a polished border between plots), with clear air above it. */
    public static void floorColumn(ServerLevel level, BlockPos origin, List<Exhibit> exhibits, int x) {
        int width = plotOffset(exhibits, exhibits.size());
        boolean between = false;
        for (int i = 1; i <= exhibits.size(); i++) between |= x == plotOffset(exhibits, i) - 1;
        for (int z = -3; z <= SIGN_Z + 6; z++) {
            BlockPos f = origin.offset(x, -1, z);
            boolean edge = x == -3 || x == width + 1 || z == -3 || z == SIGN_Z + 6 || between || z == SIGN_Z - 1;
            level.setBlock(f, (edge ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_STONE).defaultBlockState(), 2);
            for (int y = 0; y < 18; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    public static void welcomeSign(ServerLevel level, BlockPos origin) {
        sign(level, origin.offset(-2, 0, SIGN_Z + 1), List.of("EMERGENCE", "Engineering from", "first principles", "exhibits ->"));
    }

    /** An exhibit's scenery: the ordinary blocks it stands among. */
    public static void scenery(ServerLevel level, BlockPos plot, Exhibit e) {
        for (Exhibit.Scenery s : e.scenery) {
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(s.block()));
            for (int x = s.x0(); x <= s.x1(); x++)
                for (int y = s.y0(); y <= s.y1(); y++)
                    for (int z = s.z0(); z <= s.z1(); z++) level.setBlock(plot.offset(x, y, z), block.defaultBlockState(), 2);
        }
    }

    /** The sign in front of an exhibit. */
    public static void sign(ServerLevel level, BlockPos plot, Exhibit e) {
        sign(level, plot.offset(8, 0, SIGN_Z), e.sign);
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

    /** The demo world's standing conditions: noon held, clear weather, no mobs. */
    public static void galleryRules(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000);
        level.setWeatherParameters(6000, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
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
        // the whole gallery loaded (forced) while each exhibit is compared with its definition, once its blocks have
        // registered with the simulation
        // held still meanwhile: the chunks arrive one by one, and a machine straddling two would run on half of itself
        // (an engine's crankshaft braked to a stop before its cylinder's chunk came) and be saved like that
        var sim = dev.emergence.mod.sim.SimulationManager.get(level);
        sim.setPaused(true);
        List<int[]> forced = new java.util.ArrayList<>();
        int width = plotOffset(Exhibits.forWorld(), Exhibits.forWorld().size());
        for (int cx = Math.floorDiv(top.getX() - 4, 16); cx <= Math.floorDiv(top.getX() + width + 4, 16); cx++)
            for (int cz = Math.floorDiv(top.getZ() - 4, 16); cz <= Math.floorDiv(top.getZ() + SIGN_Z + 8, 16); cz++) {
                level.setChunkForced(cx, cz, true);
                forced.add(new int[]{cx, cz});
            }
        int[] wait = {60};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post ev) -> {
            if (wait[0]-- != 0) return;
            verifyAgainstDefinitions(level, top);
            for (int[] c : forced) level.setChunkForced(c[0], c[1], false);
            sim.setPaused(false);
        });
        level.setDefaultSpawnPos(spawn, 180f);
        galleryRules(server);
        if (server.getWorldData() instanceof PrimaryLevelData data) {
            try {
                Field f = PrimaryLevelData.class.getDeclaredField("settings");
                f.setAccessible(true);
                LevelSettings old = (LevelSettings) f.get(data);
                f.set(data, new LevelSettings(Exhibits.worldName(), old.gameType(), old.hardcore(), old.difficulty(),
                        old.allowCommands(), old.gameRules(), old.getDataConfiguration()));
            } catch (ReflectiveOperationException e) {
                EmergenceMod.LOG.warn("Could not rename demo world", e);
            }
        }
        server.saveEverything(false, true, true);
        EmergenceMod.LOG.info("Demo world generated; spawn at {}", spawn);
    }
}
