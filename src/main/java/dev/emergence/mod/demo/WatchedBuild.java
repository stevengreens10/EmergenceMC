package dev.emergence.mod.demo;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.mod.EmergenceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The demo gallery built where someone can watch it go up (the {@code watchDemoBuild} client): the same build as
 * {@link DemoBuilder#build}, every part made in the part editor and placed with a click, but spread over ticks, plot by
 * plot. Each plot waits for the watching player to arrive, then its floor is laid a column a tick, its scenery set,
 * its parts placed a few a tick (an exhibit takes about {@code emergence.buildSeconds}, 10 s by default, however many
 * parts it has), its sign put up, and the exhibit compared with its definition, as the headless build does. The
 * simulation is held still meanwhile, so the world comes out as the headless build's does; it is let go once the whole
 * gallery stands.
 */
public final class WatchedBuild {
    private WatchedBuild() {}

    private enum Phase { ARRIVE, FLOOR, SCENERY, PARTS, SIGN, SETTLE, SHOWN, DONE }

    private static ServerLevel level;
    private static List<Exhibit> exhibits;
    private static Phase phase;
    private static int floorX, ticks, perTick;
    private static PlayerBuilder.Job job;
    private static final List<String> PROBLEMS = new ArrayList<>();

    /** What the client shows: the gallery's origin, the exhibit under way (−1: none yet) and what is being done to it. */
    private static volatile BlockPos origin;
    private static volatile int exhibit = -1;
    private static volatile String doing = "";
    private static volatile boolean finished;
    /** How far the exhibit under way is: the share of its parts in, 1 once it is complete and checked. */
    private static volatile double progress;

    public static BlockPos origin() {
        return origin;
    }

    public static int exhibit() {
        return exhibit;
    }

    public static String doing() {
        return doing;
    }

    public static double progress() {
        return progress;
    }

    public static boolean finished() {
        return finished;
    }

    /** Problems met so far (a part that did not go in as defined, an exhibit compiled otherwise than its definition). */
    public static int problems() {
        synchronized (PROBLEMS) {
            return PROBLEMS.size();
        }
    }

    /** Starts the build at the world spawn (server thread). */
    public static void start(MinecraftServer server) {
        if (level != null) return;
        level = server.overworld();
        exhibits = Exhibits.all();
        origin = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(0, 0, 0));
        DemoBuilder.galleryRules(server);
        dev.emergence.mod.sim.SimulationManager.get(level).setPaused(true);
        begin(0);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (phase != Phase.DONE) tick(e.getServer());
        });
        EmergenceMod.LOG.info("Watched build: {} exhibits from {}", exhibits.size(), origin);
    }

    private static void begin(int i) {
        exhibit = i;
        progress = 0;
        phase = Phase.ARRIVE;
        ticks = 0;
        doing = "Flying over to the plot";
    }

    private static void tick(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) return; // no one watching
        int i = exhibit;
        Exhibit e = exhibits.get(i);
        BlockPos plot = origin.offset(DemoBuilder.plotOffset(exhibits, i), 0, 0);
        ticks++;
        switch (phase) {
            case ARRIVE -> {
                // once the watcher has come to look (its chunks are about it), or after 10 s regardless
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                double dx = p.getX() - (plot.getX() + e.viewX), dz = p.getZ() - (plot.getZ() + e.viewZ);
                if (Math.hypot(dx, dz) < 2 && ticks > 10 || ticks > 200) {
                    phase = Phase.FLOOR;
                    floorX = DemoBuilder.floorFrom(exhibits, i);
                    if (i == 0) DemoBuilder.welcomeSign(level, origin);
                    doing = "Laying the floor";
                }
            }
            case FLOOR -> {
                // two columns a tick: a plot's floor in half a second
                for (int k = 0; k < 2 && floorX <= DemoBuilder.floorTo(exhibits, i); k++) DemoBuilder.floorColumn(level, origin, exhibits, floorX++);
                if (floorX > DemoBuilder.floorTo(exhibits, i)) phase = Phase.SCENERY;
            }
            case SCENERY -> {
                if (!e.scenery.isEmpty()) doing = "Setting up its surroundings";
                DemoBuilder.scenery(level, plot, e);
                if (e.miniature) {
                    // with the tweezers, the whole block's parts at once
                    List<String> made = PlayerBuilder.buildMiniature(level, plot.offset(DemoBuilder.MINIATURE_AT), e);
                    synchronized (PROBLEMS) {
                        PROBLEMS.addAll(made);
                    }
                    for (String pr : made) EmergenceMod.LOG.error("Watched build: {}", pr);
                    phase = Phase.SIGN;
                } else {
                    job = new PlayerBuilder.Job(level, plot, e);
                    int seconds = Math.max(1, Integer.getInteger("emergence.buildSeconds", 10));
                    perTick = Math.max(1, (int) Math.ceil(e.parts.size() / (20.0 * seconds)));
                    phase = Phase.PARTS;
                }
            }
            case PARTS -> {
                boolean more = true;
                for (int k = 0; k < perTick && more; k++) more = job.step();
                progress = job.placed() / (double) Math.max(1, e.parts.size());
                Exhibit.PartDef last = job.last();
                doing = last == null ? "Placing its parts"
                        : String.format("Placing part %d of %d: %s", job.placed(), e.parts.size(), describe(last));
                if (!more) {
                    synchronized (PROBLEMS) {
                        PROBLEMS.addAll(job.problems());
                    }
                    for (String pr : job.problems()) EmergenceMod.LOG.error("Watched build: {}", pr);
                    phase = Phase.SIGN;
                }
            }
            case SIGN -> {
                DemoBuilder.sign(level, plot, e);
                doing = "Checking it compiled as its definition";
                phase = Phase.SETTLE;
                ticks = 0;
            }
            case SETTLE -> {
                // once its blocks have registered with the simulation
                if (ticks < 40) return;
                List<String> diff = DemoBuilder.verifyExhibit(level, origin, i);
                synchronized (PROBLEMS) {
                    PROBLEMS.addAll(diff);
                }
                for (String pr : diff) EmergenceMod.LOG.error("Watched build: {}", pr);
                EmergenceMod.LOG.info("Watched build: {} built ({} differences)", e.id, diff.size());
                progress = 1;
                ticks = 0;
                phase = Phase.SHOWN;
            }
            case SHOWN -> {
                // a moment to look at it finished before flying on
                if (ticks < 20) return;
                if (i + 1 < exhibits.size()) begin(i + 1);
                else finish(server);
            }
            case DONE -> {}
        }
    }

    private static String describe(Exhibit.PartDef d) {
        String kind = d.kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return kind + " (" + d.material().replace('_', ' ') + ")";
    }

    private static void finish(MinecraftServer server) {
        phase = Phase.DONE;
        BlockPos spawn = origin.offset(8, 0, DemoBuilder.SIGN_Z + 3);
        level.setDefaultSpawnPos(spawn, 180f);
        dev.emergence.mod.sim.SimulationManager.get(level).setPaused(false);
        server.saveEverything(false, true, true);
        int n = problems();
        doing = n == 0 ? "The gallery is built: every exhibit as defined. The machines are running."
                : "The gallery is built, with " + n + " problems (see the log). The machines are running.";
        EmergenceMod.LOG.info("Watched build: {} exhibits built, {} problems", exhibits.size(), n);
        finished = true;
    }
}
