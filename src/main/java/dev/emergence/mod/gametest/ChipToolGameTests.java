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

    /**
     * The same exhibit's ribbon cable: the miniature block and the full-size ribbon against its east face are one
     * machine, and the count reaches the full-size LEDs through it — each LED in turn lit, as the count goes round.
     */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "ribbon")
    public static void ribbonCarriesTheCountToFullSizeLeds(GameTestHelper h) {
        var ex = dev.emergence.core.demo.MonitorExhibits.miniatureCounterDisplay();
        BlockPos plot = h.absolutePos(new BlockPos(0, 1, 0));
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        BlockPos mini = plot.offset(dev.emergence.mod.demo.DemoBuilder.MINIATURE_AT);
        var sim = dev.emergence.mod.sim.SimulationManager.get(h.getLevel());
        java.util.function.IntFunction<BlockPos> led = k -> {
            var d = ex.blockParts.get(ex.namedBlock.get("led" + k));
            return mini.offset(d.x0(), d.y0(), d.z0());
        };
        int[] seen = new int[1];
        h.onEachTick(() -> {
            var c = sim.compiledAt(mini);
            if (c == null) return;
            for (int k = 0; k < 4; k++) {
                BlockPos at = led.apply(k);
                if (!(h.getLevel().getBlockEntity(at) instanceof AssemblyBlockEntity be)) continue;
                PlacedPart p = be.parts().stream().filter(q -> q.kind == PartKind.LED).findFirst().orElse(null);
                var pr = p == null ? null : c.probe(p.id);
                if (pr != null && Math.abs(pr.current()) > 1e-3) seen[0] |= 1 << k;
            }
        });
        h.runAtTickTime(380, () -> {
            var c = sim.compiledAt(mini);
            var ribbon = ex.blockParts.get(ex.namedBlock.get("ribbon"));
            h.assertTrue(c != null && c == sim.compiledAt(mini.offset(ribbon.x0(), ribbon.y0(), ribbon.z0())), "the miniature and its ribbon are one machine");
            h.assertTrue(c.warnings().isEmpty(), "no warnings: " + c.warnings());
            h.assertTrue(seen[0] == 15, "every full-size LED lit at some count, seen " + Integer.toBinaryString(seen[0]));
            h.succeed();
        });
    }

    /**
     * Across scales in the world: the full-size crank on the miniature generator's block turns the miniature shaft —
     * the two blocks one machine, their shafts coupled across the face — and the miniature generator lights its load.
     */
    @GameTest(template = "arena", timeoutTicks = 300, batch = "acrossscales")
    public static void aFullSizeCrankTurnsAMiniatureGenerator(GameTestHelper h) {
        var ex = dev.emergence.core.demo.ScaleExhibits.crankAcrossScales();
        BlockPos plot = h.absolutePos(new BlockPos(0, 1, 0));
        dev.emergence.mod.demo.DemoBuilder.place(h.getLevel(), plot, ex);
        BlockPos mini = plot.offset(dev.emergence.mod.demo.DemoBuilder.MINIATURE_AT);
        var cd = ex.blockParts.get(ex.namedBlock.get("crank"));
        BlockPos crankAt = mini.offset(cd.x0(), cd.y0(), cd.z0());
        var sim = dev.emergence.mod.sim.SimulationManager.get(h.getLevel());
        double[] peak = {0};
        java.util.function.Function<PartKind, PlacedPart> in = k -> {
            BlockPos at = k == PartKind.CRANK ? crankAt : mini;
            return h.getLevel().getBlockEntity(at) instanceof AssemblyBlockEntity be
                    ? be.parts().stream().filter(q -> q.kind == k).findFirst().orElse(null) : null;
        };
        h.onEachTick(() -> {
            var c = sim.compiledAt(mini);
            PlacedPart crank = in.apply(PartKind.CRANK), load = in.apply(PartKind.RESISTOR);
            if (c == null || crank == null || load == null) return;
            c.setCrankTorque(crank.id, 1.0);
            var pr = c.probe(load.id);
            if (pr != null && Double.isFinite(pr.current())) peak[0] = Math.max(peak[0], Math.abs(pr.current()));
        });
        h.runAtTickTime(280, () -> {
            var c = sim.compiledAt(mini);
            h.assertTrue(c != null && c == sim.compiledAt(crankAt), "the miniature block and the full-size crank are one machine");
            h.assertTrue(c.shaftCouplings() == 1, "their shafts are coupled across the face: " + c.shaftCouplings() + " " + c.warnings());
            PlacedPart crank = in.apply(PartKind.CRANK), shaft = in.apply(PartKind.SHAFT);
            double big = Math.abs(c.probe(crank.id).angularVelocity()), small = Math.abs(c.probe(shaft.id).angularVelocity());
            h.assertTrue(big > 2, "the full-size crank turns: " + big + " rad/s");
            h.assertTrue(Math.abs(big - small) < 0.05 * big, "the miniature shaft turns with it: " + big + " / " + small + " rad/s");
            h.assertTrue(peak[0] > 1e-3, "the miniature generator drives its load: " + peak[0] + " A");
            h.succeed();
        });
    }

    /** Places a part item by clicking the top of the block below {@code at}, the player sneaking and looking along {@code axis}. */
    private static void placeAt(GameTestHelper h, FakePlayer p, ItemStack stack, BlockPos at, Direction axis) {
        p.setShiftKeyDown(true);
        float yaw = switch (axis) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            default -> -90;
        };
        p.setYRot(yaw);
        p.setXRot(0);
        clickTop(h, p, stack, at.below());
        p.setShiftKeyDown(false);
    }

    /** A sneak-click wire run from the free cell against face {@code fa} of block {@code a} to that against {@code fb} of {@code b}. */
    private static void run(FakePlayer p, PartSpec wire, BlockPos a, Direction fa, BlockPos b, Direction fb) {
        ItemStack stack = PartPresets.stack(PartKind.INSULATED_WIRE, wire);
        WireRouting.click(p, stack, PartKind.INSULATED_WIRE, wire, a, fa);
        WireRouting.click(p, stack, PartKind.INSULATED_WIRE, wire, b, fb);
    }

    /**
     * The whole path a player takes to a working chip circuit, with the tools only: a battery, a crystal clock and a
     * chip of their own design (a blinker) placed from items — the chips whole — an LED and its resistor, every
     * connection a sneak-click wire run; the LED blinks. Then a new design written onto the placed chip, as the chip
     * designer does: the LED does what it says.
     */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "workflow")
    public static void playerBuildsAWorkingChipCircuit(GameTestHelper h) {
        BlockPos o = h.absolutePos(new BlockPos(1, 2, 1));
        FakePlayer p = player(h, o.offset(5, 0, 12), 180);
        p.setPos(o.getX() + 5.5, o.getY() + 1, o.getZ() + 12.5);
        String blink = "chip blink\nin clk\nout led\nreg lit = 0\non rise clk { lit <= !lit }\nassign led = lit\n";
        String steady = "chip steady\nin clk\nout led\nassign led = 1\n";
        BlockPos bat = o, clock = o.offset(3, 0, 3), chip = o.offset(7, 0, 3), led = o.offset(7, 0, 7), res = o.offset(7, 0, 8);
        placeAt(h, p, PartPresets.stack(PartKind.BATTERY, new PartSpec("pvc", 1, 1, Map.of("chemistry", 1.0, "cells", 4.0, "capacity", 2.0))), bat, Direction.EAST);
        // chips: placed facing north, so each runs east (to the player's right), inputs on the north side
        p.setYRot(180);
        clickTop(h, p, PartPresets.stack(PartKind.CHIP, new PartSpec("silicon", 1, 1, Map.of("function", 6.0, "frequency", 4.0))), clock.below());
        clickTop(h, p, PartPresets.stack(PartKind.CHIP, new PartSpec("silicon", 1, 1, Map.of("function", (double) ChipTypes.DESIGN), blink)), chip.below());
        placeAt(h, p, PartPresets.stack(PartKind.LED, new PartSpec("silicon", 1, 1, Map.of("wavelength", 630.0))), led, Direction.SOUTH);
        placeAt(h, p, PartPresets.stack(PartKind.RESISTOR, new PartSpec("alumina", 1, 1, Map.of("resistance", 470.0))), res, Direction.SOUTH);
        h.assertTrue(chipAt(h, clock) != null && chipAt(h, clock.east()) != null, "the clock went down whole");
        h.assertTrue(chipAt(h, chip) != null && chipAt(h, chip.east()) != null, "the blinker went down whole");
        PartSpec vdd = PartSpec.of("copper", 1, 1, "color", 1), gnd = PartSpec.of("copper", 1, 1, "color", 2),
                clk = PartSpec.of("copper", 1, 1, "color", 3), out = PartSpec.of("copper", 1, 1, "color", 4);
        p.setShiftKeyDown(true);
        run(p, clk, clock, Direction.SOUTH, chip, Direction.NORTH);             // the clock's output to the chip's clk
        run(p, out, chip, Direction.SOUTH, led, Direction.NORTH);               // its led output to the LED
        run(p, vdd, bat, Direction.EAST, clock.east(), Direction.SOUTH);        // + to the clock's V+
        run(p, vdd, clock.east().south(), Direction.UP, chip.east(), Direction.SOUTH); // and on to the chip's
        run(p, gnd, bat, Direction.WEST, clock.east(), Direction.NORTH);        // − to the clock's GND
        run(p, gnd, clock.east().north(), Direction.UP, chip.east(), Direction.NORTH); // the chip's
        run(p, gnd, chip.east().north(), Direction.UP, res, Direction.SOUTH);   // and the resistor's foot
        p.setShiftKeyDown(false);
        var sim = dev.emergence.mod.sim.SimulationManager.get(h.getLevel());
        int[] flips = {0};
        boolean[] lastOn = {false}, steadyOn = {true};
        long[] ledId = {-1};
        h.onEachTick(() -> {
            var c = sim.compiledAt(led);
            if (c == null || !(h.getLevel().getBlockEntity(led) instanceof AssemblyBlockEntity be)) return;
            if (ledId[0] < 0) ledId[0] = be.parts().stream().filter(q -> q.kind == PartKind.LED).findFirst().map(q -> q.id).orElse(-1L);
            var pr = ledId[0] < 0 ? null : c.probe(ledId[0]);
            if (pr == null) return;
            boolean on = Math.abs(pr.current()) > 1e-3;
            if (h.getTick() < 200) {
                if (on != lastOn[0]) flips[0]++;
                lastOn[0] = on;
            } else if (h.getTick() > 300 && !on) steadyOn[0] = false;
        });
        h.runAtTickTime(200, () -> {
            h.assertTrue(flips[0] >= 4, "the LED blinked (" + flips[0] + " changes)");
            String why = ChipEdits.writeText(p, chip, chipAt(h, chip).id, steady);
            h.assertTrue(why == null, "the new design written: " + why);
        });
        h.runAtTickTime(390, () -> {
            h.assertTrue(steadyOn[0], "with the new design the LED stays lit");
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
