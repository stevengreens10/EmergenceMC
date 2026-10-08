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

    public PlacedPart byId(long id) {
        for (PlacedPart p : parts) if (p.id == id) return p;
        return null;
    }

    /** Overlap rules: a shaft may pass through bearings, gears, disks and cranks; nothing else may share cells. */
    public static boolean mayOverlap(PartKind a, PartKind b) {
        if (a == PartKind.SHAFT) return b == PartKind.BEARING || b == PartKind.GEAR || b == PartKind.BLOCK || b == PartKind.CRANK;
        if (b == PartKind.SHAFT) return mayOverlap(b, a);
        return false;
    }

    public boolean canPlace(PartKind kind, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (x0 < 0 || y0 < 0 || z0 < 0 || x1 > 15 || y1 > 15 || z1 > 15) return false;
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
        shape = null;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (level instanceof ServerLevel sl) SimulationManager.get(sl).markDirty(worldPosition);
        }
    }

    public VoxelShape shape() {
        if (shape == null) {
            VoxelShape s = Shapes.empty();
            for (PlacedPart p : parts) {
                AABB b = p.localBox();
                s = Shapes.or(s, Shapes.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
            }
            shape = s;
        }
        return shape;
    }

    public List<Part> coreParts() {
        List<Part> l = new ArrayList<>(parts.size());
        for (PlacedPart p : parts) l.add(p.toCore(worldPosition));
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
            st.add(t);
        }
        tag.put("state", st);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readParts(tag);
        savedState.clear();
        for (Tag x : tag.getList("state", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x;
            savedState.put(t.getLong("id"), new double[]{t.getDouble("T"), t.getDouble("w"), t.getDouble("a"), t.getDouble("i"), t.getDouble("v")});
        }
        shape = null;
    }

    private void writeParts(CompoundTag tag) {
        ListTag list = new ListTag();
        for (PlacedPart p : parts) list.add(p.save());
        tag.put("parts", list);
    }

    private void readParts(CompoundTag tag) {
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
        shape = null;
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
            shape = null;
        }
    }
}
