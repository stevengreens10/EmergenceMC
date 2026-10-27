package dev.emergence.core.instruments;

import java.util.Arrays;

/**
 * A logic analyser's and oscilloscope's memory: up to eight channels, each a record of (time, value) — a digital net's
 * level as it changes (exact, at the time it changed) or an analog node's voltage at the solver's steps (kept when it
 * has moved, or now and then). Rolling (the newest {@link #capacity} events a channel), or a single capture: armed, it
 * waits for its trigger — an edge on a channel, crossing a level — records on for a window, and holds.
 * <p>
 * For display, {@link #envelope} reduces a time window of a channel to bins of (min, max): a band where it switched or
 * moved within a bin, a line where it held — exact at any zoom where a bin is shorter than its events' spacing.
 */
public final class LogicCapture {
    public static final int CHANNELS = 8;

    public enum Mode { RUN, SINGLE, HOLD }

    private final int capacity;
    private final double[][] t;
    private final float[][] v;
    private final int[] head = new int[CHANNELS], count = new int[CHANNELS];
    private final boolean[] digital = new boolean[CHANNELS], used = new boolean[CHANNELS];
    private final double[] lastT = new double[CHANNELS];
    private final float[] lastV = new float[CHANNELS];
    private Mode mode = Mode.RUN;
    private int trigChannel;
    private boolean trigRising = true, armed;
    private double trigLevel = 2.5, window = 1e-3, trigTime = Double.NaN, newest = Double.NEGATIVE_INFINITY;
    private boolean frozen;

    public LogicCapture(int capacity) {
        this.capacity = capacity;
        t = new double[CHANNELS][capacity];
        v = new float[CHANNELS][capacity];
        Arrays.fill(lastT, Double.NEGATIVE_INFINITY);
    }

    public int capacity() {
        return capacity;
    }

    /** Whether a channel records levels (a digital net) rather than voltages. */
    public boolean digital(int ch) {
        return digital[ch];
    }

    public boolean used(int ch) {
        return used[ch];
    }

    public void configure(int ch, boolean isDigital) {
        used[ch] = true;
        digital[ch] = isDigital;
        head[ch] = 0;
        count[ch] = 0;
        lastT[ch] = Double.NEGATIVE_INFINITY;
    }

    public void unuse(int ch) {
        used[ch] = false;
        count[ch] = 0;
    }

    public synchronized void clear() {
        Arrays.fill(count, 0);
        Arrays.fill(head, 0);
        Arrays.fill(lastT, Double.NEGATIVE_INFINITY);
        newest = Double.NEGATIVE_INFINITY;
        frozen = false;
        trigTime = Double.NaN;
        armed = mode == Mode.SINGLE;
    }

    /** Run (rolling), single capture (armed: waits for the trigger, records the window after it, holds) or hold. */
    public synchronized void mode(Mode m) {
        mode = m;
        frozen = m == Mode.HOLD;
        if (m == Mode.SINGLE) {
            armed = true;
            trigTime = Double.NaN;
        }
    }

    public Mode mode() {
        return mode;
    }

    /** The trigger: channel, edge, level (for an analog channel [V]) and the window recorded after it [s]. */
    public synchronized void trigger(int ch, boolean rising, double level, double windowSeconds) {
        trigChannel = ch;
        trigRising = rising;
        trigLevel = level;
        window = windowSeconds;
    }

    public int triggerChannel() {
        return trigChannel;
    }

    public boolean triggerRising() {
        return trigRising;
    }

    public double window() {
        return window;
    }

    /** Whether a single capture is waiting for its trigger; when it triggered (NaN if not); whether it is holding. */
    public boolean armed() {
        return armed;
    }

    public double triggerTime() {
        return trigTime;
    }

    public boolean frozen() {
        return frozen;
    }

    /** The newest time recorded on any channel [s]. */
    public double newest() {
        return newest;
    }

    /** A digital channel's level changed at time {@code time}, or an analog channel's voltage was sampled. */
    public synchronized void record(int ch, double time, double value) {
        if (frozen || !used[ch]) return;
        float fv = (float) value;
        if (!digital[ch] && count[ch] > 0) {
            // an analog sample is kept when it has moved, or every 1 ms regardless
            float d = Math.abs(fv - lastV[ch]);
            if (d < 0.002f * Math.max(1f, Math.abs(fv)) && time - lastT[ch] < 1e-3) return;
        }
        if (armed && ch == trigChannel && count[ch] > 0) {
            double th = digital[ch] ? 0.5 : trigLevel;
            boolean was = lastV[ch] > th, now = fv > th;
            if (was != now && now == trigRising) {
                armed = false;
                trigTime = time;
            }
        }
        int i = (head[ch] + count[ch]) % capacity;
        if (count[ch] == capacity) head[ch] = (head[ch] + 1) % capacity;
        else count[ch]++;
        t[ch][i] = time;
        v[ch][i] = fv;
        lastT[ch] = time;
        lastV[ch] = fv;
        newest = Math.max(newest, time);
        if (mode == Mode.SINGLE && !Double.isNaN(trigTime) && time > trigTime + window) frozen = true;
    }

    /** Recorded events on a channel. */
    public int size(int ch) {
        return count[ch];
    }

    public double time(int ch, int i) {
        return t[ch][(head[ch] + i) % capacity];
    }

    public double value(int ch, int i) {
        return v[ch][(head[ch] + i) % capacity];
    }

    /** A channel's value at time {@code at}: the last event at or before it (NaN if none). */
    public synchronized double valueAt(int ch, double at) {
        int lo = 0, hi = count[ch] - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (time(ch, mid) <= at) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found < 0 ? Double.NaN : value(ch, found);
    }

    /**
     * A channel over [t0, t1] in {@code bins} equal bins: {min[], max[]} (NaN where nothing was recorded yet). Each bin
     * spans the value held coming into it and every event inside it.
     */
    public synchronized float[][] envelope(int ch, double t0, double t1, int bins) {
        float[] min = new float[bins], max = new float[bins];
        Arrays.fill(min, Float.NaN);
        Arrays.fill(max, Float.NaN);
        int n = count[ch];
        if (n == 0 || !(t1 > t0)) return new float[][]{min, max};
        double w = (t1 - t0) / bins;
        // the first event at or after t0
        int lo = 0, hi = n - 1, first = n;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (time(ch, mid) >= t0) {
                first = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        float held = first > 0 ? (float) value(ch, first - 1) : Float.NaN;
        int i = first;
        for (int b = 0; b < bins; b++) {
            double be = t0 + (b + 1) * w;
            float mn = held, mx = held;
            while (i < n && time(ch, i) < be) {
                float x = (float) value(ch, i);
                mn = Float.isNaN(mn) ? x : Math.min(mn, x);
                mx = Float.isNaN(mx) ? x : Math.max(mx, x);
                held = x;
                i++;
            }
            // nothing recorded past the newest event: leave the future empty
            if (t0 + b * w > newest) break;
            min[b] = mn;
            max[b] = mx;
        }
        return new float[][]{min, max};
    }
}
