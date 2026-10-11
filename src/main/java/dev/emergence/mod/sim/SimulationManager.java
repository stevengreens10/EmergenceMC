package dev.emergence.mod.sim;

import dev.emergence.core.assembly.Assembly;
import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.AssemblyState;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.Part;
import dev.emergence.core.assembly.PartGeometry;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.assembly.Probe;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.units.PhysicalConstants;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlock;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.net.IslandSyncPayload;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Per-level orchestration of the physics: groups face-adjacent assembly blocks into islands, compiles
 * each island's parts with {@link AssemblyCompiler}, advances them every server tick (50 ms of
 * simulated time), applies destructive outcomes (melting), persists dynamic state into the block
 * entities and streams body motion and temperatures to clients.
 */
public final class SimulationManager {
    private static final Map<ServerLevel, SimulationManager> MANAGERS = new WeakHashMap<>();
    public static final double TICK_SECONDS = 0.05;

    private final ServerLevel level;
    /**
     * Live block entities by position. The manager never asks the level for block entities: during
     * chunk unloading that would force a synchronous chunk load and deadlock the server thread.
     */
    private final Map<BlockPos, AssemblyBlockEntity> blocks = new HashMap<>();
    private final Map<BlockPos, Island> islandOf = new HashMap<>();
    private final Set<BlockPos> dirty = new HashSet<>();
    private long tickCount;

    /** A player's data logger: the part it is clipped to, its record and what its screen shows. */
    public static final class LoggerSession {
        BlockPos pos;
        long partId;
        String title;
        final dev.emergence.core.instruments.DataLogger log = CompiledAssembly.newLogger(1e-3, 65_536);
        CompiledAssembly attachedTo;
        int channel = 2;
        int span = 1;

        public dev.emergence.core.instruments.DataLogger log() {
            return log;
        }

        public String title() {
            return title;
        }
    }

    /** Time bases of the logger's screen [s]. */
    public static final double[] LOGGER_SPANS = {0.1, 1, 10, 60};
    private final Map<UUID, LoggerSession> loggers = new HashMap<>();

    public static final class Island {
        final Set<BlockPos> blocks = new HashSet<>();
        final Map<Long, BlockPos> partPos = new HashMap<>();
        CompiledAssembly compiled;
        final Map<Long, Integer> crankTicks = new HashMap<>();
        final Map<Long, Integer> pushTicks = new HashMap<>();
        final Map<BlockPos, Integer> light = new HashMap<>();
        double ambient;
        int resolution = AssemblyBlockEntity.MINIATURE;
        /** Simulated seconds per real second: below 1 when the island cannot keep up within its budget. */
        double timeScale = 1;
        /** Smoothed wall-clock cost of this island's tick [ms]. */
        double costMs;
        /** The state the island was last compiled from (for diagnosing a failure right after a restart). */
        AssemblyState startState;
        /** Whether a player was near it this tick, and its bounding box grown by the near distance (lazily). */
        boolean near, resetLogged;
        net.minecraft.world.phys.AABB box;

        public double timeScale() {
            return timeScale;
        }
    }

    /**
     * Wall-clock budget per island per server tick [ms]. An island that needs more runs in slow motion (time
     * dilation) instead of stalling the server: physics stays exact, only its pace relative to the world slows.
     * System property {@code emergence.islandBudgetMs} overrides it (the in-world tests lift it: they check what
     * happens within a number of ticks, which slow motion would stretch).
     */
    public static final double ISLAND_BUDGET_MS = Double.parseDouble(System.getProperty("emergence.islandBudgetMs", "12"));

    private SimulationManager(ServerLevel level) {
        this.level = level;
    }

    public static synchronized SimulationManager get(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level, SimulationManager::new);
    }

    public static synchronized void clear(ServerLevel level) {
        MANAGERS.remove(level);
    }

    public void register(BlockPos pos, AssemblyBlockEntity be) {
        blocks.put(pos.immutable(), be);
        markDirty(pos);
    }

    public void unregister(BlockPos pos) {
        Island isl = islandOf.get(pos);
        // write the latest state into the block entities but do not mark them changed: this runs while chunks
        // unload, and marking a block entity in a chunk that is itself unloading makes the level request that
        // chunk again, which reschedules its unload — forever, at server shutdown. The periodic save has already
        // marked the chunks unsaved, so what is written here goes out with them.
        if (isl != null) saveIsland(isl, false);
        blocks.remove(pos);
        markDirty(pos);
    }

    private AssemblyBlockEntity be(BlockPos pos) {
        AssemblyBlockEntity be = blocks.get(pos);
        return be != null && !be.isRemoved() ? be : null;
    }

    public void markDirty(BlockPos pos) {
        dirty.add(pos.immutable());
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        tickCount++;
        if (!dirty.isEmpty()) rebuild();
        Set<Island> islands = new HashSet<>(islandOf.values());
        double nearTotal = 0, farTotal = 0;
        for (Island isl : islands) {
            if (isl.compiled == null) continue;
            // like block entities, machines advance only within the simulation distance of a player; further
            // away their state waits, intact. A machine reaching into simulation distance runs as a whole (judging
            // it by an arbitrary one of its blocks made one straddling a chunk border run or freeze by chance)
            if (!anyTicking(isl)) continue;
            updateCranks(isl);
            isl.near = nearPlayer(isl);
            List<Long> failed;
            long t0 = System.nanoTime();
            try {
                failed = isl.compiled.step(TICK_SECONDS * isl.timeScale * scaleOf(isl));
            } catch (RuntimeException e) {
                // restart from the last good state at rest (charges, heat, positions kept); if even that will not
                // run, from nothing
                EmergenceMod.LOG.error("Simulation of island at {} ({} parts) failed; restarting it at rest{}; {}", isl.blocks.iterator().next(),
                        isl.compiled.assembly().parts().size(), partHint(isl, e.getMessage()), stateSummary(isl), e);
                if (Boolean.getBoolean("emergence.dumpFailures")) dumpFailure(isl);
                AssemblyState keep = isl.compiled.exportState().recovery();
                isl.compiled = compile(isl, keep);
                try {
                    if (isl.compiled != null) isl.compiled.step(1e-4);
                } catch (RuntimeException again) {
                    EmergenceMod.LOG.error("Restart failed too; resetting the island's state", again);
                    isl.compiled = compile(isl, AssemblyState.empty());
                }
                continue;
            }
            if ((isl.compiled.coilCurrentsReset() || isl.compiled.motionReset()) && !isl.resetLogged) {
                isl.resetLogged = true;
                EmergenceMod.LOG.info("Island at {}: restored {} inconsistent with the build as loaded; started from rest",
                        isl.blocks.iterator().next(), isl.compiled.motionReset() ? "motion was" : "coil currents were");
            }
            long cost = System.nanoTime() - t0;
            if (isl.near) nearTotal += cost / 1e6;
            else farTotal += cost / 1e6;
            Profiler.record(cost, isl.compiled.network().size());
            pace(isl, cost / 1e6);
            if (!failed.isEmpty()) destroyParts(isl, failed);
            updateLight(isl);
            bubbles(isl);
            ventPlumes(isl);
            if (tickCount % 20 == 0) daylight(isl);
            if (tickCount % 400 == 0 && Boolean.getBoolean("emergence.debugIslands") && isl.compiled.sunlightAbsorbed() > 100) {
                long hot = isl.partPos.keySet().stream().max(java.util.Comparator.comparingDouble(isl.compiled::temperature)).orElse(-1L);
                EmergenceMod.LOG.info("Island at {}: t = {} s, time scale {}, {} ms/step, hottest {} at {} K", isl.blocks.iterator().next(),
                        Math.round(isl.compiled.time()), String.format("%.3f", isl.timeScale * scaleOf(isl)), String.format("%.1f", isl.costMs),
                        hot < 0 ? "-" : isl.compiled.assembly().get(hot).kind() + "@" + isl.partPos.get(hot).toShortString(),
                        hot < 0 ? 0 : Math.round(isl.compiled.temperature(hot)));
            }
            sync(isl);
            if (tickCount % 100 == 0) saveIsland(isl);
        }
        tickLoggers();
        // all islands together within the server's budget, the ones near a player first: if they cost more, those
        // far from every player slow (down to nearly paused) before the ones being watched do
        nearScale = adjust(nearScale, nearTotal, GLOBAL_BUDGET_MS, 0.05);
        farScale = adjust(farScale, farTotal, Math.max(GLOBAL_BUDGET_MS - nearTotal, 0.1 * GLOBAL_BUDGET_MS), 0.01);
    }

    /** Multiplicative controller: slow in proportion when over budget, recover by 25 % a tick when under it. */
    private static double adjust(double scale, double costMs, double budgetMs, double min) {
        double want = costMs > budgetMs ? budgetMs / costMs : 1.25;
        return Math.max(min, Math.min(1, scale * Math.min(want, 1.25)));
    }

    /** Islands within this many blocks of a player get the time budget before those further away. */
    public static final double NEAR_BLOCKS = 48;

    /** The common slow-motion factor for an island (on top of its own), by whether a player is near it. */
    private double scaleOf(Island isl) {
        return isl.near ? nearScale : farScale;
    }

    /** True if some player is within {@link #NEAR_BLOCKS} of the island's bounding box. */
    private boolean nearPlayer(Island isl) {
        if (isl.box == null) {
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (BlockPos b : isl.blocks) {
                x0 = Math.min(x0, b.getX());
                y0 = Math.min(y0, b.getY());
                z0 = Math.min(z0, b.getZ());
                x1 = Math.max(x1, b.getX());
                y1 = Math.max(y1, b.getY());
                z1 = Math.max(z1, b.getZ());
            }
            isl.box = new net.minecraft.world.phys.AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1).inflate(NEAR_BLOCKS);
        }
        for (var p : level.players()) if (isl.box.contains(p.position())) return true;
        return false;
    }

    /**
     * Wall-clock budget for all islands together per server tick [ms] (a tick is 50 ms). System property
     * {@code emergence.globalBudgetMs} overrides it (the demo-world generation lifts it so its in-world tests run at
     * full rate however long the gallery takes).
     */
    public static final double GLOBAL_BUDGET_MS = Double.parseDouble(System.getProperty("emergence.globalBudgetMs", "35"));
    /** Common slow-motion factor applied on top of each island's own when all of them together are too costly. */
    private double nearScale = 1, farScale = 1;

    // ------------------------------------------------------------------ data loggers

    /** Clips a player's logger onto a part (a new part starts a new record). */
    public void attachLogger(ServerPlayer player, BlockPos pos, long partId, String title) {
        LoggerSession s = loggers.computeIfAbsent(player.getUUID(), k -> new LoggerSession());
        if (s.attachedTo != null) s.attachedTo.detachLogger(s.log);
        s.attachedTo = null;
        if (s.partId != partId) s.log.clear();
        s.pos = pos.immutable();
        s.partId = partId;
        s.title = title;
    }

    public LoggerSession logger(ServerPlayer player) {
        return loggers.get(player.getUUID());
    }

    /** Shows the next channel the part actually has; returns a status line. */
    public String cycleLoggerChannel(ServerPlayer player) {
        LoggerSession s = loggers.get(player.getUUID());
        if (s == null) return "Clip the logger onto a part first";
        var ch = s.log.channels();
        for (int k = 1; k <= ch.size(); k++) {
            int c = (s.channel + k) % ch.size();
            if (s.log.size() > 0 && !Double.isNaN(s.log.value(c, s.log.size() - 1))) {
                s.channel = c;
                break;
            }
        }
        return "Channel: " + ch.get(s.channel).name() + " [" + ch.get(s.channel).unit() + "]";
    }

    /** Shows a channel by name and sets the time base (for scripted demonstrations). */
    public void showLogger(ServerPlayer player, String channel, int spanIndex) {
        LoggerSession s = loggers.get(player.getUUID());
        if (s == null) return;
        var ch = s.log.channels();
        for (int c = 0; c < ch.size(); c++) if (ch.get(c).name().equals(channel)) s.channel = c;
        s.span = Math.max(0, Math.min(LOGGER_SPANS.length - 1, spanIndex));
    }

    /** The newest value of a player's logger on its shown channel (NaN if none). */
    public double loggerLatest(ServerPlayer player) {
        LoggerSession s = loggers.get(player.getUUID());
        return s == null || s.log.size() == 0 ? Double.NaN : s.log.value(s.channel, s.log.size() - 1);
    }

    public String cycleLoggerSpan(ServerPlayer player) {
        LoggerSession s = loggers.get(player.getUUID());
        if (s == null) return "Clip the logger onto a part first";
        s.span = (s.span + 1) % LOGGER_SPANS.length;
        return "Time base: " + dev.emergence.core.units.Units.formatSI(LOGGER_SPANS[s.span], "s") + " across the screen";
    }

    /** Keeps each logger connected to its part's (possibly recompiled) island and streams its trace to its owner. */
    private void tickLoggers() {
        if (loggers.isEmpty()) return;
        for (var it = loggers.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            LoggerSession s = en.getValue();
            Island isl = islandOf.get(s.pos);
            CompiledAssembly now = isl == null ? null : isl.compiled;
            if (now != s.attachedTo) {
                if (s.attachedTo != null) s.attachedTo.detachLogger(s.log);
                s.attachedTo = null;
                if (now != null && now.assembly().get(s.partId) != null) {
                    now.attachLogger(s.log, s.partId);
                    s.attachedTo = now;
                }
            }
            if (tickCount % 2 != 0) continue;
            if (!(level.getPlayerByUUID(en.getKey()) instanceof ServerPlayer player)) continue;
            var ch = s.log.channels().get(s.channel);
            float[][] env = s.log.envelope(s.channel, LOGGER_SPANS[s.span], 200);
            float latest = s.log.size() == 0 ? Float.NaN : (float) s.log.value(s.channel, s.log.size() - 1);
            String title = s.title + (s.attachedTo == null ? " — disconnected" : "");
            PacketDistributor.sendToPlayer(player, new dev.emergence.mod.net.LoggerSyncPayload(title, ch.name(), ch.unit(),
                    (float) LOGGER_SPANS[s.span], (float) (1 / s.log.interval()), latest, env[0], env[1]));
        }
    }

    /** For a solver message naming a part's node ("…T123…", "…p123…"), the part's kind and block, for diagnosis. */
    private static String partHint(Island isl, String msg) {
        if (msg == null) return "";
        var m = java.util.regex.Pattern.compile("[A-Za-z]+(-?[0-9]{3,})").matcher(msg);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group(1));
                var part = isl.compiled.assembly().get(id);
                if (part != null) sb.append("; ").append(m.group()).append(" is ").append(part.kind()).append(" at ").append(isl.partPos.get(id));
            } catch (NumberFormatException ignored) {
            }
        }
        return sb.toString();
    }

    /**
     * Daylight on the island's photodiodes: the sun's direction from the time of day (it rises in the east and
     * sets in the west), irradiance 1000 W/m² at the zenith scaled by how high it stands and reduced by rain and
     * thunder; a part sees it only if open sky is above its block.
     */
    private void daylight(Island isl) {
        double angle = level.getSunAngle(1.0f); // 0 at noon
        dev.emergence.core.math.Vec3 toSun = new dev.emergence.core.math.Vec3(-Math.sin(angle), Math.cos(angle), 0);
        double irr = toSun.y() > 0 ? 1000 * (1 - 0.75 * level.getRainLevel(1.0f) - 0.15 * level.getThunderLevel(1.0f)) : 0;
        double traces = isl.compiled.sunTraceCost()[0];
        isl.compiled.setDaylight(toSun.mul(-1), irr, id -> {
            BlockPos pos = isl.partPos.get(id);
            return pos != null && level.canSeeSky(pos.above());
        });
        if (Boolean.getBoolean("emergence.debugIslands") && isl.compiled.sunTraceCost()[0] > traces) {
            String top = isl.partPos.keySet().stream()
                    .sorted((x, y) -> Double.compare(isl.compiled.sunlightAbsorbed(y), isl.compiled.sunlightAbsorbed(x))).limit(3)
                    .map(id -> isl.compiled.assembly().get(id).kind() + "@" + isl.partPos.get(id).toShortString()
                            + " " + Math.round(isl.compiled.sunlightAbsorbed(id)) + " W").collect(java.util.stream.Collectors.joining(", "));
            EmergenceMod.LOG.info("Island at {}: sunlight traced ({} W/m², {} ms so far), {} W absorbed; most by {}", isl.blocks.iterator().next(),
                    Math.round(irr), Math.round(isl.compiled.sunTraceCost()[1]), Math.round(isl.compiled.sunlightAbsorbed()), top);
        }
    }

    /** Time dilation: shrink an island's simulated step while its cost exceeds the budget, recover when cheap. */
    static void pace(Island isl, double ms) {
        isl.costMs = isl.costMs == 0 ? ms : 0.8 * isl.costMs + 0.2 * ms;
        if (isl.costMs > ISLAND_BUDGET_MS) isl.timeScale = Math.max(0.05, isl.timeScale * 0.92);
        else if (isl.costMs < 0.6 * ISLAND_BUDGET_MS) isl.timeScale = Math.min(1, isl.timeScale * 1.05);
    }

    private void rebuild() {
        // collect affected islands (and their state) then re-flood-fill from dirty positions
        Set<BlockPos> seeds = new HashSet<>(dirty);
        dirty.clear();
        Set<Island> old = new HashSet<>();
        // block-scale parts interact across air gaps (magnets near coils: 2 blocks); large gears' rims reach further,
        // and optical parts couple whatever lies in their line of sight — both as symmetric links, so island
        // membership does not depend on the flood order, and one big gear does not widen every block's reach
        int blockReach = 2;
        Map<BlockPos, Set<BlockPos>> sight = sightLinks();
        for (BlockPos p : seeds) {
            forNeighbours(p, blockReach, n -> {
                Island i = islandOf.get(n);
                if (i != null) old.add(i);
            });
            for (BlockPos n : sight.getOrDefault(p, Set.of())) {
                Island i = islandOf.get(n);
                if (i != null) old.add(i);
            }
            Island i = islandOf.get(p);
            if (i != null) old.add(i);
        }
        AssemblyState merged = AssemblyState.empty();
        for (Island i : old) {
            if (i.compiled != null) {
                AssemblyState s = i.compiled.exportState();
                merged.temperatures().putAll(s.temperatures());
                merged.bodyMotion().putAll(s.bodyMotion());
                merged.coilCurrents().putAll(s.coilCurrents());
                merged.capacitorVoltages().putAll(s.capacitorVoltages());
                merged.chemistry().putAll(s.chemistry());
                merged.fluids().putAll(s.fluids());
                merged.damage().putAll(s.damage());
            }
            for (BlockPos b : i.blocks) {
                islandOf.remove(b);
                seeds.add(b);
            }
        }
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos seed : seeds) {
            if (!blocks.containsKey(seed) || visited.contains(seed)) continue;
            Island isl = new Island();
            isl.resolution = blocks.get(seed).resolution();
            Deque<BlockPos> q = new ArrayDeque<>(List.of(seed));
            visited.add(seed);
            while (!q.isEmpty()) {
                BlockPos b = q.poll();
                isl.blocks.add(b);
                islandOf.put(b, isl);
                // only blocks of the same grid resolution form one machine
                forNeighbours(b, isl.resolution == AssemblyBlockEntity.BLOCK_SCALE ? blockReach : 1, n -> {
                    if (blocks.containsKey(n) && blocks.get(n).resolution() == isl.resolution && visited.add(n)) q.add(n);
                });
                for (BlockPos n : sight.getOrDefault(b, Set.of())) {
                    if (blocks.containsKey(n) && blocks.get(n).resolution() == isl.resolution && visited.add(n)) q.add(n);
                }
            }
            AssemblyState st = mergeWithSaved(isl, merged);
            isl.compiled = compile(isl, st);
        }
    }

    /**
     * Range [blocks] of an optical part's line of sight for joining islands: a mirror's (concentrators throw light
     * far), a lens's, LED's or photodiode's (these work at short range); and the cone's half-angle (cosine).
     */
    private static final int SIGHT = 15, SIGHT_NEAR = 8;
    private static final double SIGHT_COS = 0.5;

    /**
     * Blocks joined by light: a block-scale mirror (lens, LED or photodiode) joins its island with every assembly
     * block within {@link #SIGHT} ({@link #SIGHT_NEAR}) blocks inside a 60° cone about the directions it sends or receives light along
     * (a mirror's front, a lens's axis both ways, an LED's or photodiode's face) — the dish and the plate at its
     * focus are one machine. Symmetric, so membership does not depend on the flood order. Bodies on slides within
     * {@link #HEAR} blocks are joined too (sound).
     */
    private Map<BlockPos, Set<BlockPos>> sightLinks() {
        Map<BlockPos, Set<BlockPos>> links = new HashMap<>();
        List<BlockPos> all = null;
        List<BlockPos> slides = new ArrayList<>();
        for (AssemblyBlockEntity be : blocks.values()) {
            if (be.resolution() != AssemblyBlockEntity.BLOCK_SCALE) continue;
            for (PlacedPart p : be.parts()) if (p.kind == PartKind.LINEAR_BEARING) {
                slides.add(be.getBlockPos());
                break;
            }
            // a large gear's rim reaches into blocks beyond the common reach
            int far = reach(be);
            if (far > 2) {
                BlockPos o = be.getBlockPos();
                forNeighbours(o, far, n -> {
                    if (blocks.containsKey(n)) {
                        links.computeIfAbsent(o, k -> new HashSet<>()).add(n);
                        links.computeIfAbsent(n, k -> new HashSet<>()).add(o);
                    }
                });
            }
            List<dev.emergence.core.math.Vec3> dirs = new ArrayList<>();
            int range = SIGHT_NEAR;
            for (PlacedPart p : be.parts()) {
                switch (p.kind) {
                    case MIRROR -> {
                        dirs.add(dev.emergence.core.assembly.PartGeometry.mirrorNormal(p.toCore(BlockPos.ZERO, 1)));
                        range = SIGHT;
                    }
                    case LENS -> {
                        dirs.add(p.axis.vec());
                        dirs.add(p.axis.vec().mul(-1));
                    }
                    case LED, PHOTODIODE -> dirs.add(dev.emergence.core.assembly.PartGeometry.facing(p.toCore(BlockPos.ZERO, 1)));
                    default -> {
                    }
                }
            }
            if (dirs.isEmpty()) continue;
            if (all == null) {
                all = new ArrayList<>();
                for (var e : blocks.entrySet()) if (e.getValue().resolution() == AssemblyBlockEntity.BLOCK_SCALE) all.add(e.getKey());
            }
            BlockPos o = be.getBlockPos();
            for (BlockPos n : all) {
                int dx = n.getX() - o.getX(), dy = n.getY() - o.getY(), dz = n.getZ() - o.getZ();
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 == 0 || d2 > range * range) continue;
                double len = Math.sqrt(d2);
                for (var d : dirs) {
                    if (dx * d.x() + dy * d.y() + dz * d.z() >= SIGHT_COS * len) {
                        links.computeIfAbsent(o, k -> new HashSet<>()).add(n);
                        links.computeIfAbsent(n, k -> new HashSet<>()).add(o);
                        break;
                    }
                }
            }
        }
        // bodies on slides hear one another through the air (sound): their bearings' blocks within HEAR blocks join
        for (int i = 0; i < slides.size(); i++)
            for (int j = i + 1; j < slides.size(); j++) {
                BlockPos u = slides.get(i), v = slides.get(j);
                if (u.distSqr(v) <= HEAR * HEAR) {
                    links.computeIfAbsent(u, k -> new HashSet<>()).add(v);
                    links.computeIfAbsent(v, k -> new HashSet<>()).add(u);
                }
            }
        return links;
    }

    /** Range [blocks] over which bodies on slides are joined into one machine, to hear one another. */
    private static final int HEAR = 8;

    /** True if any block of the island is in a chunk that ticks block entities. */
    private boolean anyTicking(Island isl) {
        long lastChunk = Long.MIN_VALUE;
        for (BlockPos b : isl.blocks) {
            long chunk = ChunkPos.asLong(b.getX() >> 4, b.getZ() >> 4);
            if (chunk == lastChunk) continue;
            lastChunk = chunk;
            if (level.shouldTickBlocksAt(b)) return true;
        }
        return false;
    }

    /** Face neighbours for reach 1, otherwise every block within the cube of that half-width. */
    private static void forNeighbours(BlockPos b, int reach, java.util.function.Consumer<BlockPos> f) {
        if (reach <= 1) {
            for (Direction d : Direction.values()) f.accept(b.relative(d));
            return;
        }
        for (int dx = -reach; dx <= reach; dx++)
            for (int dy = -reach; dy <= reach; dy++)
                for (int dz = -reach; dz <= reach; dz++) if (dx != 0 || dy != 0 || dz != 0) f.accept(b.offset(dx, dy, dz));
    }

    /**
     * Interaction reach of a block-scale block [blocks]: 2 by default (magnetic coupling across a one-block
     * gap), more for large gears whose rims extend into neighbouring blocks.
     */
    private static int reach(AssemblyBlockEntity be) {
        int r = 2;
        for (PlacedPart p : be.parts()) {
            if (p.kind == PartKind.GEAR) {
                double rp = p.spec.params().getOrDefault("pitch_radius", 0.5 / 16.0) * 16.0;
                r = Math.max(r, (int) Math.ceil(rp + 3.0));
            }
        }
        return r;
    }

    /** State from the previous compilation, falling back to what the block entities persisted. */
    private AssemblyState mergeWithSaved(Island isl, AssemblyState fromOld) {
        AssemblyState st = AssemblyState.empty();
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            for (var e : be.savedState().entrySet()) {
                double[] v = e.getValue();
                if (v[0] > 0) st.temperatures().put(e.getKey(), v[0]);
                st.bodyMotion().put(e.getKey(), new double[]{v[1], v[2]});
                st.coilCurrents().put(e.getKey(), v[3]);
                st.capacitorVoltages().put(e.getKey(), v[4]);
                if (v.length > 6 && v[5] > 0) st.fluids().put(e.getKey(), new double[]{v[5], v[6]});
                if (v.length > 7 && v[7] > 0) st.damage().put(e.getKey(), v[7]);
            }
            st.chemistry().putAll(be.savedChemistry());
        }
        st.temperatures().putAll(fromOld.temperatures());
        st.bodyMotion().putAll(fromOld.bodyMotion());
        st.coilCurrents().putAll(fromOld.coilCurrents());
        st.capacitorVoltages().putAll(fromOld.capacitorVoltages());
        st.chemistry().putAll(fromOld.chemistry());
        st.fluids().putAll(fromOld.fluids());
        st.damage().putAll(fromOld.damage());
        return st;
    }

    /**
     * Writes a failed island's parts, anchoring, ambient and start state to {@code island-failure-*.txt} in the game
     * directory (system property {@code emergence.dumpFailures}), for replaying it in the core tests.
     */
    private void dumpFailure(Island isl) {
        var asm = isl.compiled.assembly();
        StringBuilder b = new StringBuilder();
        b.append("ambient ").append(asm.ambientTemperature()).append(' ').append(asm.ambientPressure()).append('\n');
        for (var p : asm.parts()) {
            b.append("P ").append(p.id()).append(' ').append(p.kind()).append(' ').append(p.material().id()).append(' ')
                    .append(p.minX()).append(' ').append(p.minY()).append(' ').append(p.minZ()).append(' ')
                    .append(p.maxX()).append(' ').append(p.maxY()).append(' ').append(p.maxZ()).append(' ').append(p.axis())
                    .append(' ').append(asm.isAnchored(p) ? 1 : 0);
            p.params().forEach((k, v) -> b.append(' ').append(k).append('=').append(v));
            b.append('\n');
        }
        AssemblyState st = isl.startState;
        if (st != null) {
            st.temperatures().forEach((k, v) -> b.append("T ").append(k).append(' ').append(v).append('\n'));
            st.coilCurrents().forEach((k, v) -> b.append("I ").append(k).append(' ').append(v).append('\n'));
            st.capacitorVoltages().forEach((k, v) -> b.append("V ").append(k).append(' ').append(v).append('\n'));
            st.bodyMotion().forEach((k, v) -> b.append("M ").append(k).append(' ').append(v[0]).append(' ').append(v[1]).append('\n'));
            st.fluids().forEach((k, v) -> b.append("F ").append(k).append(' ').append(v[0]).append(' ').append(v[1]).append('\n'));
            st.chemistry().forEach((k, v) -> b.append("C ").append(k).append(' ').append(v).append('\n'));
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("island-failure-" + System.currentTimeMillis() + ".txt"), b);
        } catch (java.io.IOException e) {
            EmergenceMod.LOG.warn("Could not write island dump", e);
        }
    }

    /** What the island started from: the largest restored temperature, coil current and capacitor voltage. */
    private static String stateSummary(Island isl) {
        AssemblyState st = isl.startState;
        if (st == null) return "no start state";
        StringBuilder b = new StringBuilder("started from ");
        summarise(b, "T", st.temperatures());
        summarise(b, "coil I", st.coilCurrents());
        summarise(b, "capacitor V", st.capacitorVoltages());
        return b.append("solver time ").append(isl.compiled.solver().time()).append(" s").toString();
    }

    private static void summarise(StringBuilder b, String what, Map<Long, Double> m) {
        long worst = -1, bad = 0;
        double max = 0;
        for (var e : m.entrySet()) {
            double v = e.getValue();
            if (!Double.isFinite(v)) bad++;
            else if (Math.abs(v) > max) {
                max = Math.abs(v);
                worst = e.getKey();
            }
        }
        b.append(String.format("%s: %d values, max |%.4g| (part %d), %d non-finite; ", what, m.size(), max, worst, bad));
    }

    private CompiledAssembly compile(Island isl, AssemblyState state) {
        isl.startState = state;
        Assembly asm = new Assembly();
        isl.partPos.clear();
        Map<Long, int[]> anchored = new HashMap<>();
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            for (PlacedPart pp : be.parts()) {
                asm.add(pp.toCore(b, be.resolution()));
                isl.partPos.put(pp.id, b);
                if (touchesWorld(b, pp, be.resolution())) anchored.put(pp.id, null);
            }
        }
        BlockPos first = isl.blocks.iterator().next();
        isl.ambient = ambientTemperature(first);
        asm.ambientTemperature(isl.ambient);
        asm.ambientPressure(airPressure(first));
        // open pipe ends inside world blocks are capped; ends in air (or water...) stay open
        int res = isl.resolution;
        asm.solidWhen((x, y, z) -> {
            BlockPos bp = new BlockPos(Math.floorDiv(x, res), Math.floorDiv(y, res), Math.floorDiv(z, res));
            if (isl.blocks.contains(bp)) return false;
            return !level.isLoaded(bp) || level.getBlockState(bp).isSolid();
        });
        asm.anchoredWhen(p -> anchored.containsKey(p.id()));
        if (asm.parts().isEmpty()) return null;
        if (Boolean.getBoolean("emergence.debugIslands")) {
            EmergenceMod.LOG.info("Compiling island {} parts, anchored {}", asm.parts().size(),
                    asm.parts().stream().filter(p -> anchored.containsKey(p.id())).map(p -> p.kind() + "@" + p.minY()).toList());
        }
        try {
            CompiledAssembly c = AssemblyCompiler.compile(asm, state);
            for (String w : c.warnings()) EmergenceMod.LOG.debug("Island warning: {}", w);
            return c;
        } catch (RuntimeException e) {
            EmergenceMod.LOG.error("Failed to compile island", e);
            return null;
        }
    }

    /** True if the part's cell box reaches a face of its block that touches a solid world block. */
    private boolean touchesWorld(BlockPos pos, PlacedPart p, int res) {
        // a thin shaft resting on a block is a point contact (a pivot), not a rigid mounting
        if (p.kind == PartKind.SHAFT || p.kind == PartKind.WIRE || p.kind == PartKind.INSULATED_WIRE) return false;
        if (!p.kind.structural && p.kind != PartKind.ELECTROLYTE) return false;
        int m = res - 1;
        for (Direction d : Direction.values()) {
            boolean atFace = switch (d) {
                case DOWN -> p.y0 == 0;
                case UP -> p.y1 == m;
                case NORTH -> p.z0 == 0;
                case SOUTH -> p.z1 == m;
                case WEST -> p.x0 == 0;
                case EAST -> p.x1 == m;
            };
            if (!atFace) continue;
            BlockPos n = pos.relative(d);
            if (!level.isLoaded(n)) continue; // never force-load a chunk from the simulation
            BlockState s = level.getBlockState(n);
            if (!s.isAir() && !(s.getBlock() instanceof AssemblyBlock) && s.isFaceSturdy(level, n, d.getOpposite())) return true;
        }
        return false;
    }

    /**
     * Ambient air temperature from the biome's climate. Minecraft's dimensionless biome temperature is
     * mapped piecewise-linearly so that snowy biomes (0.0) ≈ −5 °C, plains (0.8) ≈ 20 °C and deserts
     * (2.0) ≈ 40 °C — plausible annual-mean surface air temperatures for those climates.
     */
    private double ambientTemperature(BlockPos pos) {
        float t = level.getBiome(pos).value().getBaseTemperature();
        double celsius = t <= 0.8 ? -5.0 + 31.25 * t : 20.0 + 16.67 * (t - 0.8);
        return PhysicalConstants.T_ZERO_CELSIUS + celsius;
    }

    // ------------------------------------------------------------------ interaction

    private Island islandAt(BlockPos pos) {
        if (dirty.contains(pos)) rebuild();
        return islandOf.get(pos);
    }

    /** A player holds the crank: effort follows the human model inside the solver while held. */
    public void turnCrank(BlockPos pos, long partId, Player player) {
        Island isl = islandAt(pos);
        if (isl == null || isl.compiled == null) return;
        isl.compiled.setCrankEffort(partId, player.isShiftKeyDown() ? -1 : 1, player.isSprinting());
        isl.crankTicks.put(partId, 6); // right-click repeats every 4 ticks while held
    }

    /** A player pushes a slide along its axis (sneaking: the other way) while holding use. */
    public boolean pushSlider(BlockPos pos, long partId, Player player) {
        Island isl = islandAt(pos);
        if (isl == null || isl.compiled == null) return false;
        if (!isl.compiled.setPushEffort(partId, player.isShiftKeyDown() ? -1 : 1)) return false;
        isl.pushTicks.put(partId, 6);
        return true;
    }

    private void updateCranks(Island isl) {
        for (var it = isl.pushTicks.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0) {
                isl.compiled.setPushEffort(e.getKey(), 0);
                it.remove();
            } else {
                e.setValue(left);
            }
        }
        Iterator<Map.Entry<Long, Integer>> it = isl.crankTicks.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0) {
                isl.compiled.setCrankEffort(e.getKey(), 0, false);
                it.remove();
            } else {
                e.setValue(left);
            }
        }
    }

    /**
     * Air pressure at a world height (isothermal barometric formula, scale height 8.43 km, sea level at
     * y = 63 with one block = 1 m).
     */
    static double airPressure(BlockPos pos) {
        return PhysicalConstants.STANDARD_ATMOSPHERE * Math.exp(-(pos.getY() - 63) / 8434.0);
    }

    public boolean toggleSwitch(BlockPos pos, long partId) {
        Island isl = islandAt(pos);
        boolean closed = false;
        if (isl != null && isl.compiled != null) closed = isl.compiled.toggleSwitch(partId);
        AssemblyBlockEntity be = be(pos);
        if (be != null) {
            PlacedPart p = be.byId(partId);
            if (p != null) {
                p.closed = closed;
                be.setChanged();
                level.sendBlockUpdated(pos, be.getBlockState(), be.getBlockState(), 3); // clients redraw the lever
            }
        }
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4f, closed ? 0.8f : 0.6f);
        return closed;
    }

    public Probe probe(BlockPos pos, long partId) {
        Island isl = islandAt(pos);
        return isl == null || isl.compiled == null ? null : isl.compiled.probe(partId);
    }

    public CompiledAssembly compiledAt(BlockPos pos) {
        Island isl = islandAt(pos);
        return isl == null ? null : isl.compiled;
    }

    // ------------------------------------------------------------------ consequences

    private void destroyParts(Island isl, List<Long> ids) {
        java.util.Set<Long> broken = isl.compiled == null ? java.util.Set.of() : new java.util.HashSet<>(isl.compiled.structure().failed());
        for (long id : ids) {
            BlockPos pos = isl.partPos.get(id);
            AssemblyBlockEntity be = pos == null ? null : be(pos);
            if (be == null) continue;
            PlacedPart p = be.removePart(id);
            if (p == null) continue;
            double r = be.resolution();
            double px = pos.getX() + (p.x0 + 0.5) / r, py = pos.getY() + (p.y0 + 0.5) / r, pz = pos.getZ() + (p.z0 + 0.5) / r;
            double burst = isl.compiled == null ? Double.NaN : isl.compiled.burstUtilisation(id);
            if (burst >= 1) {
                // a pressure vessel tears open: a crack, and what it held escapes as a cloud
                level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.8f, 0.7f);
                level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.8f);
                level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 40, 0.1 * 16 / r, 0.1 * 16 / r, 0.1 * 16 / r, 0.25);
            } else if (broken.contains(id)) {
                // overloaded: the part breaks (or loses its support) and its pieces drop
                level.playSound(null, pos, p.spec.material().contains("glass") ? SoundEvents.GLASS_BREAK : SoundEvents.ANVIL_LAND,
                        SoundSource.BLOCKS, 0.6f, 1.3f);
                level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                        net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState()), px, py, pz, 12, 0.1, 0.1, 0.1, 0.05);
                net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(level, px, py, pz,
                        dev.emergence.mod.part.PartPresets.stack(p.kind, p.spec));
                level.addFreshEntity(drop);
            } else {
                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.7f, 1.2f);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, px, py, pz, 8, 0.05 * 16 / r, 0.05 * 16 / r, 0.05 * 16 / r, 0.01);
            }
            if (be.parts().isEmpty()) level.removeBlock(pos, false);
        }
    }

    /**
     * Visible incandescence: luminous output from the Stefan–Boltzmann radiated power of parts above
     * the Draper point (798 K) mapped onto Minecraft's 0–15 light levels.
     */
    private void updateLight(Island isl) {
        if (tickCount % 5 != 0) return;
        Map<BlockPos, Integer> lv = new HashMap<>();
        CompiledAssembly c = isl.compiled;
        for (var e : isl.partPos.entrySet()) {
            // LEDs: light level rising with the logarithm of optical power (1 mW → 6, 30 mW → 10)
            double opt = c.lightOutput(e.getKey());
            if (opt > 1e-4) lv.merge(e.getValue(), (int) Math.min(15, Math.round(6 + 2.7 * Math.log10(opt / 1e-3))), Math::max);
            double t = c.temperature(e.getKey());
            if (t < 798) continue;
            int l = (int) Math.min(15, Math.round((t - 798) / 1700.0 * 15 + 3));
            lv.merge(e.getValue(), l, Math::max);
        }
        for (BlockPos b : isl.blocks) {
            int want = lv.getOrDefault(b, 0);
            BlockState s = level.getBlockState(b);
            if (s.getBlock() instanceof AssemblyBlock && s.getValue(AssemblyBlock.LIGHT) != want) {
                level.setBlock(b, s.setValue(AssemblyBlock.LIGHT, want), 2);
            }
        }
    }

    /**
     * Gas evolved at electrodes (hydrogen on a corroding or cathodic metal, oxygen at an anode) rises as
     * bubbles through the electrolyte it touches; the particle rate follows the evolution rate.
     */
    private void bubbles(Island isl) {
        if (tickCount % 4 != 0) return;
        CompiledAssembly c = isl.compiled;
        double toWorld = 1.0 / (Part.CELL * isl.resolution); // metres → world blocks
        for (long id : isl.partPos.keySet()) {
            double rate = c.gasEvolution(id);
            if (!(rate > 2e-8)) continue;
            Part p = c.assembly().get(id);
            if (p == null) continue;
            // 1 particle per 4 ticks at ~20 nmol/s, rising logarithmically
            int n = (int) Math.min(6, 1 + Math.log10(rate / 2e-8) * 1.5);
            Vec3 pc = p.center();
            for (Part q : c.assembly().neighbors(p)) {
                if (q.kind() != PartKind.ELECTROLYTE) continue;
                Vec3 qc = q.center();
                // at the electrode surface, inside the liquid
                Vec3 at = pc.add(qc.sub(pc).mul(0.55));
                double spread = 0.25 / isl.resolution;
                level.sendParticles(ParticleTypes.BUBBLE_POP, at.x() * toWorld, at.y() * toWorld, at.z() * toWorld,
                        n, spread, spread, spread, 0.02);
            }
        }
    }

    /** Air leaving an open pipe end shows as a plume whose speed follows the exit velocity. */
    private void ventPlumes(Island isl) {
        if (tickCount % 2 != 0) return;
        CompiledAssembly c = isl.compiled;
        double toWorld = 1.0 / (Part.CELL * isl.resolution);
        for (CompiledAssembly.Vent v : c.vents()) {
            double q = c.ventOutflow(v);
            double[] f = c.fluid(v.partId());
            if (f == null || Math.abs(q) < 1e-4) continue;
            Part p = c.assembly().get(v.partId());
            double area = PartGeometry.passageArea(p);
            double rho = Math.max(f[2] / Math.max(PartGeometry.fluidVolume(p), 1e-12), 1e-3);
            double speed = q / (rho * area); // m/s, + outwards
            int[] cell = v.cell();
            Dir d = v.face();
            double cx = (cell[0] + 0.5 + 0.5 * d.dx) * Part.CELL * toWorld, cy = (cell[1] + 0.5 + 0.5 * d.dy) * Part.CELL * toWorld,
                    cz = (cell[2] + 0.5 + 0.5 * d.dz) * Part.CELL * toWorld;
            if (speed < 0) continue; // intake: nothing visible
            // particle speed in blocks/tick, compressed logarithmically so jets stay on screen
            double vis = Math.min(0.6, 0.05 * Math.log1p(speed)) * 16.0 / isl.resolution;
            int n = (int) Math.min(4, 1 + Math.log10(1 + q * 1000));
            var type = f[1] > 373 ? ParticleTypes.CLOUD : ParticleTypes.POOF;
            for (int i = 0; i < n; i++) {
                level.sendParticles(type, cx, cy, cz, 0, d.dx * vis, d.dy * vis, d.dz * vis, 1.0);
            }
        }
    }

    private void saveIsland(Island isl) {
        saveIsland(isl, true);
    }

    private void saveIsland(Island isl, boolean markChanged) {
        if (isl.compiled == null) return;
        AssemblyState s = isl.compiled.exportState();
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            be.savedState().clear();
            be.savedChemistry().clear();
            for (var ce : s.chemistry().entrySet()) {
                String k = ce.getKey();
                long owner = Long.parseLong(k.substring(1, k.indexOf(':')));
                if (be.byId(owner) != null) be.savedChemistry().put(k, ce.getValue());
            }
            for (PlacedPart p : be.parts()) {
                double[] m = s.bodyMotion().getOrDefault(p.id, new double[]{0, 0});
                double[] fl = s.fluids().getOrDefault(p.id, new double[]{0, 0});
                be.savedState().put(p.id, new double[]{s.temperatures().getOrDefault(p.id, isl.ambient), m[0], m[1],
                        s.coilCurrents().getOrDefault(p.id, 0.0), s.capacitorVoltages().getOrDefault(p.id, 0.0), fl[0], fl[1],
                        s.damage().getOrDefault(p.id, 0.0)});
            }
            if (markChanged) be.setChanged();
        }
    }

    public void saveAll() {
        for (Island isl : new HashSet<>(islandOf.values())) saveIsland(isl);
    }

    private void sync(Island isl) {
        CompiledAssembly c = isl.compiled;
        List<IslandSyncPayload.BodyState> bodies = new ArrayList<>();
        for (CompiledAssembly.Body b : c.bodies()) {
            if (b.isStatic()) continue;
            Vec3 pv = b.pivot(), ax = b.axis();
            long[] ids = b.parts().stream().mapToLong(Long::longValue).toArray();
            bodies.add(new IslandSyncPayload.BodyState(pv.x(), pv.y(), pv.z(), ax.x(), ax.y(), ax.z(),
                    c.angle(b), c.angularVelocity(b), ids, b.slider()));
            if (dev.emergence.mod.debug.PoseLog.ENABLED && ids.length > 0) {
                dev.emergence.mod.debug.PoseLog.server(level.getGameTime() + "," + ids[0] + "," + b.slider() + "," + c.angle(b) + ","
                        + c.angularVelocity(b) + "," + isl.timeScale + "," + scaleOf(isl) + "," + isl.costMs + "," + isl.near);
            }
        }
        List<Long> hotIds = new ArrayList<>();
        List<Float> hotT = new ArrayList<>();
        for (long id : isl.partPos.keySet()) {
            double t = c.temperature(id);
            if (Math.abs(t - isl.ambient) > 2) {
                hotIds.add(id);
                hotT.add((float) t);
            }
        }
        long[] hid = hotIds.stream().mapToLong(Long::longValue).toArray();
        float[] ht = new float[hotT.size()];
        for (int i = 0; i < ht.length; i++) ht[i] = hotT.get(i);
        List<Long> tintIds = new ArrayList<>();
        List<Integer> tints = new ArrayList<>();
        for (long id : isl.partPos.keySet()) {
            int col = c.chemicalColour(id);
            if (col >= 0) {
                tintIds.add(id);
                tints.add(col);
            }
        }
        long[] all = isl.partPos.keySet().stream().mapToLong(Long::longValue).toArray();
        // world blocks per physical metre: 1 for miniature (true scale), 16 for block-scale models
        float scale = 16f / isl.resolution;
        // gauge pressures of fluid parts (for pressure gauges on vessels)
        List<Long> fIds = new ArrayList<>();
        List<Float> gauges = new ArrayList<>();
        double pAmb = c.assembly().ambientPressure();
        for (long id : isl.partPos.keySet()) {
            double[] f = c.fluid(id);
            if (f != null && Math.abs(f[0] - pAmb) > 50) {
                fIds.add(id);
                gauges.add((float) (f[0] - pAmb));
            }
            // the same per-part scalar channel carries an LED's optical output [W]
            double light = c.lightOutput(id);
            if (light > 1e-6) {
                fIds.add(id);
                gauges.add((float) light);
            }
        }
        float[] ga = new float[gauges.size()];
        for (int i = 0; i < ga.length; i++) ga[i] = gauges.get(i);
        // piston state of cylinders (8 floats each, see CompiledAssembly.piston)
        List<Long> pIds = new ArrayList<>();
        List<Float> pData = new ArrayList<>();
        for (long id : isl.partPos.keySet()) {
            double[] ps = c.piston(id);
            if (ps == null) continue;
            pIds.add(id);
            for (double v : ps) pData.add((float) v);
        }
        float[] pa = new float[pData.size()];
        for (int i = 0; i < pa.length; i++) pa[i] = pData.get(i);
        // springs: how far each end face has moved along the spring, in world blocks
        List<Long> sIds = new ArrayList<>();
        List<Float> sEnds = new ArrayList<>();
        for (long id : isl.partPos.keySet()) {
            double[] se = c.springEnds(id);
            if (se == null) continue;
            sIds.add(id);
            sEnds.add((float) (se[0] * scale));
            sEnds.add((float) (se[1] * scale));
        }
        float[] sa = new float[sEnds.size()];
        for (int i = 0; i < sa.length; i++) sa[i] = sEnds.get(i);
        // LED beams turned by mirrors and lenses: per LED its rays, each a polyline relative to the LED's block
        // (packed: ray count, then per ray its point count, its wavelength [nm] (0: the LED's own) and x, y, z in world
        // blocks)
        List<Long> bIds = new ArrayList<>();
        List<Float> bData = new ArrayList<>();
        for (long id : isl.partPos.keySet()) {
            var rays = c.beams(id);
            if (rays == null) continue;
            double[] nms = c.beamWavelengths(id);
            BlockPos at = isl.partPos.get(id);
            bIds.add(id);
            bData.add((float) rays.size());
            for (int r = 0; r < rays.size(); r++) {
                var ray = rays.get(r);
                bData.add((float) ray.size());
                bData.add(nms == null ? 0f : (float) nms[r]);
                for (var pt : ray) {
                    bData.add((float) (pt.x() * scale - at.getX()));
                    bData.add((float) (pt.y() * scale - at.getY()));
                    bData.add((float) (pt.z() * scale - at.getZ()));
                }
            }
        }
        float[] ba = new float[bData.size()];
        for (int i = 0; i < ba.length; i++) ba[i] = bData.get(i);
        IslandSyncPayload payload = new IslandSyncPayload(all, bodies, hid, ht, level.getGameTime(), scale,
                tintIds.stream().mapToLong(Long::longValue).toArray(), tints.stream().mapToInt(Integer::intValue).toArray(),
                fIds.stream().mapToLong(Long::longValue).toArray(), ga, pIds.stream().mapToLong(Long::longValue).toArray(), pa,
                sIds.stream().mapToLong(Long::longValue).toArray(), sa, (float) (isl.timeScale * scaleOf(isl)),
                bIds.stream().mapToLong(Long::longValue).toArray(), ba);
        BlockPos any = isl.blocks.iterator().next();
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(any), payload);
    }

    public static boolean isCrank(PlacedPart p) {
        return p.kind == PartKind.CRANK;
    }
}
