package dev.emergence.mod.sim;

/**
 * Lightweight running statistics of simulation cost per server tick, reported by
 * {@code /emergence perf}. The performance budget is 5 ms of simulation per 50 ms tick.
 */
public final class Profiler {
    private Profiler() {}

    public static final double BUDGET_MS = 5.0;
    private static double avgMs, maxMs;
    private static long samples;
    private static int lastSize;

    public static synchronized void record(long nanos, int unknowns) {
        double ms = nanos / 1e6;
        samples++;
        avgMs += (ms - avgMs) / Math.min(samples, 200);
        maxMs = Math.max(maxMs * 0.995, ms);
        lastSize = unknowns;
    }

    public static synchronized String report() {
        return String.format("simulation: avg %.3f ms/island-tick, recent max %.3f ms, last island %d unknowns, budget %.1f ms%s",
                avgMs, maxMs, lastSize, BUDGET_MS, maxMs > BUDGET_MS ? " (EXCEEDED)" : "");
    }
}
