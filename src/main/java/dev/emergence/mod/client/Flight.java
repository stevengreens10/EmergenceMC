package dev.emergence.mod.client;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * How the showcase's player gets about: it flies there, as a player does, never jumping. A flight is made in spectator
 * mode (a player's own way of flying through a crowded gallery without catching on a machine): up clear of what lies
 * between, across, and down onto the spot, easing in and out and turning towards the new view on the way, at a
 * creative player's flying pace. The client moves the player tick by tick, as its own movement keys would; the server
 * follows from the movement packets. On landing the server puts the player in the game mode the job needs.
 */
final class Flight {
    private Flight() {}

    /** Flying pace [blocks/s]: about a creative player's sprinting flight. */
    private static final double SPEED = 16;
    /** How high above the tallest thing on the way the flight passes [blocks]. */
    private static final double CLEARANCE = 2;

    private record Plan(Vec3 feet, float yaw, float pitch, boolean keepView, Consumer<ServerPlayer> onLanding) {}

    private static volatile Plan pending;
    private static List<Vec3> path;
    private static double length, duration, t;
    private static float yaw0, pitch0;
    /** The flight under way (cleared on the server thread once it has landed). */
    private static volatile Plan flying;
    private static boolean landing;
    private static int waitedForMode;

    /**
     * Asks for a flight to {@code feet}, ending looking along {@code yaw}/{@code pitch} ({@code keepView}: ending as it
     * looks on the way, the job turning it); {@code onLanding} then runs on the server (null: none). Callable from
     * either thread; {@link #busy} holds from now until it has landed.
     */
    static void to(Vec3 feet, float yaw, float pitch, boolean keepView, Consumer<ServerPlayer> onLanding) {
        pending = new Plan(feet, yaw, pitch, keepView, onLanding);
    }

    static boolean busy() {
        return pending != null || flying != null;
    }

    /** Called every client tick: moves the player one tick along its flight. */
    static void tick(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (mc.player == null || server == null) return;
        if (flying == null) {
            Plan p = pending;
            if (p == null) return;
            pending = null;
            flying = p;
            landing = false;
            waitedForMode = 0;
            path = null;
            // spectator for the way (the client may not carry a creative body through blocks)
            server.execute(() -> {
                if (server.getPlayerList().getPlayers().isEmpty()) return;
                ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
                sp.setGameMode(GameType.SPECTATOR);
            });
        }
        if (landing) return; // the server is putting it down
        if (path == null) {
            // the server's word that it is a spectator has come back: plan the way from where it is now
            if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR && waitedForMode++ < 200) return;
            plan(mc);
        }
        t += 0.05;
        double u = duration <= 0 ? 1 : Math.min(1, t / duration);
        double s = u * u * (3 - 2 * u); // eased: gathers speed, slows to land
        Vec3 at = along(s * length);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(at.x, at.y, at.z);
        if (!flying.keepView()) {
            float yaw = yaw0 + wrap(flying.yaw() - yaw0) * (float) s, pitch = pitch0 + (flying.pitch() - pitch0) * (float) s;
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.setYHeadRot(yaw);
        }
        if (u < 1) return;
        // landed: the server takes it from here (its position comes with the next movement packet; set it as well, so
        // nothing on the server acts on a stale one)
        Plan done = flying;
        landing = true;
        server.execute(() -> {
            if (server.getPlayerList().getPlayers().isEmpty()) {
                flying = null;
                return;
            }
            ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
            Vec3 f = done.feet();
            sp.teleportTo(server.overworld(), f.x, f.y, f.z, mc.player.getYRot(), mc.player.getXRot());
            try {
                if (done.onLanding() != null) done.onLanding().accept(sp);
            } finally {
                flying = null;
            }
        });
        if (mc.player.position().distanceTo(done.feet()) > 1e-3) EmergenceMod.LOG.debug("Flight ended off its mark");
    }

    /** The way there: straight if it is short, else up over everything between, across, and down. */
    private static void plan(Minecraft mc) {
        Vec3 a = mc.player.position(), b = flying.feet();
        yaw0 = mc.player.getYRot();
        pitch0 = mc.player.getXRot();
        path = new ArrayList<>();
        path.add(a);
        double h = Math.hypot(b.x - a.x, b.z - a.z);
        if (h > 4) {
            // the tallest thing under the straight line, from the client's own height maps
            double top = Math.max(a.y, b.y);
            int n = (int) Math.ceil(h);
            for (int k = 0; k <= n; k++) {
                double x = a.x + (b.x - a.x) * k / n, z = a.z + (b.z - a.z) * k / n;
                top = Math.max(top, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)));
            }
            double cruise = top + CLEARANCE;
            if (cruise > a.y + 0.5) path.add(new Vec3(a.x, cruise, a.z));
            if (cruise > b.y + 0.5) path.add(new Vec3(b.x, cruise, b.z));
        }
        path.add(b);
        length = 0;
        for (int k = 1; k < path.size(); k++) length += path.get(k).distanceTo(path.get(k - 1));
        // at the flying pace, but never a crawl for a step aside nor an age for a far jump
        duration = Math.max(0.5, Math.min(6, length / SPEED));
        t = 0;
    }

    private static Vec3 along(double d) {
        for (int k = 1; k < path.size(); k++) {
            Vec3 p = path.get(k - 1), q = path.get(k);
            double l = q.distanceTo(p);
            if (d <= l || k == path.size() - 1) return l < 1e-9 ? q : p.add(q.subtract(p).scale(Math.min(1, d / l)));
            d -= l;
        }
        return path.get(path.size() - 1);
    }

    private static float wrap(float deg) {
        deg %= 360;
        if (deg > 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }
}
