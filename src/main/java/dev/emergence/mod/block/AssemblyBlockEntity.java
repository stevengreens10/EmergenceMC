package dev.emergence.mod.block;

import dev.emergence.core.assembly.Part;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.registry.ModRegistries;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the primitive parts placed within one block space and their persisted dynamic state.
 * Physics is not run here: the {@link SimulationManager} compiles connected assembly blocks into
 * islands and simulates them.
 */
public final class AssemblyBlockEntity extends BlockEntity {
    private final List<PlacedPart> parts = new ArrayList<>();
    /** Persisted dynamic state per part: {T, ω, θ, coil current, capacitor voltage}. */
    private final Map<Long, double[]> savedState = new HashMap<>();
    /** Persisted chemistry state of this block's parts (keys as in AssemblyState.chemistry). */
    private final Map<String, Double> savedChemistry = new HashMap<>();
    /**
     * Cells per block edge: {@link #BLOCK_SCALE} (one part per block, the default) or {@link #MINIATURE}
     * (16³ cells per block). A cell is always 1/16 m physically.
     */
    private int resolution = MINIATURE;
    public static final int BLOCK_SCALE = 1, MINIATURE = 16;
    private VoxelShape shape;

    public AssemblyBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.ASSEMBLY_BE.get(), pos, state);
    }

    public List<PlacedPart> parts() {
        return Collections.unmodifiableList(parts);
    }

    public Map<Long, double[]> savedState() {
        return savedState;
    }

    public Map<String, Double> savedChemistry() {
        return savedChemistry;
    }

    public int resolution() {
        return resolution;
    }

    /** Sets the grid resolution of a freshly placed (empty) assembly block. */
    public void setResolution(int res) {
        if (parts.isEmpty()) resolution = res;
    }

    public PlacedPart partAt(int x, int y, int z) {
        PlacedPart best = null;
        for (PlacedPart p : parts) {
            if (p.contains(x, y, z)) {
                // prefer the smaller part (e.g. a bearing on a shaft)
                if (best == null || volume(p) < volume(best)) best = p;
            }
        }
        return best;
    }

    private static int volume(PlacedPart p) {
        return (p.x1 - p.x0 + 1) * (p.y1 - p.y0 + 1) * (p.z1 - p.z0 + 1);
    }

    /**
     * The part a player is pointing at: the one whose outline the hit point lies on (a crank sharing its cell with the
     * shaft end it is fixed to is the crank where its handle is hit, the shaft where the shaft is) — else the part in
     * that cell. {@code hit} is in world coordinates.
     */
    public PlacedPart partHit(net.minecraft.world.phys.Vec3 hit, net.minecraft.core.Direction face) {
        net.minecraft.world.phys.Vec3 local = hit.subtract(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
        PlacedPart best = null;
        double bestGap = 2e-3;
        for (PlacedPart p : parts) {
            for (net.minecraft.world.phys.AABB b : dev.emergence.mod.part.PartShapes.boxes(this, p)) {
                if (!b.inflate(2e-3).contains(local)) continue;
                // how far the point is from the box's surface: the box that was hit has it on its surface
                double gap = Math.min(Math.min(Math.min(Math.abs(local.x - b.minX), Math.abs(local.x - b.maxX)),
                                Math.min(Math.abs(local.y - b.minY), Math.abs(local.y - b.maxY))),
                        Math.min(Math.abs(local.z - b.minZ), Math.abs(local.z - b.maxZ)));
                if (gap < bestGap) {
                    bestGap = gap;
                    best = p;
                }
            }
        }
        if (best != null) return best;
        int[] c = dev.emergence.mod.block.AssemblyBlock.cellAt(this, hit, face);
        return partAt(c[0], c[1], c[2]);
    }

    public PlacedPart byId(long id) {
        for (PlacedPart p : parts) if (p.id == id) return p;
        return null;
    }

    /**
     * Overlap rules: a shaft may pass through bearings, potentiometers, gears, disks, cranks, rotors and magnets
     * (a rotor magnet), and through pipes, valves and tanks (in a gland seal); nothing else may share cells.
     */
    public static boolean mayOverlap(PartKind a, PartKind b) {
        if (a == PartKind.SHAFT) return b == PartKind.BEARING || b == PartKind.POTENTIOMETER || b == PartKind.MAGNET
                || b == PartKind.GEAR || b == PartKind.BLOCK || b == PartKind.CRANK
                || b == PartKind.ECCENTRIC || b == PartKind.LINEAR_BEARING || b == PartKind.NUT || b == PartKind.COIL || (b.holdsFluid() && b != PartKind.CYLINDER);
        if (b == PartKind.SHAFT) return mayOverlap(b, a);
        return false;
    }

    public boolean canPlace(PartKind kind, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (x0 < 0 || y0 < 0 || z0 < 0 || x1 >= resolution || y1 >= resolution || z1 >= resolution) return false;
        for (PlacedPart p : parts) {
            boolean overlap = p.x0 <= x1 && x0 <= p.x1 && p.y0 <= y1 && y0 <= p.y1 && p.z0 <= z1 && z0 <= p.z1;
            if (overlap && !mayOverlap(kind, p.kind)) return false;
        }
        return true;
    }

    public void addPart(PlacedPart p) {
        parts.add(p);
        changed();
    }

    /** Gives a part new settings in place (the same part: its id, cells and state kept). */
    public void replaceSpec(long id, dev.emergence.mod.part.PartSpec spec) {
        for (int i = 0; i < parts.size(); i++) {
            PlacedPart p = parts.get(i);
            if (p.id != id) continue;
            parts.set(i, new PlacedPart(p.id, p.kind, spec, p.x0, p.y0, p.z0, p.x1, p.y1, p.z1, p.axis, p.closed));
            changed();
            return;
        }
    }

    public PlacedPart removePart(long id) {
        PlacedPart p = byId(id);
        if (p != null) {
            parts.remove(p);
            savedState.remove(id);
            changed();
        }
        return p;
    }

    private void changed() {
        reshape();
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (level instanceof ServerLevel sl) SimulationManager.get(sl).markDirty(worldPosition);
        }
    }

    /**
     * Outline and collision shape: the parts' drawn geometry (see {@link dev.emergence.mod.part.PartShapes}), so a thin
     * shaft or wire is thin to the crosshair and to walk past. Never empty while there are parts (something to hit).
     */
    public VoxelShape shape() {
        if (shape == null) {
            // a crowded block (a circuit built in miniature: hundreds of parts) is shaped by its parts' whole cells — on
            // the 1/16 grid every union stays small — rather than by each part's drawn geometry
            boolean coarse = parts.size() > DENSE;
            List<AABB> boxes = new ArrayList<>();
            for (PlacedPart p : parts) {
                if (coarse) boxes.add(p.localBox(resolution));
                else boxes.addAll(dev.emergence.mod.part.PartShapes.boxes(this, p));
            }
            if (boxes.isEmpty()) {
                shape = parts.isEmpty() ? Shapes.empty() : Shapes.box(0.4, 0.4, 0.4, 0.6, 0.6, 0.6);
            } else {
                // joined unoptimised and optimised once (optimising after every join made a big block's shape cost the
                // cube of its parts)
                VoxelShape s = Shapes.empty();
                for (AABB b : boxes) s = Shapes.joinUnoptimized(s, Shapes.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ), BooleanOp.OR);
                shape = s.optimize();
            }
        }
        return shape;
    }

    /** More parts than this in one block, and its shape is its parts' cells (see {@link #shape}). */
    static final int DENSE = 48;

    /** Forget this block's shape and its neighbours' (wires, pipes and plates are shaped by what they meet). */
    private void reshape() {
        shape = null;
        if (level == null) return;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(d)) instanceof AssemblyBlockEntity o) o.shape = null;
        }
    }

    public List<Part> coreParts() {
        List<Part> l = new ArrayList<>(parts.size());
        for (PlacedPart p : parts) l.add(p.toCore(worldPosition, resolution));
        return l;
    }

    public Set<Long> partIds() {
        Set<Long> s = new java.util.HashSet<>();
        for (PlacedPart p : parts) s.add(p.id);
        return s;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel sl) SimulationManager.get(sl).register(worldPosition, this);
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel sl) SimulationManager.get(sl).unregister(worldPosition);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level instanceof ServerLevel sl) SimulationManager.get(sl).unregister(worldPosition);
        super.onChunkUnloaded();
    }

    // ------------------------------------------------------------------ persistence / sync

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeParts(tag);
        ListTag st = new ListTag();
        for (var e : savedState.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("id", e.getKey());
            double[] v = e.getValue();
            t.putDouble("T", v[0]);
            t.putDouble("w", v[1]);
            t.putDouble("a", v[2]);
            t.putDouble("i", v[3]);
            t.putDouble("v", v[4]);
            if (v.length > 6 && v[5] > 0) {
                t.putDouble("p", v[5]);
                t.putDouble("Tf", v[6]);
                // a water/steam volume's phase (how much of it is water): without it, a reload kept pressures and
                // temperatures and lost every level
                if (v.length > 8 && Double.isFinite(v[8])) t.putDouble("s", v[8]);
            }
            if (v.length > 7 && v[7] > 0) t.putDouble("D", v[7]);
            st.add(t);
        }
        tag.put("state", st);
        CompoundTag chem = new CompoundTag();
        for (var e : savedChemistry.entrySet()) chem.putDouble(e.getKey(), e.getValue());
        tag.put("chem", chem);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readParts(tag);
        savedState.clear();
        for (Tag x : tag.getList("state", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x;
            savedState.put(t.getLong("id"), new double[]{t.getDouble("T"), t.getDouble("w"), t.getDouble("a"), t.getDouble("i"),
                    t.getDouble("v"), t.getDouble("p"), t.getDouble("Tf"), t.getDouble("D"), t.contains("s") ? t.getDouble("s") : Double.NaN});
        }
        savedChemistry.clear();
        CompoundTag chem = tag.getCompound("chem");
        for (String k : chem.getAllKeys()) savedChemistry.put(k, chem.getDouble(k));
        shape = null;
    }

    private void writeParts(CompoundTag tag) {
        tag.putInt("res", resolution);
        ListTag list = new ListTag();
        for (PlacedPart p : parts) list.add(p.save());
        tag.put("parts", list);
    }

    private void readParts(CompoundTag tag) {
        resolution = tag.contains("res") ? tag.getInt("res") : MINIATURE;
        parts.clear();
        for (Tag t : tag.getList("parts", Tag.TAG_COMPOUND)) parts.add(PlacedPart.load((CompoundTag) t));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        writeParts(t);
        return t;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readParts(tag);
        reshape();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        CompoundTag t = pkt.getTag();
        if (t != null) {
            readParts(t);
            reshape();
        }
    }
}
