package dev.emergence.core.instruments;

import java.util.List;
import java.util.Locale;

/**
 * A sampling data logger / oscilloscope: records a fixed set of channels at a fixed sample interval of simulated
 * time. It is fed the measured quantities at every accepted solver step and resamples them onto its own clock by
 * linear interpolation between steps (a sample-and-hold ADC after an ideal anti-alias stage would see the same
 * values to within the solver's own interpolation error). Samples are stored as 32-bit floats in a ring buffer, so
 * the record is finite: the newest {@code capacity} samples. Its bandwidth is half its sample rate; anything faster
 * that the solver resolves aliases, as on a real instrument.
 */
public final class DataLogger {
    /** A recorded quantity. */
    public record Channel(String name, String unit) {}

    private final List<Channel> channels;
    private final double interval;
    private final int capacity;
    private final double[] times;
    private final float[][] values;
    private int head, count;
    private double lastT = Double.NaN, nextT = Double.NaN, offset;
    private double[] lastV;

    public DataLogger(List<Channel> channels, double interval, int capacity) {
        this.channels = List.copyOf(channels);
        this.interval = interval;
        this.capacity = capacity;
        this.times = new double[capacity];
        this.values = new float[channels.size()][capacity];
    }

    public List<Channel> channels() {
        return channels;
    }

    /** Sample interval [s]. */
    public double interval() {
        return interval;
    }

    /** Number of samples held. */
    public int size() {
        return count;
    }

    /** Time of sample i (0 = oldest held) [s]. */
    public double time(int i) {
        return times[(head - count + i + capacity) % capacity];
    }

    /** Value of channel ch at sample i (0 = oldest held). */
    public double value(int ch, int i) {
        return values[ch][(head - count + i + capacity) % capacity];
    }

    /**
     * Continues the record from a new source whose clock reads {@code tNow} (a recompiled simulation restarts its
     * time): later offers are shifted so the record's time runs on without a jump.
     */
    public void rebase(double tNow) {
        if (!Double.isNaN(lastT)) offset = lastT - tNow;
    }

    /** Clears the record (the clock restarts at the next offered point). */
    public void clear() {
        count = 0;
        head = 0;
        lastT = Double.NaN;
        nextT = Double.NaN;
    }

    /**
     * Offers the measured values at simulated time t (non-decreasing between calls). Every sample instant
     * passed since the previous offer is recorded, interpolated between the two. A jump backwards in time (a new
     * simulation) restarts the clock.
     */
    public void offer(double tSim, double[] v) {
        double t = tSim + offset;
        if (Double.isNaN(lastT) || t < lastT) {
            lastT = t;
            lastV = v.clone();
            nextT = Math.ceil(t / interval) * interval;
            if (nextT == t) {
                record(t, v, v, 0);
                nextT += interval;
            }
            return;
        }
        double span = t - lastT;
        while (nextT <= t) {
            double w = span > 0 ? (nextT - lastT) / span : 1;
            record(nextT, lastV, v, w);
            nextT += interval;
        }
        lastT = t;
        System.arraycopy(v, 0, lastV, 0, Math.min(v.length, lastV.length));
    }

    private void record(double t, double[] a, double[] b, double w) {
        times[head] = t;
        for (int c = 0; c < values.length; c++) {
            double x;
            if (c >= a.length || c >= b.length) x = Double.NaN;
            else if (Double.isNaN(a[c]) || Double.isNaN(b[c])) x = w < 0.5 ? a[c] : b[c]; // the nearer reading
            else x = a[c] + (b[c] - a[c]) * w;
            values[c][head] = (float) x;
        }
        head = (head + 1) % capacity;
        if (count < capacity) count++;
    }

    /**
     * Display envelope of channel ch over the newest {@code span} seconds in {@code bins} columns: for each
     * column the minimum and maximum of the samples falling in it (NaN where none), as an oscilloscope's
     * peak-detect mode draws them. Returns {mins, maxs}.
     */
    public float[][] envelope(int ch, double span, int bins) {
        float[] lo = new float[bins], hi = new float[bins];
        java.util.Arrays.fill(lo, Float.NaN);
        java.util.Arrays.fill(hi, Float.NaN);
        if (count == 0) return new float[][]{lo, hi};
        double tEnd = time(count - 1), t0 = tEnd - span;
        for (int i = count - 1; i >= 0; i--) {
            double t = time(i);
            if (t < t0) break;
            int k = Math.min(bins - 1, (int) ((t - t0) / span * bins));
            float x = (float) value(ch, i);
            if (Float.isNaN(x)) continue;
            if (Float.isNaN(lo[k]) || x < lo[k]) lo[k] = x;
            if (Float.isNaN(hi[k]) || x > hi[k]) hi[k] = x;
        }
        return new float[][]{lo, hi};
    }

    /** The record as comma-separated values: a header of names with units, then time and every channel per row. */
    public String csv() {
        StringBuilder sb = new StringBuilder("time [s]");
        for (Channel c : channels) sb.append(',').append(c.name()).append(" [").append(c.unit()).append(']');
        sb.append('\n');
        for (int i = 0; i < count; i++) {
            sb.append(String.format(Locale.ROOT, "%.6f", time(i)));
            for (int c = 0; c < channels.size(); c++) {
                double x = value(c, i);
                sb.append(',');
                if (!Double.isNaN(x)) sb.append(String.format(Locale.ROOT, "%.7g", x));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
