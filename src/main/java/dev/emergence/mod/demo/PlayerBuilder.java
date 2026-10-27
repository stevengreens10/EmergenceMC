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
        Job j = new Job(level, plot, e);
        while (j.step()) {}
        return j.problems();
    }

    /**
     * One exhibit's build a click at a time ({@link #step}), so that it can be watched: each step places one part,
     * puts up one scaffold, or clicks one switch.
     */
    public static final class Job {
        private final ServerLevel level;
        private final BlockPos plot;
        private final Exhibit e;
        private final FakePlayer p;
        private final List<String> problems = new ArrayList<>();
        private final List<Exhibit.PartDef> pending;
        private final Map<Integer, Long> placedId = new java.util.HashMap<>();
        private final Set<BlockPos> scaffold = new LinkedHashSet<>();
        /** The pass over the parts still to place (null: between passes), and whether it has placed any. */
        private java.util.Iterator<Exhibit.PartDef> pass;
        private boolean progress;
        private java.util.Iterator<Integer> latching;
        private boolean done;
        private Exhibit.PartDef last;

        public Job(ServerLevel level, BlockPos plot, Exhibit e) {
            this.level = level;
            this.plot = plot;
            this.e = e;
            p = FakePlayerFactory.get(level, BUILDER);
            p.setGameMode(GameType.CREATIVE);
            pending = new ArrayList<>(e.parts);
        }

        public List<String> problems() {
            return problems;
        }

        /** Parts placed so far (or given up on). */
        public int placed() {
            return e.parts.size() - pending.size();
        }

        /** The part placed last (null: none yet). */
        public Exhibit.PartDef last() {
            return last;
        }

        /** Takes the next step; false once the exhibit is finished. */
        public boolean step() {
            if (done) return false;
            if (latching == null) {
                if (placeNext()) return true;
                for (BlockPos s : scaffold) level.setBlock(s, Blocks.AIR.defaultBlockState(), 3);
                scaffold.clear();
                latching = e.latched.iterator();
            }
            // switches and valves that start closed: a click with an empty hand
            if (latching.hasNext()) {
                latch(latching.next());
                return true;
            }
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            done = true;
            return false;
        }

        /** Whether a segment of the same chip lies just before d along its axis (d is not the chip's first). */
        private boolean chipBefore(Exhibit.PartDef d) {
            for (Exhibit.PartDef q : e.parts) {
                if (q.kind() == PartKind.CHIP && q.axis() == d.axis() && q.x0() == d.x0() - d.axis().dx && q.y0() == d.y0() - d.axis().dy
                        && q.z0() == d.z0() - d.axis().dz && java.util.Objects.equals(q.params().get("function"), d.params().get("function"))) return true;
            }
            return false;
        }

        /** One part placed or one scaffold put up; false once there is nothing more to place. */
        private boolean placeNext() {
            if (pending.isEmpty()) return false;
            if (pass == null) {
                pass = pending.iterator();
                progress = false;
            }
            while (pass.hasNext()) {
                Exhibit.PartDef d = pass.next();
                BlockPos at = plot.offset(d.x0(), d.y0(), d.z0());
                // a shaft goes in before what is mounted on it (whose orientation it would otherwise follow)
                if (d.kind() != PartKind.SHAFT && mountable(d.kind()) && pending.stream().anyMatch(q -> q != d && q.kind() == PartKind.SHAFT
                        && q.x0() == d.x0() && q.y0() == d.y0() && q.z0() == d.z0())) continue;
                // a chip goes down whole from its first segment: the rest of its row comes with it
                if (d.kind() == PartKind.CHIP && chipBefore(d)) {
                    pass.remove();
                    progress = true;
                    continue;
                }
                Long id = place(level, p, at, d, problems, e.id, e.texts.getOrDefault(d.index(), ""));
                if (id == null) continue;
                placedId.put(d.index(), id);
                // a warm start where getting warm would take many minutes (see Exhibit.Initial): set as the part goes
                // in, before anything (a click on a switch) has the machine compiled from its cold parts
                Exhibit.Initial in = e.initial.get(d.index());
                if (in != null && id > 0 && level.getBlockEntity(at) instanceof AssemblyBlockEntity ibe) {
                    double temp = Double.isNaN(in.temperature()) ? dev.emergence.core.units.PhysicalConstants.T_REF : in.temperature();
                    ibe.savedState().put(id, new double[]{temp, in.omega(), in.angle(), 0, in.capacitorVoltage()});
                }
                pass.remove();
                progress = true;
                last = d;
                return true;
            }
            // the pass is over: its scaffolding comes down
            pass = null;
            for (BlockPos s : scaffold) level.setBlock(s, Blocks.AIR.defaultBlockState(), 3);
            scaffold.clear();
            if (pending.isEmpty()) return false;
            if (progress) return true;
            // nothing to click against: a dirt pillar from the ground up beside the lowest stranded part (scaffolding,
            // knocked down once the part is in), in a column that no part still to come needs
            Exhibit.PartDef low = pending.stream().min(java.util.Comparator.comparingInt(Exhibit.PartDef::y0)).get();
            BlockPos lowAt = plot.offset(low.x0(), low.y0(), low.z0());
            Set<BlockPos> wanted = new HashSet<>();
            for (var d : pending) wanted.add(plot.offset(d.x0(), d.y0(), d.z0()));
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
                return true;
            }
            for (var d : pending) problems.add(e.id + ": could not place the " + d.kind() + " at " + cell(d) + " (nothing to click against)");
            pending.clear();
            return false;
        }

        private void latch(int idx) {
            Long id = placedId.get(idx);
            Exhibit.PartDef d = e.parts.get(idx);
            if (id == null) return;
            BlockPos at = plot.offset(d.x0(), d.y0(), d.z0());
            boolean was = level.getBlockEntity(at) instanceof AssemblyBlockEntity b0 && b0.byId(id) != null && b0.byId(id).closed;
            String clicked = click(level, p, at, id);
            if (clicked == null || !(level.getBlockEntity(at) instanceof AssemblyBlockEntity be) || be.byId(id) == null || !be.byId(id).closed) {
                problems.add(e.id + ": could not close the " + d.kind() + " at " + cell(d) + " by clicking it (" + (clicked != null ? clicked : "no face to click")
                        + ", closed before " + was + ")");
            }
        }
    }

    /**
     * Builds a miniature exhibit inside the block {@code at} as a player does with the precision tweezers in the
     * off-hand: each part made in the part editor (its length and cross-section too) and placed with a click. The click
     * lands on the face of the cell behind the part's anchor, the face pointing along the part's axis (so the part runs
     * from its anchor that way, its square cross-section centred on it); a part whose cells take in a shaft's — a
     * bearing, the rotor's plate — is clicked onto that shaft's side and mounted on it. What comes out otherwise than
     * the exhibit defines it is reported.
     */
    public static List<String> buildMiniature(ServerLevel level, BlockPos at, Exhibit e) {
        FakePlayer p = FakePlayerFactory.get(level, BUILDER);
        p.setGameMode(GameType.CREATIVE);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(dev.emergence.mod.registry.ModRegistries.TWEEZERS.get()));
        p.setShiftKeyDown(false);
        List<String> problems = new ArrayList<>();
        List<Exhibit.PartDef> pending = new ArrayList<>(e.parts);
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                Exhibit.PartDef d = it.next();
                // a part to be mounted on a shaft waits for the shaft
                boolean waits = pending.stream().anyMatch(q -> q != d && q.kind() == PartKind.SHAFT && overlaps(q, d));
                if (waits) continue;
                placeMiniature(level, p, at, d, problems, e.id, e.texts.getOrDefault(d.index(), ""));
                it.remove();
                progress = true;
            }
            if (!progress) {
                for (var d : pending) problems.add(e.id + ": the " + d.kind() + " at " + cell(d) + " waits on a shaft that never comes");
                break;
            }
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        // its full-size parts beside it (a screen), placed as any block-scale part, from the lowest up
        List<Exhibit.PartDef> big = new ArrayList<>(e.blockParts);
        big.sort(java.util.Comparator.comparingInt(Exhibit.PartDef::y0));
        for (Exhibit.PartDef d : big) {
            Long id = place(level, p, at.offset(d.x0(), d.y0(), d.z0()), d, problems, e.id, "");
            if (id == null) problems.add(e.id + ": nothing to click against for the full-size " + d.kind() + " at " + cell(d));
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // what came out, against the definitions
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            List<String> want = new ArrayList<>(), got = new ArrayList<>();
            for (var d : e.parts) want.add(d.kind() + " " + d.material() + " " + d.axis() + " " + d.x0() + "," + d.y0() + "," + d.z0()
                    + "–" + d.x1() + "," + d.y1() + "," + d.z1());
            for (PlacedPart q : be.parts()) got.add(q.kind + " " + q.spec.material() + " " + q.axis + " " + q.x0 + "," + q.y0 + "," + q.z0
                    + "–" + q.x1 + "," + q.y1 + "," + q.z1);
            List<String> onlyWant = new ArrayList<>(want), onlyGot = new ArrayList<>(got);
            for (String s : got) onlyWant.remove(s);
            for (String s : want) onlyGot.remove(s);
            if (!onlyWant.isEmpty() || !onlyGot.isEmpty()) problems.add(e.id + ": placed with the tweezers it differs: wanted " + onlyWant + ", got " + onlyGot);
        } else {
            problems.add(e.id + ": nothing was placed");
        }
        return problems;
    }

    private static boolean overlaps(Exhibit.PartDef a, Exhibit.PartDef b) {
        return a.x0() <= b.x1() && b.x0() <= a.x1() && a.y0() <= b.y1() && b.y0() <= a.y1() && a.z0() <= b.z1() && b.z0() <= a.z1();
    }

    private static void placeMiniature(ServerLevel level, FakePlayer p, BlockPos at, Exhibit.PartDef d, List<String> problems, String exhibit, String text) {
        int ax = d.axis().axis();
        int[] lo = {d.x0(), d.y0(), d.z0()}, hi = {d.x1(), d.y1(), d.z1()};
        int len = hi[ax] - lo[ax] + 1, cross = 1;
        for (int k = 0; k < 3; k++) if (k != ax) cross = Math.max(cross, hi[k] - lo[k] + 1);
        ItemStack stack = PartPresets.stack(d.kind(), PartSpec.of("steel_1045", 1, 1));
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        String why = PartSpecEdits.apply(p, InteractionHand.MAIN_HAND, new PartSpec(d.material(), len, cross, d.params(), text));
        if (why != null) {
            problems.add(exhibit + ": the editor refused the " + d.kind() + " at " + cell(d) + ": " + why);
            return;
        }
        stack = p.getMainHandItem();
        // onto a shaft's side, if the part takes in a shaft's cell
        int[] mount = null;
        Dir shaftAxis = null;
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            for (PlacedPart q : be.parts()) {
                if (q.kind != PartKind.SHAFT) continue;
                for (int x = Math.max(lo[0], q.x0); x <= Math.min(hi[0], q.x1) && mount == null; x++)
                    for (int y = Math.max(lo[1], q.y0); y <= Math.min(hi[1], q.y1) && mount == null; y++)
                        for (int z = Math.max(lo[2], q.z0); z <= Math.min(hi[2], q.z1) && mount == null; z++) {
                            mount = new int[]{x, y, z};
                            shaftAxis = q.axis;
                        }
            }
        }
        Vec3 hit;
        Direction face;
        if (mount != null) {
            int sideAxis = (shaftAxis.axis() + 1) % 3;
            face = Direction.values()[Dir.fromAxis(sideAxis, true).ordinal()];
            hit = cellCentre(at, mount).add(face.getStepX() * 0.5 / 16, face.getStepY() * 0.5 / 16, face.getStepZ() * 0.5 / 16);
        } else {
            // the anchor: the part's first cell along its axis, centred across it
            int[] anchor = new int[3];
            for (int k = 0; k < 3; k++) {
                anchor[k] = k == ax ? (d.axis().sign() > 0 ? lo[k] : hi[k]) : lo[k] + (hi[k] - lo[k]) / 2;
            }
            face = Direction.values()[d.axis().ordinal()];
            hit = cellCentre(at, anchor).subtract(face.getStepX() * 0.5 / 16, face.getStepY() * 0.5 / 16, face.getStepZ() * 0.5 / 16);
        }
        InteractionResult r = p.gameMode.useItemOn(p, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, at, false));
        if (!r.consumesAction()) problems.add(exhibit + ": clicking with the " + d.kind() + " placed nothing at " + cell(d) + " (" + r + ")");
    }

    private static Vec3 cellCentre(BlockPos at, int[] c) {
        return new Vec3(at.getX() + (c[0] + 0.5) / 16, at.getY() + (c[1] + 0.5) / 16, at.getZ() + (c[2] + 0.5) / 16);
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
    private static Long place(ServerLevel level, FakePlayer p, BlockPos at, Exhibit.PartDef d, List<String> problems, String exhibit, String text) {
        // the part from the creative tab, then the editor
        ItemStack stack = PartPresets.stack(d.kind(), PartSpec.of("steel_1045", 1, 1));
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        PartSpec spec = new PartSpec(d.material(), 1, 1, d.params(), text);
        String why = PartSpecEdits.apply(p, InteractionHand.MAIN_HAND, spec);
        if (why != null) {
            problems.add(exhibit + ": the editor refused the " + d.kind() + " at " + cell(d) + ": " + why);
            return -1L;
        }
        stack = p.getMainHandItem();
        // sneaking, looking along the part's axis: its orientation (and no switch or valve clicked against is worked);
        // a wire block is placed plainly (sneaking with wire runs a whole wire: WireRouting), its axis meaningless
        boolean wire = d.kind() == PartKind.WIRE || d.kind() == PartKind.INSULATED_WIRE;
        p.setShiftKeyDown(!wire);
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
                // a wire, placed without sneaking, must not be clicked against something a click works (a switch, a valve)
                if (wire && level.getBlockEntity(n) instanceof AssemblyBlockEntity wbe && wbe.parts().stream().anyMatch(q -> q.kind == PartKind.SWITCH
                        || q.kind == PartKind.VALVE || q.kind == PartKind.BRAKE || q.kind == PartKind.CRANK || q.kind == PartKind.CHIP)) continue;
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
        if (got.kind != d.kind() || (got.axis != d.axis() && !wire) || !got.spec.material().equals(d.material()) || !got.spec.params().equals(spec.params())) {
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
