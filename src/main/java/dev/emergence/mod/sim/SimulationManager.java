package dev.emergence.mod.sim;

import dev.emergence.core.assembly.Assembly;
import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.AssemblyState;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Part;
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

    public static final class Island {
        final Set<BlockPos> blocks = new HashSet<>();
        final Map<Long, BlockPos> partPos = new HashMap<>();
        CompiledAssembly compiled;
        final Map<Long, Integer> crankTicks = new HashMap<>();
        final Map<BlockPos, Integer> light = new HashMap<>();
        double ambient;
    }

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
        if (isl != null) saveIsland(isl);
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
        for (Island isl : islands) {
            if (isl.compiled == null) continue;
            updateCranks(isl);
            List<Long> failed;
            long t0 = System.nanoTime();
            try {
                failed = isl.compiled.step(TICK_SECONDS);
            } catch (RuntimeException e) {
                EmergenceMod.LOG.error("Simulation of island at {} failed; resetting its state", isl.blocks.iterator().next(), e);
                isl.compiled = compile(isl, AssemblyState.empty());
                continue;
            }
            Profiler.record(System.nanoTime() - t0, isl.compiled.network().size());
            if (!failed.isEmpty()) destroyParts(isl, failed);
            updateLight(isl);
            sync(isl);
            if (tickCount % 100 == 0) saveIsland(isl);
        }
    }

    private void rebuild() {
        // collect affected islands (and their state) then re-flood-fill from dirty positions
        Set<BlockPos> seeds = new HashSet<>(dirty);
        dirty.clear();
        Set<Island> old = new HashSet<>();
        for (BlockPos p : seeds) {
            for (Direction d : Direction.values()) {
                Island i = islandOf.get(p.relative(d));
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
            Deque<BlockPos> q = new ArrayDeque<>(List.of(seed));
            visited.add(seed);
            while (!q.isEmpty()) {
                BlockPos b = q.poll();
                isl.blocks.add(b);
                islandOf.put(b, isl);
                for (Direction d : Direction.values()) {
                    BlockPos n = b.relative(d);
                    if (blocks.containsKey(n) && visited.add(n)) q.add(n);
                }
            }
            AssemblyState st = mergeWithSaved(isl, merged);
            isl.compiled = compile(isl, st);
        }
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
            }
        }
        st.temperatures().putAll(fromOld.temperatures());
        st.bodyMotion().putAll(fromOld.bodyMotion());
        st.coilCurrents().putAll(fromOld.coilCurrents());
        st.capacitorVoltages().putAll(fromOld.capacitorVoltages());
        return st;
    }

    private CompiledAssembly compile(Island isl, AssemblyState state) {
        Assembly asm = new Assembly();
        isl.partPos.clear();
        Map<Long, int[]> anchored = new HashMap<>();
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            for (PlacedPart pp : be.parts()) {
                asm.add(pp.toCore(b));
                isl.partPos.put(pp.id, b);
                if (touchesWorld(b, pp)) anchored.put(pp.id, null);
            }
        }
        isl.ambient = ambientTemperature(isl.blocks.iterator().next());
        asm.ambientTemperature(isl.ambient);
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
    private boolean touchesWorld(BlockPos pos, PlacedPart p) {
        // a thin shaft resting on a block is a point contact (a pivot), not a rigid mounting
        if (p.kind == PartKind.SHAFT) return false;
        for (Direction d : Direction.values()) {
            boolean atFace = switch (d) {
                case DOWN -> p.y0 == 0;
                case UP -> p.y1 == 15;
                case NORTH -> p.z0 == 0;
                case SOUTH -> p.z1 == 15;
                case WEST -> p.x0 == 0;
                case EAST -> p.x1 == 15;
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

    private void updateCranks(Island isl) {
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
        for (long id : ids) {
            BlockPos pos = isl.partPos.get(id);
            AssemblyBlockEntity be = pos == null ? null : be(pos);
            if (be == null) continue;
            PlacedPart p = be.removePart(id);
            if (p == null) continue;
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.7f, 1.2f);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + (p.x0 + 0.5) / 16, pos.getY() + (p.y0 + 0.5) / 16,
                    pos.getZ() + (p.z0 + 0.5) / 16, 8, 0.05, 0.05, 0.05, 0.01);
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

    private void saveIsland(Island isl) {
        if (isl.compiled == null) return;
        AssemblyState s = isl.compiled.exportState();
        for (BlockPos b : isl.blocks) {
            AssemblyBlockEntity be = be(b);
            if (be == null) continue;
            be.savedState().clear();
            for (PlacedPart p : be.parts()) {
                double[] m = s.bodyMotion().getOrDefault(p.id, new double[]{0, 0});
                be.savedState().put(p.id, new double[]{s.temperatures().getOrDefault(p.id, isl.ambient), m[0], m[1],
                        s.coilCurrents().getOrDefault(p.id, 0.0), s.capacitorVoltages().getOrDefault(p.id, 0.0)});
            }
            be.setChanged();
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
                    c.angle(b), c.angularVelocity(b), ids));
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
        long[] all = isl.partPos.keySet().stream().mapToLong(Long::longValue).toArray();
        IslandSyncPayload payload = new IslandSyncPayload(all, bodies, hid, ht, level.getGameTime());
        BlockPos any = isl.blocks.iterator().next();
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(any), payload);
    }

    public static boolean isCrank(PlacedPart p) {
        return p.kind == PartKind.CRANK;
    }
}
