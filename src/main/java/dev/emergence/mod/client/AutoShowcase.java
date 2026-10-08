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
        int n = Exhibits.all().size();
        BlockPos origin = spawn.offset(-(n * DemoBuilder.PLOT_SPACING) / 2, 0, -4);
        STEPS.add(new Step(60, () -> look(server, origin.getX() + 12.5, origin.getY() + 4, origin.getZ() + 9.5, 180, 25), "showcase-0-overview"));
        var ex = Exhibits.all();
        for (int i = 0; i < n; i++) {
            BlockPos plot = origin.offset(i * DemoBuilder.PLOT_SPACING, 0, 0);
            var e = ex.get(i);
            String id = e.id;
            Runnable view = () -> look(server, plot.getX() + 0.5, plot.getY() + e.viewHeight, plot.getZ() + 0.5 + e.viewDistance,
                    180, (float) e.viewPitch);
            STEPS.add(new Step(50, view, "showcase-" + (i + 1) + "-" + id));
            if (id.equals("glow") || id.equals("fuse")) {
                STEPS.add(new Step(40, () -> closeSwitch(server.overworld(), plot), "showcase-" + (i + 1) + "-" + id + "-discharged",
                        () -> hottest(plot) > 950 || gone(plot)));
            }
            if (id.equals("dcmotor")) {
                STEPS.add(new Step(30, () -> closeSwitch(server.overworld(), plot), "showcase-" + (i + 1) + "-" + id + "-running"));
            }
            if (id.equals("generator") || id.equals("gears")) {
                STEPS.add(new Step(40, () -> crank(server.overworld(), plot), "showcase-" + (i + 1) + "-" + id + "-cranked"));
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

    private static void closeSwitch(ServerLevel level, BlockPos plot) {
        if (level.getBlockEntity(plot) instanceof AssemblyBlockEntity be) {
            PlacedPart s = find(be, PartKind.SWITCH);
            PlacedPart cap = find(be, PartKind.CAPACITOR);
            SimulationManager sim = SimulationManager.get(level);
            if (cap != null) EmergenceMod.LOG.info("Showcase: before discharge at {}: capacitor {}", plot, sim.probe(plot, cap.id));
            if (s != null) EmergenceMod.LOG.info("Showcase: switch now closed={}", sim.toggleSwitch(plot, s.id));
        }
    }

    private static void crank(ServerLevel level, BlockPos plot) {
        if (level.getBlockEntity(plot) instanceof AssemblyBlockEntity be) {
            PlacedPart c = find(be, PartKind.CRANK);
            var compiled = SimulationManager.get(level).compiledAt(plot);
            if (c != null && compiled != null) compiled.setCrankEffort(c.id, 1, true);
        }
    }

    private static double hottest(BlockPos plot) {
        double t = 0;
        if (Minecraft.getInstance().level.getBlockEntity(plot) instanceof AssemblyBlockEntity be) {
            for (PlacedPart p : be.parts()) t = Math.max(t, ClientSimState.temperature(p.id, 0f));
        }
        return t;
    }

    /** True once the fuse wire has been destroyed. */
    private static boolean gone(BlockPos plot) {
        return Minecraft.getInstance().level.getBlockEntity(plot) instanceof AssemblyBlockEntity be
                && be.parts().stream().filter(p -> p.kind == PartKind.WIRE).count() < 2;
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
