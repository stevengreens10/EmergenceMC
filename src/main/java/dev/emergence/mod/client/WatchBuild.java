package dev.emergence.mod.client;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.demo.DemoBuilder;
import dev.emergence.mod.demo.WatchedBuild;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * Watching the demo gallery being built ({@code -Demergence.watchBuild=true}, the {@code runWatchDemoBuild} task):
 * from the title screen the client makes a fresh flat creative world, "Emergence Build", and has the server build the
 * gallery in it a part at a time ({@link WatchedBuild}). The player flies to each plot as its turn comes and watches it
 * go up from the exhibit's own viewpoint, the caption saying which exhibit it is and which part is going in. Once the
 * gallery stands, the player is set down in creative in front of the first exhibit, free to play.
 */
final class WatchBuild {
    private WatchBuild() {}

    static final String WORLD = "Emergence Build";

    private static boolean created, started, ended;
    /** Ticks till the client closes once the gallery stands ({@code -Demergence.watchQuit=true}; 0: stays open). */
    private static int quitIn;
    private static int shown = -1, joinedTicks;
    /** Screenshots of each exhibit half built and built ({@code -Demergence.watchShots=true}): the stages taken so far. */
    private static int shotStage;
    private static List<Exhibit> exhibits;

    static boolean enabled() {
        return Boolean.getBoolean("emergence.watchBuild");
    }

    static void init() {
        if (!enabled()) return;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!created) {
            if (mc.screen instanceof TitleScreen) {
                created = true;
                createWorld(mc);
            }
            return;
        }
        var server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) return;
        if (!started) {
            // a moment for the world to settle round the player first
            if (joinedTicks++ < 40) return;
            started = true;
            mc.options.hideGui = true;
            mc.options.pauseOnLostFocus = false; // it builds on while people use the computer
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            server.execute(() -> WatchedBuild.start(server));
            return;
        }
        Flight.tick(mc);
        BlockPos origin = WatchedBuild.origin();
        if (origin == null) return;
        if (exhibits == null) exhibits = Exhibits.all();
        List<Exhibit> ex = exhibits;
        int i = WatchedBuild.exhibit();
        if (i != shown && i >= 0 && i < ex.size() && !WatchedBuild.finished()) {
            shown = i;
            Exhibit e = ex.get(i);
            BlockPos plot = origin.offset(DemoBuilder.plotOffset(ex, i), 0, 0);
            Flight.to(new Vec3(plot.getX() + e.viewX, plot.getY() + e.viewHeight - PlayerHand.EYE_STANDING, plot.getZ() + e.viewZ),
                    (float) e.viewYaw, (float) e.viewPitch, false, null);
            ShowcaseHud.exhibit(i + 1, ex.size(), "Building: " + e.title, e.description);
            shotStage = 0;
        }
        if (Boolean.getBoolean("emergence.watchShots") && i == shown && !Flight.busy()) {
            double p = WatchedBuild.progress();
            String stage = shotStage == 0 && p >= 0.5 && p < 1 ? "half" : shotStage < 2 && p >= 1 ? "built" : null;
            if (stage != null) {
                shotStage = stage.equals("half") ? 1 : 2;
                Screenshot.grab(mc.gameDirectory, "build-" + (i + 1) + "-" + ex.get(i).id + "-" + stage + ".png", mc.getMainRenderTarget(),
                        msg -> EmergenceMod.LOG.info("Watched build screenshot: {}", msg.getString()));
            }
        }
        if (WatchedBuild.finished() && !ended) {
            ended = true;
            ShowcaseHud.exhibit(ex.size(), ex.size(), "Emergence demo gallery", "");
            // down in front of the first exhibit, in creative, to look round
            BlockPos spawn = origin.offset(8, 0, DemoBuilder.SIGN_Z + 3);
            Flight.to(Vec3.atBottomCenterOf(spawn), 180, 15, false, sp -> sp.setGameMode(GameType.CREATIVE));
            mc.options.hideGui = false;
            if (Boolean.getBoolean("emergence.watchQuit")) quitIn = 200;
        }
        if (quitIn > 0 && --quitIn == 0) mc.stop();
        ShowcaseHud.doing(WatchedBuild.doing());
    }

    /** A fresh flat creative world, made as the Create World screen makes one (no structures: an empty plain). */
    private static void createWorld(Minecraft mc) {
        EmergenceMod.LOG.info("Watched build: creating the world \"{}\"", WORLD);
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(WorldOptions.randomSeed(), false, false);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options,
                reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }
}
