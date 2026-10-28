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
        /** The tick its state was last written into its blocks while they unloaded (once a tick is enough). */
        long unloadSavedTick = -1;
        final Set<BlockPos> blocks = new HashSet<>();
        final Map<Long, BlockPos> partPos = new HashMap<>();
        CompiledAssembly compiled;
        final Map<Long, Integer> crankTicks = new HashMap<>();
        final Map<Long, Integer> turnTicks = new HashMap<>();
        final Map<Long, Integer> pushTicks = new HashMap<>();
        final Map<BlockPos, Integer> light = new HashMap<>();
        /** The world blocks beside it that were solid when it was compiled (what it may be anchored to, or capped by). */
        final Set<BlockPos> solidAround = new HashSet<>();
        double ambient;
        /** Height of the island's middle above the ground under it [m, world blocks]; −1 until found. */
        double windHeight = -1;
        /** The share of the wind reaching it past solid blocks upwind (1: open), and the direction it was found for. */
        double shelter = 1, shelterDir = Double.NaN;
        int resolution = AssemblyBlockEntity.MINIATURE;
        /** Holds blocks of both scales, joined by ribbon cables ({@link #resolution} is then its first block's). */
        boolean mixed;
        /**
         * In a joined island, how far its full-size parts are moved in the assembly's grid [cells]: from their own grid
         * (a cell a block) to just beside the miniature cells (sixteen a block), so the assembly spans metres, not
         * the gap between the two grids' coordinates (sunlight tracing and the like are sized by its extent). The two
         * never touch: physics does not see the move; positions going back to the world take it off.
         */
        int[] shift;
        /** Simulated seconds per real second: below 1 when the island cannot keep up within its budget. */
        double timeScale = 1;
        /** Smoothed wall-clock cost of this island's tick [ms]. */
        double costMs;
        /** Whether its bodies were sounding at the last sound update. */
        boolean sounding;
        /** The state the island was last compiled from (for diagnosing a failure right after a restart). */
        AssemblyState startState;
        /**
         * Whether a player was near it this tick, or right beside it (watching it); its bounding box (lazily), and that
         * grown by the near distance.
         */
        boolean near, watched, resetLogged;
        /** Its blocks reach into a chunk not loaded: perhaps not all of the machine is here, so it is not run. */
        boolean incomplete;
        /** Rebuilt as parts arrived: it waits until this tick for the rest of it (see {@link #arrived}). */
        long settleUntil;

        net.minecraft.world.phys.AABB bounds, box;
        /** For the pace log: simulated and wall time when last logged. */
        double paceSim = Double.NaN;
        long paceWall;
        /** Its compiling, while a worker does it (not stepped meanwhile: {@link #compiled} is null). */
        java.util.concurrent.Future<CompiledAssembly> pending;
        long pendingSince;
        /** This tick's step (possibly on a worker thread): its length, its cost, and what came of it. */
        double stepDt;
        long stepNanos;
        List<Long> stepFailed;
        RuntimeException stepError;
        /**
         * Off the tick: its step runs on a thread of its own across the server's tick, what came of it taken up at a
         * later tick (see {@link #OFF_TICK}); the step while it runs, the tick it set off, and the ticks it covers.
         */
        boolean offTick;
        /** Each display panel's image version and tick last sent to clients. */
        final Map<Long, long[]> displaySent = new HashMap<>();
        /** Full-size screens its miniature display controllers drive across a block face (see {@link #syncDisplayLinks}). */
        List<DisplayLink> displayLinks;
        CompiledAssembly displayLinksFor;
        long displayLinksAt;
        /** Its truncation-error tolerances' relaxation (1: as validated; up to {@link #MAX_RELAX}): see {@link #land}. */
        double relax = 1;
        java.util.concurrent.Future<?> step;
        long launchTick = Long.MIN_VALUE;
        int stepTicks = 1;

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
        arrived = true;
    }

    /**
     * Parts arrived (a chunk loaded, or a part was placed) since the last rebuild. A chunk counts as loaded a tick
     * before its block entities register, so a machine straddling two chunks can be rebuilt with one half; the
     * machines rebuilt then wait {@link #SETTLE_TICKS} before they run, for the rest of them to arrive.
     */
    private boolean arrived;
    private static final int SETTLE_TICKS = 10;

    public void unregister(BlockPos pos) {
        Island isl = islandOf.get(pos);
        // write the latest state into the block entities but do not mark them changed: this runs while chunks
        // unload, and marking a block entity in a chunk that is itself unloading makes the level request that
        // chunk again, which reschedules its unload — forever, at server shutdown. The periodic save has already
        // marked the chunks unsaved, so what is written here goes out with them.
        // (once a tick: its blocks unload one by one, and at shutdown, with no ticks, all of them — writing the whole state
        // for each took the 1100-block station minutes to quit)
        if (isl != null && isl.unloadSavedTick != tickCount) {
            saveIsland(isl, false);
            isl.unloadSavedTick = tickCount;
        }
        blocks.remove(pos);
        markDirty(pos);
    }

    /**
     * Whether two neighbouring blocks of different scales are one machine: a full-size ribbon against the miniature
     * block, or anything of either touching across their shared face (heat, a shaft, a pipe: see {@link #crossLinks}).
     */
    private boolean joinedAcross(BlockPos a, BlockPos b) {
        AssemblyBlockEntity ba = blocks.get(a), bb = blocks.get(b);
        boolean aFull = ba.resolution() == AssemblyBlockEntity.BLOCK_SCALE;
        AssemblyBlockEntity full = aFull ? ba : bb, mini = aFull ? bb : ba;
        if (full.resolution() != AssemblyBlockEntity.BLOCK_SCALE || mini.resolution() != AssemblyBlockEntity.MINIATURE) return false;
        if (full.parts().stream().anyMatch(p -> p.kind == PartKind.RIBBON)) return true;
        BlockPos fp = aFull ? a : b, mp = aFull ? b : a;
        Direction d = Direction.getNearest(mp.getX() - fp.getX(), mp.getY() - fp.getY(), mp.getZ() - fp.getZ());
        return !full.parts().isEmpty() && mini.parts().stream().anyMatch(p -> onFace(p, d.getOpposite(), mini.resolution()));
    }

    /** Whether a miniature part has a cell on its block's face in direction {@code f}. */
    private static boolean onFace(PlacedPart p, Direction f, int res) {
        int last = res - 1;
        return switch (f) {
            case EAST -> p.x1 == last;
            case WEST -> p.x0 == 0;
            case UP -> p.y1 == last;
            case DOWN -> p.y0 == 0;
            case SOUTH -> p.z1 == last;
            case NORTH -> p.z0 == 0;
        };
    }

    /** The links across the face between a full-size block and the miniature block next to it (core CrossScale's rules). */
    private void crossLinks(Island isl, BlockPos fp, AssemblyBlockEntity full, Direction d, BlockPos mp, AssemblyBlockEntity mini, Assembly asm) {
        int r = mini.resolution();
        int[] sh = isl.shift == null ? new int[3] : isl.shift;
        int[] origin = {mp.getX() * r, mp.getY() * r, mp.getZ() * r};
        List<Part> miniParts = new ArrayList<>();
        for (PlacedPart p : mini.parts()) miniParts.add(p.toCore(mp, r));
        for (PlacedPart q : full.parts()) {
            Part fq = q.toCore(fp, AssemblyBlockEntity.BLOCK_SCALE, sh);
            dev.emergence.core.assembly.CrossScale.link(asm, fq, new int[]{fq.minX(), fq.minY(), fq.minZ()}, Dir.values()[d.ordinal()], miniParts, origin, r);
        }
    }

    /**
     * The joins across scales: each full-size ribbon against a miniature block of the island to the miniature
     * insulated wires touching that face from inside — each to the conductor of its colour (Assembly#bridge).
     */
    private void bridgeRibbons(Island isl, Assembly asm) {
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null || be.resolution() != AssemblyBlockEntity.BLOCK_SCALE) continue;
            for (PlacedPart r : be.parts()) {
                if (r.kind != PartKind.RIBBON) continue;
                for (Direction d : Direction.values()) {
                    BlockPos n = b.relative(d);
                    AssemblyBlockEntity m = isl.blocks.contains(n) ? be(n) : null;
                    if (m == null || m.resolution() != AssemblyBlockEntity.MINIATURE) continue;
                    // the miniature block's face towards the ribbon: its cells on the side −d
                    int last = m.resolution() - 1;
                    for (PlacedPart w : m.parts()) {
                        if (w.kind != PartKind.INSULATED_WIRE) continue;
                        boolean face = switch (d) {
                            case EAST -> w.x0 == 0;
                            case WEST -> w.x1 == last;
                            case UP -> w.y0 == 0;
                            case DOWN -> w.y1 == last;
                            case SOUTH -> w.z0 == 0;
                            case NORTH -> w.z1 == last;
                        };
                        if (face) asm.bridge(r.id, w.id);
                    }
                }
            }
        }
        // and the other way round: a miniature ribbon on its block's face against a full-size insulated wire (whose end
        // covers the whole face) — the wire to the ribbon's conductor of its colour
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity m = be(b);
            if (m == null || m.resolution() != AssemblyBlockEntity.MINIATURE) continue;
            int last = m.resolution() - 1;
            for (PlacedPart r : m.parts()) {
                if (r.kind != PartKind.RIBBON) continue;
                for (Direction d : Direction.values()) {
                    boolean face = switch (d) {
                        case EAST -> r.x1 == last;
                        case WEST -> r.x0 == 0;
                        case UP -> r.y1 == last;
                        case DOWN -> r.y0 == 0;
                        case SOUTH -> r.z1 == last;
                        case NORTH -> r.z0 == 0;
                    };
                    if (!face) continue;
                    BlockPos n = b.relative(d);
                    AssemblyBlockEntity full = isl.blocks.contains(n) ? be(n) : null;
                    if (full == null || full.resolution() != AssemblyBlockEntity.BLOCK_SCALE) continue;
                    for (PlacedPart w : full.parts()) if (w.kind == PartKind.INSULATED_WIRE) asm.bridge(r.id, w.id);
                }
            }
        }
    }

    /**
     * Cells of an island's assembly grid as world blocks. One resolution, or (a joined island) both: a cell is a
     * full-size one when, shift taken off, it falls on or within two blocks of the island's full-size blocks (the
     * shift puts them well clear of the miniature cells), else a miniature one.
     */
    record CellMap(int res, int[] shift, Set<BlockPos> full) {
        int res(int x, int y, int z) {
            if (shift == null) return res;
            BlockPos b = new BlockPos(x - shift[0], y - shift[1], z - shift[2]);
            for (BlockPos f : full) if (Math.abs(f.getX() - b.getX()) <= 2 && Math.abs(f.getY() - b.getY()) <= 2 && Math.abs(f.getZ() - b.getZ()) <= 2) {
                return AssemblyBlockEntity.BLOCK_SCALE;
            }
            return AssemblyBlockEntity.MINIATURE;
        }

        BlockPos block(int x, int y, int z) {
            int r = res(x, y, z);
            if (shift != null && r == AssemblyBlockEntity.BLOCK_SCALE) return new BlockPos(x - shift[0], y - shift[1], z - shift[2]);
            return new BlockPos(Math.floorDiv(x, r), Math.floorDiv(y, r), Math.floorDiv(z, r));
        }
    }

    /** A joined island's shift for its full-size parts: their box put 32 cells past the miniature cells' box in x. */
    private int[] shiftFor(Island isl) {
        int[] mini = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        int[] full = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            int r = be.resolution();
            if (r == AssemblyBlockEntity.MINIATURE) {
                mini[0] = Math.min(mini[0], b.getX() * r);
                mini[1] = Math.min(mini[1], b.getY() * r);
                mini[2] = Math.min(mini[2], b.getZ() * r);
                mini[3] = Math.max(mini[3], b.getX() * r + r - 1);
            } else {
                full[0] = Math.min(full[0], b.getX());
                full[1] = Math.min(full[1], b.getY());
                full[2] = Math.min(full[2], b.getZ());
            }
        }
        if (mini[0] == Integer.MAX_VALUE || full[0] == Integer.MAX_VALUE) return null;
        return new int[]{mini[3] + 32 - full[0], mini[1] - full[1], mini[2] - full[2]};
    }

    /** A part's shift in the assembly's grid [cells] (a joined island's full-size parts), or null. */
    private int[] shiftOf(Island isl, long partId) {
        return isl.shift != null && resOf(isl, partId) == AssemblyBlockEntity.BLOCK_SCALE ? isl.shift : null;
    }

    /** The grid resolution of the block a part of the island is in (a joined island holds both). */
    private int resOf(Island isl, long partId) {
        BlockPos b = isl.partPos.get(partId);
        AssemblyBlockEntity be = b == null ? null : be(b);
        return be == null ? isl.resolution : be.resolution();
    }

    private AssemblyBlockEntity be(BlockPos pos) {
        AssemblyBlockEntity be = blocks.get(pos);
        return be != null && !be.isRemoved() ? be : null;
    }

    public void markDirty(BlockPos pos) {
        dirty.add(pos.immutable());
    }

    /**
     * A world block beside the assembly block at {@code at} changed: if it became solid or stopped being so, the machine
     * is rebuilt — knock away the block it stood on and it is free; set one against a rotor and it is held. (Other
     * changes — a furnace lit, a fire burning on — leave it be.)
     */
    public void neighbourChanged(BlockPos at, BlockPos n, Direction towards) {
        Island isl = islandOf.get(at);
        if (isl == null || isl.blocks.contains(n) || dirty.contains(at)) return;
        if (level.getBlockState(n).getBlock() instanceof AssemblyBlock) return; // registered with the simulation itself
        if (worldSolid(n, towards) != isl.solidAround.contains(n)) markDirty(at);
    }

    /** A world block that can hold a part (anchor it, cap a pipe's end): solid, or sturdy on the face {@code face}. */
    private boolean worldSolid(BlockPos n, Direction face) {
        BlockState s = level.getBlockState(n);
        return !s.isAir() && !(s.getBlock() instanceof AssemblyBlock) && (s.isSolid() || s.isFaceSturdy(level, n, face));
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Held: islands are built and compiled but not stepped (the demo builder's check force-loads the whole gallery for a
     * moment; machines whose chunks arrived one by one would otherwise run on half of themselves meanwhile).
     */
    private boolean paused;

    /** How many times an island has failed (and been restarted) in this level since the server started. */
    public int failures;

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void tick() {
        tickCount++;
        if (!dirty.isEmpty()) {
            // (a machine being rebuilt finishes the step it is taking first)
            for (Island isl : new HashSet<>(islandOf.values())) settle(isl);
            long r0 = System.nanoTime();
            int n = dirty.size();
            rebuild();
            long ms = (System.nanoTime() - r0) / 1_000_000;
            if (ms > SLOW_MS) EmergenceMod.LOG.warn("Slow simulation: rebuilding the islands around {} changed blocks took {} ms", n, ms);
        }
        installCompiled();
        if (paused) return;
        Set<Island> islands = new HashSet<>(islandOf.values());
        double watchedTotal = 0, nearTotal = 0, farTotal = 0;
        Set<Island> nearest = nearestToPlayers(islands);
        // steps taken off the tick that have finished: what came of them, applied to the world (on this thread)
        for (Island isl : islands) {
            if (isl.step == null || !isl.step.isDone()) continue;
            isl.step = null;
            if (isl.compiled == null) continue;
            land(isl, 1, true);
        }
        // the islands to step this tick, their inputs set from the world (on this thread)
        List<Island> run = new ArrayList<>(), off = new ArrayList<>();
        for (Island isl : islands) {
            if (isl.compiled == null) continue;
            // still stepping off the tick
            if (isl.step != null) continue;
            // like block entities, machines advance only within the simulation distance of a player; further
            // away their state waits, intact. A machine reaching into simulation distance runs as a whole (judging
            // it by an arbitrary one of its blocks made one straddling a chunk border run or freeze by chance)
            if (!anyTicking(isl)) continue;
            // part of the machine may lie in a chunk not loaded yet (it straddles a border at the edge of the loaded
            // world): run on what is here, an engine braked its crankshaft to a stop before its cylinder's chunk came.
            // It waits, as it is, until all of it can be here (its blocks arriving rebuild it; a chunk arriving empty is
            // noticed within a second)
            if (isl.incomplete && (tickCount % 20 != 0 || reachesUnloaded(isl))) {
                if (LOG_PACE && tickCount % 200 == 0) EmergenceMod.LOG.info("Held: island at {} ({} blocks) reaches into a chunk not loaded", isl.blocks.iterator().next(), isl.blocks.size());
                continue;
            }
            if (tickCount < isl.settleUntil) continue;
            isl.incomplete = false;
            updateCranks(isl);
            updateWind(isl);
            isl.near = nearPlayer(isl);
            isl.watched = isl.near && (nearest.contains(isl) || watchedBy(isl));
            // a costly machine steps off the tick (from above OFF_TICK_MS a tick, back below half of it)
            isl.offTick = OFF_TICK && (isl.offTick ? isl.costMs > 0.5 * OFF_TICK_MS : isl.costMs > OFF_TICK_MS);
            if (isl.offTick) {
                // across the ticks since it last set off (a step that ran over a tick: the next one covers both), at its
                // own pace — it weighs on no tick, so on no common budget
                isl.stepTicks = (int) Math.max(1, Math.min(4, tickCount - isl.launchTick));
                isl.stepDt = TICK_SECONDS * isl.stepTicks * isl.timeScale;
                off.add(isl);
            } else {
                isl.stepTicks = 1;
                isl.stepDt = TICK_SECONDS * isl.timeScale * scaleOf(isl);
                run.add(isl);
            }
            isl.launchTick = tickCount;
        }
        for (Island isl : off) {
            // (its wall-clock cap the ticks it covers, not one tick's: capped at 60 ms, a step of two ticks stopped short
            // and the machine ran in slow motion by the cap, not by its pace)
            if (STEP_WALL_MS > 0) isl.compiled.wallBudget(Math.max(STEP_WALL_MS, 1.2 * isl.stepTicks * TICK_SECONDS * 1e3));
            isl.compiled.relaxAccuracy(isl.relax); // (a machine compiled anew starts at full accuracy)
            isl.step = OFF_TICK_POOL.submit(() -> stepIsolated(isl));
        }
        for (Island isl : run) {
            if (STEP_WALL_MS > 0) isl.compiled.wallBudget(STEP_WALL_MS);
            if (isl.relax != 1) {
                isl.relax = 1;
                isl.compiled.relaxAccuracy(1);
            }
        }
        // the steps: each island alone with its own compiled machine (nothing in the core is shared between them), so
        // on several cores at once — the machines around a player each get their budget, not a share of one
        long wall0 = System.nanoTime();
        if (STEP_POOL == null || run.size() < 2) {
            for (Island isl : run) stepIsolated(isl);
        } else {
            List<java.util.concurrent.Callable<Void>> jobs = new ArrayList<>(run.size());
            for (Island isl : run) jobs.add(() -> {
                stepIsolated(isl);
                return null;
            });
            try {
                for (var f : STEP_POOL.invokeAll(jobs)) {
                    try {
                        f.get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (e.getCause() instanceof Error err) throw err;
                        throw new IllegalStateException(e.getCause());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long wall = System.nanoTime() - wall0, cpu = 0;
        for (Island isl : run) cpu += isl.stepNanos;
        // what the budgets weigh is the server's time: the steps' share of the wall clock they took together
        double share = cpu > 0 ? Math.min(1, (double) wall / cpu) : 1;
        // the results, applied to the world (on this thread)
        for (Island isl : run) {
            double ms = land(isl, share, false);
            if (isl.watched) watchedTotal += ms;
            else if (isl.near) nearTotal += ms;
            else farTotal += ms;
        }
        tickLoggers();
        tickAnalyzers();
        // all islands together within the server's budget, by priority: the machine a player stands at first, then
        // those around them, then the rest — if they cost more, the further ones slow (down to nearly paused) before
        // the one being watched does
        watchedScale = adjust(watchedScale, watchedTotal, GLOBAL_BUDGET_MS, 0.05);
        double left = Math.max(GLOBAL_BUDGET_MS - watchedTotal, 0.1 * GLOBAL_BUDGET_MS);
        nearScale = adjust(nearScale, nearTotal, left, 0.02);
        farScale = adjust(farScale, farTotal, Math.max(left - nearTotal, 0.1 * GLOBAL_BUDGET_MS), 0.01);
    }

    /**
     * What came of an island's step, applied to the world (on the server thread): a failure restarted, parts destroyed,
     * light, plumes, the clients' view. Returns the step's cost to the server's tick [ms] (0 for one taken off it).
     */
    private double land(Island isl, double share, boolean offTick) {
        {
            RuntimeException err = isl.stepError;
            isl.stepError = null;
            if (err != null) {
                failures++;
                // restart from the last good state at rest (charges, heat, positions kept); if even that will not
                // run, from nothing
                EmergenceMod.LOG.error("Simulation of island at {} ({} parts) failed; restarting it at rest{}; {}", isl.blocks.iterator().next(),
                        isl.compiled.assembly().parts().size(), partHint(isl, err.getMessage()), stateSummary(isl), err);
                // (the state it was compiled from, for a failure on loading; and the last good one, to replay a failure
                // after minutes from where it happened)
                if (Boolean.getBoolean("emergence.dumpFailures")) {
                    dumpFailure(isl, isl.startState, "island-failure-");
                    dumpFailure(isl, isl.compiled.exportState(), "island-failure-last-");
                }
                AssemblyState keep = isl.compiled.exportState().recovery();
                isl.compiled = compile(isl, keep);
                try {
                    if (isl.compiled != null) isl.compiled.step(1e-4);
                } catch (RuntimeException again) {
                    EmergenceMod.LOG.error("Restart failed too; resetting the island's state", again);
                    isl.compiled = compile(isl, AssemblyState.empty());
                }
                return 0;
            }
            List<Long> failed = isl.stepFailed;
            isl.stepFailed = null;
            if ((isl.compiled.coilCurrentsReset() || isl.compiled.motionReset()) && !isl.resetLogged) {
                isl.resetLogged = true;
                EmergenceMod.LOG.info("Island at {}: restored {} inconsistent with the build as loaded; started from rest",
                        isl.blocks.iterator().next(), isl.compiled.motionReset() ? "motion was" : "coil currents were");
            }
            long cost = isl.stepNanos;
            if (cost / 1_000_000 > SLOW_MS * isl.stepTicks) EmergenceMod.LOG.warn("Slow simulation: a step of the island at {} ({} parts) took {} ms",
                    isl.blocks.iterator().next(), isl.compiled.assembly().parts().size(), cost / 1_000_000);
            Profiler.record(cost, isl.compiled.network().size());
            // (paced by its cost per tick: off the tick, against most of a tick's wall time on a thread of its own)
            if (offTick) {
                // falling behind, it first relaxes its accuracy a little (up to MAX_RELAX), and only then slows down;
                // with time to spare, it is back at full speed before it tightens again
                double perTick = cost / 1e6 / isl.stepTicks;
                double smoothed = isl.costMs == 0 ? perTick : 0.8 * isl.costMs + 0.2 * perTick;
                if (smoothed > OFF_TICK_BUDGET_MS && isl.relax < MAX_RELAX) {
                    isl.costMs = smoothed;
                    isl.relax = Math.min(MAX_RELAX, isl.relax * 1.1);
                    isl.compiled.relaxAccuracy(isl.relax);
                } else {
                    pace(isl, perTick, OFF_TICK_BUDGET_MS, 0.85);
                    if (isl.timeScale >= 1 && isl.costMs < 0.6 * OFF_TICK_BUDGET_MS && isl.relax > 1) {
                        isl.relax = Math.max(1, isl.relax / 1.1);
                        isl.compiled.relaxAccuracy(isl.relax);
                    }
                }
            }
            else pace(isl, cost / 1e6, isl.watched ? Math.max(ISLAND_BUDGET_MS, GLOBAL_BUDGET_MS) : ISLAND_BUDGET_MS);
            if (LOG_PACE && isl.watched && tickCount % 200 == 0) logPace(isl);
            if (failed != null && !failed.isEmpty()) destroyParts(isl, failed);
            updateLight(isl);
            bubbles(isl);
            ventPlumes(isl);
            if (tickCount % 20 == 0) daylight(isl);
            if (tickCount % 100 == 0 && Boolean.getBoolean("emergence.debugIslands") && (isl.compiled.sunlightAbsorbed() > 100 || isl.costMs > 1)) {
                long hot = isl.partPos.keySet().stream().max(java.util.Comparator.comparingDouble(isl.compiled::temperature)).orElse(-1L);
                double fastest = 0;
                for (var body : isl.compiled.bodies()) if (!body.isStatic()) fastest = Math.max(fastest, Math.abs(isl.compiled.angularVelocity(body)));
                EmergenceMod.LOG.info("Island at {}: fastest body {} rad/s, {} blocks", isl.blocks.iterator().next(), String.format("%.2f", fastest), isl.blocks.size());
                EmergenceMod.LOG.info("Island at {}: t = {} s, time scale {}, {} ms/step, hottest {} at {} K", isl.blocks.iterator().next(),
                        Math.round(isl.compiled.time()), String.format("%.3f", isl.timeScale * scaleOf(isl)), String.format("%.1f", isl.costMs),
                        hot < 0 ? "-" : isl.compiled.assembly().get(hot).kind() + "@" + isl.partPos.get(hot).toShortString(),
                        hot < 0 ? 0 : Math.round(isl.compiled.temperature(hot)));
            }
            sync(isl);
            if (tickCount % 100 == 0) saveIsland(isl);
            return offTick ? 0 : share * cost / 1e6;
        }
    }

    /**
     * {@code emergence.offTick} (on by default): an island whose step costs more than {@link #OFF_TICK_MS} a tick
     * steps on a thread of its own across the server's tick, what came of it taken up at the next tick it has
     * finished by — it holds up no tick, and may take most of one (paced against {@link #OFF_TICK_BUDGET_MS}), so a
     * large machine runs at real time where its step costs up to that. The world sees its results a tick later.
     */
    static final boolean OFF_TICK = !"false".equals(System.getProperty("emergence.offTick"))
            && Double.parseDouble(System.getProperty("emergence.globalBudgetMs", "35")) < 1e6; // (not where the budgets are lifted)
    static final double OFF_TICK_MS = Double.parseDouble(System.getProperty("emergence.offTickMs", "8"));
    /**
     * How far an island off the tick may relax its truncation-error tolerances to keep real time before it slows
     * ({@code emergence.maxRelax}; 1 for never): ×3 left the power station's engine speed, pressures, temperatures,
     * water and fuel as they were to three digits.
     */
    static final double MAX_RELAX = Double.parseDouble(System.getProperty("emergence.maxRelax", "3"));
    /**
     * An island off the tick may take a whole tick's wall time per tick of simulation: the server never waits for it,
     * and a step that runs over makes the next one cover the ticks it missed, so it keeps real time while its step
     * costs less than the time it simulates. (At 45 ms the power station, costing ~48, sat at 0.8 of real time.)
     */
    static final double OFF_TICK_BUDGET_MS = Double.parseDouble(System.getProperty("emergence.offTickBudgetMs", "50"));
    private static final java.util.concurrent.ExecutorService OFF_TICK_POOL = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)), r -> {
                Thread t = new Thread(r, "Emergence off-tick simulation");
                t.setDaemon(true);
                return t;
            });

    /** Waits for an island's step off the tick, if one is running (before anything else touches its machine). */
    private static void settle(Island isl) {
        var f = isl.step;
        if (f == null) return;
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        }
    }

    /** One island's step, touching nothing but its own compiled machine: its outcome and cost kept on it. */
    private static void stepIsolated(Island isl) {
        long t0 = System.nanoTime();
        try {
            isl.stepFailed = isl.compiled.step(isl.stepDt);
        } catch (RuntimeException e) {
            isl.stepError = e;
        }
        isl.stepNanos = System.nanoTime() - t0;
    }

    /**
     * Worker threads stepping islands side by side: one fewer than the processors (the server thread waits on them),
     * at most 8; {@code emergence.simThreads} overrides, and 1 steps them one after another on the server thread.
     */
    private static final int SIM_THREADS = Integer.getInteger("emergence.simThreads",
            Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)));
    private static final java.util.concurrent.ExecutorService STEP_POOL = SIM_THREADS < 2 ? null
            : java.util.concurrent.Executors.newFixedThreadPool(SIM_THREADS, r -> {
                Thread t = new Thread(r, "Emergence simulation");
                t.setDaemon(true);
                return t;
            });

    /** Logs how fast a watched island runs: simulated seconds per real second since the last log (and why, if slow). */
    private void logPace(Island isl) {
        double sim = isl.compiled.time();
        long wall = System.nanoTime();
        if (!Double.isNaN(isl.paceSim) && wall > isl.paceWall) {
            double pace = (sim - isl.paceSim) / ((wall - isl.paceWall) * 1e-9);
            EmergenceMod.LOG.info("Pace: island at {} ({} parts) ran {} s simulated per real second (its own scale {}, accuracy relaxed ×{}, the watched {}, near {}; {} ms a tick)",
                    isl.blocks.iterator().next(), isl.compiled.assembly().parts().size(), String.format("%.2f", pace),
                    String.format("%.2f", isl.timeScale), String.format("%.2f", isl.relax), String.format("%.2f", watchedScale), String.format("%.2f", nearScale),
                    String.format("%.1f", isl.costMs));
            if (Boolean.getBoolean("emergence.autoShowcase")) {
                isl.compiled.solver().phaseMatrices(); // (keeps the diagnostics' counters in step)
                EmergenceMod.LOG.info("Pace: its solver: {}", isl.compiled.solver().phaseCounts());
            }
        }
        isl.paceSim = sim;
        isl.paceWall = wall;
    }

    /** Logs watched islands' pace (the automated tour, or {@code -Demergence.logPace=true}). */
    private static final boolean LOG_PACE = Boolean.getBoolean("emergence.autoShowcase") || Boolean.getBoolean("emergence.logPace");

    /** Each player's nearest island (by distance to its blocks' bounds): the machine they are at or looking over. */
    private Set<Island> nearestToPlayers(Set<Island> islands) {
        Set<Island> out = new HashSet<>();
        for (var p : level.players()) {
            Island best = null;
            double bestD = Double.MAX_VALUE;
            for (Island isl : islands) {
                if (isl.compiled == null) continue;
                if (isl.bounds == null) nearPlayer(isl);
                var b = isl.bounds;
                double dx = Math.max(0, Math.max(b.minX - p.getX(), p.getX() - b.maxX)), dy = Math.max(0, Math.max(b.minY - p.getY(), p.getY() - b.maxY)),
                        dz = Math.max(0, Math.max(b.minZ - p.getZ(), p.getZ() - b.maxZ)), d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = isl;
                }
            }
            if (best != null) out.add(best);
        }
        return out;
    }

    /** A player within this many blocks of an island's own bounds is taken to be watching it (at it, within reach). */
    public static final double WATCH_BLOCKS = 6;

    /** True if some player is within {@link #WATCH_BLOCKS} of the island's blocks. */
    private boolean watchedBy(Island isl) {
        if (isl.bounds == null) nearPlayer(isl);
        var b = isl.bounds.inflate(WATCH_BLOCKS);
        for (var p : level.players()) if (b.contains(p.position())) return true;
        return false;
    }

    /** Multiplicative controller: slow in proportion when over budget, recover by 25 % a tick when under it. */
    private static double adjust(double scale, double costMs, double budgetMs, double min) {
        double want = costMs > budgetMs ? budgetMs / costMs : 1.25;
        return Math.max(min, Math.min(1, scale * Math.min(want, 1.25)));
    }

    /** Islands within this many blocks of a player get the time budget before those further away. */
    public static final double NEAR_BLOCKS = 48;

    /** The common slow-motion factor for an island (on top of its own), by whether a player is at it or near it. */
    private double scaleOf(Island isl) {
        return isl.watched ? watchedScale : isl.near ? nearScale : farScale;
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
            isl.bounds = new net.minecraft.world.phys.AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
            isl.box = isl.bounds.inflate(NEAR_BLOCKS);
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
    private double watchedScale = 1, nearScale = 1, farScale = 1;

    // ------------------------------------------------------------------ logic analysers

    /** A player's logic analyser: its probes (parts of one machine), its ground clip, its capture. */
    public static final class AnalyzerSession {
        BlockPos pos;
        final long[] probes = {-1, -1, -1, -1, -1, -1, -1, -1};
        final String[] names = {"", "", "", "", "", "", "", ""};
        long ground = -1;
        final dev.emergence.core.instruments.LogicCapture cap = new dev.emergence.core.instruments.LogicCapture(131_072);
        CompiledAssembly attachedTo;
        boolean dirty;
        /** Its decoder ({@link dev.emergence.mod.net.AnalyzerPayloads#DECODE_OFF} …). */
        int decode;
    }

    private final Map<UUID, AnalyzerSession> analyzers = new HashMap<>();

    /** Saves a player's analyser record as CSV (in the server's emergence-logs); a status line. */
    public String exportAnalyzer(ServerPlayer player) {
        AnalyzerSession s = analyzers.get(player.getUUID());
        if (s == null) return "Your logic analyser has no probes yet: clip one onto a part.";
        String csv = dev.emergence.core.instruments.BusDecoder.csv(s.cap, s.names);
        long rows = csv.chars().filter(ch -> ch == '\n').count() - 1;
        if (rows <= 0) return "Your logic analyser has recorded nothing yet.";
        try {
            java.nio.file.Path dir = level.getServer().getServerDirectory().resolve("emergence-logs");
            java.nio.file.Files.createDirectories(dir);
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            java.nio.file.Path file = dir.resolve(stamp + "-analyser.csv");
            java.nio.file.Files.writeString(file, csv);
            return "Saved " + rows + " rows to " + file.toAbsolutePath();
        } catch (java.io.IOException e) {
            return "Could not write the record: " + e.getMessage();
        }
    }

    /**
     * Clips a probe onto a part: the next free channel (all used: the first is moved), or the ground lead. Probes are on
     * one machine: clipping onto another machine's part starts afresh. Returns a status line.
     */
    public String clipAnalyzer(ServerPlayer player, BlockPos pos, long partId, String title, boolean ground) {
        AnalyzerSession s = analyzers.computeIfAbsent(player.getUUID(), k -> new AnalyzerSession());
        Island here = islandOf.get(pos), there = s.pos == null ? null : islandOf.get(s.pos);
        if (here == null) return "That part is not in a machine yet.";
        if (there != here) {
            java.util.Arrays.fill(s.probes, -1);
            java.util.Arrays.fill(s.names, "");
            s.ground = -1;
            s.cap.clear();
            s.pos = pos.immutable();
        }
        s.dirty = true;
        if (ground) {
            s.ground = partId;
            return "Ground lead on " + title;
        }
        int k = 0;
        while (k < s.probes.length && s.probes[k] >= 0 && s.probes[k] != partId) k++;
        if (k == s.probes.length) k = 0;
        s.probes[k] = partId;
        s.names[k] = title;
        s.cap.unuse(k);
        return "Probe " + (k + 1) + " on " + title + " (right-click the air to see the traces)";
    }

    /** A control from the analyser's screen. */
    public void controlAnalyzer(ServerPlayer player, int op, int arg) {
        AnalyzerSession s = analyzers.get(player.getUUID());
        if (s == null) return;
        var cap = s.cap;
        switch (op) {
            case dev.emergence.mod.net.AnalyzerPayloads.RUN -> cap.mode(dev.emergence.core.instruments.LogicCapture.Mode.RUN);
            case dev.emergence.mod.net.AnalyzerPayloads.SINGLE -> {
                cap.clear();
                cap.mode(dev.emergence.core.instruments.LogicCapture.Mode.SINGLE);
            }
            case dev.emergence.mod.net.AnalyzerPayloads.HOLD -> cap.mode(dev.emergence.core.instruments.LogicCapture.Mode.HOLD);
            case dev.emergence.mod.net.AnalyzerPayloads.CLEAR -> cap.clear();
            case dev.emergence.mod.net.AnalyzerPayloads.TRIGGER_CHANNEL -> cap.trigger(Math.floorMod(arg, 8), cap.triggerRising(), 2.5, cap.window());
            case dev.emergence.mod.net.AnalyzerPayloads.TRIGGER_EDGE -> cap.trigger(cap.triggerChannel(), !cap.triggerRising(), 2.5, cap.window());
            case dev.emergence.mod.net.AnalyzerPayloads.WINDOW_US -> cap.trigger(cap.triggerChannel(), cap.triggerRising(), 2.5, Math.max(1, arg) * 1e-6);
            case dev.emergence.mod.net.AnalyzerPayloads.DECODE -> s.decode = Math.floorMod(arg, 3);
            case dev.emergence.mod.net.AnalyzerPayloads.EXPORT -> player.displayClientMessage(net.minecraft.network.chat.Component.literal(exportAnalyzer(player)), false);
            case dev.emergence.mod.net.AnalyzerPayloads.REMOVE_PROBE -> {
                if (arg >= 0 && arg < 8) {
                    s.probes[arg] = -1;
                    s.names[arg] = "";
                    s.dirty = true;
                }
            }
            default -> { }
        }
    }

    /** The traces the analyser's screen asks for. */
    public dev.emergence.mod.net.AnalyzerPayloads.Data viewAnalyzer(ServerPlayer player, double span, double back, int bins) {
        AnalyzerSession s = analyzers.get(player.getUUID());
        String[] names = new String[8];
        boolean[] dig = new boolean[8];
        float[][] mn = new float[8][], mx = new float[8][];
        if (s == null) {
            java.util.Arrays.fill(names, "");
            for (int k = 0; k < 8; k++) {
                mn[k] = new float[0];
                mx[k] = new float[0];
            }
            return new dev.emergence.mod.net.AnalyzerPayloads.Data(names, dig, mn, mx, 0, span, 0, 0, false, false, 0, true, Double.NaN, 1e-3, false,
                    0, "", new int[0], new double[0], new double[0], new String[0]);
        }
        var cap = s.cap;
        double ref = cap.frozen() && !Double.isNaN(cap.triggerTime()) ? cap.triggerTime() + cap.window() : cap.newest();
        double end = ref - back;
        for (int k = 0; k < 8; k++) {
            names[k] = s.names[k];
            dig[k] = cap.digital(k);
            float[][] env = s.probes[k] >= 0 && cap.used(k) ? cap.envelope(k, end - span, end, bins) : new float[][]{new float[0], new float[0]};
            mn[k] = env[0];
            mx[k] = env[1];
        }
        // the decoder's bytes in the window
        List<dev.emergence.core.instruments.BusDecoder.Frame> frames = new ArrayList<>();
        String note = "";
        boolean[] lv = new boolean[8];
        for (int k = 0; k < 8; k++) lv[k] = s.probes[k] >= 0 && cap.used(k) && cap.digital(k);
        if (s.decode == dev.emergence.mod.net.AnalyzerPayloads.DECODE_SPI) {
            if (lv[0] && lv[1]) {
                int cs = lv[2] ? 2 : -1;
                frames.addAll(dev.emergence.core.instruments.BusDecoder.spi(cap, 0, 1, cs, true, end - span, end));
                note = "SPI: CH1 clock (rising), CH2 data" + (cs >= 0 ? ", CH3 select (low)" : ", no select");
            } else {
                note = "SPI: probe the clock on CH1 and the data on CH2 (nets between chips)";
            }
        } else if (s.decode == dev.emergence.mod.net.AnalyzerPayloads.DECODE_UART) {
            StringBuilder b = new StringBuilder("UART 8N1:");
            for (int k = 0; k < 8; k++) {
                if (!lv[k]) continue;
                double baud = dev.emergence.core.instruments.BusDecoder.guessBaud(cap, k);
                if (!(baud > 0)) continue;
                frames.addAll(dev.emergence.core.instruments.BusDecoder.uart(cap, k, baud, end - span, end));
                b.append(String.format(" CH%d %.0f baud", k + 1, baud));
            }
            note = b.length() > 9 ? b.toString() : "UART: no level channel has switched";
        }
        int nf = Math.min(frames.size(), dev.emergence.mod.net.AnalyzerPayloads.Data.MAX_FRAMES);
        int[] fch = new int[nf];
        double[] f0 = new double[nf], f1 = new double[nf];
        String[] ft = new String[nf];
        for (int i = 0; i < nf; i++) {
            var f = frames.get(frames.size() - nf + i);
            fch[i] = f.channel();
            f0[i] = f.start();
            f1[i] = f.end();
            ft[i] = f.text();
        }
        return new dev.emergence.mod.net.AnalyzerPayloads.Data(names, dig, mn, mx, end, span, cap.newest(), cap.mode().ordinal(), cap.armed(),
                cap.frozen(), cap.triggerChannel(), cap.triggerRising(), cap.triggerTime(), cap.window(), s.ground >= 0, s.decode, note, fch, f0, f1, ft);
    }

    /** Keeps each analyser attached to its machine as it is rebuilt (and to new probes). */
    private void tickAnalyzers() {
        for (AnalyzerSession s : analyzers.values()) {
            Island isl = s.pos == null ? null : islandOf.get(s.pos);
            CompiledAssembly now = isl == null ? null : isl.compiled;
            if (now != s.attachedTo || s.dirty) {
                if (s.attachedTo != null) s.attachedTo.detachCapture(s.cap);
                s.attachedTo = null;
                if (now != null) {
                    now.attachCapture(s.cap, s.ground, s.probes);
                    s.attachedTo = now;
                }
                s.dirty = false;
            }
        }
    }

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

    /** The largest magnitude across a player's logger screen: its shown channel over its time base (NaN if none). */
    public double loggerPeak(ServerPlayer player) {
        LoggerSession s = loggers.get(player.getUUID());
        if (s == null || s.log.size() == 0) return Double.NaN;
        double tEnd = s.log.time(s.log.size() - 1), peak = 0;
        for (int i = s.log.size() - 1; i >= 0 && s.log.time(i) >= tEnd - LOGGER_SPANS[s.span]; i--) {
            double v = s.log.value(s.channel, i);
            if (!Double.isNaN(v)) peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    /** What a player's logger screen shows: its channel's name and time-base index (null if it is not clipped on). */
    public String[] loggerShowing(ServerPlayer player) {
        LoggerSession s = loggers.get(player.getUUID());
        return s == null ? null : new String[]{s.log.channels().get(s.channel).name(), String.valueOf(s.span)};
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
    static void pace(Island isl, double ms, double budgetMs) {
        pace(isl, ms, budgetMs, 0.6);
    }

    /**
     * As {@link #pace(Island, double, double)}, recovering below {@code recover} of the budget. (An island off the tick
     * recovers up to 85 % of its own: at 60 % one costing 35 of its 45 ms stayed at the slow motion it fell into while
     * starting, 0.58, where 0.75 was within reach.)
     */
    static void pace(Island isl, double ms, double budgetMs, double recover) {
        isl.costMs = isl.costMs == 0 ? ms : 0.8 * isl.costMs + 0.2 * ms;
        if (isl.costMs > budgetMs) isl.timeScale = Math.max(0.05, isl.timeScale * 0.92);
        else if (isl.costMs < recover * budgetMs) isl.timeScale = Math.min(1, isl.timeScale * 1.05);
    }

    private void rebuild() {
        // collect affected islands (and their state) then re-flood-fill from dirty positions
        Set<BlockPos> seeds = new HashSet<>(dirty);
        dirty.clear();
        boolean arrivedNow = arrived;
        arrived = false;
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
            // (one still compiling: the state it is being compiled from — it has not run since)
            AssemblyState from = i.compiled != null ? i.compiled.exportState() : i.pending != null ? i.startState : null;
            if (i.pending != null) {
                if (DEBUG_ISLANDS) EmergenceMod.LOG.info("Island at {} rebuilt while still compiling", i.blocks.iterator().next());
                i.pending.cancel(true); // (interrupted: the compile stops at its next phase, the worker freed for the new one)
                i.pending = null;
            }
            if (from != null) {
                AssemblyState s = from;
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
                // blocks of the same grid resolution form one machine; across scales, only where a full-size ribbon
                // cable lies against a miniature block (its conductors join the miniature wires at that face)
                int bcx = b.getX() >> 4, bcz = b.getZ() >> 4, res = blocks.get(b).resolution();
                forNeighbours(b, res == AssemblyBlockEntity.BLOCK_SCALE ? blockReach : 1, n -> {
                    if (blocks.containsKey(n) && blocks.get(n).resolution() == res && visited.add(n)) q.add(n);
                    // a neighbour in a chunk not loaded: the machine may go on there, unseen
                    else if (((n.getX() >> 4) != bcx || (n.getZ() >> 4) != bcz) && !level.isLoaded(n)) isl.incomplete = true;
                });
                for (Direction d : Direction.values()) {
                    BlockPos n = b.relative(d);
                    if (blocks.containsKey(n) && blocks.get(n).resolution() != res && joinedAcross(b, n) && visited.add(n)) {
                        q.add(n);
                        isl.mixed = true;
                    }
                }
                for (BlockPos n : sight.getOrDefault(b, Set.of())) {
                    if (blocks.containsKey(n) && blocks.get(n).resolution() == res && visited.add(n)) q.add(n);
                }
            }
            AssemblyState st = mergeWithSaved(isl, merged);
            compileAsync(isl, st);
            if (arrivedNow) isl.settleUntil = tickCount + SETTLE_TICKS;
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

    /**
     * True if an island's blocks still reach into a chunk not loaded (a chunk with more of the machine in it rebuilds the
     * island when it loads; this catches one that loads with none).
     */
    private boolean reachesUnloaded(Island isl) {
        int reach = isl.resolution == AssemblyBlockEntity.BLOCK_SCALE ? 2 : 1;
        for (BlockPos b : isl.blocks) {
            int bcx = b.getX() >> 4, bcz = b.getZ() >> 4;
            boolean[] out = {false};
            forNeighbours(b, reach, n -> {
                if (!out[0] && ((n.getX() >> 4) != bcx || (n.getZ() >> 4) != bcz) && !level.isLoaded(n)) out[0] = true;
            });
            if (out[0]) return true;
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
                if (v.length > 6 && v[5] > 0) st.fluids().put(e.getKey(), v.length > 8 && Double.isFinite(v[8])
                        ? new double[]{v[5], v[6], v[8]} : new double[]{v[5], v[6]});
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

    /** Writes the island at {@code pos} and its present state as a failure dump would (island-dump-*.txt), to replay it in the core. */
    public void dumpIsland(BlockPos pos) {
        Island isl = islandAt(pos);
        if (isl != null && isl.compiled != null) dumpFailure(isl, isl.compiled.exportState(), "island-dump-");
    }

    /**
     * Writes a failed island's parts, anchoring, ambient and start state to {@code island-failure-*.txt} in the game
     * directory (system property {@code emergence.dumpFailures}), for replaying it in the core tests.
     */
    private void dumpFailure(Island isl, AssemblyState st, String prefix) {
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
            p.state().forEach((k, v) -> b.append("S ").append(p.id()).append(' ').append(k).append(' ').append(v).append('\n'));
            // its text (a chip's design, a ROM's image), base 64 on a line of its own
            if (p.text() != null && !p.text().isEmpty())
                b.append("X ").append(p.id()).append(' ').append(java.util.Base64.getEncoder().encodeToString(p.text().getBytes(java.nio.charset.StandardCharsets.UTF_8))).append('\n');
        }
        // the joins across scales: ribbons to the miniature wires they carry, and the links at blocks' faces
        for (long[] br : asm.bridges()) b.append("B ").append(br[0]).append(' ').append(br[1]).append('\n');
        for (var l : asm.links())
            b.append("L ").append(l.kind()).append(' ').append(l.a()).append(' ').append(l.cellA()[0]).append(' ').append(l.cellA()[1]).append(' ')
                    .append(l.cellA()[2]).append(' ').append(l.faceA()).append(' ').append(l.b()).append(' ').append(l.cellB()[0]).append(' ')
                    .append(l.cellB()[1]).append(' ').append(l.cellB()[2]).append(' ').append(l.faceB()).append(' ').append(l.share()).append('\n');
        if (st != null) {
            st.temperatures().forEach((k, v) -> b.append("T ").append(k).append(' ').append(v).append('\n'));
            st.coilCurrents().forEach((k, v) -> b.append("I ").append(k).append(' ').append(v).append('\n'));
            st.capacitorVoltages().forEach((k, v) -> b.append("V ").append(k).append(' ').append(v).append('\n'));
            st.bodyMotion().forEach((k, v) -> b.append("M ").append(k).append(' ').append(v[0]).append(' ').append(v[1]).append('\n'));
            st.fluids().forEach((k, v) -> {
                b.append("F ").append(k);
                for (double x : v) b.append(' ').append(x);
                b.append('\n');
            });
            st.chemistry().forEach((k, v) -> b.append("C ").append(k).append(' ').append(v).append('\n'));
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(prefix + System.currentTimeMillis() + ".txt"), b);
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

    /** A rebuild, compile or step taking longer than this [ms] is logged: the server's tick is 50 ms. */
    private static final long SLOW_MS = 1000;

    /** Compiles the island now, on this thread (a restart after a failure). */
    private CompiledAssembly compile(Island isl, AssemblyState state) {
        Assembly asm = prepare(isl, state);
        return asm == null ? null : finish(isl.blocks.iterator().next(), isl.blocks.size(), asm, state);
    }

    /**
     * Compiles the island off the server thread: the assembly and what the compiler asks of the world are taken now,
     * the compiling done by a worker. The server waits for it a moment ({@link #COMPILE_WAIT_MS}), so an ordinary
     * machine is ready the same tick, as before; a slow one (an optical bench tracing its light, a second or more)
     * is left pending, and the machine waits, its state as it was, while everything else runs on — not the server.
     */
    private void compileAsync(Island isl, AssemblyState state) {
        Assembly asm = prepare(isl, state);
        isl.compiled = null;
        if (asm == null) return;
        BlockPos at = isl.blocks.iterator().next();
        int n = isl.blocks.size();
        java.util.concurrent.Future<CompiledAssembly> job = COMPILE_POOL.submit(() -> finish(at, n, asm, state));
        try {
            isl.compiled = COMPILE_WAIT_MS < 0 ? job.get() : job.get(COMPILE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            isl.pending = job;
            isl.pendingSince = tickCount;
        } catch (java.util.concurrent.ExecutionException e) {
            EmergenceMod.LOG.error("Failed to compile island", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            isl.pending = job;
        }
    }

    /** Installs the islands whose compiling has finished. */
    private void installCompiled() {
        for (Island isl : new HashSet<>(islandOf.values())) {
            if (isl.pending == null || !isl.pending.isDone()) continue;
            try {
                isl.compiled = isl.pending.get();
            } catch (java.util.concurrent.ExecutionException e) {
                EmergenceMod.LOG.error("Failed to compile island", e.getCause());
            } catch (InterruptedException | java.util.concurrent.CancellationException e) {
                isl.compiled = null;
            }
            if (DEBUG_ISLANDS) EmergenceMod.LOG.info("Island at {} compiled off the server thread ({} ticks){}", isl.blocks.iterator().next(),
                    tickCount - isl.pendingSince, isl.compiled == null ? ", failed" : "");
            isl.pending = null;
        }
    }

    private static final boolean DEBUG_ISLANDS = Boolean.getBoolean("emergence.debugIslands");

    /** Waits for every island still compiling and installs it (the demo builder's check reads them all at once). */
    public void awaitCompiles() {
        for (Island isl : new HashSet<>(islandOf.values())) {
            if (isl.pending == null) continue;
            try {
                isl.pending.get();
            } catch (Exception ignored) {
                // reported when installed
            }
        }
        installCompiled();
    }

    /**
     * How long the server waits for a compile before leaving it pending [ms]; where the budgets are lifted (in-world
     * tests, demo generation) it waits for it whatever it takes, as they expect every machine compiled at once.
     */
    private static final long COMPILE_WAIT_MS = Long.getLong("emergence.compileWaitMs", GLOBAL_BUDGET_MS >= 1e6 ? -1 : 20);

    /** Two workers compiling (a compile is parallel within itself where it can be: magnet tables, light by band). */
    private static final java.util.concurrent.ExecutorService COMPILE_POOL = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Emergence compile");
        t.setDaemon(true);
        return t;
    });

    /** Compiles a prepared assembly. It touches nothing of the world, so it may run on any thread. */
    private static CompiledAssembly finish(BlockPos at, int blocks, Assembly asm, AssemblyState state) {
        long c0 = System.nanoTime();
        try {
            CompiledAssembly c = AssemblyCompiler.compile(asm, state).wallBudget(STEP_WALL_MS);
            for (String w : c.warnings()) EmergenceMod.LOG.debug("Island warning: {}", w);
            long ms = (System.nanoTime() - c0) / 1_000_000;
            if (ms > SLOW_MS) EmergenceMod.LOG.warn("Slow simulation: compiling the island at {} ({} blocks) took {} ms", at, blocks, ms);
            if (DEBUG_ISLANDS) EmergenceMod.LOG.info("Island at {} ({} parts) compiled in {} ms on {}", at, asm.parts().size(), ms, Thread.currentThread().getName());
            return c;
        } catch (AssemblyCompiler.Cancelled e) {
            return null; // no longer wanted: the machine changed again
        } catch (RuntimeException e) {
            EmergenceMod.LOG.error("Failed to compile island", e);
            return null;
        }
    }

    /**
     * Wall-clock limit on one island's step per server tick [ms]: a burst of costly switching runs that island in slow
     * motion for the moment rather than stalling the server (and every other machine) for seconds. System property
     * {@code emergence.stepWallMs} overrides it; off (0) where the global budget is lifted (the demo world's in-world
     * tests run every machine at full rate).
     */
    private static final double STEP_WALL_MS = Double.parseDouble(System.getProperty("emergence.stepWallMs", GLOBAL_BUDGET_MS >= 1e6 ? "0" : "60"));

    /** The island's parts as an assembly, with what the compiler will ask of the world read now (on this thread). */
    private Assembly prepare(Island isl, AssemblyState state) {
        isl.startState = state;
        Assembly asm = new Assembly();
        isl.partPos.clear();
        Map<Long, int[]> anchored = new HashMap<>();
        isl.shift = isl.mixed ? shiftFor(isl) : null;
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            int[] shift = be.resolution() == AssemblyBlockEntity.BLOCK_SCALE ? isl.shift : null;
            for (PlacedPart pp : be.parts()) {
                asm.add(pp.toCore(b, be.resolution(), shift));
                isl.partPos.put(pp.id, b);
                if (touchesWorld(b, pp, be.resolution())) anchored.put(pp.id, null);
            }
        }
        if (isl.mixed) {
            bridgeRibbons(isl, asm);
            // heat, shafts and pipes across each face where a full-size block meets a miniature one
            for (BlockPos b : isl.blocks) {
                AssemblyBlockEntity be = be(b);
                if (be == null || be.resolution() != AssemblyBlockEntity.BLOCK_SCALE) continue;
                for (Direction d : Direction.values()) {
                    BlockPos n = b.relative(d);
                    AssemblyBlockEntity m = isl.blocks.contains(n) ? be(n) : null;
                    if (m != null && m.resolution() == AssemblyBlockEntity.MINIATURE) crossLinks(isl, b, be, d, n, m, asm);
                }
            }
        }
        isl.solidAround.clear();
        for (BlockPos b : isl.blocks) {
            for (Direction d : Direction.values()) {
                BlockPos n = b.relative(d);
                if (!isl.blocks.contains(n) && level.isLoaded(n) && worldSolid(n, d.getOpposite())) isl.solidAround.add(n);
            }
        }
        BlockPos first = isl.blocks.iterator().next();
        isl.ambient = ambientTemperature(first);
        asm.ambientTemperature(isl.ambient);
        asm.ambientPressure(airPressure(first));
        // open pipe ends inside world blocks are capped; ends in air (or water...) stay open
        int res = isl.resolution;
        // a cell of the assembly's grid as a world block, and its grid's resolution (a joined island holds both: its
        // full-size parts' cells, shifted, lie by its full-size blocks)
        int[] shift = isl.shift;
        Set<BlockPos> fullBlocks = new HashSet<>();
        if (shift != null) for (BlockPos b : isl.blocks) if (be(b) != null && be(b).resolution() == AssemblyBlockEntity.BLOCK_SCALE) fullBlocks.add(b);
        CellMap cells = new CellMap(res, shift, fullBlocks);
        // (the blocks beside it, read now: the compiler may run on another thread; a pipe's end looks one cell on)
        Set<BlockPos> own = new HashSet<>(isl.blocks), solidCells = new HashSet<>();
        for (BlockPos b : own) {
            for (Direction d : Direction.values()) {
                BlockPos n = b.relative(d);
                if (!own.contains(n) && (!level.isLoaded(n) || level.getBlockState(n).isSolid())) solidCells.add(n);
            }
        }
        asm.solidWhen((x, y, z) -> {
            BlockPos bp = cells.block(x, y, z);
            return !own.contains(bp) && solidCells.contains(bp);
        });
        // still water in and over its blocks (its own waterlogged, the world's water above each and just under it),
        // read now as well: the surface a float rides is where the column of water over it ends
        Set<BlockPos> water = new HashSet<>();
        Map<BlockPos, dev.emergence.core.math.Vec3> current = new HashMap<>();
        Map<BlockPos, Double> height = new HashMap<>();
        java.util.function.Consumer<BlockPos> readWater = n -> {
            if (water.contains(n) || !level.isLoaded(n)) return;
            var fs = level.getFluidState(n);
            if (!fs.is(net.minecraft.tags.FluidTags.WATER)) return;
            water.add(n);
            height.put(n, (double) fs.getHeight(level, n));
            if (fs.isSource()) return;
            // flowing water: its direction from the game, its speed from its slope (each block of run drops it a ninth of
            // a block) and depth by Manning's formula for a smooth channel, v = R^⅔·S^½/n (n = 0.015; a wide stream's
            // hydraulic radius is its depth)
            net.minecraft.world.phys.Vec3 dir = fs.getFlow(level, n);
            double hx = dir.x, hz = dir.z, len = Math.sqrt(hx * hx + hz * hz);
            if (len < 1e-6) return;
            double depth = Math.max(fs.getHeight(level, n), 1.0 / 9) * dev.emergence.core.assembly.Part.CELL * res;
            double v = Math.pow(depth, 2.0 / 3) * Math.sqrt(1.0 / 9) / 0.015;
            current.put(n, new dev.emergence.core.math.Vec3(v * hx / len, 0, v * hz / len));
        };
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos b : own) {
            x0 = Math.min(x0, b.getX()); y0 = Math.min(y0, b.getY()); z0 = Math.min(z0, b.getZ());
            x1 = Math.max(x1, b.getX()); y1 = Math.max(y1, b.getY()); z1 = Math.max(z1, b.getZ());
        }
        // all the water about the machine (a paddle's path passes cells it does not own), then the columns over it
        if ((long) (x1 - x0 + 5) * (y1 - y0 + 5) * (z1 - z0 + 5) <= 200_000) {
            for (int x = x0 - 2; x <= x1 + 2; x++)
                for (int y = y0 - 2; y <= y1 + 2; y++)
                    for (int z = z0 - 2; z <= z1 + 2; z++) readWater.accept(new BlockPos(x, y, z));
        } else {
            for (BlockPos b : own) for (int k = -1; k <= 0; k++) readWater.accept(b.above(k));
        }
        for (BlockPos b : new java.util.ArrayList<>(water)) {
            for (int k = 1; k <= 16; k++) {
                BlockPos n = b.above(k);
                int before = water.size();
                if (water.contains(n)) continue;
                readWater.accept(n);
                if (water.size() == before) break;
            }
        }
        // its own blocks standing in a stream: a part there is no waterlogged source (that would still the stream at
        // it), so it takes the water of the stream beside it — its current, and its level
        for (BlockPos b : own) {
            if (water.contains(b)) continue;
            double vx = 0, vz = 0, h = 0;
            int n = 0;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos nb = b.relative(d);
                dev.emergence.core.math.Vec3 cur = current.get(nb);
                if (cur == null) continue;
                vx += cur.x();
                vz += cur.z();
                h += height.get(nb);
                n++;
            }
            if (n == 0) continue;
            water.add(b);
            current.put(b, new dev.emergence.core.math.Vec3(vx / n, 0, vz / n));
            height.put(b, h / n);
        }
        if (!height.isEmpty()) asm.waterTopWhen((x, y, z) -> {
            BlockPos bp = cells.block(x, y, z);
            int r = cells.res(x, y, z);
            Double hb = height.get(bp);
            // the surface within its top cell: the block's own level over a finer lattice's cells
            return hb == null ? dev.emergence.core.assembly.Assembly.WATER_TOP
                    : r == 1 ? hb : Math.max(0, Math.min(1, hb * r - Math.floorMod(y, r)));
        });
        if (!current.isEmpty()) asm.flowWhen((x, y, z) -> current.getOrDefault(cells.block(x, y, z), dev.emergence.core.math.Vec3.ZERO));
        if (!water.isEmpty()) {
            // a finer lattice: the top block of a column holds water only up to its surface, 8/9 of the block
            asm.waterWhen((x, y, z) -> {
                BlockPos bp = cells.block(x, y, z);
                if (!water.contains(bp)) return false;
                int r = cells.res(x, y, z);
                int wet = r == 1 ? 1 : (int) Math.round(r * dev.emergence.core.assembly.Assembly.WATER_TOP);
                return r == 1 || water.contains(bp.above()) || Math.floorMod(y, r) < wet;
            });
        }
        asm.anchoredWhen(p -> anchored.containsKey(p.id()));
        if (asm.parts().isEmpty()) return null;
        if (Boolean.getBoolean("emergence.debugIslands")) {
            EmergenceMod.LOG.info("Compiling island {} parts, anchored {}", asm.parts().size(),
                    asm.parts().stream().filter(p -> anchored.containsKey(p.id())).map(p -> p.kind() + "@" + p.minY()).toList());
        }
        return asm;
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

    /**
     * The island at {@code pos}, compiled: one still compiling off the server thread is waited for here. A hand on a
     * machine mid-compile was otherwise lost — a crank turned for nothing, or a switch flipped on its block while the
     * compile, begun from the state before the click, built it open (the optocoupler stayed dark, the gas engine
     * never got going when the tour came to them straight from a costlier machine).
     */
    private Island islandAt(BlockPos pos) {
        if (dirty.contains(pos)) {
            for (Island i : new HashSet<>(islandOf.values())) settle(i);
            rebuild();
        }
        Island isl = islandOf.get(pos);
        // (a player's hand, a probe: on the machine as its step off the tick leaves it)
        if (isl != null) settle(isl);
        if (isl != null && isl.pending != null) {
            try {
                isl.pending.get();
            } catch (Exception ignored) {
                // reported as it is installed
            }
            installCompiled();
        }
        return isl;
    }

    /** A player holds the crank: effort follows the human model inside the solver while held. */
    public void turnCrank(BlockPos pos, long partId, Player player) {
        Island isl = islandAt(pos);
        if (isl == null || isl.compiled == null) return;
        isl.compiled.setCrankEffort(partId, player.isShiftKeyDown() ? -1 : 1, player.isSprinting());
        isl.crankTicks.put(partId, 6); // right-click repeats every 4 ticks while held
    }

    /** A player grips a part and turns the body it is fixed to (sneaking: the other way) while holding use. */
    public boolean turnBody(BlockPos pos, long partId, Player player) {
        Island isl = islandAt(pos);
        if (isl == null || isl.compiled == null) return false;
        if (!isl.compiled.setTurnEffort(partId, player.isShiftKeyDown() ? -1 : 1, player.isSprinting())) return false;
        isl.turnTicks.put(partId, 6);
        return true;
    }

    /** A player pushes a slide along its axis (sneaking: the other way) while holding use. */
    public boolean pushSlider(BlockPos pos, long partId, Player player) {
        Island isl = islandAt(pos);
        if (isl == null || isl.compiled == null) return false;
        boolean ok = isl.compiled.setPushEffort(partId, player.isShiftKeyDown() ? -1 : 1);
        if (LOG_PACE && ok && !isl.pushTicks.containsKey(partId)) EmergenceMod.LOG.info("Hand: push on part {} at {} ({})", partId, pos.toShortString(),
                player.isShiftKeyDown() ? "sneaking, back" : "forward");
        if (!ok) return false;
        isl.pushTicks.put(partId, 6);
        return true;
    }

    /**
     * The wind at an island. Minecraft has none, so it is modelled: 4.5 m/s at 10 m in clear weather, 8 in rain, 12 in
     * a thunderstorm, from the west (veering ±25° over twenty minutes, gusting ±15 % over tens of seconds), less near
     * the ground by the neutral log profile u(h) = u₁₀·ln(h/z₀)/ln(10/z₀) over open country (z₀ = 0.1 m), h the
     * height of the machine's middle above the ground beneath it (at least 2 m).
     */
    private void updateWind(Island isl) {
        if (isl.windHeight < 0) isl.windHeight = windHeight(isl);
        double t = level.getGameTime() / 20.0;
        double u10 = 4.5 + 3.5 * level.getRainLevel(1f) + 4.0 * level.getThunderLevel(1f);
        double z0 = 0.1, u = u10 * Math.log(Math.max(isl.windHeight, 2) / z0) / Math.log(10 / z0);
        double gust = 1 + 0.1 * Math.sin(2 * Math.PI * t / 37) + 0.05 * Math.sin(2 * Math.PI * t / 11.3 + 1.3);
        double dir = Math.toRadians(25) * Math.sin(2 * Math.PI * t / 1200);
        // a wall or a hedge upwind calms the air behind it: looked for again as the wind veers
        if (Double.isNaN(isl.shelterDir) || Math.abs(dir - isl.shelterDir) > Math.toRadians(3)) {
            isl.shelter = shelter(isl, dir);
            isl.shelterDir = dir;
        }
        u *= isl.shelter;
        isl.compiled.setWind(new Vec3(u * gust * Math.cos(dir), 0, u * gust * Math.sin(dir)));
    }

    /** How far upwind a solid block shelters a machine [blocks], and the wind left in its lee. */
    private static final int SHELTER_REACH = 16;
    private static final double LEE = 0.25;

    /**
     * The share of the wind that reaches an island blowing towards direction {@code dir} (radians from east, about the
     * vertical): rays from its blocks run upwind; the fraction stopped within {@value #SHELTER_REACH} blocks by a solid
     * block that is no part of a machine is in the lee of a wall, where the wind is down to {@value #LEE} of itself.
     * (A wall's wake is calm for some times its height downwind; this keeps the first few.)
     */
    private double shelter(Island isl, double dir) {
        double ux = -Math.cos(dir), uz = -Math.sin(dir); // upwind
        int rays = 0, blocked = 0;
        for (BlockPos b : isl.blocks) {
            rays++;
            for (int k = 1; k <= SHELTER_REACH; k++) {
                BlockPos q = BlockPos.containing(b.getX() + 0.5 + ux * k, b.getY() + 0.5, b.getZ() + 0.5 + uz * k);
                if (isl.blocks.contains(q) || blocks.containsKey(q) || !level.isLoaded(q)) continue;
                if (level.getBlockState(q).isSolid()) {
                    blocked++;
                    break;
                }
            }
        }
        if (rays == 0) return 1;
        double f = (double) blocked / rays;
        return 1 - (1 - LEE) * f;
    }

    /** Height of an island's middle above the first non-machine block under its lowest block [blocks]. */
    private double windHeight(Island isl) {
        double sum = 0;
        int minY = Integer.MAX_VALUE;
        BlockPos low = null;
        for (BlockPos b : isl.blocks) {
            sum += b.getY();
            if (b.getY() < minY) {
                minY = b.getY();
                low = b;
            }
        }
        if (low == null) return 2;
        int ground = minY - 1;
        for (int k = 0; k < 256 && ground > level.getMinBuildHeight(); k++, ground--) {
            BlockPos q = new BlockPos(low.getX(), ground, low.getZ());
            if (!isl.blocks.contains(q) && !level.getBlockState(q).isAir()) break;
        }
        return sum / isl.blocks.size() + 0.5 - (ground + 1);
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
        for (var it = isl.turnTicks.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0) {
                isl.compiled.setTurnEffort(e.getKey(), 0, false);
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
        // (a block placed this tick is not registered yet: take it from the world)
        AssemblyBlockEntity be = be(pos) != null ? be(pos) : level.getBlockEntity(pos) instanceof AssemblyBlockEntity w ? w : null;
        PlacedPart part = be == null ? null : be.byId(partId);
        // compiled with it in (an island compiled before the part was placed does not know it yet)
        boolean compiled = isl != null && isl.compiled != null && isl.compiled.assembly().get(partId) != null;
        // not simulated yet (just placed or loaded, or its build failed): flip the part itself; the island compiles from it
        boolean closed = compiled ? isl.compiled.toggleSwitch(partId) : part != null && !part.closed;
        if (be != null) {
            PlacedPart p = part;
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

    /**
     * The machine the part at {@code pos} is in, for the goggles: its size, and how fast it runs against real time (its
     * own time scale, the distance scale, and how far its accuracy is relaxed to keep up). Null if not simulated.
     */
    String machineSummary(BlockPos pos) {
        Island isl = islandOf.get(pos);
        if (isl == null || isl.compiled == null) return null;
        double pace = isl.timeScale * scaleOf(isl);
        int nb = isl.blocks.size(), np = isl.compiled.assembly().parts().size();
        return String.format("%d block%s, %d part%s; %s%s", nb, nb == 1 ? "" : "s", np, np == 1 ? "" : "s",
                pace >= 0.995 ? "running at real time" : String.format("running at %.2f× real time", pace),
                isl.relax > 1.01 ? String.format(" (accuracy relaxed ×%.1f)", isl.relax) : "");
    }

    /** Chips a wafer is cut into (a 6.25 cm square, dies about 1.5 cm across). */
    public static final int CHIPS_PER_WAFER = 16;

    /**
     * A silicon block clicked with an empty hand: what heating has made of it, its oxide and (a wafer, with a dopant
     * film) its junctions and the chips they would make. {@code cut}: a cool wafer is cut into chips carrying its
     * process ({@link dev.emergence.core.digital.ChipProcess}), and is gone.
     */
    public void waferClick(BlockPos pos, PlacedPart part, Player player, boolean cut) {
        CompiledAssembly c = compiledAt(pos);
        double[] w = c == null ? null : c.wafer(part.id);
        if (w == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.emergence.wafer_unsimulated"), true);
            return;
        }
        double t = c.temperature(part.id);
        var cp = c.chipProcess(part.id);
        if (cp == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.emergence.wafer",
                    String.format("%.0f", t), String.format("%.1f", w[0] * 1e9)), false);
            return;
        }
        String chips = cp.works() ? net.minecraft.network.chat.Component.translatable("message.emergence.wafer_chips_work",
                String.format("%.2f", cp.delayScale()), String.format("%.2g", cp.leakageScale())).getString() : cp.fault();
        if (!cut) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.emergence.wafer_doped",
                    String.format("%.0f", t), String.format("%.1f", w[0] * 1e9), cp.pChannel() ? "p+" : "n+",
                    String.format("%.2f", w[1] * 1e6), String.format("%.0f", cp.gateLength() * 1e6), chips), false);
            return;
        }
        if (t > 373) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.emergence.wafer_hot", String.format("%.0f", t)), true);
            return;
        }
        AssemblyBlockEntity be = be(pos) != null ? be(pos) : level.getBlockEntity(pos) instanceof AssemblyBlockEntity x ? x : null;
        if (be == null || be.removePart(part.id) == null) return;
        if (be.parts().isEmpty()) level.removeBlock(pos, false);
        var spec = dev.emergence.mod.part.PartSpec.of("silicon", 1, 1, "function", dev.emergence.core.digital.ChipTypes.DESIGN,
                dev.emergence.core.digital.ChipProcess.OXIDE, cp.oxide(), dev.emergence.core.digital.ChipProcess.JUNCTION, cp.junction(),
                dev.emergence.core.digital.ChipProcess.GATE, cp.gateLength(), dev.emergence.core.digital.ChipProcess.CHANNEL, cp.pChannel() ? 1 : 0);
        net.minecraft.world.item.ItemStack chipsStack = dev.emergence.mod.part.PartPresets.stack(PartKind.CHIP, spec);
        chipsStack.setCount(CHIPS_PER_WAFER);
        if (!player.getInventory().add(chipsStack)) net.minecraft.world.level.block.Block.popResource(level, pos, chipsStack);
        level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.5f, 1.6f);
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.emergence.wafer_cut", CHIPS_PER_WAFER, chips), false);
    }

    /** The block a part of the island at {@code pos} is in (null if none such). */
    public BlockPos partBlock(BlockPos pos, long partId) {
        Island isl = islandAt(pos);
        return isl == null ? null : isl.partPos.get(partId);
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
            EmergenceMod.LOG.info("Part destroyed: {} #{} at {} ({}; {} K)", p.kind, id, pos,
                    isl.compiled != null && isl.compiled.burstUtilisation(id) >= 1 ? "burst" : broken.contains(id) ? "overloaded" : "too hot",
                    isl.compiled == null ? "?" : String.format("%.0f", isl.compiled.temperature(id)));
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
        for (long id : isl.partPos.keySet()) {
            double rate = c.gasEvolution(id);
            if (!(rate > 2e-8)) continue;
            int res = resOf(isl, id);
            double toWorld = 1.0 / (Part.CELL * res); // metres → world blocks
            Part p = c.assembly().get(id);
            if (p == null) continue;
            // 1 particle per 4 ticks at ~20 nmol/s, rising logarithmically
            int n = (int) Math.min(6, 1 + Math.log10(rate / 2e-8) * 1.5);
            Vec3 pc = p.center();
            int[] sh = shiftOf(isl, id);
            if (sh != null) pc = pc.sub(new Vec3(sh[0] * Part.CELL, sh[1] * Part.CELL, sh[2] * Part.CELL));
            for (Part q : c.assembly().neighbors(p)) {
                if (q.kind() != PartKind.ELECTROLYTE) continue;
                Vec3 qc = sh != null ? q.center().sub(new Vec3(sh[0] * Part.CELL, sh[1] * Part.CELL, sh[2] * Part.CELL)) : q.center();
                // at the electrode surface, inside the liquid
                Vec3 at = pc.add(qc.sub(pc).mul(0.55));
                double spread = 0.25 / res;
                level.sendParticles(ParticleTypes.BUBBLE_POP, at.x() * toWorld, at.y() * toWorld, at.z() * toWorld,
                        n, spread, spread, spread, 0.02);
            }
        }
    }

    /** Air leaving an open pipe end shows as a plume whose speed follows the exit velocity. */
    private void ventPlumes(Island isl) {
        if (tickCount % 2 != 0) return;
        CompiledAssembly c = isl.compiled;
        for (CompiledAssembly.Vent v : c.vents()) {
            int res = resOf(isl, v.partId());
            double toWorld = 1.0 / (Part.CELL * res);
            double q = c.ventOutflow(v);
            double[] f = c.fluid(v.partId());
            if (f == null || Math.abs(q) < 1e-4) continue;
            Part p = c.assembly().get(v.partId());
            double area = PartGeometry.passageArea(p);
            double rho = Math.max(f[2] / Math.max(PartGeometry.fluidVolume(p), 1e-12), 1e-3);
            double speed = q / (rho * area); // m/s, + outwards
            int[] cell = v.cell().clone();
            int[] vs = shiftOf(isl, v.partId());
            if (vs != null) for (int k = 0; k < 3; k++) cell[k] -= vs[k];
            Dir d = v.face();
            double cx = (cell[0] + 0.5 + 0.5 * d.dx) * Part.CELL * toWorld, cy = (cell[1] + 0.5 + 0.5 * d.dy) * Part.CELL * toWorld,
                    cz = (cell[2] + 0.5 + 0.5 * d.dz) * Part.CELL * toWorld;
            if (speed < 0) continue; // intake: nothing visible
            // particle speed in blocks/tick, compressed logarithmically so jets stay on screen
            double vis = Math.min(0.6, 0.05 * Math.log1p(speed)) * 16.0 / res;
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
        settle(isl);
        // (still compiling: the state it is being compiled from, which it has not run on from)
        AssemblyState s = isl.compiled != null ? isl.compiled.exportState() : isl.pending != null ? isl.startState : null;
        if (s == null) return;
        // the chemistry (and chips', flows') entries by the part that owns each, once — not every entry for every block
        Map<Long, List<Map.Entry<String, Double>>> byOwner = new HashMap<>();
        for (var ce : s.chemistry().entrySet()) {
            String k = ce.getKey();
            byOwner.computeIfAbsent(Long.parseLong(k.substring(1, k.indexOf(':'))), o -> new ArrayList<>()).add(ce);
        }
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            be.savedState().clear();
            be.savedChemistry().clear();
            for (PlacedPart p : be.parts()) {
                var mine = byOwner.get(p.id);
                if (mine != null) for (var ce : mine) be.savedChemistry().put(ce.getKey(), ce.getValue());
            }
            for (PlacedPart p : be.parts()) {
                double[] m = s.bodyMotion().getOrDefault(p.id, new double[]{0, 0});
                double[] fl = s.fluids().getOrDefault(p.id, new double[]{0, 0});
                be.savedState().put(p.id, new double[]{s.temperatures().getOrDefault(p.id, isl.ambient), m[0], m[1],
                        s.coilCurrents().getOrDefault(p.id, 0.0), s.capacitorVoltages().getOrDefault(p.id, 0.0), fl[0], fl[1],
                        s.damage().getOrDefault(p.id, 0.0), fl.length > 2 ? fl[2] : Double.NaN});
            }
            if (markChanged) markUnsaved(b);
        }
    }

    /**
     * Marks the chunk holding {@code pos} for saving, if it is loaded — and never more. A block entity's setChanged()
     * also updates comparator outputs, reading the neighbours' block states: next to a chunk border that requests the
     * neighbouring chunk, and at server shutdown (the level's save event runs while chunks unload) a chunk requested
     * mid-unload has its unload rescheduled, forever — the server spun in ChunkMap.processUnloads.
     */
    private void markUnsaved(BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) chunk.setUnsaved(true);
    }

    public void saveAll() {
        for (Island isl : new HashSet<>(islandOf.values())) saveIsland(isl);
    }

    /**
     * Each display panel's image, when it has changed (at most every other tick) or every 5 s regardless (for players
     * who came since): 1 KB, as shown.
     */
    /**
     * A display controller in a miniature machine whose segment lies against a face of its block drives the full-size
     * screen standing against that face — as a flex cable would run from a small board to a big panel. Panels are
     * passive (their current is in the controller's); the screen's panels, a machine of their own at block scale,
     * just show the controller's image. Physically consistent: a block-scale panel is the same 6.25 cm panel, drawn
     * sixteen times larger.
     */
    record DisplayLink(dev.emergence.core.digital.DisplayPanel controller, long[] panels, float[][] tiles, BlockPos at, long[] sent) {}

    private void syncDisplayLinks(Island isl) {
        if (isl.resolution != AssemblyBlockEntity.MINIATURE && !isl.mixed) return;
        if (isl.displayLinks == null || isl.displayLinksFor != isl.compiled || tickCount - isl.displayLinksAt >= 40) {
            List<DisplayLink> old = isl.displayLinks;
            isl.displayLinks = findDisplayLinks(isl);
            isl.displayLinksFor = isl.compiled;
            isl.displayLinksAt = tickCount;
            // a link found again keeps its pace
            if (old != null) for (int i = 0; i < isl.displayLinks.size(); i++) {
                DisplayLink l = isl.displayLinks.get(i);
                for (DisplayLink o : old) if (java.util.Arrays.equals(o.panels(), l.panels()) && o.controller() == l.controller()) System.arraycopy(o.sent(), 0, l.sent(), 0, 2);
            }
        }
        if (DEBUG_ISLANDS && tickCount % 200 == 0) {
            StringBuilder sb = new StringBuilder();
            for (DisplayLink l : isl.displayLinks) {
                String pins = "";
                for (long id : isl.partPos.keySet()) {
                    var chip = isl.compiled.digitalChip(id);
                    if (chip != null && chip.component() == l.controller()) pins = chip.describePins();
                }
                sb.append(String.format(" [%d panels: version %d, on %s, %d lit, %d bytes, last sent tick %d; %s]", l.panels().length, l.controller().version(),
                        l.controller().on(), l.controller().shown(), l.controller().bytes(), l.sent()[1], pins));
            }
            EmergenceMod.LOG.info("Displays of the island at {} (t = {} s):{}", isl.blocks.iterator().next(), Math.round(isl.compiled.time()), sb);
        }
        for (DisplayLink l : isl.displayLinks) {
            var d = l.controller();
            boolean changed = d.version() != l.sent()[0];
            if (!(changed && tickCount - l.sent()[1] >= 2) && tickCount - l.sent()[1] < 100) continue;
            byte[] img = screenImage(d);
            for (int i = 0; i < l.panels().length; i++) {
                PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(l.at()),
                        new dev.emergence.mod.net.DisplaySyncPayload(l.panels()[i], img, d.contrast(), l.tiles()[i]));
            }
            l.sent()[0] = d.version();
            l.sent()[1] = tickCount;
        }
    }

    /** What a display controller's screen shows: its image, or "NO SIGNAL" while it has heard nothing since it powered up. */
    static byte[] screenImage(dev.emergence.core.digital.DisplayPanel d) {
        if (d.awaitingData()) return dev.emergence.core.digital.DisplayPanel.noSignalImage();
        byte[] img = new byte[1024];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) if (d.pixel(x, y)) img[(y >> 3) * 128 + x] |= (byte) (1 << (y & 7));
        return img;
    }

    /** The full-size screens the miniature machine at {@code pos} drives across its block faces: each screen's panel ids. */
    public List<long[]> linkedScreens(BlockPos pos) {
        Island isl = islandAt(pos);
        if (isl == null || isl.compiled == null) return List.of();
        List<long[]> out = new ArrayList<>();
        for (DisplayLink l : findDisplayLinks(isl)) out.add(l.panels());
        return out;
    }

    private List<DisplayLink> findDisplayLinks(Island isl) {
        List<DisplayLink> out = new ArrayList<>();
        CompiledAssembly c = isl.compiled;
        if (c == null) return out;
        java.util.Set<Long> linked = new java.util.HashSet<>();
        for (var en : isl.partPos.entrySet()) {
            var chip = c.digitalChip(en.getKey());
            if (chip == null || !(chip.component() instanceof dev.emergence.core.digital.DisplayPanel dp)) continue;
            BlockPos b = en.getValue();
            AssemblyBlockEntity be = be(b);
            PlacedPart seg = be == null ? null : be.byId(en.getKey());
            if (seg == null || be.resolution() != AssemblyBlockEntity.MINIATURE) continue;
            int last = be.resolution() - 1;
            for (Direction dir : Direction.values()) {
                int coord = switch (dir.getAxis()) {
                    case X -> seg.x0;
                    case Y -> seg.y0;
                    case Z -> seg.z0;
                };
                if (coord != (dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? last : 0)) continue;
                BlockPos n = b.relative(dir);
                if (!(level.getBlockEntity(n) instanceof AssemblyBlockEntity nb) || nb.resolution() != AssemblyBlockEntity.BLOCK_SCALE) continue;
                PlacedPart first = nb.parts().stream().filter(q -> q.kind == PartKind.DISPLAY).findFirst().orElse(null);
                if (first == null || linked.contains(first.id)) continue;
                // the whole screen: block-scale panels side by side with it, in its plane, facing its way
                List<dev.emergence.core.assembly.Part> panels = new ArrayList<>();
                Map<Long, BlockPos> where = new HashMap<>();
                Deque<BlockPos> q = new ArrayDeque<>(List.of(n));
                java.util.Set<BlockPos> seen = new HashSet<>(List.of(n));
                while (!q.isEmpty() && panels.size() < 256) {
                    BlockPos p = q.poll();
                    if (!(level.getBlockEntity(p) instanceof AssemblyBlockEntity pb) || pb.resolution() != AssemblyBlockEntity.BLOCK_SCALE) continue;
                    PlacedPart panel = pb.parts().stream().filter(x -> x.kind == PartKind.DISPLAY && x.axis == first.axis).findFirst().orElse(null);
                    if (panel == null) continue;
                    panels.add(panel.toCore(p, AssemblyBlockEntity.BLOCK_SCALE));
                    where.put(panel.id, p);
                    for (Direction e : Direction.values()) {
                        if (e.getAxis().ordinal() == first.axis.axis()) continue; // stays in the screen's plane
                        BlockPos m = p.relative(e);
                        if (seen.add(m)) q.add(m);
                    }
                }
                var tiles = dev.emergence.core.assembly.DisplayTiles.tiles(panels);
                long[] ids = new long[panels.size()];
                float[][] t = new float[panels.size()][];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = panels.get(i).id();
                    t[i] = tiles.get(ids[i]);
                    linked.add(ids[i]);
                }
                out.add(new DisplayLink(dp, ids, t, n, new long[]{-1, Long.MIN_VALUE / 2}));
            }
        }
        return out;
    }

    private void syncDisplays(Island isl, BlockPos any) {
        syncDisplayLinks(isl);
        for (var en : isl.compiled.displays().entrySet()) {
            var d = en.getValue();
            long[] last = isl.displaySent.computeIfAbsent(en.getKey(), k -> new long[]{-1, Long.MIN_VALUE / 2});
            boolean changed = d.version() != last[0];
            if (!(changed && tickCount - last[1] >= 2) && tickCount - last[1] < 100) continue;
            byte[] img = screenImage(d);
            PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(any),
                    new dev.emergence.mod.net.DisplaySyncPayload(en.getKey(), img, d.contrast(), isl.compiled.displayTile(en.getKey())));
            last[0] = d.version();
            last[1] = tickCount;
        }
    }

    private void sync(Island isl) {
        CompiledAssembly c = isl.compiled;
        List<IslandSyncPayload.BodyState> bodies = new ArrayList<>();
        for (CompiledAssembly.Body b : c.bodies()) {
            if (b.isStatic()) continue;
            Vec3 pv = b.pivot(), ax = b.axis();
            long[] ids = b.parts().stream().mapToLong(Long::longValue).toArray();
            long carrier = b.carrier() == null || b.carrier().parts().isEmpty() ? -1 : b.carrier().parts().get(0);
            // world blocks per physical metre for this body: its parts' grid (an island joined across scales holds both)
            float bodyScale = 16f / (ids.length > 0 ? resOf(isl, ids[0]) : isl.resolution);
            int[] sh = ids.length > 0 ? shiftOf(isl, ids[0]) : null;
            if (sh != null) pv = pv.sub(new Vec3(sh[0] * Part.CELL, sh[1] * Part.CELL, sh[2] * Part.CELL));
            bodies.add(new IslandSyncPayload.BodyState(pv.x(), pv.y(), pv.z(), ax.x(), ax.y(), ax.z(),
                    c.angle(b), c.angularVelocity(b), ids, b.slider(), carrier, bodyScale));
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
            float ps = 16f / resOf(isl, id); // its own grid's (a joined island holds both)
            sEnds.add((float) (se[0] * ps));
            sEnds.add((float) (se[1] * ps));
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
            // its own grid's scale, and (a joined island's full-size part) its shift taken off
            double ps = 16.0 / resOf(isl, id);
            int[] sh = shiftOf(isl, id);
            double ox = sh == null ? 0 : sh[0] * Part.CELL, oy = sh == null ? 0 : sh[1] * Part.CELL, oz = sh == null ? 0 : sh[2] * Part.CELL;
            bIds.add(id);
            bData.add((float) rays.size());
            for (int r = 0; r < rays.size(); r++) {
                var ray = rays.get(r);
                bData.add((float) ray.size());
                bData.add(nms == null ? 0f : (float) nms[r]);
                for (var pt : ray) {
                    bData.add((float) ((pt.x() - ox) * ps - at.getX()));
                    bData.add((float) ((pt.y() - oy) * ps - at.getY()));
                    bData.add((float) ((pt.z() - oz) * ps - at.getZ()));
                }
            }
        }
        float[] ba = new float[bData.size()];
        for (int i = 0; i < ba.length; i++) ba[i] = bData.get(i);
        // link bars: where each piece's share of its bar runs from the piece's centre, in world blocks
        List<Long> lIds = new ArrayList<>();
        List<Float> lData = new ArrayList<>();
        for (long id : isl.partPos.keySet()) {
            double[] lp = c.linkPins(id);
            if (lp == null) continue;
            lIds.add(id);
            float ps = 16f / resOf(isl, id);
            for (double v : lp) lData.add((float) (v * ps));
        }
        float[] la = new float[lData.size()];
        for (int i = 0; i < la.length; i++) la[i] = lData.get(i);
        IslandSyncPayload payload = new IslandSyncPayload(all, bodies, hid, ht, level.getGameTime(), scale,
                tintIds.stream().mapToLong(Long::longValue).toArray(), tints.stream().mapToInt(Integer::intValue).toArray(),
                fIds.stream().mapToLong(Long::longValue).toArray(), ga, pIds.stream().mapToLong(Long::longValue).toArray(), pa,
                sIds.stream().mapToLong(Long::longValue).toArray(), sa, (float) (isl.timeScale * scaleOf(isl)),
                bIds.stream().mapToLong(Long::longValue).toArray(), ba, lIds.stream().mapToLong(Long::longValue).toArray(), la);
        BlockPos any = isl.blocks.iterator().next();
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(any), payload);
        syncSounds(isl, scale, any);
        syncDisplays(isl, any);
    }

    /**
     * Every fourth tick: what the island's bodies on slides sound like (their tones, from their simulated motion), for
     * the clients to play — and once more, empty, when it falls silent.
     */
    private void syncSounds(Island isl, float scale, BlockPos any) {
        if (tickCount % 4 != 0) return;
        var sounds = isl.compiled.sounds();
        if (sounds.isEmpty() && !isl.sounding) return;
        isl.sounding = !sounds.isEmpty();
        List<dev.emergence.mod.net.SoundPayload.Source> out = new ArrayList<>();
        for (var s : sounds) {
            var t = s.tone();
            float[] amp = new float[t.amplitude().length], ph = new float[t.phase().length];
            for (int k = 0; k < amp.length; k++) {
                amp[k] = (float) t.amplitude()[k];
                ph[k] = (float) t.phase()[k];
            }
            out.add(new dev.emergence.mod.net.SoundPayload.Source(s.body(), s.position().x() * scale, s.position().y() * scale,
                    s.position().z() * scale, (float) s.axis().x(), (float) s.axis().y(), (float) s.axis().z(), (float) t.frequency(),
                    (float) t.rmsPressure(), amp, ph));
        }
        long key = any.asLong();
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(any), new dev.emergence.mod.net.SoundPayload(key, 1f / scale, out));
    }

    public static boolean isCrank(PlacedPart p) {
        return p.kind == PartKind.CRANK;
    }
}
