package dev.emergence.mod.client;

import dev.emergence.mod.net.IslandSyncPayload;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache of the latest server snapshots, used only for rendering. Moving things (body angles and slide
 * positions, pistons, springs) are drawn by snapshot interpolation: each keeps its last few values stamped with the
 * client's own arrival time, and is drawn about 1.5 packet intervals in the past on a smooth curve through them.
 * That is independent of the client's and server's game clocks (which drift apart by many ticks) and of an island
 * running in slow motion, and never overshoots. Extrapolating with the synced velocity did both, and made moving
 * parts jitter.
 */
public final class ClientSimState {
    private ClientSimState() {}

    /**
     * A body's pose; for a slide, angle and ω are its displacement and velocity in world blocks. A rotor riding a slide
     * names a part of it ({@code carrier}, else −1): its pivot moves with that slide.
     */
    public record Pose(double px, double py, double pz, double ax, double ay, double az, double angle, double omega, long time, boolean slider,
                       long key, long carrier) {}

    private static final Map<Long, Pose> POSE = new HashMap<>();
    private static final Map<Long, Float> TEMP = new HashMap<>();
    private static final Map<Long, Integer> TINT = new HashMap<>();
    private static final Map<Long, Float> GAUGE = new HashMap<>();
    private static final Map<Long, float[]> PISTON = new HashMap<>();
    private static final Map<Long, float[]> SPRING = new HashMap<>();
    private static final Map<Long, float[]> BEAM = new HashMap<>();
    private static final Map<Long, Track> BODY_TRACK = new HashMap<>(), PISTON_TRACK = new HashMap<>(), SPRING_TRACK = new HashMap<>();
    private static long lastPurge;

    /** Recent values of a synced quantity with their arrival times (chronological, newest last). */
    private static final class Track {
        final long[] t = new long[6];
        final double[][] v = new double[6][];
        int n;
        double interval = 50e6;
        long seen;

        void add(long now, double[] x, int wrapIndex) {
            if (n > 0) {
                long dt = now - t[n - 1];
                if (wrapIndex >= 0) {
                    // an angle reported modulo 2 pi: continue from the previous value
                    double prev = v[n - 1][wrapIndex];
                    x[wrapIndex] = prev + Math.IEEEremainder(x[wrapIndex] - prev, 2 * Math.PI);
                }
                if (dt < 2_000_000) { // same frame: the newer value replaces it
                    v[n - 1] = x;
                    seen = now;
                    return;
                }
                if (dt < 500_000_000) interval = 0.8 * interval + 0.2 * dt;
            }
            if (n == t.length) {
                System.arraycopy(t, 1, t, 0, n - 1);
                System.arraycopy(v, 1, v, 0, n - 1);
                n--;
            }
            t[n] = now;
            v[n] = x;
            n++;
            seen = now;
        }

        /** The value to draw at client time now: on a Catmull-Rom curve through the samples, 1.5 intervals back. */
        double[] at(long now) {
            long r = now - (long) Math.max(40e6, Math.min(300e6, 1.5 * interval));
            if (n == 1 || r <= t[0]) return v[0];
            if (r >= t[n - 1]) return v[n - 1];
            int k = 0;
            while (k < n - 2 && t[k + 1] <= r) k++;
            double u = (r - t[k]) / (double) (t[k + 1] - t[k]);
            double[] p0 = v[Math.max(k - 1, 0)], p1 = v[k], p2 = v[k + 1], p3 = v[Math.min(k + 2, n - 1)];
            double[] out = new double[p1.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = 0.5 * (2 * p1[i] + (p2[i] - p0[i]) * u + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * u * u
                        + (3 * p1[i] - p0[i] - 3 * p2[i] + p3[i]) * u * u * u);
            }
            return out;
        }
    }

    private static void record(Map<Long, Track> m, long key, long now, double[] x, int wrapIndex) {
        m.computeIfAbsent(key, k -> new Track()).add(now, x, wrapIndex);
    }

    public static synchronized void apply(IslandSyncPayload p) {
        for (long id : p.partIds()) {
            POSE.remove(id);
            TEMP.remove(id);
            TINT.remove(id);
            GAUGE.remove(id);
            PISTON.remove(id);
            SPRING.remove(id);
            BEAM.remove(id);
        }
        double s = p.worldScale();
        long now = System.nanoTime();
        for (IslandSyncPayload.BodyState b : p.bodies()) {
            // pivot arrives in physical metres; convert to world block coordinates
            double k = b.slider() ? s : 1;
            Pose pose = new Pose(b.px() * s, b.py() * s, b.pz() * s, b.ax(), b.ay(), b.az(), b.angle() * k, b.omega() * k, p.gameTime(), b.slider(),
                    b.partIds().length > 0 ? b.partIds()[0] : -1, b.carrier());
            for (long id : b.partIds()) POSE.put(id, pose);
            if (pose.key() >= 0) record(BODY_TRACK, pose.key(), now, new double[]{pose.angle()}, -1);
        }
        for (int i = 0; i < p.hotIds().length; i++) TEMP.put(p.hotIds()[i], p.hotTemps()[i]);
        for (int i = 0; i < p.tintIds().length; i++) TINT.put(p.tintIds()[i], p.tints()[i]);
        for (int i = 0; i < p.fluidIds().length; i++) GAUGE.put(p.fluidIds()[i], p.gauges()[i]);
        for (int i = 0; i < p.pistonIds().length; i++) {
            float[] st = java.util.Arrays.copyOfRange(p.pistons(), 8 * i, 8 * i + 8);
            PISTON.put(p.pistonIds()[i], st);
            double[] d = new double[st.length];
            for (int k = 0; k < d.length; k++) d[k] = st[k];
            record(PISTON_TRACK, p.pistonIds()[i], now, d, 1);
        }
        for (int i = 0, k = 0; i < p.beamIds().length; i++) {
            int start = k, rays = (int) p.beams()[k++];
            for (int r = 0; r < rays; r++) k += 2 + 3 * (int) p.beams()[k];
            BEAM.put(p.beamIds()[i], java.util.Arrays.copyOfRange(p.beams(), start, k));
        }
        for (int i = 0; i < p.springIds().length; i++) {
            float[] se = java.util.Arrays.copyOfRange(p.springEnds(), 2 * i, 2 * i + 2);
            SPRING.put(p.springIds()[i], se);
            record(SPRING_TRACK, p.springIds()[i], now, new double[]{se[0], se[1]}, -1);
        }
        if (now - lastPurge > 5_000_000_000L) {
            lastPurge = now;
            for (Map<Long, Track> m : java.util.List.of(BODY_TRACK, PISTON_TRACK, SPRING_TRACK)) {
                m.values().removeIf(tr -> now - tr.seen > 10_000_000_000L);
            }
        }
    }

    /** An LED's beam rays (see IslandSyncPayload: ray count, then point count and points per ray), or null. */
    public static synchronized float[] beams(long partId) {
        return BEAM.get(partId);
    }

    public static synchronized Pose pose(long partId) {
        return POSE.get(partId);
    }

    public static synchronized float temperature(long partId, float fallback) {
        Float t = TEMP.get(partId);
        return t == null ? fallback : t;
    }

    /** Piston state of a cylinder (see CompiledAssembly.piston), or null. */
    /** The frame being drawn [System.nanoTime()]: one time for every part in a frame. */
    private static long frame;

    /** Called at the start of drawing a block: begins a new frame time if the last is a few milliseconds old. */
    public static synchronized long frameTime() {
        long now = System.nanoTime();
        if (now - frame > 4_000_000) frame = now;
        return frame;
    }

    /** A body's angle (or a slide's displacement, in blocks) to draw now, interpolated between packets. */
    public static synchronized double bodyAngle(Pose pose) {
        Track tr = BODY_TRACK.get(pose.key());
        return tr == null ? pose.angle() : tr.at(frame)[0];
    }

    /** How far a spring's end faces have moved along its axis [blocks]: {−axis end, +axis end}, or null. */
    public static synchronized float[] spring(long partId) {
        float[] latest = SPRING.get(partId);
        Track tr = SPRING_TRACK.get(partId);
        if (latest == null || tr == null) return latest;
        double[] v = tr.at(frame);
        return new float[]{(float) v[0], (float) v[1]};
    }

    public static synchronized float[] piston(long partId) {
        float[] latest = PISTON.get(partId);
        Track tr = PISTON_TRACK.get(partId);
        if (latest == null || tr == null) return latest;
        double[] v = tr.at(frame);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) v[i];
        return out;
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
        SPRING.clear();
        BODY_TRACK.clear();
        PISTON_TRACK.clear();
        SPRING_TRACK.clear();
    }
}
