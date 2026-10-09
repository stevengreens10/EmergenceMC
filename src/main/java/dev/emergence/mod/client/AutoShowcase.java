package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.demo.DemoBuilder;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * Automated visual verification of the demo world (enabled with {@code -Demergence.autoShowcase=true},
 * see the {@code showcaseClient} run). After joining it flies the player to each exhibit, triggers the
 * capacitor discharge, saves screenshots to {@code screenshots/showcase-*.png} and quits. It sends no
 * OS-level input, so it is safe to run while someone is using the computer.
 */
public final class AutoShowcase {
    private AutoShowcase() {}

    /** A server action, then a screenshot after {@code delayTicks} or as soon as {@code ready} holds. */
    private record Step(int delayTicks, Runnable server, String shot, java.util.function.BooleanSupplier ready) {
        Step(int delayTicks, Runnable server, String shot) {
            this(delayTicks, server, shot, null);
        }
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index, wait = -1, joinedTicks;

    public static void init() {
        if (!Boolean.getBoolean("emergence.autoShowcase")) return;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
    }

    private static void plan(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        var ex = Exhibits.all();
        int n = ex.size();
        BlockPos origin = spawn.offset(-8, 0, -(DemoBuilder.SIGN_Z + 3));
        STEPS.add(new Step(80, () -> look(server, origin.getX() + 26, origin.getY() + 16, origin.getZ() + 38, 160, 28), "showcase-0-overview"));
        for (int i = 0; i < n; i++) {
            BlockPos plot = origin.offset(DemoBuilder.plotOffset(ex, i), 0, 0);
            var e = ex.get(i);
            String id = e.id;
            Runnable view = () -> look(server, plot.getX() + e.viewX, plot.getY() + e.viewHeight, plot.getZ() + e.viewZ,
                    (float) e.viewYaw, (float) e.viewPitch);
            STEPS.add(new Step(50, () -> {
                LoggerHud.showcase = false;
                view.run();
            }, "showcase-" + (i + 1) + "-" + id));
            if (id.equals("glow") || id.equals("fuse")) {
                BlockPos wire = DemoBuilder.partBlock(plot, e, "wire");
                STEPS.add(new Step(40, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-discharged", () -> hottest(wire) > 950 || gone(wire)));
            }
            if (id.equals("dcmotor") || id.equals("corrosion")) {
                STEPS.add(new Step(id.equals("corrosion") ? 60 : 30, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-on"));
            }
            if (id.equals("turbine")) {
                STEPS.add(new Step(16, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "valve")),
                        "showcase-" + (i + 1) + "-" + id + "-on"));
            }
            if (id.equals("transistor")) {
                STEPS.add(new Step(20, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-on"));
            }
            if (id.equals("nand")) {
                STEPS.add(new Step(20, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "a")),
                        "showcase-" + (i + 1) + "-" + id + "-a"));
                STEPS.add(new Step(20, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "b")),
                        "showcase-" + (i + 1) + "-" + id + "-ab"));
            }
            if (id.equals("thermostat")) {
                STEPS.add(new Step(60, () -> { }, "showcase-" + (i + 1) + "-" + id + "-later"));
            }
            if (id.equals("counter")) {
                STEPS.add(new Step(110, () -> { }, "showcase-" + (i + 1) + "-" + id + "-later"));
            }
            if (id.equals("stirling")) {
                STEPS.add(new Step(100, () -> { }, "showcase-" + (i + 1) + "-" + id + "-running"));
            }
            if (id.equals("shelves")) {
                // a second lead block on the steel and the glass shelf: past yield / fracture they break
                STEPS.add(new Step(60, () -> {
                    DemoBuilder.addPart(server.overworld(), plot, 5, 5, 5, dev.emergence.core.assembly.PartKind.BLOCK, "lead",
                            dev.emergence.core.assembly.Dir.UP);
                    DemoBuilder.addPart(server.overworld(), plot, 5, 5, 11, dev.emergence.core.assembly.PartKind.BLOCK, "lead",
                            dev.emergence.core.assembly.Dir.UP);
                }, "showcase-" + (i + 1) + "-" + id + "-overloaded"));
            }
            if (id.equals("computer")) {
                STEPS.add(new Step(200, () -> { }, "showcase-" + (i + 1) + "-" + id + "-running"));
                STEPS.add(new Step(200, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "sense")),
                        "showcase-" + (i + 1) + "-" + id + "-reverse"));
            }
            if (id.equals("steam")) {
                STEPS.add(new Step(80, () -> { }, "showcase-" + (i + 1) + "-" + id + "-running"));
                STEPS.add(new Step(80, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "regulator")),
                        "showcase-" + (i + 1) + "-" + id + "-shut"));
            }
            if (id.equals("servo")) {
                // turn the knob down to its stop: the arm rises against gravity to just above horizontal
                STEPS.add(new Step(220, () -> crank(server.overworld(), DemoBuilder.partBlock(plot, e, "crank"), -1),
                        "showcase-" + (i + 1) + "-" + id + "-raised"));
            }
            if (id.equals("pressure")) {
                STEPS.add(new Step(200, () -> { }, "showcase-" + (i + 1) + "-" + id + "-later"));
            }
            if (id.equals("generator")) {
                // the data logger clipped to the load resistor: its screen shows the alternating EMF
                STEPS.add(new Step(30, () -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.item.ItemStack(dev.emergence.mod.registry.ModRegistries.DATA_LOGGER.get()));
                    BlockPos at = DemoBuilder.partBlock(plot, e, "load");
                    if (server.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be) {
                        PlacedPart load = find(be, PartKind.RESISTOR);
                        if (load != null) SimulationManager.get(server.overworld()).attachLogger(p, at, load.id, "Resistor (load, 20 Ω)");
                    }
                    crank(server.overworld(), DemoBuilder.partBlock(plot, e, "crank"));
                    LoggerHud.showcase = true;
                }, "showcase-" + (i + 1) + "-" + id + "-scope"));
            }
            if (id.equals("generator") || id.equals("gears") || id.equals("miniature") || id.equals("fan") || id.equals("lightbarrier")) {
                STEPS.add(new Step(40, () -> crank(server.overworld(), DemoBuilder.partBlock(plot, e, "crank")),
                        "showcase-" + (i + 1) + "-" + id + "-cranked"));
            }
        }
    }

    private static void look(net.minecraft.server.MinecraftServer server, double x, double y, double z, float yaw, float pitch) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        // spectator: no collision, so the eye can be placed anywhere (y is the eye height)
        p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        p.teleportTo(server.overworld(), x, y - p.getEyeHeight(), z, yaw, pitch);
    }

    private static PlacedPart find(AssemblyBlockEntity be, PartKind k) {
        for (PlacedPart p : be.parts()) if (p.kind == k) return p;
        return null;
    }

    private static void toggle(ServerLevel level, BlockPos at) {
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            PlacedPart s = find(be, PartKind.SWITCH);
            if (s == null) s = find(be, PartKind.VALVE);
            if (s != null) EmergenceMod.LOG.info("Showcase: switch at {} now closed={}", at, SimulationManager.get(level).toggleSwitch(at, s.id));
        }
    }

    private static void crank(ServerLevel level, BlockPos at) {
        crank(level, at, 1);
    }

    /** Leaves a hand on the crank, turning it in direction {@code dir} (it keeps pushing until the island rebuilds). */
    private static void crank(ServerLevel level, BlockPos at, int dir) {
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            PlacedPart c = find(be, PartKind.CRANK);
            var compiled = SimulationManager.get(level).compiledAt(at);
            if (c != null && compiled != null) compiled.setCrankEffort(c.id, dir, dir > 0);
        }
    }

    private static double hottest(BlockPos at) {
        double t = 0;
        if (Minecraft.getInstance().level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            for (PlacedPart p : be.parts()) t = Math.max(t, ClientSimState.temperature(p.id, 0f));
        }
        return t;
    }

    /** True once the fuse wire has been destroyed. */
    private static boolean gone(BlockPos at) {
        return !(Minecraft.getInstance().level.getBlockEntity(at) instanceof AssemblyBlockEntity be)
                || be.parts().stream().noneMatch(p -> p.kind == PartKind.WIRE);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (joinedTicks++ == 0) {
            mc.options.hideGui = true;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            plan(mc);
        }
        if (index >= STEPS.size()) {
            if (wait-- == 0) mc.stop();
            if (wait < -5) wait = 40;
            return;
        }
        Step s = STEPS.get(index);
        if (wait < 0) {
            mc.getSingleplayerServer().execute(s.server);
            wait = s.delayTicks;
        } else if (s.ready != null && wait > 2 && wait < s.delayTicks - 1 && s.ready.getAsBoolean()) {
            wait = 2; // condition met: grab after a couple of frames have rendered the new state
        } else if (wait-- == 0) {
            Screenshot.grab(mc.gameDirectory, s.shot + ".png", mc.getMainRenderTarget(),
                    msg -> EmergenceMod.LOG.info("Showcase screenshot: {}", msg.getString()));
            index++;
            wait = -1;
        }
    }
}
