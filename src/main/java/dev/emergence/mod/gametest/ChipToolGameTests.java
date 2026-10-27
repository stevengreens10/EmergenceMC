package dev.emergence.mod.gametest;

import com.mojang.authlib.GameProfile;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.digital.ChipTypes;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.ChipEdits;
import dev.emergence.mod.part.ChipRows;
import dev.emergence.mod.part.PartPresets;
import dev.emergence.mod.part.PartSpec;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.part.WireRouting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/** The tools a player builds chip circuits with: chips placed and taken out whole, designs written onto them, wires run. */
@GameTestHolder(EmergenceMod.MODID)
@PrefixGameTestTemplate(false)
public final class ChipToolGameTests {
    private static final GameProfile TESTER = new GameProfile(UUID.fromString("5f4c2a1e-7b3d-4e8a-9c21-0d6e3b8f1a47"), "[ChipTester]");

    private static FakePlayer player(GameTestHelper h, BlockPos near, float yaw) {
        FakePlayer p = FakePlayerFactory.get(h.getLevel(), TESTER);
        p.setGameMode(GameType.CREATIVE);
        Vec3 at = Vec3.atCenterOf(near);
        p.moveTo(at.x, at.y + 1, at.z, yaw, 30);
        p.setShiftKeyDown(false);
        return p;
    }

    /** Right-clicks the top of the block at {@code on} (absolute) with the stack. */
    private static void clickTop(GameTestHelper h, FakePlayer p, ItemStack stack, BlockPos on) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        p.gameMode.useItemOn(p, h.getLevel(), stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), Direction.UP, on, false));
    }

    private static PlacedPart chipAt(GameTestHelper h, BlockPos abs) {
        if (!(h.getLevel().getBlockEntity(abs) instanceof AssemblyBlockEntity be)) return null;
        return be.parts().stream().filter(q -> q.kind == PartKind.CHIP).findFirst().orElse(null);
    }

    /** A counter chip goes down as its nine blocks, to the right of a player facing north, and comes out whole. */
    @GameTest(template = "arena", timeoutTicks = 40, batch = "chiptools")
    public static void chipPlacesWholeAndComesOutWhole(GameTestHelper h) {
        BlockPos floor = h.absolutePos(new BlockPos(1, 1, 3)), start = floor.above();
        FakePlayer p = player(h, start.south(2), 180); // facing north: its right is east
        ItemStack counter = PartPresets.stack(PartKind.CHIP, new PartSpec("silicon", 1, 1, Map.of("function", 0.0)));
        clickTop(h, p, counter, floor);
        PlacedPart first = chipAt(h, start);
        h.assertTrue(first != null, "a chip segment where clicked");
        var row = ChipRows.row(h.getLevel(), start, first);
        h.assertTrue(row.size() == 9, "the counter's nine blocks in a row, got " + row.size());
        h.assertTrue(row.get(8).pos().equals(start.east(8)), "running east (to the player's right): last at " + row.get(8).pos().subtract(start));
        h.assertTrue(ChipRows.index(row, first) == 0, "the clicked block is its first");
        // blocked: a second counter where it would not fit
        clickTop(h, p, counter, floor.east(3));
        h.assertTrue(chipAt(h, start.east(3).above()) == null, "no chip laid over another's row");
        ChipRows.removeWhole(h.getLevel(), start.east(4), chipAt(h, start.east(4)));
        for (int k = 0; k < 9; k++) h.assertTrue(chipAt(h, start.east(k)) == null, "segment " + k + " gone");
        h.succeed();
    }

    /** Writing a design with more pins onto a placed chip lengthens its row; fewer, shortens it; its parts keep their ids. */
    @GameTest(template = "arena", timeoutTicks = 40, batch = "chiptools")
    public static void chipRowFollowsItsDesignsPins(GameTestHelper h) {
        BlockPos floor = h.absolutePos(new BlockPos(1, 1, 6)), start = floor.above();
        FakePlayer p = player(h, start.south(2), 180);
        String four = "chip c\nin clk\nout [3:0] q\nreg [3:0] n = 0\non rise clk { n <= n + 1 }\nassign q = n\n";
        String eight = four.replace("[3:0] q", "[7:0] q").replace("[3:0] n", "[7:0] n");
        ItemStack chip = PartPresets.stack(PartKind.CHIP, new PartSpec("silicon", 1, 1, Map.of("function", (double) ChipTypes.DESIGN), four));
        clickTop(h, p, chip, floor);
        PlacedPart first = chipAt(h, start);
        h.assertTrue(first != null && ChipRows.row(h.getLevel(), start, first).size() == 5, "a design of 1 input and 4 outputs: 5 blocks");
        String why = ChipEdits.writeText(p, start.east(2), chipAt(h, start.east(2)).id, eight);
        h.assertTrue(why == null, "written: " + why);
        var row = ChipRows.row(h.getLevel(), start, chipAt(h, start));
        h.assertTrue(row.size() == 9, "8 outputs: 9 blocks, got " + row.size());
        h.assertTrue(chipAt(h, start).id == first.id, "its first segment is the same part");
        h.assertTrue(row.stream().allMatch(s -> s.part().spec.text().equals(eight)), "every segment carries the new design");
        why = ChipEdits.writeText(p, start, first.id, four);
        h.assertTrue(why == null && ChipRows.row(h.getLevel(), start, chipAt(h, start)).size() == 5, "and back to 5 blocks");
        h.assertTrue(chipAt(h, start.east(6)) == null, "the blocks it no longer needs are empty");
        // a draft that does not compile is kept as written, the chip's length unchanged
        why = ChipEdits.writeText(p, start, first.id, "chip broken\nin clk\nout q\nassign q = nonsense\n");
        h.assertTrue(why == null && ChipRows.row(h.getLevel(), start, chipAt(h, start)).size() == 5, "a broken draft keeps its row");
        h.succeed();
    }

    /**
     * The miniature counter display: its controller, inside a miniature block against its north face, is linked to the
     * full-size 4 × 2 screen standing against that face (a block-scale machine of its own), and draws the count.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "displaylink")
    public static void miniatureControllerDrivesFullSizeScreen(GameTestHelper h) {
        var ex = dev.emergence.core.demo.MonitorExhibits.miniatureCounterDisplay();
        BlockPos plot = h.absolutePos(new BlockPos(0, 1, 0));
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        BlockPos mini = plot.offset(dev.emergence.mod.demo.DemoBuilder.MINIATURE_AT);
        var sim = dev.emergence.mod.sim.SimulationManager.get(h.getLevel());
        h.runAtTickTime(120, () -> {
            var screens = sim.linkedScreens(mini);
            h.assertTrue(screens.size() == 1 && screens.get(0).length == 8, "one linked screen of 8 panels, got "
                    + screens.stream().map(s -> s.length).toList());
            var c = sim.compiledAt(mini);
            h.assertTrue(c != null, "the miniature is simulated");
            var d = ex.parts.get(ex.named.get("controller"));
            PlacedPart ctl = ((AssemblyBlockEntity) h.getLevel().getBlockEntity(mini)).parts().stream()
                    .filter(q -> q.kind == PartKind.CHIP && q.x0 == d.x0() && q.y0 == d.y0() && q.z0 == d.z0()).findFirst().orElse(null);
            h.assertTrue(ctl != null && c.digitalChip(ctl.id) != null, "its controller is a chip");
            var panel = (dev.emergence.core.digital.DisplayPanel) c.digitalChip(ctl.id).component();
            h.assertTrue(panel.on() && panel.shown() > 100, "it draws: " + panel.on() + ", " + panel.shown() + " pixels");
            h.succeed();
        });
    }

    /** A wire run between two resistors' ends goes round a third part rather than beside it. */
    @GameTest(template = "arena", timeoutTicks = 40, batch = "chiptools")
    public static void wireRunsAroundWhatItShouldNotTouch(GameTestHelper h) {
        BlockPos base = h.absolutePos(new BlockPos(2, 2, 10));
        FakePlayer p = player(h, base.south(3), 180);
        p.setShiftKeyDown(true);
        PartSpec res = PartSpec.of("alumina", 1, 1, "resistance", 100.0);
        // two resistors along x, 6 apart, and a third right on the straight line between them
        for (int x : new int[]{0, 7, 4}) {
            BlockPos at = base.east(x);
            h.getLevel().setBlock(at, dev.emergence.mod.block.AssemblyBlock.stateAt(h.getLevel(), at), 3);
            AssemblyBlockEntity be = (AssemblyBlockEntity) h.getLevel().getBlockEntity(at);
            be.setResolution(AssemblyBlockEntity.BLOCK_SCALE);
            be.addPart(new PlacedPart(9_000_000L + x, PartKind.RESISTOR, res, 0, 0, 0, 0, 0, 0, dev.emergence.core.assembly.Dir.EAST, false));
        }
        PartSpec wire = PartSpec.of("copper", 1, 1, "diameter", 1.5e-3, "color", 3);
        ItemStack stack = PartPresets.stack(PartKind.INSULATED_WIRE, wire);
        // from the first resistor's east end to the second's west end
        WireRouting.click(p, stack, PartKind.INSULATED_WIRE, wire, base, Direction.EAST);
        WireRouting.click(p, stack, PartKind.INSULATED_WIRE, wire, base.east(7), Direction.WEST);
        int laid = 0;
        for (int x = -2; x <= 9; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos q = base.offset(x, y, z);
                    if (!(h.getLevel().getBlockEntity(q) instanceof AssemblyBlockEntity be)) continue;
                    if (be.parts().stream().noneMatch(pp -> pp.kind == PartKind.INSULATED_WIRE)) continue;
                    laid++;
                    // never beside the third resistor
                    h.assertTrue(q.distManhattan(base.east(4)) > 1, "a wire beside the part in the way at " + q.subtract(base));
                }
            }
        }
        h.assertTrue(laid >= 6, "a wire run laid between them, " + laid + " blocks");
        h.assertTrue(h.getLevel().getBlockEntity(base.east(1)) instanceof AssemblyBlockEntity, "it starts in front of the first resistor's end");
        h.assertTrue(h.getLevel().getBlockEntity(base.east(6)) instanceof AssemblyBlockEntity, "and ends in front of the second's");
        h.succeed();
    }
}
