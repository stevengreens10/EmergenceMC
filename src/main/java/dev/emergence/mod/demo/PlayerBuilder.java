package dev.emergence.mod.demo;

import com.mojang.authlib.GameProfile;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PartPresets;
import dev.emergence.mod.part.PartShapes;
import dev.emergence.mod.part.PartSpec;
import dev.emergence.mod.part.PartSpecEdits;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds an exhibit as a player does: each part is taken from the creative tab, set up in the part editor (its
 * material and parameters, through {@link PartSpecEdits#apply} like the editor screen), and placed with a click on a
 * neighbouring face — sneaking and looking along the part's axis, which is how a player chooses its orientation —
 * or, for a part mounted on a shaft, on the shaft's side. The click goes through the server's own item-use path
 * ({@code ServerPlayerGameMode.useItemOn}). Switches and valves that should start closed are then clicked with an
 * empty hand. What does not come out as the exhibit defines it is reported: the exhibit is not buildable by a player.
 */
public final class PlayerBuilder {
    private PlayerBuilder() {}

    private static final GameProfile BUILDER = new GameProfile(UUID.fromString("5e1f0b4d-6a3c-4c1e-9b7f-0e5d1a2b3c4d"), "[Emergence builder]");

    /** Builds exhibit e at plot; returns the problems met (empty: built exactly as defined). */
    public static List<String> build(ServerLevel level, BlockPos plot, Exhibit e) {
        FakePlayer p = FakePlayerFactory.get(level, BUILDER);
        p.setGameMode(GameType.CREATIVE);
        List<String> problems = new ArrayList<>();
        List<Exhibit.PartDef> pending = new ArrayList<>(e.parts);
        Map<Integer, Long> placedId = new java.util.HashMap<>();
        Set<BlockPos> scaffold = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                Exhibit.PartDef d = it.next();
                BlockPos at = plot.offset(d.x0(), d.y0(), d.z0());
                // a shaft goes in before what is mounted on it (whose orientation it would otherwise follow)
                if (d.kind() != PartKind.SHAFT && mountable(d.kind()) && pending.stream().anyMatch(q -> q != d && q.kind() == PartKind.SHAFT
                        && q.x0() == d.x0() && q.y0() == d.y0() && q.z0() == d.z0())) continue;
                Long id = place(level, p, at, d, problems, e.id);
                if (id == null) continue;
                placedId.put(d.index(), id);
                // a warm start where getting warm would take many minutes (see Exhibit.Initial): set as the part goes
                // in, before anything (a click on a switch) has the machine compiled from its cold parts
                Exhibit.Initial in = e.initial.get(d.index());
                if (in != null && id > 0 && level.getBlockEntity(at) instanceof AssemblyBlockEntity ibe) {
                    double temp = Double.isNaN(in.temperature()) ? dev.emergence.core.units.PhysicalConstants.T_REF : in.temperature();
                    ibe.savedState().put(id, new double[]{temp, in.omega(), in.angle(), 0, in.capacitorVoltage()});
                }
                it.remove();
                progress = true;
            }
            for (BlockPos s : scaffold) level.setBlock(s, Blocks.AIR.defaultBlockState(), 3);
            scaffold.clear();
            if (progress || pending.isEmpty()) continue;
            // nothing to click against: a dirt pillar from the ground up beside the lowest stranded part (scaffolding,
            // knocked down once the part is in), in a column that no part still to come needs
            Exhibit.PartDef low = pending.stream().min(java.util.Comparator.comparingInt(Exhibit.PartDef::y0)).get();
            BlockPos lowAt = plot.offset(low.x0(), low.y0(), low.z0());
            Set<BlockPos> wanted = new HashSet<>();
            for (var d : pending) wanted.add(plot.offset(d.x0(), d.y0(), d.z0()));
            boolean grew = false;
            for (Direction f : Direction.Plane.HORIZONTAL) {
                BlockPos top = lowAt.relative(f);
                List<BlockPos> column = new ArrayList<>();
                for (BlockPos s = top; s.getY() >= plot.getY() && level.getBlockState(s).canBeReplaced()
                        && !(level.getBlockEntity(s) instanceof AssemblyBlockEntity) && !wanted.contains(s); s = s.below()) column.add(s);
                // it must stand on the ground (or something solid) and pass no part still to come
                BlockPos foot = column.isEmpty() ? top : column.get(column.size() - 1).below();
                if (column.isEmpty() || !column.get(0).equals(top) || wanted.contains(foot) || level.getBlockState(foot).canBeReplaced()) continue;
                for (BlockPos s : column) {
                    level.setBlock(s, Blocks.DIRT.defaultBlockState(), 3);
                    scaffold.add(s);
                }
                grew = true;
                break;
            }
            if (!grew) {
                for (var d : pending) problems.add(e.id + ": could not place the " + d.kind() + " at " + cell(d) + " (nothing to click against)");
                break;
            }
        }
        for (BlockPos s : scaffold) level.setBlock(s, Blocks.AIR.defaultBlockState(), 3);
        // switches and valves that start closed: a click with an empty hand
        for (int idx : e.latched) {
            Long id = placedId.get(idx);
            Exhibit.PartDef d = e.parts.get(idx);
            if (id == null) continue;
            BlockPos at = plot.offset(d.x0(), d.y0(), d.z0());
            boolean was = level.getBlockEntity(at) instanceof AssemblyBlockEntity b0 && b0.byId(id) != null && b0.byId(id).closed;
            String clicked = click(level, p, at, id);
            if (clicked == null || !(level.getBlockEntity(at) instanceof AssemblyBlockEntity be) || be.byId(id) == null || !be.byId(id).closed) {
                problems.add(e.id + ": could not close the " + d.kind() + " at " + cell(d) + " by clicking it (" + (clicked != null ? clicked : "no face to click")
                        + ", closed before " + was + ")");
            }
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return problems;
    }

    private static String cell(Exhibit.PartDef d) {
        return "(" + d.x0() + ", " + d.y0() + ", " + d.z0() + ")";
    }

    static boolean mountable(PartKind k) {
        return k == PartKind.BEARING || k == PartKind.POTENTIOMETER || k == PartKind.MAGNET || k == PartKind.GEAR
                || k == PartKind.CRANK || k == PartKind.BLOCK || k == PartKind.ROTOR || k == PartKind.ECCENTRIC
                || k == PartKind.LINEAR_BEARING || k == PartKind.NUT || k == PartKind.COIL;
    }

    /**
     * Places one part: makes it in the editor, then clicks where it goes. Returns its id once it is there (problems
     * noted if it came out otherwise than defined), or null if there is nothing to click against yet.
     */
    private static Long place(ServerLevel level, FakePlayer p, BlockPos at, Exhibit.PartDef d, List<String> problems, String exhibit) {
        // the part from the creative tab, then the editor
        ItemStack stack = PartPresets.stack(d.kind(), PartSpec.of("steel_1045", 1, 1));
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        PartSpec spec = new PartSpec(d.material(), 1, 1, d.params());
        String why = PartSpecEdits.apply(p, InteractionHand.MAIN_HAND, spec);
        if (why != null) {
            problems.add(exhibit + ": the editor refused the " + d.kind() + " at " + cell(d) + ": " + why);
            return -1L;
        }
        stack = p.getMainHandItem();
        // sneaking, looking along the part's axis: its orientation (and no switch or valve clicked against is worked)
        p.setShiftKeyDown(true);
        look(p, d.axis());
        Set<Long> before = ids(level, at);
        BlockHitResult hit = null;
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity tbe && mountable(d.kind())
                && tbe.parts().stream().anyMatch(q -> q.kind == PartKind.SHAFT)) {
            // onto the side of the shaft already there
            PlacedPart shaft = tbe.parts().stream().filter(q -> q.kind == PartKind.SHAFT).findFirst().get();
            for (Direction f : Direction.values()) {
                if (f.getAxis().ordinal() == shaft.axis.axis()) continue;
                hit = new BlockHitResult(Vec3.atCenterOf(at).add(f.getStepX() * 0.5, f.getStepY() * 0.5, f.getStepZ() * 0.5), f, at, false);
                break;
            }
        } else {
            for (Direction f : Direction.values()) {
                BlockPos n = at.relative(f.getOpposite());
                if (level.getBlockState(n).canBeReplaced() && !(level.getBlockEntity(n) instanceof AssemblyBlockEntity)) continue;
                // a mountable clicked against a shaft's side would go onto that shaft, not here
                if (mountable(d.kind()) && level.getBlockEntity(n) instanceof AssemblyBlockEntity nbe
                        && nbe.parts().stream().anyMatch(q -> q.kind == PartKind.SHAFT && q.axis.axis() != f.getAxis().ordinal())) continue;
                hit = new BlockHitResult(Vec3.atCenterOf(n).add(f.getStepX() * 0.5, f.getStepY() * 0.5, f.getStepZ() * 0.5), f, n, false);
                break;
            }
        }
        if (hit == null) {
            p.setShiftKeyDown(false);
            return null;
        }
        InteractionResult r = p.gameMode.useItemOn(p, level, stack, InteractionHand.MAIN_HAND, hit);
        p.setShiftKeyDown(false);
        Set<Long> after = ids(level, at);
        after.removeAll(before);
        if (after.isEmpty()) {
            String there = level.getBlockEntity(at) instanceof AssemblyBlockEntity be0
                    ? be0.parts().stream().map(q -> q.kind + " along " + q.axis).toList().toString() : level.getBlockState(at).toString();
            problems.add(exhibit + ": clicking with the " + d.kind() + " placed nothing at " + cell(d) + " (" + r + "; the block holds " + there
                    + ", clicked " + hit.getBlockPos().subtract(at) + " " + hit.getDirection() + ")");
            return -1L;
        }
        long id = after.iterator().next();
        PlacedPart got = ((AssemblyBlockEntity) level.getBlockEntity(at)).byId(id);
        if (got.kind != d.kind() || got.axis != d.axis() || !got.spec.material().equals(d.material()) || !got.spec.params().equals(spec.params())) {
            problems.add(exhibit + ": the " + d.kind() + " at " + cell(d) + " came out as " + got.kind + " along " + got.axis
                    + " (wanted along " + d.axis() + ")");
        }
        return id;
    }

    private static Set<Long> ids(ServerLevel level, BlockPos at) {
        Set<Long> s = new HashSet<>();
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) for (PlacedPart q : be.parts()) s.add(q.id);
        return s;
    }

    /** Turns the player to look along a direction. */
    private static void look(FakePlayer p, Dir a) {
        float yaw = switch (a) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> -90;
            default -> 0;
        };
        float pitch = a == Dir.UP ? -90 : a == Dir.DOWN ? 90 : 0;
        p.setYRot(yaw);
        p.setXRot(pitch);
    }

    /** Right-clicks a placed part with an empty hand, on its outline (a switch closes, a valve opens); null if there is no face to click. */
    private static String click(ServerLevel level, FakePlayer p, BlockPos at, long id) {
        if (!(level.getBlockEntity(at) instanceof AssemblyBlockEntity be) || be.byId(id) == null) return null;
        PlacedPart part = be.byId(id);
        List<AABB> boxes = PartShapes.boxes(be, part);
        if (boxes.isEmpty()) return null;
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setShiftKeyDown(false);
        for (AABB b : boxes) {
            Vec3 c = b.getCenter();
            for (Direction f : Direction.values()) {
                Vec3 on = new Vec3(f.getStepX() > 0 ? b.maxX : f.getStepX() < 0 ? b.minX : c.x, f.getStepY() > 0 ? b.maxY : f.getStepY() < 0 ? b.minY : c.y,
                        f.getStepZ() > 0 ? b.maxZ : f.getStepZ() < 0 ? b.minZ : c.z).add(at.getX(), at.getY(), at.getZ());
                PlacedPart there = be.partHit(on, f);
                if (there == null || there.id != id) continue; // the click would land on something else
                InteractionResult r = p.gameMode.useItemOn(p, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, new BlockHitResult(on, f, at, false));
                return "clicked on its " + f + " face: " + r;
            }
        }
        return null;
    }
}
