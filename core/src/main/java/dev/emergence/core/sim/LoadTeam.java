package dev.emergence.core.sim;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * A few helper threads for one phase's parallel element loading, kept warm between passes. A Newton iteration's pass is
 * a fraction of a millisecond; handed to the common pool, each pass woke its workers from sleep, and that wake-up cost
 * as much as the loading it shared out (the same time with 4 workers as with 16). Helpers here spin for a while after
 * each pass, waiting for the next, then sleep, and end after a few seconds without work (a machine taken apart leaves
 * none behind). Which worker does which share is fixed by the caller, so the results do not depend on the threads.
 */
final class LoadTeam {
    /** How long a helper spins for the next pass before it sleeps [ns], and idles before it ends. */
    private static final long SPIN_NS = 300_000, IDLE_NS = 3_000_000_000L;

    private final int helpers;
    private final Thread[] threads;
    /** A helper that has ended (or never started): the next pass starts it anew. Guarded by this. */
    private final boolean[] dead;
    private volatile Runnable[] work;
    private volatile int generation;
    private final AtomicInteger done = new AtomicInteger();
    private volatile Throwable failure;

    LoadTeam(int helpers) {
        this.helpers = helpers;
        this.threads = new Thread[helpers];
        this.dead = new boolean[helpers];
        java.util.Arrays.fill(dead, true);
    }

    int ways() {
        return helpers + 1;
    }

    /** Runs share 0 on the calling thread and share w on helper w − 1; returns when all have finished. */
    void run(Runnable[] shares) {
        work = shares;
        done.set(0);
        failure = null;
        // (published under the lock a helper ends under: one that has decided to end is restarted, one that has not
        // sees the new pass)
        synchronized (this) {
            generation++;
            for (int w = 0; w < helpers; w++) {
                if (dead[w]) {
                    dead[w] = false;
                    threads[w] = start(w, generation - 1);
                }
            }
        }
        for (int w = 0; w < helpers; w++) LockSupport.unpark(threads[w]);
        try {
            shares[0].run();
        } catch (RuntimeException | Error e) {
            failure = e;
        }
        while (done.get() < helpers) Thread.onSpinWait();
        Throwable f = failure;
        if (f instanceof RuntimeException re) throw re;
        if (f instanceof Error er) throw er;
    }

    private Thread start(int w, int seen0) {
        Thread t = new Thread(() -> loop(w, seen0), "Emergence load helper");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void loop(int w, int seen) {
        long idleSince = System.nanoTime();
        while (true) {
            int g = generation;
            if (g != seen) {
                seen = g;
                try {
                    work[w + 1].run();
                } catch (RuntimeException | Error e) {
                    failure = e;
                }
                done.incrementAndGet();
                idleSince = System.nanoTime();
                continue;
            }
            long idle = System.nanoTime() - idleSince;
            if (idle < SPIN_NS) Thread.onSpinWait();
            else if (idle < IDLE_NS) LockSupport.parkNanos(200_000);
            else {
                // ending: a pass published from now on starts a new helper (run() sees this thread dead)
                synchronized (this) {
                    if (generation == seen) {
                        dead[w] = true;
                        return;
                    }
                }
            }
        }
    }
}
