package dev.emergence.mod.part;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.block.AssemblyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Running a wire between two points in one go: with insulated (or bare) wire in hand, sneak-right-click a face — a
 * chip's pin, a component's terminal, the end of a wire — then sneak-right-click another. The wire is laid, one block
 * at a time, along the shortest path through free blocks between the cells in front of the two faces. The path never
 * runs beside another part's block (where it could touch a terminal it was not meant for), nor beside a wire it would
 * join — one of the same colour (insulated wires of one colour splice), or any wire for bare wire. Clicking the same
 * face twice, or a third click after a failed route, starts again.
 */
public final class WireRouting {
    private WireRouting() {
    }

    /** The longest run laid at once [blocks], and how far the search looks. */
    static final int MAX_LENGTH = 64, MAX_VISITED = 20_000;

    private record Start(BlockPos cell, String dim) {
    }

    private static final Map<UUID, Start> STARTS = new HashMap<>();

    /** A sneak-right-click with wire on a face. */
    public static void click(ServerPlayer player, ItemStack stack, PartKind kind, PartSpec spec, BlockPos clicked, Direction face) {
        ServerLevel level = player.serverLevel();
        // a click on a wire end starts or ends at that wire itself, else at the free cell in front of the face
        BlockPos cell = isWire(level, clicked, kind, spec) ? clicked : clicked.relative(face);
        String dim = level.dimension().location().toString();
        Start s = STARTS.get(player.getUUID());
        if (s == null || !s.dim().equals(dim) || s.cell().equals(cell)) {
            if (!free(level, cell) && !isWire(level, cell, kind, spec)) {
                say(player, "Start from a face with a free block in front of it.");
                return;
            }
            STARTS.put(player.getUUID(), new Start(cell, dim));
            say(player, "Wire starts here. Sneak-right-click where it should go.");
            return;
        }
        STARTS.remove(player.getUUID());
        List<BlockPos> path = route(level, s.cell(), cell, kind, spec);
        if (path == null) {
            say(player, "No clear path: the wire may not pass beside other parts, or beside a wire it would join. Run it in pieces.");
            return;
        }
        int need = 0;
        for (BlockPos p : path) if (free(level, p)) need++;
        if (!player.getAbilities().instabuild && stack.getCount() < need) {
            say(player, "That run needs " + need + " wire; you hold " + stack.getCount() + ".");
            return;
        }
        for (BlockPos p : path) {
            if (!free(level, p)) continue; // an existing wire end it starts or ends at
            level.setBlock(p, dev.emergence.mod.block.AssemblyBlock.stateAt(level, p), 3);
            if (!(level.getBlockEntity(p) instanceof AssemblyBlockEntity be)) continue;
            be.setResolution(AssemblyBlockEntity.BLOCK_SCALE);
            be.addPart(new PlacedPart(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), kind, spec, 0, 0, 0, 0, 0, 0, Dir.UP, false));
        }
        if (!player.getAbilities().instabuild) stack.shrink(need);
        level.playSound(null, cell, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 1.2f);
        say(player, "Laid " + need + " blocks of wire.");
    }

    /** Forgets a half-made run (the player put the wire away, logged out). */
    public static void cancel(UUID player) {
        STARTS.remove(player);
    }

    private static void say(ServerPlayer p, String s) {
        p.displayClientMessage(Component.literal(s), true);
    }

    static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).canBeReplaced() && level.getBlockEntity(p) == null;
    }

    /** Whether the block holds a wire this wire would join (same kind; for insulated, the same colour). */
    static boolean isWire(ServerLevel level, BlockPos p, PartKind kind, PartSpec spec) {
        if (!(level.getBlockEntity(p) instanceof AssemblyBlockEntity be) || be.resolution() != AssemblyBlockEntity.BLOCK_SCALE) return false;
        for (PlacedPart q : be.parts()) {
            if (q.kind == PartKind.WIRE && kind == PartKind.WIRE) return true;
            if (q.kind == PartKind.INSULATED_WIRE && kind == PartKind.INSULATED_WIRE
                    && q.spec.params().getOrDefault("color", 0.0).equals(spec.params().getOrDefault("color", 0.0))) return true;
        }
        return false;
    }

    /** Whether a block beside the path would connect to it where it should not. */
    private static boolean hazard(ServerLevel level, BlockPos p, PartKind kind, PartSpec spec) {
        if (!(level.getBlockEntity(p) instanceof AssemblyBlockEntity be)) return false;
        for (PlacedPart q : be.parts()) {
            if (q.kind == PartKind.INSULATED_WIRE) {
                // a different colour lies alongside without joining; bare wire joins any wire
                if (kind == PartKind.WIRE || isWire(level, p, kind, spec)) return true;
            } else if (q.kind == PartKind.WIRE) {
                return true;
            } else {
                return true; // a component: its terminals face every way
            }
        }
        return false;
    }

    /**
     * The blocks of the shortest clear path from a to b (both included), or null. Every block between them is free and
     * has nothing beside it that the wire would wrongly join — apart from what is beside the ends, which is what they
     * are meant to touch.
     */
    static List<BlockPos> route(ServerLevel level, BlockPos a, BlockPos b, PartKind kind, PartSpec spec) {
        if (a.distManhattan(b) > MAX_LENGTH) return null;
        Map<BlockPos, BlockPos> from = new HashMap<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        from.put(a, a);
        q.add(a);
        while (!q.isEmpty() && from.size() < MAX_VISITED) {
            BlockPos c = q.poll();
            if (c.equals(b)) break;
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (from.containsKey(n) || n.distManhattan(a) > MAX_LENGTH) continue;
                boolean end = n.equals(b);
                if (!end) {
                    if (!free(level, n)) continue;
                    boolean bad = false;
                    for (Direction e : Direction.values()) {
                        BlockPos m = n.relative(e);
                        if (m.equals(c) || m.equals(b) || m.equals(a)) continue;
                        if (hazard(level, m, kind, spec)) {
                            bad = true;
                            break;
                        }
                    }
                    if (bad) continue;
                } else if (!free(level, n) && !isWire(level, n, kind, spec)) {
                    continue;
                }
                from.put(n, c);
                q.add(n);
            }
        }
        if (!from.containsKey(b)) return null;
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos c = b; ; c = from.get(c)) {
            path.add(0, c);
            if (c.equals(a)) break;
        }
        return path.size() - 1 > MAX_LENGTH ? null : path;
    }

}
