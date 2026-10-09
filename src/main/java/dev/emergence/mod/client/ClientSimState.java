package dev.emergence.mod.client;

import dev.emergence.mod.net.IslandSyncPayload;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache of the latest server snapshots, used only for rendering. Body angles are
 * extrapolated with the received angular velocity so rotation looks smooth between packets.
 */
public final class ClientSimState {
    private ClientSimState() {}

    public record Pose(double px, double py, double pz, double ax, double ay, double az, double angle, double omega, long time) {}

    private static final Map<Long, Pose> POSE = new HashMap<>();
    private static final Map<Long, Float> TEMP = new HashMap<>();
    private static final Map<Long, Integer> TINT = new HashMap<>();
    private static final Map<Long, Float> GAUGE = new HashMap<>();
    private static final Map<Long, float[]> PISTON = new HashMap<>();

    public static synchronized void apply(IslandSyncPayload p) {
        for (long id : p.partIds()) {
            POSE.remove(id);
            TEMP.remove(id);
            TINT.remove(id);
            GAUGE.remove(id);
            PISTON.remove(id);
        }
        double s = p.worldScale();
        for (IslandSyncPayload.BodyState b : p.bodies()) {
            // pivot arrives in physical metres; convert to world block coordinates
            Pose pose = new Pose(b.px() * s, b.py() * s, b.pz() * s, b.ax(), b.ay(), b.az(), b.angle(), b.omega(), p.gameTime());
            for (long id : b.partIds()) POSE.put(id, pose);
        }
        for (int i = 0; i < p.hotIds().length; i++) TEMP.put(p.hotIds()[i], p.hotTemps()[i]);
        for (int i = 0; i < p.tintIds().length; i++) TINT.put(p.tintIds()[i], p.tints()[i]);
        for (int i = 0; i < p.fluidIds().length; i++) GAUGE.put(p.fluidIds()[i], p.gauges()[i]);
        for (int i = 0; i < p.pistonIds().length; i++) PISTON.put(p.pistonIds()[i], java.util.Arrays.copyOfRange(p.pistons(), 8 * i, 8 * i + 8));
    }

    public static synchronized Pose pose(long partId) {
        return POSE.get(partId);
    }

    public static synchronized float temperature(long partId, float fallback) {
        Float t = TEMP.get(partId);
        return t == null ? fallback : t;
    }

    /** Piston state of a cylinder (see CompiledAssembly.piston), or null. */
    public static synchronized float[] piston(long partId) {
        return PISTON.get(partId);
    }

    /** Gauge pressure of a fluid part [Pa] (0 if at ambient or unknown). */
    public static synchronized float gauge(long partId) {
        Float g = GAUGE.get(partId);
        return g == null ? 0 : g;
    }

    /** Colour as changed by chemistry (0xRRGGBB), or {@code fallback}. */
    public static synchronized int colour(long partId, int fallback) {
        Integer t = TINT.get(partId);
        return t == null ? fallback : t;
    }

    public static synchronized void clear() {
        POSE.clear();
        TEMP.clear();
        TINT.clear();
        GAUGE.clear();
        PISTON.clear();
    }
}
