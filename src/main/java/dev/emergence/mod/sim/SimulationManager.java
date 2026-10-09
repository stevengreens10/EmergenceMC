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
        int resolution = AssemblyBlockEntity.MINIATURE;
        /** Simulated seconds per real second: below 1 when the island cannot keep up within its budget. */
        double timeScale = 1;
        /** Smoothed wall-clock cost of this island's tick [ms]. */
        double costMs;

        public double timeScale() {
            return timeScale;
        }
    }

    /**
     * Wall-clock budget per island per server tick [ms]. An island that needs more runs in slow motion (time
     * dilation) instead of stalling the server: physics stays exact, only its pace relative to the world slows.
     */
    public static final double ISLAND_BUDGET_MS = 12;

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
                failed = isl.compiled.step(TICK_SECONDS * isl.timeScale);
            } catch (RuntimeException e) {
                EmergenceMod.LOG.error("Simulation of island at {} failed; resetting its state", isl.blocks.iterator().next(), e);
                isl.compiled = compile(isl, AssemblyState.empty());
                continue;
            }
            long cost = System.nanoTime() - t0;
            Profiler.record(cost, isl.compiled.network().size());
            pace(isl, cost / 1e6);
            if (!failed.isEmpty()) destroyParts(isl, failed);
            updateLight(isl);
            bubbles(isl);
            ventPlumes(isl);
            if (tickCount % 20 == 0) daylight(isl);
            sync(isl);
            if (tickCount % 100 == 0) saveIsland(isl);
        }
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
        isl.compiled.setDaylight(toSun.mul(-1), irr, id -> {
            BlockPos pos = isl.partPos.get(id);
            return pos != null && level.canSeeSky(pos.above());
        });
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
        // block-scale parts interact across air gaps (gear rims reaching into the next blocks, magnets near
        // coils); a symmetric reach keeps island membership independent of the flood order
        int blockReach = 1;
        for (AssemblyBlockEntity be : blocks.values()) {
            if (be.resolution() == AssemblyBlockEntity.BLOCK_SCALE) blockReach = Math.max(blockReach, reach(be));
        }
        for (BlockPos p : seeds) {
            forNeighbours(p, blockReach, n -> {
                Island i = islandOf.get(n);
                if (i != null) old.add(i);
            });
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
            }
            AssemblyState st = mergeWithSaved(isl, merged);
            isl.compiled = compile(isl, st);
        }
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
            }
            st.chemistry().putAll(be.savedChemistry());
        }
        st.temperatures().putAll(fromOld.temperatures());
        st.bodyMotion().putAll(fromOld.bodyMotion());
        st.coilCurrents().putAll(fromOld.coilCurrents());
        st.capacitorVoltages().putAll(fromOld.capacitorVoltages());
        st.chemistry().putAll(fromOld.chemistry());
        st.fluids().putAll(fromOld.fluids());
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
            if (broken.contains(id)) {
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
                        s.coilCurrents().getOrDefault(p.id, 0.0), s.capacitorVoltages().getOrDefault(p.id, 0.0), fl[0], fl[1]});
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
        IslandSyncPayload payload = new IslandSyncPayload(all, bodies, hid, ht, level.getGameTime(), scale,
                tintIds.stream().mapToLong(Long::longValue).toArray(), tints.stream().mapToInt(Integer::intValue).toArray(),
                fIds.stream().mapToLong(Long::longValue).toArray(), ga, pIds.stream().mapToLong(Long::longValue).toArray(), pa);
        BlockPos any = isl.blocks.iterator().next();
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(any), payload);
    }

    public static boolean isCrank(PlacedPart p) {
        return p.kind == PartKind.CRANK;
    }
}
