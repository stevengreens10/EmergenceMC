package dev.emergence.core.sim;

import dev.emergence.core.math.LinearSystem;
import dev.emergence.core.math.SingularMatrixException;

import java.util.Arrays;
import java.util.List;

/**
 * Adaptive implicit transient solver for a {@link Network}.
 * <p>
 * Each step solves the nonlinear residual system F(x_{n+1}) = 0 by damped Newton–Raphson with a sparse
 * (or small dense) LU factorisation. Stored quantities are integrated with the trapezoidal rule by default
 * (A-stable, second order, conserving the energy of linear oscillators) or {@link IntegrationMethod#BDF2}
 * (L-stable: damps unresolved stiff modes — the choice for switching electronics), and with backward Euler
 * on the first step and after any discontinuity. The step size is controlled by an estimate of the local truncation error of every
 * stored quantity (computed from divided differences of its time derivative) against mixed
 * relative/absolute tolerances.
 * <p>
 * An energy ledger integrates the power exchanged with external reservoirs and untracked losses and
 * compares it with the change in stored energy, giving a running measurement of the conservation
 * error of the discretisation.
 */
public final class TransientSolver implements StateView {
    private static final boolean DEBUG = Boolean.getBoolean("emergence.debugSolver");
    private final Network net;
    private final List<Element> elements;
    private final List<EventSource> eventSources = new java.util.ArrayList<>();
    /** (Added and removed from other threads — an instrument averaging what it reads — while the solver steps.) */
    private final List<java.util.function.Consumer<TransientSolver>> stepListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final int n, ns;
    private final LinearSystem jac;
    private final LoadContext ctx = new LoadContext();

    private final double[] x, xPrev, xTrial, f, dx, dxPrev;
    private final double[] q0, q1, qd0, qd1, qNew, qdNew;
    private final double[] absTol, slotTol;
    private final double[] gmin;
    private final double[] stepLimit;
    private final java.util.function.DoubleBinaryOperator[] limiters;
    /** Electrical nodes get a GMIN relative to their diagonal (floating conductive islands). */
    private final boolean[] relGmin;

    private double t;
    private double hPrev = 1e-6;
    private double hNext;
    private int validHistory;
    private boolean restart = true;

    // configuration
    private IntegrationMethod method = IntegrationMethod.TRAPEZOIDAL;
    // trapezoidal-ringing detection: per-slot count of consecutive sign alternations of Δq̇
    private int[] alternations;
    private boolean forceBE;
    private double relTol = 1e-4;
    private double lteRelTol = 1e-3;
    /** Diagnostic: every truncation-error tolerance scaled by {@code diag.lteScale} (accuracy against speed). */
    static final double LTE_SCALE = Double.parseDouble(System.getProperty("diag.lteScale", "1"));
    /**
     * The accuracy level: truncation-error tolerances times this (1: as validated). A machine that cannot keep real
     * time may relax it a little before it falls into slow motion (see SimulationManager): energy and mass stay
     * booked exactly, only each step's local error may be larger.
     */
    private volatile double accuracy = 1;

    public void setAccuracy(double relax) {
        this.accuracy = Math.max(1, relax);
    }

    public double accuracy() {
        return accuracy;
    }
    private double hMin = 1e-10;
    private double hMax = 5e-3;
    /**
     * First step after a restart. Backward Euler damps oscillatory modes by ≈(ωh)² per step, so the
     * restart step is kept tiny; LTE control then grows h geometrically (×2 per step).
     */
    private double hRestart = 1e-7;
    private int maxNewton = 40;
    /** {@code emergence.dampNear}: past three cycles, updates are halved only while above this many tolerances (0: to the end). */
    static final double DAMP_NEAR = Double.parseDouble(System.getProperty("emergence.dampNear", "0"));

    // energy ledger
    private double storedInitial, externalIn, untrackedOut, lastPext, lastPloss;
    /** The circuits' share of the ledger (kept apart: they may step on their own thread). */
    private double emExternalIn, emUntrackedOut;
    /** The plant's independent parts' share (they may step on a thread of their own). */
    private double partExternalIn, partUntrackedOut;
    /**
     * Whether the stored energy is to be summed again: it is summed when asked for, not at every step (summed over
     * every element at each of a machine's thousands of steps a second, it was a twentieth of a circuit's time).
     */
    private boolean storedStale = true;
    private double storedCache;

    // statistics
    private final java.util.concurrent.atomic.LongAdder acceptedSteps = new java.util.concurrent.atomic.LongAdder(),
            rejectedSteps = new java.util.concurrent.atomic.LongAdder(), newtonFailures = new java.util.concurrent.atomic.LongAdder(),
            newtonIterations = new java.util.concurrent.atomic.LongAdder();

    /**
     * A part of the system stepped with its own clock: the whole system (single rate), or under multirate the fast
     * partition (everything but the thermal network) and the slow one (the thermal network). Each keeps its time, step
     * history and integration state; the unknowns of the other are held fixed while it steps.
     */
    private final class Phase {
        double t, hPrev = 1e-6, hNext;
        int validHistory;
        boolean restart = true, forceBE;
        /** Elements loaded (indices into {@link #elements}); null: all. */
        int[] elems;
        /** Its id among the plant's parts (see LoadContext#partOf): 0 the plant phase, k its k-th part, −2 the circuits, −1 none. */
        int partId = -1;
        /** Slots it integrates; null: all. */
        int[] slots;
        /** Unknowns held fixed while it steps; null: none. */
        boolean[] frozen;
        /** The rows that move in it and those held (built from {@link #frozen}: its Newton loops visit only its own rows). */
        int[] free, held;
        /** Diagnostics: Newton iterations, accepted and rejected steps, Newton failures, restarts by discontinuities. */
        long nNewton, nAccepted, nRejected, nFailed, nRestarts;
        /** Diagnostics: wall time in its loads, its linear solves, and its whole tryStep calls [ns]. */
        long loadNs, solveNs, stepNs;
        /** The matrix's entry count when the datums last looked at it (−1: never). */
        int datumNnz = -1;
        /** Diagnostics: how many iterations its converged solves took (index = iterations − 1). */
        long[] itHist = Boolean.getBoolean("diag.itHist") ? new long[41] : null;
        boolean[] freeOf;
        /** Heat [W] delivered into each unknown's row from the other partition (slow phase only), or null. */
        double[] heatIn;
        /** The rows {@link #heatIn} feeds (slow rows for the thermal network; the plant's rows the circuits drive). */
        int[] inRows;
        /** Its last rate of heat into each slow row [W] (the trapezoid's other end), and its ∂(heat in)/∂T there. */
        double[] lastRate, cpl;
        /** Last external and untracked power of its elements, for the ledger's trapezoid. */
        double lastPext, lastPloss;
        /** Its Jacobian: under multirate each phase factorises only what moves in it (see LoadContext#frozen). */
        LinearSystem jac;
        /** Its elements with kinks, each kink's switching function at its last accepted state, and the step to resume at. */
        Element[] kinkEls;
        double[][] kinkPrev;
        double kinkResumeH;
        boolean kinkLanded;
        /**
         * Its working arrays and assembly context: its own where it steps on a thread of its own (the circuits, see
         * CONCURRENT_EM), else the solver's (set on first use).
         */
        LoadContext ctx;
        double[] f, dx, dxPrev, xTrial;
        LoadContext[] workers;
        dev.emergence.core.math.SparseLinearSystem.Sink[] sinks;
        LinearSystem sinksOf;
        Tuning tuning;
        LinearSystem tuningOf;
        /** The rescue's relaxed tolerances (see stepAdaptive0). */
        double tolScale = 1;
        boolean skipLte;
    }

    /** A phase works in the solver's own arrays and context. */
    private void shareScratch(Phase ph) {
        ph.f = f;
        ph.dx = dx;
        ph.dxPrev = dxPrev;
        ph.xTrial = xTrial;
        ph.ctx = ctx;
    }

    /** A phase gets arrays and a context of its own (it steps on a thread of its own). */
    private void ownScratch(Phase ph) {
        LoadContext c = new LoadContext();
        c.q0 = q0;
        c.q1 = q1;
        c.qd0 = qd0;
        c.qNew = qNew;
        c.qdNew = qdNew;
        c.f = ph.f = new double[n];
        c.jac = ph.jac;
        c.partOf = ctx.partOf;
        ph.dx = new double[n];
        ph.dxPrev = new double[n];
        ph.xTrial = new double[n];
        ph.ctx = c;
    }

    private final Phase all = new Phase();
    private Phase active = all;

    // ---- multirate (see #multirate)
    private boolean multirate;
    private Phase fast, slow;
    private int[] slowRows;
    /** ∫ heat delivered into each slow row by the fast partition since the last synchronisation [J], and its rate [W]. */
    private double[] heatAccum, heatRate;
    /** The circuits' share of {@link #heatAccum} (kept apart: they may step on their own thread). */
    private double[] emHeat, partHeat;
    /** Per slow row: heat capacity [J/K] and the fast side's latest ∂(heat in)/∂T [W/K] (the coupling's stiffness). */
    private double[] capacity, coupling;
    private long slowSteps;
    /**
     * {@code emergence.multirateEm} (on by default): the circuits (electrical nodes, coils' currents, the iron's magnetisation) stepped
     * apart from the plant (mechanisms, fluids): each plant step first, with what the circuits deliver into its rows
     * (a generator's torque) carried on from their means over the last two intervals; then the circuits across the same interval in
     * steps of their own, the plant's rows (a shaft's angle and speed) between the plant step's two ends.
     */
    static final boolean MULTIRATE_EM = !"false".equals(System.getProperty("emergence.multirateEm"));
    private Phase em;
    /** The plant's rows the circuits write, their ∫ delivery over the current interval, and the rows the circuits hold. */
    private int[] plantInRows, plantRows;
    private double[] emAccum, emRate, plantStart, emMeanPrev;
    /** The plant's rows at the end of the interval the circuits are crossing (a snapshot: the plant may be past it). */
    private double[] plantEnd;
    /** The circuits' interval ends. */
    private double emT1;
    /**
     * {@code emergence.concurrentEm} (on by default): the circuits cross one plant interval on this thread while the
     * plant takes its next step on another — the plant a step ahead, its input carried on from the circuits' means
     * over the two intervals before (see plantWithCircuits).
     */
    static final boolean CONCURRENT_EM = !"false".equals(System.getProperty("emergence.concurrentEm"));
    /** Whether the circuits step alongside the plant here (their states and slots apart from the plant's). */
    private boolean overlap;
    /** The plant is stepping on another thread now. */
    private volatile boolean inFlight;
    /** A discontinuity marked while the plant stepped on another thread: applied when it is back. */
    private boolean discontinuityLater;
    /** A plant interval the circuits have still to cross: [pendT0, pendT1]. */
    private boolean pending;
    private double pendT0, pendT1;
    /** The circuits' rows as they were when the plant set off on its step (what it holds them at). */
    private double[] emHeld;
    /** The circuits' means over their last two intervals and those intervals' middles (the overlapped plant's input). */
    private double[] emMeanLast;
    private double emMidLast = Double.NaN, emMidPrev = Double.NaN;
    /** Plant steps overlapped with the circuits, and times the circuits were brought level (diagnostics). */
    private volatile long taskDelay, taskRun, plantSpin, circuitsSpin;
    private long overlaps, drains, overlapBusy, overlapWait, partsNanos, syncNanos, drainNanos, lonePlantNanos;
    private boolean emMeanValid;
    private double emT0;
    /** Energy the circuits delivered into the plant's rows not yet taken up by it (work along the plant's motion) [J]. */
    private double couplingTransit;
    /** The plant's side of it: what it took up of the circuits' delivery (negative) [J]. */
    private double plantTransit;
    private long emSteps;
    /** The latest thermal step's largest change relative to its allowance (see #tryStep). */
    private double slowChange;

    public TransientSolver(Network network) {
        this.net = network;
        net.compile();
        this.elements = net.elements();
        for (Element e : elements) if (e instanceof EventSource es) eventSources.add(es);
        this.n = net.size();
        this.ns = net.slotCount();
        this.jac = LinearSystem.create(Math.max(n, 1));
        x = new double[n];
        xPrev = new double[n];
        xTrial = new double[n];
        f = new double[n];
        dx = new double[n];
        dxPrev = new double[n];
        q0 = new double[ns];
        q1 = new double[ns];
        qd0 = new double[ns];
        qd1 = new double[ns];
        qNew = new double[ns];
        qdNew = new double[ns];
        absTol = new double[n];
        slotTol = new double[ns];
        gmin = new double[n];
        relGmin = new boolean[n];
        alternations = new int[ns];
        for (int i = 0; i < n; i++) {
            absTol[i] = net.unknownAbsTol(i);
            x[i] = net.initialValue(i);
        }
        for (Node node : net.nodes()) {
            gmin[node.index] = node.domain.gmin;
            relGmin[node.index] = node.domain == Domain.ELECTRICAL;
        }
        if (net.isElectricalUngrounded() && !Boolean.getBoolean("diag.noDatum")) { // (diag.noDatum: the circuits left floating)
            circuitOf = new int[n];
            for (int i = 0; i < n; i++) circuitOf[i] = i;
            thermalUnknown = new boolean[n];
            electricalNode = new boolean[n];
            for (Node node : net.nodes()) {
                thermalUnknown[node.index] = node.domain == Domain.THERMAL;
                electricalNode[node.index] = node.domain == Domain.ELECTRICAL;
            }
        }
        for (int s = 0; s < ns; s++) slotTol[s] = net.slotAbsTol(s);
        stepLimit = new double[n];
        for (int i = 0; i < n; i++) stepLimit[i] = net.stepLimit(i);
        limiters = new java.util.function.DoubleBinaryOperator[n];
        for (int i = 0; i < n; i++) limiters[i] = net.limiter(i);
        ctx.q0 = q0;
        ctx.q1 = q1;
        ctx.qd0 = qd0;
        ctx.qNew = qNew;
        ctx.qdNew = qdNew;
        ctx.f = f;
        ctx.jac = jac;
        hNext = hRestart;
        all.jac = jac;
        initializeHistory();
    }

    // ------------------------------------------------------------------ parallel loading

    /**
     * {@code emergence.parallelLoad} (on by default): a large machine's elements are loaded side by side on the common
     * pool, each worker into its own residual and its own copy of the matrix's values (see SparseLinearSystem.Sink),
     * summed in a fixed order afterwards — the same result whatever the scheduling. Below {@link #PARALLEL_MIN}
     * elements the hand-off costs more than it saves (a phase of the plant alone, ~500 of them, gained 1.7×).
     */
    static final boolean PARALLEL_LOAD = !"false".equals(System.getProperty("emergence.parallelLoad"));
    static final int PARALLEL_MIN = Integer.getInteger("emergence.parallelMin", 200);
    static final int PARALLEL_WAYS = Math.max(1, Math.min(8, Integer.getInteger("emergence.parallelWays",
            java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() + 1)));
    /** Passes loaded in parallel, and those that fell back to a serial pass (diagnostics). */
    private final java.util.concurrent.atomic.LongAdder parallelPasses = new java.util.concurrent.atomic.LongAdder(),
            parallelFallbacks = new java.util.concurrent.atomic.LongAdder();

    public long parallelPasses() {
        return parallelPasses.sum();
    }

    public long parallelFallbacks() {
        return parallelFallbacks.sum();
    }

    private boolean parallelEligible(LoadContext ctx, LinearSystem jac, int[] el) {
        int count = el == null ? elements.size() : el.length;
        return PARALLEL_LOAD && PARALLEL_WAYS >= 2 && count >= PARALLEL_MIN && !ctx.initializing && ctx.touched == null
                && jac instanceof dev.emergence.core.math.SparseLinearSystem;
    }

    /** Loads the phase's elements in parallel; false if the caller should load them serially. */
    private boolean loadParallel(Phase ph, LinearSystem jac, int[] el) {
        LoadContext ctx = ph.ctx;
        int count = el == null ? elements.size() : el.length;
        var sp = (dev.emergence.core.math.SparseLinearSystem) jac;
        int ways = PARALLEL_WAYS;
        if (ph.sinks == null || ph.sinksOf != jac) {
            ph.sinks = new dev.emergence.core.math.SparseLinearSystem.Sink[ways];
            for (int i = 0; i < ways; i++) ph.sinks[i] = sp.newSink();
            ph.sinksOf = jac;
        }
        var sk = ph.sinks;
        if (ph.workers == null) {
            ph.workers = new LoadContext[ways];
            for (int i = 0; i < ways; i++) ph.workers[i] = new LoadContext();
        }
        LoadContext[] workers = ph.workers;
        java.util.concurrent.ForkJoinTask<?>[] tasks = new java.util.concurrent.ForkJoinTask<?>[ways];
        for (int w = 0; w < ways; w++) {
            LoadContext c = workers[w];
            c.x = ctx.x;
            c.time = ctx.time;
            c.h = ctx.h;
            c.hPrev = ctx.hPrev;
            c.method = ctx.method;
            c.initializing = false;
            c.alpha = ctx.alpha;
            c.q0 = ctx.q0;
            c.q1 = ctx.q1;
            c.qd0 = ctx.qd0;
            c.qNew = ctx.qNew;
            c.qdNew = ctx.qdNew;
            c.frozen = ctx.frozen;
            c.residualOnly = ctx.residualOnly;
            c.partOf = ctx.partOf;
            c.part = ctx.part;
            c.crossed = false;
            boolean held = ctx.frozen != null;
            if (!held) c.frozenDiag = null;
            final var sink = sk[w];
            c.jac = sink;
            final int first = w;
            tasks[w] = java.util.concurrent.ForkJoinTask.adapt(() -> {
                // (each worker clears its own accumulators: cleared here, serially, they were a tenth of a pass)
                if (c.f == null || c.f.length != n) c.f = new double[n];
                else Arrays.fill(c.f, 0.0);
                if (c.floor == null || c.floor.length != n) c.floor = new double[n];
                else Arrays.fill(c.floor, 0.0);
                if (held) {
                    if (c.frozenDiag == null || c.frozenDiag.length != n) c.frozenDiag = new double[n];
                    else Arrays.fill(c.frozenDiag, 0.0);
                }
                sink.clear();
                // the elements w, w + ways, w + 2·ways, …: interleaved, so each worker gets a share of every kind
                for (int i = first; i < count; i += ways) {
                    int k = el == null ? i : el[i];
                    c.jac.beginSegment(k);
                    elements.get(k).load(c);
                }
                c.jac.beginSegment(-1);
            });
        }
        for (int w = 1; w < ways; w++) tasks[w].fork();
        tasks[0].invoke();
        for (int w = 1; w < ways; w++) tasks[w].join();
        for (LoadContext c : workers) ctx.crossed |= c.crossed;
        for (var s : sk) {
            if (s.missed()) {
                // an element stamped off its record (a new entry, a changed pattern): this pass again, serially, which
                // records it
                parallelFallbacks.increment();
                return false;
            }
        }
        // the workers' sums merged in parallel too, each worker a range of entries and rows, worker by worker in order
        int nnz = sp.nonZeros();
        for (int w = 0; w < ways; w++) {
            final int r0 = (int) ((long) n * w / ways), r1 = (int) ((long) n * (w + 1) / ways);
            final int e0 = (int) ((long) nnz * w / ways), e1 = (int) ((long) nnz * (w + 1) / ways);
            tasks[w] = java.util.concurrent.ForkJoinTask.adapt(() -> {
                for (LoadContext c : workers) {
                    for (int i = r0; i < r1; i++) {
                        ctx.f[i] += c.f[i];
                        if (c.floor[i] > ctx.floor[i]) ctx.floor[i] = c.floor[i];
                    }
                    if (c.frozenDiag != null && ctx.frozenDiag != null) for (int i = r0; i < r1; i++) ctx.frozenDiag[i] += c.frozenDiag[i];
                }
                sp.merge(sk, e0, e1);
            });
        }
        for (int w = 1; w < ways; w++) tasks[w].fork();
        tasks[0].invoke();
        for (int w = 1; w < ways; w++) tasks[w].join();
        parallelPasses.increment();
        return true;
    }

    // ------------------------------------------------------------------ configuration

    /** Every element stamps the Jacobian and residual, each in a segment of its own (see SparseLinearSystem). */
    private void loadAll() {
        loadAll(active);
    }

    private void loadAll(Phase ph) {
        if (ph.ctx == null) shareScratch(ph);
        LoadContext ctx = ph.ctx;
        if (ctx.floor == null || ctx.floor.length != n) ctx.floor = new double[n];
        else Arrays.fill(ctx.floor, 0.0);
        LinearSystem jac = ph.jac;
        ctx.jac = jac;
        ctx.frozen = ph.frozen;
        ctx.part = ph.partId;
        if (ph.frozen != null) {
            if (ctx.frozenDiag == null) ctx.frozenDiag = new double[n];
            else Arrays.fill(ctx.frozenDiag, 0.0);
        }
        int[] el = ph.elems;
        // in parallel where it pays: each phase times its passes both ways and takes the faster, trying the other again
        // now and then (a loudspeaker's thousands of tiny passes a tick lose more to the hand-off than they gain)
        Tuning tu = null;
        if (parallelEligible(ctx, jac, el)) {
            if (ph.tuning == null || ph.tuningOf != jac) {
                ph.tuning = new Tuning();
                ph.tuningOf = jac;
            }
            tu = ph.tuning;
        }
        boolean par = tu != null && tu.choose();
        long t0 = System.nanoTime();
        if (par && loadParallel(ph, jac, el)) {
            tu.record(true, System.nanoTime() - t0);
            return;
        }
        if (el == null) {
            for (int k = 0; k < elements.size(); k++) {
                jac.beginSegment(k);
                elements.get(k).load(ctx);
            }
        } else {
            for (int k : el) {
                jac.beginSegment(k);
                elements.get(k).load(ctx);
            }
        }
        jac.beginSegment(-1);
        if (tu != null && !par) tu.record(false, System.nanoTime() - t0);
    }

    /** A phase's passes timed serially and in parallel: moving averages [ns], and how many it has made. */
    private static final class Tuning {
        double serial = Double.NaN, parallel = Double.NaN;
        long passes;

        boolean choose() {
            passes++;
            if (Double.isNaN(parallel)) return true;
            if (Double.isNaN(serial)) return false;
            boolean faster = parallel < serial;
            return passes % 64 == 0 ? !faster : faster;
        }

        void record(boolean par, long nanos) {
            if (par) parallel = Double.isNaN(parallel) ? nanos : 0.9 * parallel + 0.1 * nanos;
            else serial = Double.isNaN(serial) ? nanos : 0.9 * serial + 0.1 * nanos;
        }
    }


    /**
     * Multirate stepping: the thermal network — thermal nodes and the elements that touch nothing else (heat
     * capacities, conduction, convection, radiation) — is stepped apart from the rest, at its own pace. The fast
     * partition steps with temperatures held; the heat its elements deliver into thermal nodes (Joule heat, friction,
     * absorbed light…) is integrated (trapezoid) and handed to the thermal network, at each synchronisation, as a
     * constant power over the interval — so energy moves across exactly. Synchronisations come at least at the end of
     * every {@link #advance}, and as often as the thermal network's error control and the coupling's stability ask:
     * the interval stays below half the shortest C/G, G the fast side's ∂(heat in)/∂T at a node (a filament whose
     * resistance rises with its temperature). Off by default; a no-op where either partition would be empty.
     */
    /** Thermal nodes kept in the fast partition for their tight coupling (found as they tighten; kept). */
    private boolean[] tightNodes;
    private boolean repartitioning, tightFound;

    /** Marks the slow rows whose C/G is now under TIGHT_S; true if any is new. */
    private boolean findTight() {
        boolean any = false;
        for (int i : slowRows) {
            double g = Math.abs(couplingAt(i));
            if (g > 0 && capacity[i] > 0 && capacity[i] / g < TIGHT_S) {
                if (tightNodes == null || tightNodes.length != n) tightNodes = new boolean[n];
                if (!tightNodes[i]) any = true;
                tightNodes[i] = true;
            }
        }
        return any;
    }

    /** The partition done again (the tight nodes in the fast one), from the state as it is now. */
    private TransientSolver repartition() {
        repartitioning = true;
        try {
            return multirate(true);
        } finally {
            repartitioning = false;
            tightFound = false;
        }
    }
    /** {@code emergence.tightThermalS}: below this C/G [s] a thermal node steps with the fast side (0: never). */
    static final double TIGHT_S = Double.parseDouble(System.getProperty("emergence.tightThermalS", "0"));

    public TransientSolver multirate(boolean on) {
        multirate = false;
        active = all;
        if (!on || n == 0) return this;
        boolean[] isNode = new boolean[n], thermal = new boolean[n];
        for (Node nd : net.nodes()) {
            if (nd.index < 0) continue;
            isNode[nd.index] = true;
            thermal[nd.index] = nd.domain == Domain.THERMAL;
        }
        // dry run: the rows and slots each element writes
        int ne = elements.size();
        boolean[][] rows = new boolean[ne][], slotsOf = new boolean[ne][];
        ctx.x = x;
        ctx.time = t;
        ctx.h = hRestart;
        ctx.hPrev = hRestart;
        ctx.method = IntegrationMethod.BACKWARD_EULER;
        ctx.alpha = 1 / hRestart;
        ctx.initializing = false;
        if (ctx.floor == null || ctx.floor.length != n) ctx.floor = new double[n];
        double[] q0s = q0.clone(), qd0s = qd0.clone();
        ctx.jac = jac;
        ctx.frozen = null;
        boolean[][] cols = new boolean[ne][];
        for (int k = 0; k < ne; k++) {
            ctx.touched = new boolean[n];
            ctx.touchedCols = new boolean[n];
            ctx.touchedSlots = new boolean[ns];
            Arrays.fill(f, 0);
            jac.clear();
            jac.beginSegment(k);
            elements.get(k).load(ctx);
            jac.beginSegment(-1);
            rows[k] = ctx.touched;
            cols[k] = ctx.touchedCols;
            slotsOf[k] = ctx.touchedSlots;
        }
        ctx.touchedCols = null;
        ctx.touched = null;
        ctx.touchedSlots = null;
        System.arraycopy(q0s, 0, q0, 0, ns);
        System.arraycopy(qd0s, 0, qd0, 0, ns);
        Arrays.fill(f, 0);
        jac.clear();
        // slow candidates: elements whose node rows are all thermal (and there is one); their internal rows are slow
        // unless an element outside the candidates writes them too (then neither is slow) — to a fixed point
        boolean[] cand = new boolean[ne];
        for (int k = 0; k < ne; k++) {
            boolean anyThermal = false, onlyThermal = true;
            for (int i = 0; i < n; i++) {
                if (!rows[k][i] || !isNode[i]) continue;
                if (thermal[i]) anyThermal = true;
                else onlyThermal = false;
            }
            cand[k] = anyThermal && onlyThermal;
        }
        // a thermal node is slow only where a candidate stores heat at it (a part's thermal mass): a gas's or liquid's
        // temperature, whose inertia lives in its fluid element, moves with the flow and stays in the fast partition
        boolean[] slowNode = new boolean[n];
        boolean changed = true;
        while (changed) {
            changed = false;
            Arrays.fill(slowNode, false);
            for (int k = 0; k < ne; k++) {
                if (!cand[k]) continue;
                boolean stores = false;
                for (int s = 0; s < ns && !stores; s++) stores = slotsOf[k][s];
                if (!stores) continue;
                for (int i = 0; i < n; i++) if (rows[k][i] && isNode[i] && thermal[i]) slowNode[i] = true;
            }
            // a thermal node written by candidates alone is slow too, though none stores heat there: the ambient, held by
            // its fixed temperature, which every part's convection and radiation touch (counted fast, they were loaded at
            // every fast step — a quarter of the loudspeaker's time at audio rate)
            for (int i = 0; i < n; i++) {
                if (!isNode[i] || !thermal[i] || slowNode[i]) continue;
                boolean any = false, allCand = true;
                for (int k = 0; k < ne && allCand; k++) {
                    if (!rows[k][i]) continue;
                    any = true;
                    allCand = cand[k];
                }
                if (any && allCand) slowNode[i] = true;
            }
            if (tightNodes != null) for (int i = 0; i < n; i++) if (tightNodes[i]) slowNode[i] = false;
            for (int k = 0; k < ne; k++) {
                if (!cand[k]) continue;
                for (int i = 0; i < n; i++) {
                    if (rows[k][i] && isNode[i] && !slowNode[i]) {
                        cand[k] = false;
                        changed = true;
                        break;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (isNode[i]) continue;
                boolean byCand = false, byOther = false;
                for (int k = 0; k < ne; k++) {
                    if (!rows[k][i]) continue;
                    if (cand[k]) byCand = true;
                    else byOther = true;
                }
                if (byCand && byOther) {
                    for (int k = 0; k < ne; k++) {
                        if (rows[k][i] && cand[k]) {
                            cand[k] = false;
                            changed = true;
                        }
                    }
                }
            }
        }
        boolean[] slowR = new boolean[n];
        for (int i = 0; i < n; i++) if (slowNode[i]) slowR[i] = true;
        for (int k = 0; k < ne; k++) {
            if (!cand[k]) continue;
            for (int i = 0; i < n; i++) if (rows[k][i] && !isNode[i]) slowR[i] = true;
        }
        java.util.List<Integer> fe = new java.util.ArrayList<>(), se = new java.util.ArrayList<>();
        for (int k = 0; k < ne; k++) (cand[k] ? se : fe).add(k);
        boolean[] slowSlot = new boolean[ns];
        for (int k : se) for (int s = 0; s < ns; s++) if (slotsOf[k][s]) slowSlot[s] = true;
        int nSlow = 0;
        for (boolean b : slowR) if (b) nSlow++;
        if (nSlow == 0 || nSlow == n || fe.isEmpty() || se.isEmpty()) return this;
        slowRows = new int[nSlow];
        for (int i = 0, j = 0; i < n; i++) if (slowR[i]) slowRows[j++] = i;
        boolean[] fastR = new boolean[n];
        for (int i = 0; i < n; i++) fastR[i] = !slowR[i];
        fast = new Phase();
        slow = new Phase();
        fast.jac = LinearSystem.create(n);
        slow.jac = LinearSystem.create(n);
        fast.elems = fe.stream().mapToInt(Integer::intValue).toArray();
        slow.elems = se.stream().mapToInt(Integer::intValue).toArray();
        java.util.List<Integer> fs = new java.util.ArrayList<>(), ss = new java.util.ArrayList<>();
        for (int s = 0; s < ns; s++) (slowSlot[s] ? ss : fs).add(s);
        fast.slots = fs.stream().mapToInt(Integer::intValue).toArray();
        slow.slots = ss.stream().mapToInt(Integer::intValue).toArray();
        fast.frozen = slowR;
        slow.frozen = fastR;
        slow.heatIn = new double[n];
        slow.inRows = slowRows;
        fast.lastRate = new double[n];
        fast.cpl = new double[n];
        em = null;
        eventInEm = null;
        overlap = false;
        pending = false;
        if (MULTIRATE_EM) splitCircuits(fe, rows, cols, slotsOf, isNode, slowR);
        parts = new Phase[0];
        ctx.partOf = null;
        partsMet = false;
        if (INDEPENDENT) splitIndependent(rows, cols, slotsOf);
        heatAccum = new double[n];
        emHeat = new double[n];
        partHeat = new double[n];
        heatRate = new double[n];
        coupling = new double[n];
        // heat capacities: the slow elements' ∂F/∂T with α = 1 less that with α = 0
        capacity = new double[n];
        Phase saved = active;
        active = slow;
        for (int pass = 0; pass < 2; pass++) {
            ctx.alpha = pass == 0 ? 1 : 0;
            Arrays.fill(f, 0);
            slow.jac.clear();
            loadAll();
            for (int i : slowRows) capacity[i] += (pass == 0 ? 1 : -1) * slow.jac.diagonal(i);
        }
        System.arraycopy(q0s, 0, q0, 0, ns);
        System.arraycopy(qd0s, 0, qd0, 0, ns);
        Arrays.fill(f, 0);
        slow.jac.clear();
        active = saved;
        ctx.jac = active.jac;
        ctx.frozen = active.frozen;
        multirate = true;
        resetPhases();
        // a part's heat held so tightly to the fast side that its time constant C/G is under TIGHT_S (a small valve body
        // under condensing steam) would force a synchronisation every few milliseconds: it steps with the fast side
        // instead, one unknown more there (the partition done again with it so)
        if (!repartitioning && TIGHT_S > 0 && findTight()) return repartition();
        return this;
    }

    /**
     * Splits the circuits off the fast phase: electrical nodes, and the rows of their own that the elements writing them
     * keep (a coil's current); an element writing only rows of its own goes with what it reads. Every other fast row is
     * the plant's.
     */
    private void splitCircuits(java.util.List<Integer> fe, boolean[][] rows, boolean[][] cols, boolean[][] slotsOf,
                               boolean[] isNode, boolean[] slowR) {
        boolean[] emR = new boolean[n], emEl = new boolean[elements.size()];
        for (Node nd : net.nodes()) if (nd.index >= 0 && nd.domain == Domain.ELECTRICAL && !slowR[nd.index]) emR[nd.index] = true;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k : fe) {
                if (emEl[k]) continue;
                boolean writes = false, anyNode = false, readsEm = false;
                for (int i = 0; i < n; i++) {
                    if (rows[k][i]) {
                        if (emR[i]) writes = true;
                        if (isNode[i]) anyNode = true;
                    }
                    if (cols[k][i] && emR[i]) readsEm = true;
                }
                if (writes || !anyNode && readsEm) {
                    emEl[k] = true;
                    changed = true;
                }
            }
            for (int i = 0; i < n; i++) {
                if (emR[i] || isNode[i] || slowR[i]) continue;
                boolean byEm = false, ownedByPlant = false;
                for (int k : fe) {
                    if (!rows[k][i]) continue;
                    if (emEl[k]) byEm = true;
                    else {
                        boolean stores = false;
                        for (int sl = 0; sl < ns && !stores; sl++) stores = slotsOf[k][sl];
                        ownedByPlant |= stores;
                    }
                }
                if (byEm && !ownedByPlant) {
                    emR[i] = true;
                    changed = true;
                }
            }
        }
        java.util.List<Integer> ee = new java.util.ArrayList<>(), pe = new java.util.ArrayList<>();
        for (int k : fe) (emEl[k] ? ee : pe).add(k);
        if (ee.isEmpty() || pe.isEmpty()) return;
        boolean[] plantR = new boolean[n], emFrozen = new boolean[n], plantFrozen = new boolean[n], inR = new boolean[n];
        for (int i = 0; i < n; i++) {
            plantR[i] = !slowR[i] && !emR[i];
            emFrozen[i] = !emR[i];
            plantFrozen[i] = slowR[i] || emR[i];
        }
        for (int k : ee) for (int i = 0; i < n; i++) if (rows[k][i] && plantR[i]) inR[i] = true;
        Phase c = new Phase();
        c.jac = LinearSystem.create(n);
        c.elems = ee.stream().mapToInt(Integer::intValue).toArray();
        java.util.List<Integer> cs = new java.util.ArrayList<>(), ps = new java.util.ArrayList<>();
        for (int sl = 0; sl < ns; sl++) {
            boolean byEm = false, byPlant = false;
            for (int k : ee) byEm |= slotsOf[k][sl];
            for (int k : pe) byPlant |= slotsOf[k][sl];
            if (byEm) cs.add(sl);
            if (byPlant) ps.add(sl);
        }
        c.slots = cs.stream().mapToInt(Integer::intValue).toArray();
        c.frozen = emFrozen;
        c.lastRate = new double[n];
        c.cpl = new double[n];
        fast.elems = pe.stream().mapToInt(Integer::intValue).toArray();
        fast.slots = ps.stream().mapToInt(Integer::intValue).toArray();
        fast.frozen = plantFrozen;
        int nIn = 0, nPlant = 0;
        for (int i = 0; i < n; i++) {
            if (inR[i]) nIn++;
            if (plantR[i]) nPlant++;
        }
        plantInRows = new int[nIn];
        plantRows = new int[nPlant];
        for (int i = 0, a = 0, b = 0; i < n; i++) {
            if (inR[i]) plantInRows[a++] = i;
            if (plantR[i]) plantRows[b++] = i;
        }
        fast.heatIn = new double[n];
        fast.inRows = plantInRows;
        emAccum = new double[n];
        emRate = new double[n];
        emMeanPrev = new double[n];
        powerRow = new boolean[n];
        for (Node nd : net.nodes())
            if (nd.index >= 0 && (nd.domain == Domain.ROTATIONAL || nd.domain == Domain.TRANSLATIONAL)) powerRow[nd.index] = true;
        plantStart = new double[n];
        plantEnd = new double[n];
        emHeld = new double[n];
        emMeanLast = new double[n];
        emMidLast = emMidPrev = Double.NaN;
        pending = false;
        em = c;
        // overlapped only where the circuits and the plant keep no state in common (a slot both integrate)
        boolean shared = false;
        java.util.Set<Integer> plantSlots = new java.util.HashSet<>(ps);
        for (int q : cs) shared |= plantSlots.contains(q);
        overlap = CONCURRENT_EM && !shared && java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() >= 2;
        if (overlap) ownScratch(c);
        if (Boolean.getBoolean("emergence.debugMultirate")) {
            int nEm = 0;
            for (boolean b : emR) if (b) nEm++;
            System.out.printf("multirate: circuits %d unknowns, %d elements; plant %d unknowns, %d elements, %d rows driven by the circuits%n",
                    nEm, ee.size(), nPlant, pe.size(), nIn);
        }
    }

    /** {@code emergence.independent} (on by default): parts of the plant that share no unknown step apart (see splitIndependent). */
    static final boolean INDEPENDENT = !"false".equals(System.getProperty("emergence.independent"));
    /** The plant's independent parts, each stepping on its own (empty if it is all one). */
    private Phase[] parts = new Phase[0];
    private static final boolean DBG_PARTS = Boolean.getBoolean("diag.parts");
    /** Set when an element reached across parts (see LoadContext#crossed): they are joined at the next chance. */
    private boolean partsMet;
    /** The plant phase as it was before the split (restored when the parts meet). */
    private boolean[] plantHeldWhole;
    private int[] plantRowsWhole;
    private long partMerges;

    /** Times the plant's parts met and were joined again (diagnostics). */
    public long partMerges() {
        return partMerges;
    }

    /**
     * The parts met (see LoadContext#crossed): each brought up to the plant's time, then all of them joined into the
     * plant phase again, for good (until the machine is rebuilt), its integration started afresh.
     */
    private void joinParts() {
        partsMet = false;
        if (parts.length == 0) return;
        stepParts(t, true);
        partsMet = false;
        java.util.TreeSet<Integer> el = new java.util.TreeSet<>(), sl = new java.util.TreeSet<>();
        for (int k : fast.elems) el.add(k);
        for (int q : fast.slots) sl.add(q);
        double hn = fast.hNext;
        for (Phase p : parts) {
            for (int k : p.elems) el.add(k);
            for (int q : p.slots) sl.add(q);
            for (int i = 0; i < n; i++) {
                fast.cpl[i] += p.cpl[i];
                fast.lastRate[i] += p.lastRate[i];
            }
            fast.lastPext += p.lastPext;
            fast.lastPloss += p.lastPloss;
            hn = Math.min(hn, p.hNext);
        }
        fast.elems = el.stream().mapToInt(Integer::intValue).toArray();
        fast.slots = sl.stream().mapToInt(Integer::intValue).toArray();
        fast.frozen = plantHeldWhole;
        if (plantRowsWhole != null) plantRows = plantRowsWhole;
        fast.free = null;
        fast.restart = true;
        fast.validHistory = 0;
        fast.hNext = Math.min(hn, hRestart);
        parts = new Phase[0];
        ctx.partOf = null;
        if (em != null && em.ctx != null) em.ctx.partOf = null;
        setupKinks(fast);
        partMerges++;
        if (DBG_PARTS || Boolean.getBoolean("emergence.debugMultirate")) System.out.printf("multirate: the plant's parts met at t=%.4f s and are one again%n", t);
    }

    /**
     * Splits off the parts of the plant that share nothing with the rest within it — no unknown that both write or read,
     * no state — and meet the rest only through the thermal network (a firebox's gas and a steam engine, joined by the
     * boiler's walls). Each then steps at the pace its own physics asks, not at the fastest part's, and brings itself
     * up to date before each synchronisation with the thermal network, which is where they meet: an exact split, since
     * within the plant they are separate systems. The part that the circuits read and drive, or whose elements raise
     * events, stays the plant phase itself.
     */
    private void splitIndependent(boolean[][] rows, boolean[][] cols, boolean[][] slotsOf) {
        int[] el = fast.elems;
        boolean[] held = fast.frozen;
        int m = el.length;
        int[] parent = new int[m];
        for (int a = 0; a < m; a++) parent[a] = a;
        java.util.function.IntUnaryOperator find = a -> {
            while (parent[a] != a) a = parent[a] = parent[parent[a]];
            return a;
        };
        int[] rowOwner = new int[n], slotOwner = new int[ns];
        Arrays.fill(rowOwner, -1);
        Arrays.fill(slotOwner, -1);
        boolean[] touchesFree = new boolean[m];
        for (int a = 0; a < m; a++) {
            int k = el[a];
            for (int i = 0; i < n; i++) {
                if (held != null && held[i] || !(rows[k][i] || cols[k][i])) continue;
                touchesFree[a] = true;
                if (rowOwner[i] < 0) rowOwner[i] = a;
                else {
                    int ra = find.applyAsInt(a), rb = find.applyAsInt(rowOwner[i]);
                    if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
                }
            }
            for (int sl = 0; sl < ns; sl++) {
                if (!slotsOf[k][sl]) continue;
                if (slotOwner[sl] < 0) slotOwner[sl] = a;
                else {
                    int ra = find.applyAsInt(a), rb = find.applyAsInt(slotOwner[sl]);
                    if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
                }
            }
        }
        // what stays with the plant phase: elements touching no free row, those raising events, the parts holding rows
        // the circuits read or drive
        boolean[] primary = new boolean[m];
        boolean[] emReads = new boolean[n];
        if (em != null) {
            for (int k : em.elems) for (int i = 0; i < n; i++) if (cols[k][i] || rows[k][i]) emReads[i] = true;
        }
        for (int a = 0; a < m; a++) {
            int k = el[a];
            if (!touchesFree[a] || elements.get(k) instanceof EventSource) primary[find.applyAsInt(a)] = true;
        }
        for (int i = 0; i < n; i++) if (emReads[i] && rowOwner[i] >= 0) primary[find.applyAsInt(rowOwner[i])] = true;
        java.util.Map<Integer, java.util.List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int a = 0; a < m; a++) groups.computeIfAbsent(find.applyAsInt(a), r -> new java.util.ArrayList<>()).add(a);
        boolean anyPrimary = false;
        for (int r : groups.keySet()) anyPrimary |= primary[r];
        if (!anyPrimary) {
            // none is singled out: the largest stays
            int best = -1;
            for (var en : groups.entrySet()) if (best < 0 || en.getValue().size() > groups.get(best).size()) best = en.getKey();
            primary[best] = true;
        }
        java.util.List<Integer> keep = new java.util.ArrayList<>();
        java.util.List<java.util.List<Integer>> split = new java.util.ArrayList<>();
        for (var en : groups.entrySet()) {
            if (primary[en.getKey()]) for (int a : en.getValue()) keep.add(el[a]);
            else {
                java.util.List<Integer> g = new java.util.ArrayList<>();
                for (int a : en.getValue()) g.add(el[a]);
                split.add(g);
            }
        }
        if (split.isEmpty()) return;
        plantHeldWhole = held;
        plantRowsWhole = plantRows;
        boolean[] plantHeld = held == null ? new boolean[n] : held.clone();
        Phase[] ps = new Phase[split.size()];
        for (int g = 0; g < ps.length; g++) {
            Phase p = new Phase();
            java.util.List<Integer> ge = split.get(g);
            p.elems = ge.stream().mapToInt(Integer::intValue).toArray();
            boolean[] mine = new boolean[n], mySlot = new boolean[ns];
            for (int k : p.elems) {
                for (int i = 0; i < n; i++) if ((rows[k][i] || cols[k][i]) && !(held != null && held[i])) mine[i] = true;
                for (int sl = 0; sl < ns; sl++) if (slotsOf[k][sl]) mySlot[sl] = true;
            }
            p.frozen = new boolean[n];
            for (int i = 0; i < n; i++) {
                p.frozen[i] = !mine[i];
                if (mine[i]) plantHeld[i] = true;
            }
            java.util.List<Integer> sl = new java.util.ArrayList<>();
            for (int q = 0; q < ns; q++) if (mySlot[q]) sl.add(q);
            p.slots = sl.stream().mapToInt(Integer::intValue).toArray();
            p.jac = LinearSystem.create(n);
            p.lastRate = new double[n];
            p.cpl = new double[n];
            ps[g] = p;
        }
        fast.elems = keep.stream().mapToInt(Integer::intValue).toArray();
        java.util.Set<Integer> partSlots = new java.util.HashSet<>();
        for (Phase p : ps) for (int q : p.slots) partSlots.add(q);
        fast.slots = Arrays.stream(fast.slots).filter(q -> !partSlots.contains(q)).toArray();
        fast.frozen = plantHeld;
        if (plantRows != null) plantRows = Arrays.stream(plantRows).filter(i -> !plantHeld[i]).toArray();
        parts = ps;
        int[] partOf = new int[n];
        Arrays.fill(partOf, -1);
        for (int i = 0; i < n; i++) if (!plantHeld[i]) partOf[i] = 0;
        for (int g = 0; g < ps.length; g++) {
            ps[g].partId = g + 1;
            for (int i = 0; i < n; i++) if (!ps[g].frozen[i]) partOf[i] = g + 1;
        }
        fast.partId = 0;
        if (em != null) em.partId = -2;
        ctx.partOf = partOf;
        if (em != null && em.ctx != null) em.ctx.partOf = partOf;
        // overlapped, the parts step on the calling thread while the plant steps on another: arrays of their own
        if (overlap) for (Phase p : ps) ownScratch(p);
        if (Boolean.getBoolean("emergence.debugMultirate")) {
            StringBuilder sb = new StringBuilder("multirate: the plant in " + (ps.length + 1) + " independent parts, elements:");
            sb.append(' ').append(fast.elems.length);
            for (Phase p : ps) sb.append(' ').append(p.elems.length);
            System.out.println(sb);
        }
    }

    /**
     * Brings the plant's independent parts up to {@code end} in steps of their own: exactly (before a synchronisation),
     * or (after each plant step) as far as whole steps of their own reach, so a part is never more than one of its own
     * steps behind the plant.
     */
    private void stepParts(double end, boolean exactly) {
        if (parts.length == 0) return;
        Phase saved = active;
        double tSaved = t;
        double eps = 1e-12 * Math.max(1, Math.abs(end));
        for (Phase p : parts) {
            active = p;
            while (p.t < end - eps) {
                double h = Math.min(p.hNext, hMax);
                if (!exactly && p.t + h > end + eps) break;
                if (h >= end - p.t || end - p.t - h < 0.25 * h) h = end - p.t;
                stepAdaptive(p, h);
            }
            if (exactly && p.t < end) p.t = end;
            if (DBG_PARTS) System.out.printf("part t=%.4f end=%.4f exactly=%b accepted=%d hNext=%.3g free=%d%n", p.t, end, exactly, p.nAccepted, p.hNext, p.free == null ? -1 : p.free.length);
        }
        t = tSaved;
        active = saved;
    }

    /** {@code emergence.kinks} (on by default): steps end on elements' kinks (see Element#kinkCount). */
    static final boolean KINKS = !"false".equals(System.getProperty("emergence.kinks"));
    private final java.util.concurrent.atomic.LongAdder kinkStops = new java.util.concurrent.atomic.LongAdder();

    /** Steps cut short to land on a kink (diagnostics). */
    public long kinkStops() {
        return kinkStops.sum();
    }

    /** A phase's elements with kinks, found among those it loads (or all), their switching functions recorded now. */
    private void setupKinks(Phase ph) {
        java.util.List<Element> ke = new java.util.ArrayList<>();
        if (ph.elems == null) {
            for (Element e : elements) if (e.kinkCount() > 0) ke.add(e);
        } else {
            for (int k : ph.elems) if (elements.get(k).kinkCount() > 0) ke.add(elements.get(k));
        }
        if (ke.isEmpty()) {
            ph.kinkEls = null;
            return;
        }
        ph.kinkEls = ke.toArray(new Element[0]);
        ph.kinkPrev = new double[ke.size()][];
        for (int e = 0; e < ke.size(); e++) {
            ph.kinkPrev[e] = new double[ke.get(e).kinkCount()];
            for (int k = 0; k < ph.kinkPrev[e].length; k++) ph.kinkPrev[e][k] = ke.get(e).kink(this, k);
        }
        ph.kinkResumeH = 0;
        ph.kinkLanded = false;
    }

    /** After an accepted step: the kinks' switching functions now; on a kink, the next step starts afresh at the step before it. */
    private void afterKinks(Phase ph) {
        if (ph.kinkEls == null) return;
        StateView sv = ph.ctx != null ? ph.ctx : this;
        for (int e = 0; e < ph.kinkEls.length; e++)
            for (int k = 0; k < ph.kinkPrev[e].length; k++) ph.kinkPrev[e][k] = ph.kinkEls[e].kink(sv, k);
        if (ph.kinkLanded) {
            ph.restart = true;
            ph.validHistory = 0;
            ph.hNext = Math.max(ph.hNext, ph.kinkResumeH);
            ph.kinkLanded = false;
            ph.kinkResumeH = 0;
        }
    }

    /** Whether multirate stepping is in effect. */
    public boolean isMultirate() {
        return multirate;
    }

    /** Thermal-network steps taken under multirate (diagnostics). */
    public long slowSteps() {
        return slowSteps;
    }

    /** A phase's rows that move in it and those it holds. */
    private void splitRows(Phase ph) {
        int nf = 0;
        for (int i = 0; i < n; i++) if (ph.frozen == null || !ph.frozen[i]) nf++;
        ph.free = new int[nf];
        ph.held = new int[n - nf];
        for (int i = 0, a = 0, b = 0; i < n; i++) {
            if (ph.frozen == null || !ph.frozen[i]) ph.free[a++] = i;
            else ph.held[b++] = i;
        }
        ph.freeOf = ph.frozen;
    }

    /** Puts every phase at the current time with no history (after initialisation or a state change). */
    private void resetPhases() {
        java.util.List<Phase> every = new java.util.ArrayList<>(Arrays.asList(all, slow));
        if (multirate) every.addAll(Arrays.asList(plantPhases()));
        for (Phase ph : every) {
            if (ph == null) continue;
            setupKinks(ph);
            ph.t = t;
            ph.validHistory = 0;
            ph.restart = true;
            ph.hNext = ph == slow ? 1e-3 : hRestart;
        }
        active = multirate ? fast : all;
        if (multirate) {
            Arrays.fill(heatAccum, 0);
        Arrays.fill(emHeat, 0);
        Arrays.fill(partHeat, 0);
            fastHeatRate();
            fast.lastPext = power(fast, true);
            fast.lastPloss = power(fast, false);
            for (Phase p : parts) {
                p.lastPext = power(p, true);
                p.lastPloss = power(p, false);
            }
            if (em != null) {
                em.lastPext = power(em, true);
                em.lastPloss = power(em, false);
                Arrays.fill(emAccum, 0);
                Arrays.fill(fast.heatIn, 0);
                emMeanValid = false;
                emT0 = t;
                pending = false;
                emMidLast = emMidPrev = Double.NaN;
            }
            slow.lastPext = power(slow, true);
            slow.lastPloss = power(slow, false);
        }
    }

    /** The fast partition's heat into each slow row [W] at the current state, and the coupling stiffness. */
    private void fastHeatRate() {
        Phase saved = active;
        for (Phase ph : plantPhases()) {
            if (ph == null) continue;
            active = ph;
            if (ph.ctx == null) shareScratch(ph);
            LoadContext ctx = ph.ctx;
            double[] f = ph.f;
            ctx.x = x;
            ctx.time = t;
            ctx.h = Math.max(ph.hPrev, hRestart);
            ctx.hPrev = ctx.h;
            ctx.method = IntegrationMethod.BACKWARD_EULER;
            ctx.alpha = 1 / ctx.h;
            double[] q0s = q0.clone(), qd0s = qd0.clone();
            Arrays.fill(f, 0);
            ph.jac.clear();
            loadAll(ph);
            for (int i : slowRows) {
                ph.lastRate[i] = -f[i];
                ph.cpl[i] = ctx.frozenDiag[i];
            }
            if (ph == em) for (int i : plantInRows) emRate[i] = -f[i];
            System.arraycopy(q0s, 0, q0, 0, ns);
            System.arraycopy(qd0s, 0, qd0, 0, ns);
            Arrays.fill(f, 0);
            ph.jac.clear();
        }
        for (int i : slowRows) coupling[i] = couplingAt(i);
        active = saved;
        ctx.jac = active.jac;
        ctx.frozen = active.frozen;
    }

    /** The fast phases' coupling stiffness at thermal row i, all together. */
    private double couplingAt(int i) {
        double g = fast.cpl[i] + (em != null ? em.cpl[i] : 0);
        for (Phase p : parts) g += p.cpl[i];
        return g;
    }

    private boolean isPart(Phase ph) {
        for (Phase p : parts) if (p == ph) return true;
        return false;
    }

    /** The phases that step with the plant: itself, the circuits, its independent parts. */
    private Phase[] plantPhases() {
        Phase[] r = new Phase[2 + parts.length];
        r[0] = fast;
        r[1] = em;
        System.arraycopy(parts, 0, r, 2, parts.length);
        return r;
    }

    /** External (true) or untracked (false) power of a phase's elements. */
    private double power(Phase ph, boolean external) {
        return power(ph, external, this);
    }

    private double power(Phase ph, boolean external, StateView sv) {
        double s = 0;
        if (ph.elems == null) {
            for (Element e : elements) s += external ? e.externalPower(sv) : e.untrackedLoss(sv);
        } else {
            for (int k : ph.elems) s += external ? elements.get(k).externalPower(sv) : elements.get(k).untrackedLoss(sv);
        }
        return s;
    }

    /**
     * Steps the thermal network from where it is to the fast partition's time, the heat the fast partition delivered
     * meanwhile entering as a constant power, in as many steps as its error control asks.
     */
    private void synchronise() {
        if (!multirate) return;
        // up to the plant: the circuits may be an interval behind it (overlapped), their heat since then counted at
        // the next synchronisation (in transit meanwhile, in the ledger too)
        double tCircuits = t, end = now();
        stepParts(end, true);
        double span = end - slow.t;
        if (span <= 1e-15 * Math.max(1, Math.abs(end))) return;
        for (int i : slowRows) slow.heatIn[i] = (heatAccum[i] + emHeat[i] + partHeat[i]) / span;
        // its input steps here: start afresh (backward Euler), not carrying the old input's derivative across
        slow.restart = true;
        active = slow;
        while (slow.t < end - 1e-12 * Math.max(1, Math.abs(end))) {
            double h = Math.min(slow.hNext, end - slow.t);
            if (end - slow.t - h < 0.25 * h) h = end - slow.t;
            stepAdaptive(slow, h);
        }
        slow.t = end;
        t = pending ? tCircuits : end;
        active = fast;
        Arrays.fill(heatAccum, 0);
        Arrays.fill(emHeat, 0);
        Arrays.fill(partHeat, 0);
        storedStale = true;
    }


    /** The interval after which the thermal network is brought up to date: its error control, the coupling's stability. */
    private double syncInterval() {
        double lim = Math.max(slow.hNext, fast.hPrev);
        int by = -1;
        for (int i : slowRows) {
            double g = Math.abs(couplingAt(i));
            if (g > 0 && capacity[i] > 0 && 0.5 * capacity[i] / g < lim) {
                lim = 0.5 * capacity[i] / g;
                by = i;
            }
        }
        if (SYNC_STATS != null) SYNC_STATS.merge(by < 0 ? "thermal step " : net.unknownName(by), 1, Integer::sum);
        if (by >= 0 && TIGHT_S > 0 && capacity[by] / Math.abs(couplingAt(by)) < TIGHT_S) tightFound = true;
        return Math.max(lim, fast.hPrev);
    }

    public TransientSolver method(IntegrationMethod m) {
        this.method = m;
        return this;
    }

    public TransientSolver tolerances(double newtonRelTol, double lteRelTol) {
        this.relTol = newtonRelTol;
        this.lteRelTol = lteRelTol;
        return this;
    }

    public TransientSolver maxStep(double h) {
        this.hMax = h;
        return this;
    }

    public TransientSolver minStep(double h) {
        this.hMin = h;
        return this;
    }

    public IntegrationMethod method() {
        return method;
    }

    public Network network() {
        return net;
    }

    /** Re-reads initial values after the caller changed them (call before the first step). */
    public void reinitialize() {
        for (int i = 0; i < n; i++) x[i] = net.initialValue(i);
        initializeHistory();
    }

    /** Sets an unknown's current value directly (used when transferring state across rebuilds). */
    public void setState(int index, double value) {
        x[index] = value;
    }

    /** Recomputes stored quantities from the current x and restarts integration history. */
    public void resyncHistory() {
        initializeHistory();
    }

    private void initializeHistory() {
        ctx.initializing = true;
        ctx.x = x;
        ctx.time = t;
        ctx.h = hRestart;
        ctx.hPrev = hRestart;
        ctx.method = IntegrationMethod.BACKWARD_EULER;
        ctx.alpha = 0;
        Arrays.fill(f, 0);
        active = all; // every element initialises its history
        jac.clear();
        loadAll();
        ctx.initializing = false;
        System.arraycopy(qNew, 0, q0, 0, ns);
        System.arraycopy(qNew, 0, q1, 0, ns);
        Arrays.fill(qd0, 0);
        Arrays.fill(qd1, 0);
        System.arraycopy(x, 0, xPrev, 0, n);
        validHistory = 0;
        restart = true;
        storedStale = true;
        // (the phases start with nothing in transit but the coupling's balance, which carries on)
        storedInitial = totalStored() + (multirate && em != null ? couplingTransit + plantTransit : 0);
        externalIn = emExternalIn = partExternalIn = 0;
        untrackedOut = emUntrackedOut = partUntrackedOut = 0;
        lastPext = totalExternalPower();
        lastPloss = totalUntrackedLoss();
        all.lastPext = lastPext;
        all.lastPloss = lastPloss;
        if (multirate) resetPhases();
        else {
            all.t = t;
            all.validHistory = 0;
            all.restart = true;
            all.hNext = hRestart;
        }
    }

    /**
     * Signals a discontinuity (switch toggled, source stepped, parameter changed): the next step
     * restarts with backward Euler and a small step so the integrator does not extrapolate across it.
     */
    public void markDiscontinuity() {
        if (inFlight) {
            discontinuityLater = true;
            return;
        }
        for (Phase ph : multirate ? plantPhases() : new Phase[]{all}) {
            if (ph == null) continue;
            ph.restart = true;
            ph.validHistory = 0;
            ph.hNext = Math.min(ph.hNext, hRestart);
            ph.nRestarts++;
        }
    }

    // ------------------------------------------------------------------ stepping

    /**
     * Advances the simulation by {@code duration} seconds with adaptive internal steps.
     *
     * @param maxSteps safety budget; if exceeded the solver stops early (the caller sees lag)
     * @return simulated time actually advanced
     */
    public double advance(double duration, int maxSteps) {
        return advance(duration, maxSteps, 0);
    }

    /**
     * As {@link #advance(double, int)}, stopping early (after at least one step, on an accepted state) once the wall
     * clock passes {@code deadlineNanos} ({@link System#nanoTime()}; 0 for none): a costly stretch — a burst of
     * switching edges — then runs in slow motion instead of holding up whoever is waiting on it.
     */
    public double advance(double duration, int maxSteps, long deadlineNanos) {
        double tStart = t;
        double tEnd = t + duration;
        int steps = 0;
        Phase ph = multirate ? fast : all;
        while (now() < tEnd - 1e-12 * Math.max(1.0, Math.abs(tEnd))) {
            if (steps++ >= maxSteps) break;
            if (deadlineNanos != 0 && steps > 1 && System.nanoTime() - deadlineNanos > 0) break;
            fireEvents();
            double h = Math.min(ph.hNext, hMax);
            double remaining = tEnd - now();
            if (h >= remaining * 0.99) h = remaining;
            else if (remaining - h < 0.25 * h) h = 0.5 * remaining;
            // land exactly on the next event
            double te = nextEventTime();
            if (overlap && PIPELINE) {
                steps += Math.max(0, pipeline(tEnd, maxSteps - steps, deadlineNanos) - 1);
            } else if (em != null) {
                plantThenCircuits(h);
            } else {
                if (te > t && te - t < h) h = Math.max(te - t, hMin * 2);
                stepAdaptive(ph, h);
            }
            long pt0 = System.nanoTime();
            stepParts(now(), false);
            partsNanos += System.nanoTime() - pt0;
            if (partsMet) {
                drainCircuits();
                joinParts();
            }
            if (multirate && now() - slow.t >= syncInterval()) {
                long st0 = System.nanoTime();
                synchronise();
                syncNanos += System.nanoTime() - st0;
                // a thermal node tightened past TIGHT_S: the partition again, with it on the fast side
                if (tightFound && !pending && findTight()) {
                    repartition();
                    ph = fast;
                }
                tightFound = false;
            }
        }
        long dt0 = System.nanoTime();
        drainCircuits();
        long dt1 = System.nanoTime();
        synchronise();
        drainNanos += dt1 - dt0;
        syncNanos += System.nanoTime() - dt1;
        fireEvents();
        return t - tStart;
    }

    /**
     * One plant step of at most {@code h}, the circuits' delivery into its rows held at their mean over the last
     * interval; then the circuits across the same interval in steps of their own, landing on their events, the plant's
     * rows taken between the step's two ends.
     */
    private void plantThenCircuits(double h) {
        if (overlap) {
            plantWithCircuits(h);
            return;
        }
        double t0 = fast.t;
        for (int i : plantRows) plantStart[i] = x[i];
        stepAdaptive(fast, h);
        if (partsMet && fast.t == t0) return;
        double t1 = fast.t, span = t1 - t0;
        for (int i : plantRows) plantEnd[i] = x[i];
        emT0 = t0;
        emT1 = t1;
        while (em.t < t1 - 1e-12 * Math.max(1, Math.abs(t1))) {
            double he = Math.min(em.hNext, t1 - em.t);
            if (t1 - em.t - he < 0.25 * he) he = t1 - em.t;
            double te = nextEventTime();
            if (te > em.t && te - em.t < he) he = Math.max(te - em.t, hMin * 2);
            stepAdaptive(em, he);
            emSteps++;
            fireEvents();
        }
        em.t = t1;
        t = t1;
        // what the circuits delivered into the plant's rows over the interval, carried on to the middle of the next
        // (1.5·this − 0.5·the last, the Adams–Bashforth predictor): the next plant step's input. Held at the last
        // interval's mean, the input lagged half a step behind and jumped at each, and the plant took twice its steps
        for (int i : plantInRows) {
            double mean = span > 0 ? emAccum[i] / span : 0;
            fast.heatIn[i] = emMeanValid ? 1.5 * mean - 0.5 * emMeanPrev[i] : mean;
            emMeanPrev[i] = mean;
            emAccum[i] = 0;
        }
        emMeanValid = true;
        active = fast;
    }

    /**
     * Overlapped: the plant's next step on the common pool while the circuits cross the interval before it here. The
     * plant's input — what the circuits deliver into its rows — is carried on (linearly) to the middle of its step from
     * the circuits' means over the two intervals before; it holds the circuits' own rows as they were when it set off.
     * Energy stays booked exactly (what the circuits deliver along the plant's motion, less what the plant takes up,
     * is in transit), so the plant's being a step ahead costs accuracy in the coupling, not conservation.
     */
    private void plantWithCircuits(double h) {
        double mid = fast.t + 0.5 * h;
        if (!Double.isNaN(emMidPrev) && emMidLast > emMidPrev) {
            double r = (mid - emMidLast) / (emMidLast - emMidPrev);
            for (int i : plantInRows) fast.heatIn[i] = emMeanLast[i] + r * (emMeanLast[i] - emMeanPrev[i]);
        } else if (!Double.isNaN(emMidLast)) {
            for (int i : plantInRows) fast.heatIn[i] = emMeanLast[i];
        }
        if (!pending) {
            // nothing to overlap with yet: the plant's step alone
            double t0 = fast.t;
            for (int i : plantRows) plantStart[i] = x[i];
            long l0 = System.nanoTime();
            stepAdaptive(fast, h);
            lonePlantNanos += System.nanoTime() - l0;
            if (fast.t == t0) return;
            for (int i : plantRows) plantEnd[i] = x[i];
            pending = true;
            pendT0 = t0;
            pendT1 = fast.t;
            return;
        }
        if (em.free == null || em.freeOf != em.frozen) splitRows(em);
        for (int i : em.free) emHeld[i] = x[i];
        double t0 = fast.t;
        inFlight = true;
        overlaps++;
        final long sub0 = System.nanoTime();
        java.util.concurrent.ForkJoinTask<?> task = java.util.concurrent.ForkJoinPool.commonPool().submit(() -> {
            long st = System.nanoTime();
            taskDelay += st - sub0;
            stepAdaptive(fast, h);
            taskRun += System.nanoTime() - st;
        });
        long c0 = System.nanoTime(), c1 = c0;
        try {
            crossCircuits(pendT0, pendT1);
            // and the plant's independent parts, as far as the plant is going, while it is still at it
            stepParts(t0 + h, false);
            c1 = System.nanoTime();
        } finally {
            try {
                task.join();
            } finally {
                inFlight = false;
            }
        }
        long c2 = System.nanoTime();
        overlapBusy += c1 - c0;
        overlapWait += c2 - c1;
        if (discontinuityLater) {
            discontinuityLater = false;
            markDiscontinuity();
        }
        if (fast.t == t0) {
            // the plant did not step (its parts met): nothing pending, the circuits level with it
            pending = false;
            return;
        }
        System.arraycopy(plantEnd, 0, plantStart, 0, n);
        for (int i : plantRows) plantEnd[i] = x[i];
        pendT0 = t0;
        pendT1 = fast.t;
    }

    /** The circuits across [t0, t1] in steps of their own, the plant's rows between its snapshots; then their mean. */
    private void crossCircuits(double t0, double t1) {
        crossCircuits(t0, t1, null);
    }

    /** As {@link #crossCircuits(double, double)}; with {@code out}, the interval's means go there, the history untouched. */
    private void crossCircuits(double t0, double t1, double[] out) {
        emT0 = t0;
        emT1 = t1;
        double span = t1 - t0;
        while (em.t < t1 - 1e-12 * Math.max(1, Math.abs(t1))) {
            double he = Math.min(em.hNext, t1 - em.t);
            if (t1 - em.t - he < 0.25 * he) he = t1 - em.t;
            double te = nextEventTime();
            if (te > em.t && te - em.t < he) he = Math.max(te - em.t, hMin * 2);
            stepAdaptive(em, he);
            emSteps++;
            fireEvents();
        }
        em.t = t1;
        t = t1;
        if (out != null) {
            for (int i : plantInRows) {
                out[i] = span > 0 ? emAccum[i] / span : 0;
                emAccum[i] = 0;
            }
            return;
        }
        for (int i : plantInRows) {
            double mean = span > 0 ? emAccum[i] / span : 0;
            emMeanPrev[i] = emMeanLast[i];
            emMeanLast[i] = mean;
            emAccum[i] = 0;
        }
        emMidPrev = emMidLast;
        emMidLast = 0.5 * (t0 + t1);
    }

    /**
     * {@code emergence.pipeDepth}: how many intervals the plant may run ahead of the circuits when they overlap. Its
     * step k takes the circuits' means up to interval k − depth (and holds their rows as that interval left them),
     * so what it sees is fixed by the step's index, not by the threads' timing: the results are deterministic.
     */
    /** Diagnostics ({@code diag.timeline=<t>}): the pipeline's plant steps and circuit intervals around simulated time t. */
    public static final java.util.List<String> TIMELINE = System.getProperty("diag.timeline") != null
            ? java.util.Collections.synchronizedList(new java.util.ArrayList<>()) : null;
    static final double TIMELINE_AT = Double.parseDouble(System.getProperty("diag.timeline", "-1"));
    /**
     * {@code emergence.pipeline} (off: experimental): the free-running overlap below instead of lockstep. It broke two
     * exhibits' checks (an energy ledger, a contact governor) and gained nothing measurable on the power station's
     * in-game state (5.9 against 6.0 s per 5 s): the plant's Newton failures and the circuits' long intervals set it.
     */
    static final boolean PIPELINE = Boolean.getBoolean("emergence.pipeline");
    static final int PIPE_DEPTH = Math.max(1, Integer.getInteger("emergence.pipeDepth", 3));
    private double[][] ringEnd, ringMean, ringEm;
    private double[] ringT0, ringT1;
    private volatile int plantDone, circuitsDone;
    private volatile boolean plantStopped, pipeAbort;
    /** A part met from the circuits' side during a run (applied when it ends: the plant's own decides when it stops). */
    private boolean partsMetLater;

    private int ring(int k) {
        int r = ringT0.length;
        return ((k % r) + r) % r;
    }

    /**
     * Overlapped, free-running: the plant steps on the common pool, as far as the next synchronisation with the
     * thermal network (or the end), each step k as soon as the circuits have crossed interval k − depth; the circuits
     * cross each interval here as soon as the plant has taken it, the plant's independent parts after them. In
     * lockstep — one plant step against the circuits' interval before it — each pair waited for the slower of the two,
     * and their costs vary against each other: the circuits waited for the plant two fifths of the time, though over a
     * second the plant needed less than they did. Returns the plant steps taken.
     */
    private int pipeline(double tEnd, int maxSteps, long deadlineNanos) {
        int depth = PIPE_DEPTH, r = depth + 3;
        if (ringT0 == null || ringT0.length != r || ringEnd[0].length != n) {
            ringEnd = new double[r][n];
            ringMean = new double[r][n];
            ringEm = new double[r][n];
            ringT0 = new double[r];
            ringT1 = new double[r];
        }
        if (em.free == null || em.freeOf != em.frozen) splitRows(em);
        double eps = 1e-12 * Math.max(1.0, Math.abs(tEnd));
        // interval 0: where both stand now
        ringT0[ring(0)] = ringT1[ring(0)] = fast.t;
        for (int i : plantRows) ringEnd[ring(0)][i] = x[i];
        for (int i : em.free) ringEm[ring(0)][i] = x[i];
        final double si = syncInterval(), slowT = slow.t;
        plantDone = 0;
        circuitsDone = 0;
        plantStopped = false;
        pipeAbort = false;
        inFlight = true;
        overlaps++;
        long c0 = System.nanoTime();
        java.util.concurrent.ForkJoinTask<?> task = java.util.concurrent.ForkJoinPool.commonPool().submit(() -> {
            long st = System.nanoTime();
            try {
                int k = 0;
                while (true) {
                    if (fast.t >= tEnd - eps || k >= maxSteps || partsMet || pipeAbort) break;
                    if (k > 0 && (fast.t - slowT >= si || deadlineNanos != 0 && System.nanoTime() - deadlineNanos > 0)) break;
                    int need = k + 1 - depth;
                    if (circuitsDone < need) {
                        long w0 = System.nanoTime();
                        while (circuitsDone < need && !pipeAbort) Thread.onSpinWait();
                        plantSpin += System.nanoTime() - w0;
                    }
                    if (pipeAbort) break;
                    double h = Math.min(fast.hNext, hMax), remaining = tEnd - fast.t;
                    if (h >= remaining * 0.99) h = remaining;
                    else if (remaining - h < 0.25 * h) h = 0.5 * remaining;
                    plantInput(need, fast.t + 0.5 * h);
                    double[] held = ringEm[ring(Math.max(0, need))];
                    for (int i : em.free) emHeld[i] = held[i];
                    double t0 = fast.t;
                    long ps0 = System.nanoTime();
                    stepAdaptive(fast, h);
                    if (TIMELINE != null && t > TIMELINE_AT && t < TIMELINE_AT + 0.05)
                        TIMELINE.add(String.format("P%d %.3f-%.3f ms (h %.2e)", k + 1, (ps0 - c0) / 1e6, (System.nanoTime() - c0) / 1e6, h));
                    if (fast.t == t0) break; // (its parts met: not stepped)
                    k++;
                    int sl = ring(k);
                    ringT0[sl] = t0;
                    ringT1[sl] = fast.t;
                    double[] e = ringEnd[sl];
                    for (int i : plantRows) e[i] = x[i];
                    plantDone = k;
                }
            } finally {
                taskRun += System.nanoTime() - st;
                plantStopped = true;
            }
        });
        // the plant's independent parts on a thread of their own, as far as the plant is going (to the synchronisation or
        // the end, a fixed time: what they do does not depend on the threads)
        final double partsEnd = Math.min(tEnd, slowT + si);
        java.util.concurrent.ForkJoinTask<?> partsTask = parts.length == 0 ? null
                : java.util.concurrent.ForkJoinPool.commonPool().submit(() -> stepPartsAlone(partsEnd));
        int j = 0;
        try {
            while (true) {
                int next = j + 1;
                if (plantDone < next && !plantStopped) {
                    long w0 = System.nanoTime();
                    while (plantDone < next && !plantStopped) Thread.onSpinWait();
                    circuitsSpin += System.nanoTime() - w0;
                }
                if (plantDone < next) break;
                j = next;
                int sl = ring(j);
                plantStart = ringEnd[ring(j - 1)];
                plantEnd = ringEnd[sl];
                long cs0 = System.nanoTime();
                crossCircuits(ringT0[sl], ringT1[sl], ringMean[sl]);
                long cs1 = System.nanoTime();
                if (TIMELINE != null && t > TIMELINE_AT && t < TIMELINE_AT + 0.05)
                    TIMELINE.add(String.format("C%d %.3f-%.3f ms, parts to %.3f ms", j, (cs0 - c0) / 1e6, (cs1 - c0) / 1e6, (System.nanoTime() - c0) / 1e6));
                double[] e = ringEm[sl];
                for (int i : em.free) e[i] = x[i];
                circuitsDone = j;
            }
        } catch (RuntimeException | Error ex) {
            pipeAbort = true;
            throw ex;
        } finally {
            try {
                task.join();
                if (partsTask != null) partsTask.join();
            } finally {
                inFlight = false;
                overlapBusy += System.nanoTime() - c0;
            }
        }
        // the history the next run starts from: the last two intervals' means and middles
        if (j >= 1) {
            double[] last = ringMean[ring(j)];
            double midLast = 0.5 * (ringT0[ring(j)] + ringT1[ring(j)]);
            if (j >= 2) {
                double[] prev = ringMean[ring(j - 1)];
                for (int i : plantInRows) emMeanPrev[i] = prev[i];
                emMidPrev = 0.5 * (ringT0[ring(j - 1)] + ringT1[ring(j - 1)]);
            } else {
                for (int i : plantInRows) emMeanPrev[i] = emMeanLast[i];
                emMidPrev = emMidLast;
            }
            for (int i : plantInRows) emMeanLast[i] = last[i];
            emMidLast = midLast;
        }
        plantStart = new double[n];
        plantEnd = new double[n];
        if (discontinuityLater) {
            discontinuityLater = false;
            markDiscontinuity();
        }
        if (partsMetLater) {
            partsMetLater = false;
            partsMet = true;
        }
        return j;
    }

    /** The parts stepped to {@code end} exactly, on the calling thread, touching neither the solver's time nor its active phase. */
    private void stepPartsAlone(double end) {
        double eps = 1e-12 * Math.max(1, Math.abs(end));
        for (Phase p : parts) {
            while (p.t < end - eps) {
                double h = Math.min(p.hNext, hMax);
                if (h >= end - p.t || end - p.t - h < 0.25 * h) h = end - p.t;
                stepAdaptive(p, h);
            }
            p.t = Math.max(p.t, end);
        }
    }

    /**
     * The plant's input for a step with middle {@code mid}: the circuits' means over intervals {@code j} and j − 1
     * carried on linearly (intervals 0 and −1 are the history from before the run).
     */
    private void plantInput(int j, double mid) {
        double[] a, b;
        double ma, mb;
        if (j >= 1) {
            a = ringMean[ring(j)];
            ma = 0.5 * (ringT0[ring(j)] + ringT1[ring(j)]);
        } else if (j == 0) {
            a = emMeanLast;
            ma = emMidLast;
        } else {
            a = emMeanPrev;
            ma = emMidPrev;
        }
        if (j >= 2) {
            b = ringMean[ring(j - 1)];
            mb = 0.5 * (ringT0[ring(j - 1)] + ringT1[ring(j - 1)]);
        } else if (j == 1) {
            b = emMeanLast;
            mb = emMidLast;
        } else if (j == 0) {
            b = emMeanPrev;
            mb = emMidPrev;
        } else {
            b = null;
            mb = Double.NaN;
        }
        if (Double.isNaN(ma)) return;
        if (b != null && !Double.isNaN(mb) && ma > mb) {
            double rr = (mid - ma) / (ma - mb);
            for (int i : plantInRows) fast.heatIn[i] = a[i] + rr * (a[i] - b[i]);
        } else {
            for (int i : plantInRows) fast.heatIn[i] = a[i];
        }
    }

    /** The circuits brought level with the plant (before the solver's state is looked at, or the thermal network synchronised). */
    private void drainCircuits() {
        if (!pending) return;
        drains++;
        pending = false;
        crossCircuits(pendT0, pendT1);
        active = fast;
    }

    /** Where the plant stands: ahead of the circuits by a step when they overlap, else the solver's time. */
    private double now() {
        return pending ? fast.t : t;
    }

    /** Each phase's counts since the last call (diagnostics). */
    public String phaseCounts() {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> names = new java.util.ArrayList<>(List.of("all", "plant", "thermal", "circuits"));
        java.util.List<Phase> phs = new java.util.ArrayList<>(Arrays.asList(all, fast, slow, em));
        for (int i = 0; i < parts.length; i++) {
            names.add("part " + (i + 1));
            phs.add(parts[i]);
        }
        for (int i = 0; i < phs.size(); i++) {
            Phase ph = phs.get(i);
            if (ph == null || ph.nNewton == 0) continue;
            sb.append(String.format("%s %d steps (+%d rejected, %d failed), %d Newton, %d restarts [per Newton: load %.0f µs, solve %.0f µs, all %.0f µs]; ",
                    names.get(i), ph.nAccepted, ph.nRejected, ph.nFailed, ph.nNewton, ph.nRestarts,
                    ph.loadNs / 1e3 / Math.max(1, ph.nNewton), ph.solveNs / 1e3 / Math.max(1, ph.nNewton), ph.stepNs / 1e3 / Math.max(1, ph.nNewton)));
            ph.nAccepted = ph.nRejected = ph.nFailed = ph.nNewton = ph.nRestarts = 0;
            ph.loadNs = ph.solveNs = ph.stepNs = 0;
            if (ph.itHist != null) {
                sb.append("iterations ").append(Arrays.toString(ph.itHist)).append("; ");
                Arrays.fill(ph.itHist, 0);
            }
        }
        if (overlap) sb.append(String.format("overlapped %d, drained %d; circuits busy %.1f s, waiting for the plant %.1f s; "
                        + "parts %.1f s, syncs %.1f s, drains %.1f s, lone plant steps %.1f s; plant task: %.1f s waiting to start, %.1f s running; spinning: plant %.1f s, circuits %.1f s; ",
                overlaps, drains, overlapBusy / 1e9, overlapWait / 1e9, partsNanos / 1e9, syncNanos / 1e9, drainNanos / 1e9, lonePlantNanos / 1e9, taskDelay / 1e9, taskRun / 1e9, plantSpin / 1e9, circuitsSpin / 1e9));
        taskDelay = taskRun = plantSpin = circuitsSpin = 0;
        overlaps = drains = overlapBusy = overlapWait = partsNanos = syncNanos = drainNanos = lonePlantNanos = 0;
        return sb.toString();
    }

    /** Circuit steps taken under multirate (diagnostics). */
    public long circuitSteps() {
        return emSteps;
    }

    /** Energy delivered by the circuits into the mechanisms not yet taken up by them [J] (diagnostics; it should stay small). */
    public double couplingTransit() {
        return couplingTransit + plantTransit;
    }

    /** Calls {@code l} after every accepted step (instruments sampling the state). */
    public void addStepListener(java.util.function.Consumer<TransientSolver> l) {
        stepListeners.add(l);
    }

    public void removeStepListener(java.util.function.Consumer<TransientSolver> l) {
        stepListeners.remove(l);
    }

    private double nextEventTime() {
        double te = Double.POSITIVE_INFINITY;
        for (EventSource e : eventSources) te = Math.min(te, e.nextEvent(t));
        return te;
    }

    /** Fires every event due by now; a change restarts the integration (the history is not smooth across it). */
    private void fireEvents() {
        if (eventSources.isEmpty()) return;
        boolean changed = false, changedCircuits = false;
        double eps = 1e-12 * Math.max(1, Math.abs(t)) + hMin;
        boolean[] inEm = eventsInCircuits();
        for (int k = 0; k < eventSources.size(); k++) {
            EventSource e = eventSources.get(k);
            if (e.nextEvent(t - eps) > t + eps || !e.fireEvent(this, t)) continue;
            if (inEm != null && inEm[k]) changedCircuits = true;
            else changed = true;
        }
        if (changed) markDiscontinuity();
        else if (changedCircuits) {
            // a circuit's switch is a discontinuity for the circuits alone: the plant meets them only through what they
            // deliver over its steps (restarted too, it began again at its smallest steps at each of a counter's ticks)
            em.restart = true;
            em.validHistory = 0;
            em.hNext = Math.min(em.hNext, hRestart);
            em.nRestarts++;
        }
    }

    private boolean[] eventInEm;

    /** Which event sources step with the circuits (null if the circuits do not step apart). */
    private boolean[] eventsInCircuits() {
        if (!multirate || em == null) return null;
        if (eventInEm == null || eventInEm.length != eventSources.size()) {
            java.util.Set<Element> mine = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int k : em.elems) mine.add(elements.get(k));
            eventInEm = new boolean[eventSources.size()];
            for (int k = 0; k < eventInEm.length; k++) eventInEm[k] = mine.contains(eventSources.get(k));
        }
        return eventInEm;
    }

    public double advance(double duration) {
        return advance(duration, 1_000_000);
    }

    /** Takes one accepted step of at most {@code hTry}, shrinking on Newton/LTE failure. */
    private void stepAdaptive(Phase ph, double hTry) {
        long t0 = System.nanoTime();
        try {
            stepAdaptive0(ph, hTry);
        } finally {
            long dt = System.nanoTime() - t0;
            if (ph == em) emNanos += dt;
            else if (ph == slow) slowNanos += dt;
            else plantNanos += dt;
        }
    }

    private long emNanos, slowNanos, plantNanos;

    /** Wall time spent stepping each phase [ns]: {circuits, plant (or the whole), thermal network} (diagnostics). */
    public long[] phaseNanos() {
        return new long[]{emNanos, plantNanos, slowNanos};
    }

    private void stepAdaptive0(Phase ph, double hTry) {
        if (!inFlight || ph == em) active = ph;
        double h = hTry;
        while (true) {
            if (h < hMin) {
                // Last resort before giving up: Newton stalls at round-off level on an ill-conditioned variable (a
                // nearly closed valve's flow, a floating node) can collapse the step although the state is sound.
                // Take one backward-Euler step of 1 µs with the Newton tolerances relaxed 100× and no truncation-
                // error test; it is accepted only if Newton converges, and counted.
                double hr = Math.max(1e-6, hMin * 1e4);
                ph.tolScale = 100;
                ph.skipLte = true;
                ph.restart = true;
                ph.validHistory = 0;
                int rr = tryStep(ph, Math.min(hr, Math.max(hTry, hMin)));
                ph.tolScale = 1;
                ph.skipLte = false;
                if (rr == 0) {
                    rescues.increment();
                    ph.hNext = Math.max(hr, ph.hNext);
                    return;
                }
                throw new SimulationException(String.format(
                        "Time step underflow at t=%.6g s (h=%.3g s)%s. %s", ph.t, h, ph == slow ? " in the thermal network" : "",
                        worstNewtonDiagnostic()));
            }
            long ts0 = System.nanoTime();
            int r = tryStep(ph, h);
            ph.stepNs += System.nanoTime() - ts0;
            if (r == 0 || r == 3) return;
            if (r == 1) { // Newton failure
                newtonFailures.increment();
                ph.nFailed++;
                if (FAILURE_CAUSES != null) FAILURE_CAUSES.merge((ph == em ? "circuits " : ph == fast ? "plant " : "other ") + (lastBadIndex >= 0 ? net.unknownName(lastBadIndex) : lastBadUnknown), 1, Integer::sum);
                ph.restart = true;
                ph.validHistory = 0;
                h *= 0.25;
            } else { // LTE rejection: hNext already set
                rejectedSteps.increment();
                ph.nRejected++;
                h = Math.min(ph.hNext, h * 0.9);
                // An estimate that still fails after the step has shrunk by orders of magnitude compares against
                // stale history (e.g. a derivative left by ringing across a fast edge): restart the integration
                // there, as after any discontinuity, rather than chase it down to underflow.
                if (h < 1e-4 * hTry || h < 1e3 * hMin) {
                    ph.restart = true;
                    ph.validHistory = 0;
                }
            }
        }
    }

    private String lastBadUnknown = "";
    /** The last unknown still moving in a Newton iteration (its message is built only when a step fails). */
    private int lastBadIndex = -1;
    private double lastBadX, lastBadDx;
    private final java.util.concurrent.atomic.LongAdder rescues = new java.util.concurrent.atomic.LongAdder();

    /** Steps accepted by the underflow rescue (relaxed-tolerance backward Euler) — should stay rare. */
    public long rescuedSteps() {
        return rescues.sum();
    }
    /** Diagnostics: print the worst unknowns of each Newton iteration once the step has collapsed. */
    public static boolean TRACE = Boolean.getBoolean("diag.trace");
    private final java.util.Map<String, Integer> limiterCounts = new java.util.concurrent.ConcurrentHashMap<>();

    /** How often each element's truncation error limited the step (diagnostics). */
    public java.util.Map<String, Integer> stepLimiters() {
        return limiterCounts;
    }

    /**
     * Diagnostics ({@code diag.newtonLaggards=true}): for each Newton iteration from the third on, the unknown that
     * still moved by more than its tolerance — which unknowns make a step take many iterations.
     */
    public java.util.Map<String, Integer> newtonLaggards() {
        return newtonLaggards;
    }

    private static final boolean NEWTON_LAGGARDS = Boolean.getBoolean("diag.newtonLaggards");
    /** Diagnostics ({@code diag.syncStats=true}): what set each synchronisation interval (a node's C/G, or the thermal step). */
    public static final java.util.Map<String, Integer> SYNC_STATS = Boolean.getBoolean("diag.syncStats") ? new java.util.concurrent.ConcurrentHashMap<>() : null;
    /** Diagnostics ({@code diag.failureCauses=true}): Newton failures by phase and the unknown last seen not converging. */
    public static final java.util.Map<String, Integer> FAILURE_CAUSES = Boolean.getBoolean("diag.failureCauses")
            ? new java.util.concurrent.ConcurrentHashMap<>() : null;
    /** Diagnostics ({@code diag.lagTrace=<name part>}): each iteration that unknown holds Newton back, its value, update, residual and diagonal (400 lines). */
    private static final String LAG_TRACE = System.getProperty("diag.lagTrace");
    private static final double LAG_FROM = Double.parseDouble(System.getProperty("diag.lagFrom", "0"));
    /** Diagnostics ({@code diag.stepTraceFrom=<t>}): every step attempt over 4 ms from t, its size, error and the slot limiting it. */
    private static final double STEP_TRACE_FROM = Double.parseDouble(System.getProperty("diag.stepTraceFrom", "-1"));
    private int lagLines;
    private final java.util.Map<String, Integer> newtonLaggards = new java.util.HashMap<>();

    /** The unknown that last failed to converge in a Newton iteration, with its value and update (diagnostics). */
    public String lastNewtonFailure() {
        return worstNewtonDiagnostic();
    }

    private String worstNewtonDiagnostic() {
        if (lastBadIndex >= 0) lastBadUnknown = net.unknownName(lastBadIndex) + " = " + lastBadX + " (step " + lastBadDx + ")";
        return "Last non-converging unknown: " + lastBadUnknown;
    }

    /** Diagnostics: at the 9th Newton iteration of a step, compare the assembled Jacobian with finite differences. */
    public static boolean CHECK_JACOBIAN = false;

    private void checkJacobian(dev.emergence.core.math.DenseLinearSystem dj) {
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) a[i][j] = dj.get(i, j);
        double[] f0 = f.clone(), x0 = xTrial.clone();
        StringBuilder sb = new StringBuilder("JACOBIAN CHECK t=" + t + System.lineSeparator());
        for (int j = 0; j < n; j++) {
            double d = 1e-7 * Math.max(Math.abs(x0[j]), 1e3 * absTol[j]);
            System.arraycopy(x0, 0, xTrial, 0, n);
            xTrial[j] += d;
            Arrays.fill(f, 0.0);
            jac.clear();
            loadAll();
            for (int i = 0; i < n; i++) if (gmin[i] != 0) f[i] += (relGmin[i] ? Math.max(gmin[i], 1e-12 * Math.abs(jac.diagonal(i))) : gmin[i]) * xTrial[i];
            for (int i = 0; i < n; i++) {
                double fd = (f[i] - f0[i]) / d;
                double an = a[i][j] - (i == j ? 0 : 0);
                double scale = 0;
                for (int k = 0; k < n; k++) scale = Math.max(scale, Math.abs(a[i][k]) * Math.max(Math.abs(x0[k]) * 1e-7, absTol[k]));
                if (Math.abs(fd - an) * Math.max(Math.abs(x0[j]) * 1e-7, absTol[j]) > 1e-2 * scale + 1e-300) {
                    sb.append(String.format("  d[%d %s]/d[%d %s]: assembled %.6g, numeric %.6g x=%.6g%n", i, net.unknownName(i), j, net.unknownName(j), an, fd, x0[j]));
                }
            }
        }
        System.out.print(sb);
        System.arraycopy(x0, 0, xTrial, 0, n);
        Arrays.fill(f, 0.0);
        jac.clear();
        loadAll();
        for (int i = 0; i < n; i++) if (gmin[i] != 0) {
            double g = relGmin[i] ? Math.max(gmin[i], 1e-12 * Math.abs(jac.diagonal(i))) : gmin[i];
            f[i] += g * xTrial[i];
            jac.add(i, i, g);
        }
    }

    // ---- datums (Network.electricalUngrounded): one grounded node per galvanically connected circuit
    /** Union–find over the unknowns, joined by the Jacobian's entries (thermal nodes left out: heat is no connection). */
    private int[] circuitOf;
    private boolean[] thermalUnknown, electricalNode;
    /** How many of each matrix's entries have been joined in. */
    private final java.util.Map<LinearSystem, Integer> datumSeen = new java.util.IdentityHashMap<>();
    /** The datum's shunt [S]: what flows through it is the circuit's share of the other nodes' 10⁻¹² shunts. */
    private static final double DATUM_G = 1.0;

    private int circuit(int i) {
        while (circuitOf[i] != i) i = circuitOf[i] = circuitOf[circuitOf[i]];
        return i;
    }

    /**
     * After an assembly: joins in the entries new to the matrix's pattern and, if circuits joined (or on the first call),
     * grounds the lowest node of each circuit and only that one. Connectivity from the pattern can only over-join — every
     * real coupling has an entry (a contact that closes adds one when it does) — so a circuit never gets two datums; two
     * galvanically separate circuits coupled otherwise (a transformer's windings) share one, the other left as before.
     */
    private synchronized void updateDatums(LinearSystem js) {
        Integer seen = datumSeen.get(js);
        boolean[] joined = {seen == null};
        int now = js.visitPattern(seen == null ? 0 : seen, (r, c) -> {
            if (r == c || thermalUnknown[r] || thermalUnknown[c]) return;
            int a = circuit(r), b = circuit(c);
            if (a == b) return;
            if (a < b) circuitOf[b] = a;
            else circuitOf[a] = b;
            joined[0] = true;
        });
        datumSeen.put(js, now);
        if (!joined[0]) return;
        boolean[] grounded = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!electricalNode[i]) continue;
            int r = circuit(i);
            boolean datum = !grounded[r];
            grounded[r] = true;
            gmin[i] = datum ? DATUM_G : Domain.ELECTRICAL.gmin;
            relGmin[i] = !datum;
        }
    }

    /** @return 0 accepted, 1 Newton failure, 2 LTE rejection */
    private int tryStep(Phase ph, double h) {
        if (ph.ctx == null) shareScratch(ph);
        // the phase's own working arrays (the circuits may be stepping on another thread meanwhile)
        final LoadContext ctx = ph.ctx;
        final double[] f = ph.f, dx = ph.dx, dxPrev = ph.dxPrev, xTrial = ph.xTrial;
        final double tolScale = ph.tolScale;
        final boolean skipLte = ph.skipLte;
        final boolean overlapped = inFlight && (ph == fast || ph == em);
        LinearSystem jac = ph.jac;
        boolean be = ph.restart || ph.forceBE || ph.validHistory == 0 || method == IntegrationMethod.BACKWARD_EULER;
        boolean wasRestart = ph.restart;
        double hPrev = ph.hPrev;
        boolean[] frozen = ph.frozen;
        if (ph.free == null || ph.freeOf != frozen || ph.free.length + ph.held.length != n) splitRows(ph);
        int[] free = ph.free, held = ph.held;
        IntegrationMethod m = be ? IntegrationMethod.BACKWARD_EULER : method;
        ctx.method = m;
        ctx.h = h;
        ctx.hPrev = hPrev;
        ctx.time = ph.t + h;
        ctx.alpha = switch (m) {
            case BACKWARD_EULER -> 1.0 / h;
            case TRAPEZOIDAL -> 2.0 / h;
            case BDF2 -> {
                double w = h / hPrev;
                yield (1 + 2 * w) / ((1 + w) * h);
            }
        };
        // predictor: linear extrapolation of the solution (held unknowns stay put; the thermal network starts from now)
        if (!be && ph != slow) {
            double r = h / hPrev;
            System.arraycopy(x, 0, xTrial, 0, n);
            for (int i : free) xTrial[i] = x[i] + r * (x[i] - xPrev[i]);
        } else {
            System.arraycopy(x, 0, xTrial, 0, n);
        }
        // the circuits read the plant's rows between the plant step's two ends (the plant has already taken it)
        if (ph == em && em != null) {
            double span = emT1 - emT0, fr = span > 0 ? Math.min(1, (ph.t + h - emT0) / span) : 1;
            for (int i : plantRows) xTrial[i] = plantStart[i] + (plantEnd[i] - plantStart[i]) * fr;
        } else if (overlapped && ph == fast) {
            for (int i : em.free) xTrial[i] = emHeld[i];
        }
        ctx.x = xTrial;

        boolean converged = false, damped;
        int bounces = 0;
        for (int it = 0; it < maxNewton; it++) {
            newtonIterations.increment();
            ph.nNewton++;
            long ln0 = System.nanoTime();
            Arrays.fill(f, 0.0);
            jac.clear();
            loadAll(ph);
            ph.loadNs += System.nanoTime() - ln0;
            if (ctx.crossed) {
                // two parts of the plant met: they are joined before the plant's next step; the plant's own step is
                // not taken until then (a part's, already behind the plant, goes on without the meeting for this step)
                ctx.crossed = false;
                if (inFlight && ph != fast) partsMetLater = true;
                else partsMet = true;
                if (ph == fast) return 3;
            }
            // (only when the matrix has entries the datums have not seen: checked without the lock, which the circuits
            // and the plant, stepping side by side, had taken in turns at every iteration — each stalling the other)
            if (circuitOf != null && !(jac instanceof dev.emergence.core.math.SparseLinearSystem sp && sp.nonZeros() == ph.datumNnz)) {
                updateDatums(jac);
                if (jac instanceof dev.emergence.core.math.SparseLinearSystem sp2) ph.datumNnz = sp2.nonZeros();
            }
            if (ph.heatIn != null) for (int i : ph.inRows) f[i] -= ph.heatIn[i];
            // held unknowns: their rows (stamped with nothing else) say "do not move"; these stamps, and the diagonals
            // below, are a segment of their own, past the elements' (replayed, not looked up: hashed, they were a
            // twentieth of the plant's time)
            jac.beginSegment(elements.size());
            for (int i : held) {
                f[i] = 0;
                jac.add(i, i, 1.0);
            }
            for (int i : free) {
                if (gmin[i] != 0) {
                    // Relative shunt: an absolute GMIN vanishes in floating point next to large
                    // conductances of a floating sub-network (e.g. an isolated steel frame), leaving an
                    // exactly singular pivot. 1e-12·|J_ii| is still physically negligible.
                    double g = relGmin[i] ? Math.max(gmin[i], 1e-12 * Math.abs(jac.diagonal(i))) : gmin[i];
                    f[i] += g * xTrial[i];
                    jac.add(i, i, g);
                } else {
                    jac.add(i, i, 0.0); // keep structural diagonal for the sparse pivot search
                }
            }
            jac.beginSegment(-1);
            if (CHECK_JACOBIAN && it == 8 && jac instanceof dev.emergence.core.math.DenseLinearSystem dj) checkJacobian(dj);
            for (int i = 0; i < n; i++) dx[i] = -f[i];
            long sn0 = System.nanoTime();
            try {
                jac.solve(dx);
                ph.solveNs += System.nanoTime() - sn0;
            } catch (SingularMatrixException e) {
                lastBadUnknown = "singular in column of " + net.unknownName(Math.min(e.row(), n - 1));
                lastBadIndex = -1;
                return 1;
            }
            for (int i : held) dx[i] = 0;
            boolean small = true;
            // damping: limit enormous Newton updates on nonlinear elements; and halve an update that points nearly
            // opposite to the one before — the iteration bouncing between two states across a steep edge (a comparator
            // switching under an inductive load, its battery current ±1 A an iteration) (only far from converging: near
            // the solution, rounding noise alternates too, and halving it there costs conservation and iterations).
            // Only while it bounces: halving every update for the rest of the solve once it had made one Newton
            // converge at a rate of ½ — a gas passage's flow reversing once at an exhaust valve's opening, then ten
            // more iterations to settle. A bounce that recurs (a third time) is a cycle, not a single overshoot: from then on
            // every update is halved, as before (a PWM comparator's op-amp at its rail, its supply's current ±0.2 A and
            // its state ±0.3 V an iteration, to the iteration cap at every step size)
            // Once it has cycled three times, halved while the update is still far above its tolerances (DAMP_NEAR of them),
            // full steps near the end: halved to the last, a cycle broken early converged at a rate of ½ to the end — two
            // fifths of a power station's circuit iterations went into solves of 26–30 iterations
            damped = bounces >= 3 && DAMP_NEAR <= 0;
            if (it >= 3 && !damped) {
                double dot = 0, n1 = 0, n2 = 0, far = 0;
                for (int i : free) {
                    dot += dx[i] * dxPrev[i];
                    n1 += dx[i] * dx[i];
                    n2 += dxPrev[i] * dxPrev[i];
                    far = Math.max(far, Math.abs(dx[i]) / (relTol * Math.abs(xTrial[i]) + absTol[i]));
                }
                boolean bounce = far > 1e3 && dot < -0.9 * Math.sqrt(n1 * n2);
                if (bounce && bounces < 3) bounces++;
                damped = bounce || bounces >= 3 && far > DAMP_NEAR;
            }
            System.arraycopy(dx, 0, dxPrev, 0, n);
            double scale = damped ? 0.5 : 1.0;
            for (int i : free) {
                if (!Double.isFinite(dx[i])) {
                    lastBadUnknown = net.unknownName(i) + " (non-finite" + nonFiniteSource(ph) + ")";
                    lastBadIndex = -1;
                    return 1;
                }
            }
            boolean limited = false;
            for (int i : free) {
                if (Math.abs(dx[i]) > stepLimit[i]) {
                    dx[i] = Math.copySign(stepLimit[i], dx[i]);
                    limited = true;
                }
            }
            for (int i : free) {
                if (limiters[i] != null) {
                    double lim = limiters[i].applyAsDouble(xTrial[i], xTrial[i] + scale * dx[i]);
                    if (lim != xTrial[i] + scale * dx[i]) limited = true;
                    dx[i] = (lim - xTrial[i]) / scale;
                }
                xTrial[i] += scale * dx[i];
                double tol = Math.max((relTol * Math.max(Math.abs(xTrial[i]), Math.abs(x[i])) + absTol[i]) * tolScale, ctx.floor[i]);
                if (Math.abs(dx[i]) > tol) {
                    small = false;
                    lastBadIndex = i;
                    lastBadX = xTrial[i];
                    lastBadDx = dx[i];
                }
            }
            if (DEBUG) System.out.println("t=" + t + " h=" + h + " it=" + it + " m=" + m + " x=" + Arrays.toString(xTrial) + " dx=" + Arrays.toString(dx));
            if (TRACE && (h < 1e-9 || it >= maxNewton - 2)) {
                Integer[] order = new Integer[n];
                for (int i = 0; i < n; i++) order[i] = i;
                final double[] ratio = new double[n];
                for (int i = 0; i < n; i++) ratio[i] = Math.abs(dx[i]) / (relTol * Math.abs(xTrial[i]) + absTol[i]);
                Arrays.sort(order, (p1, p2) -> Double.compare(ratio[p2], ratio[p1]));
                StringBuilder sb = new StringBuilder("TRACE t=" + t + " h=" + h + " it=" + it + ":");
                for (int k = 0; k < 5; k++) sb.append(" ").append(net.unknownName(order[k])).append("=").append(String.format("%.6g(dx %.3g)", xTrial[order[k]], dx[order[k]]));
                System.out.println(sb);
            }
            if (small && !limited) {
                converged = true;
                if (ph.itHist != null) ph.itHist[Math.min(it, ph.itHist.length - 1)]++;
                break;
            }
            if (NEWTON_LAGGARDS && it >= 2 && lastBadIndex >= 0) newtonLaggards.merge(net.unknownName(lastBadIndex), 1, Integer::sum);
            if (LAG_TRACE != null && lagLines < 400 && t >= LAG_FROM && lastBadIndex >= 0 && net.unknownName(lastBadIndex).contains(LAG_TRACE)) {
                lagLines++;
                int i = lastBadIndex;
                System.out.printf("LAG t=%.6f h=%.2e it=%d %s x=%.6g dx=%.3g f=%.3g J=%.3g%n", t, h, it, net.unknownName(i), xTrial[i], dx[i], f[i], jac.diagonal(i));
            }
        }
        if (!converged) return 1;

        // Re-evaluate stored quantities at the converged point so the history is exact (residuals only: nothing uses
        // a Jacobian assembled here — the next solve clears it — and assembling one was a quarter of a circuit's loads)
        Arrays.fill(f, 0.0);
        ctx.residualOnly = true;
        loadAll(ph);
        ctx.residualOnly = false;

        // ---- local truncation error control
        double err = 0.0;
        int p = m.order;
        int[] sl = ph.slots;
        int nsl = sl == null ? ns : sl.length;
        int validHistory = ph.validHistory;
        int worstSlot = -1;
        if (validHistory >= 1) {
            for (int si = 0; si < nsl; si++) {
                int s = sl == null ? si : sl[si];
                double est;
                if (p == 1 || validHistory < 2) {
                    est = 0.5 * Math.abs(qdNew[s] - qd0[s]) * h; // ≈ h²/2 |q''|
                } else {
                    double q3 = 2.0 * ((qdNew[s] - qd0[s]) / h - (qd0[s] - qd1[s]) / hPrev) / (h + hPrev);
                    est = m.errorConstant * h * h * h * Math.abs(q3);
                }
                double tol = (lteRelTol * Math.max(Math.abs(qNew[s]), Math.abs(q0[s])) + slotTol[s]) * LTE_SCALE * accuracy;
                if (est / tol > err) {
                    err = est / tol;
                    worstSlot = s;
                }
            }
        }
        // the thermal network's steps are also held to modest changes: no node more than 5 K + 1 % per step (an
        // accuracy guard that needs no history — it often takes a single step per synchronisation)
        if (ph == slow) {
            double worst = 0;
            for (int i : slowRows) worst = Math.max(worst, Math.abs(xTrial[i] - x[i]) / (5 + 0.01 * Math.abs(x[i])));
            if (worst > 1 && !skipLte) {
                ph.hNext = h * Math.max(0.1, 0.9 / worst);
                return 2;
            }
            slowChange = worst;
        }
        double exponent = 1.0 / ((validHistory >= 2 ? p : 1) + 1);
        // a kink crossed inside the step (see Element#kinkCount): the step again, ending just past it, then afresh from
        // there — its corner inside a step was what the error estimate would shrink the steps to
        if (KINKS && !skipLte && ph.kinkEls != null) {
            double frac = 1;
            for (int e = 0; e < ph.kinkEls.length; e++) {
                Element el = ph.kinkEls[e];
                double[] g0 = ph.kinkPrev[e];
                for (int k = 0; k < g0.length; k++) {
                    double g1 = el.kink(ctx, k);
                    if (g0[k] * g1 < 0 && Math.max(Math.abs(g0[k]), Math.abs(g1)) > el.kinkScale(k)) frac = Math.min(frac, g0[k] / (g0[k] - g1));
                }
            }
            if (frac < 0.98 && frac * h > 20 * hMin) {
                if (ph.kinkResumeH <= 0) ph.kinkResumeH = h;
                ph.hNext = h * Math.min(1, frac + 0.002);
                kinkStops.increment();
                return 2;
            }
            if (frac < 1 && ph.kinkResumeH > 0) ph.kinkLanded = true;
        }
        if (worstSlot >= 0 && err > 0.5) limiterCounts.merge((ph == em && em != null ? "circuits:" : "") + net.slotOwner(worstSlot) + "#" + worstSlot, 1, Integer::sum);
        if (STEP_TRACE_FROM >= 0 && t >= STEP_TRACE_FROM && t < STEP_TRACE_FROM + 0.004)
            System.out.printf("STEP t=%.7f h=%.3e err=%.2f %s%n", t, h, err, worstSlot >= 0 ? net.slotOwner(worstSlot) + "#" + worstSlot : "-");
        if (err > 1.0 && !skipLte) {
            ph.hNext = h * Math.max(0.1, 0.9 * Math.pow(err, -exponent));
            if (TRACE && h < 1e-8) System.out.println("TRACE LTE reject h=" + h + " err=" + err + " slot " + net.slotOwner(worstSlot)
                    + "#" + worstSlot + " q=" + qNew[worstSlot] + " qd " + qd0[worstSlot] + " -> " + qdNew[worstSlot] + " validHistory " + validHistory);
            return 2;
        }

        // ---- trapezoidal ringing detection (sawtooth in q̇ from an under-resolved stiff mode).
        // A well-resolved oscillation changes the sign of Δq̇ at most once per half period, so three
        // consecutive alternations above tolerance can only be numerical ringing; one damping
        // backward-Euler step then removes it (BE amplification → 0 for stiff modes).
        ph.forceBE = false;
        if (m == IntegrationMethod.TRAPEZOIDAL && validHistory >= 2) {
            for (int si = 0; si < nsl; si++) {
                int s = sl == null ? si : sl[si];
                double d1 = qdNew[s] - qd0[s], d0 = qd0[s] - qd1[s];
                double tol = (lteRelTol * Math.max(Math.abs(qNew[s]), Math.abs(q0[s])) + slotTol[s]) * LTE_SCALE * accuracy / h;
                if (d1 * d0 < 0 && Math.abs(d1) > tol && Math.abs(d0) > tol) {
                    if (++alternations[s] >= 3) {
                        ph.forceBE = true;
                        alternations[s] = 0;
                    }
                } else {
                    alternations[s] = 0;
                }
            }
        }

        // ---- accept
        if (sl == null) {
            System.arraycopy(q0, 0, q1, 0, ns);
            System.arraycopy(qNew, 0, q0, 0, ns);
            System.arraycopy(qd0, 0, qd1, 0, ns);
            System.arraycopy(qdNew, 0, qd0, 0, ns);
        } else {
            for (int s : sl) {
                q1[s] = q0[s];
                q0[s] = qNew[s];
                qd1[s] = qd0[s];
                qd0[s] = qdNew[s];
            }
        }
        if (frozen == null) {
            System.arraycopy(x, 0, xPrev, 0, n);
            System.arraycopy(xTrial, 0, x, 0, n);
        } else {
            for (int i : free) {
                xPrev[i] = x[i];
                x[i] = xTrial[i];
            }
        }
        ph.t += h;
        ph.hPrev = h;
        // The first step after a restart absorbs any discontinuity (inconsistent initial conditions,
        // a switched source): its derivative q̇ spans the jump and must not seed the trapezoidal
        // history or the error estimator, so history counting begins with the following step.
        ph.validHistory = wasRestart ? 0 : validHistory + 1;
        ph.restart = false;
        double grow = err == 0.0 ? 2.0 : Math.min(2.0, 0.9 * Math.pow(err, -exponent));
        if (ph == slow && slowChange > 0) grow = Math.min(grow, 0.9 / slowChange);
        ph.hNext = Math.max(h * Math.max(grow, 0.5), hMin * 10);
        if (ph == slow) {
            slowSteps++;
            afterKinks(ph);
            updateLedger(ph, h, m);
            return 0;
        }
        if (ph != slow && ph != all) {
            // the heat the fast elements delivered into each thermal node over this step (f is their residual at
            // the converged point: −heat in), and the coupling's stiffness there
            boolean beq = m == IntegrationMethod.BACKWARD_EULER;
            // (the circuits and the plant's parts step on the calling thread, the plant perhaps on another meanwhile)
            double[] acc = ph == em ? emHeat : isPart(ph) ? partHeat : heatAccum;
            for (int i : slowRows) {
                double rate = -f[i];
                // the quadrature of the step taken: the end point after backward Euler, else the trapezoid
                acc[i] += (beq ? rate : 0.5 * (rate + ph.lastRate[i])) * h;
                ph.lastRate[i] = rate;
                ph.cpl[i] = ctx.frozenDiag[i];
            }
            if (ph == em) {
                // the circuits' delivery into the plant's rows (a generator's torque), and its work along the plant's motion
                double span = emT1 - emT0;
                for (int i : plantInRows) {
                    double rate = -f[i], mean = beq ? rate : 0.5 * (rate + emRate[i]);
                    emAccum[i] += mean * h;
                    emRate[i] = rate;
                    double v0 = span > 0 ? plantStart[i] + (plantEnd[i] - plantStart[i]) * (ph.t - h - emT0) / span : plantEnd[i];
                    double v1 = span > 0 ? plantStart[i] + (plantEnd[i] - plantStart[i]) * (ph.t - emT0) / span : plantEnd[i];
                    couplingTransit += mean * 0.5 * (v0 + v1) * h * powerSign(i);
                }
            } else if (em != null && ph == fast) {
                // the plant took the circuits' held delivery along its own motion over this step
                for (int i : plantInRows) plantTransit -= fast.heatIn[i] * 0.5 * (xPrev[i] + x[i]) * h * powerSign(i);
            }
        }
        if ((ph != fast || em == null) && !(inFlight && isPart(ph))) {
            t = ph.t;
            hPrev = h;
        }
        acceptedSteps.increment();
        ph.nAccepted++;

        // each element hears of its own phase's steps (an element recording its motion against time hears its own times)
        // (seen through the phase's context: its accepted state, at its own time — the circuits may be stepping meanwhile)
        ctx.time = ph.t;
        if (ph.elems == null) for (Element e : elements) e.acceptStep(ctx, ph.t, h);
        else for (int k : ph.elems) elements.get(k).acceptStep(ctx, ph.t, h);
        afterKinks(ph);
        updateLedger(ph, h, m);
        // (instruments sample along the plant's and the circuits' steps; a part's own steps run on their own clock).
        // Overlapped, they sample along the circuits' steps alone, and see the plant where the circuits see it
        if (!isPart(ph) && !(overlapped && ph == fast)) {
            if (overlapped) view = xTrial;
            try {
                for (var l : stepListeners) l.accept(this);
            } finally {
                view = null;
            }
        }
        return 0;
    }

    /** Diagnostic after a non-finite Newton update: which elements produce non-finite residuals at the iterate. */
    private String nonFiniteSource(Phase ph) {
        LoadContext ctx = ph.ctx;
        double[] f = ph.f, xTrial = ph.xTrial;
        LinearSystem jac = ph.jac;
        StringBuilder sb = new StringBuilder();
        for (Element e : elements) {
            Arrays.fill(f, 0.0);
            jac.clear();
            try {
                e.load(ctx);
            } catch (RuntimeException ex) {
                sb.append("; ").append(e.label()).append(" threw ").append(ex.getMessage());
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(f[i])) {
                    sb.append("; ").append(e.label()).append(" → ").append(net.unknownName(i));
                    break;
                }
            }
            if (sb.length() > 300) break;
        }
        int bad = 0;
        for (int i = 0; i < n && bad < 3; i++) if (!Double.isFinite(xTrial[i])) {
            sb.append("; x[").append(net.unknownName(i)).append("] = ").append(xTrial[i]);
            bad++;
        }
        return sb.toString();
    }

    /**
     * Integrates the energy crossing the system's boundary over an accepted step with the quadrature the step's method
     * implies — the end point after backward Euler, the trapezoid otherwise — so the ledger and the integrated state agree.
     */
    private void updateLedger(Phase ph, double h, IntegrationMethod m) {
        StateView sv = ph.ctx != null ? ph.ctx : this;
        double pExt = power(ph, true, sv);
        double pLoss = power(ph, false, sv);
        boolean be = m == IntegrationMethod.BACKWARD_EULER;
        double in = (be ? pExt : 0.5 * (pExt + ph.lastPext)) * h, out = (be ? pLoss : 0.5 * (pLoss + ph.lastPloss)) * h;
        if (ph == em) {
            emExternalIn += in;
            emUntrackedOut += out;
        } else if (isPart(ph)) {
            partExternalIn += in;
            partUntrackedOut += out;
        } else {
            externalIn += in;
            untrackedOut += out;
        }
        ph.lastPext = pExt;
        ph.lastPloss = pLoss;
        storedStale = true;
    }

    /** The energy stored now: in the elements, and in transit between the phases. */
    private double storedNow() {
        if (!storedStale) return storedCache;
        double e = totalStored();
        // heat delivered by the fast partition, not yet in the thermal network (during a synchronisation, the part of
        // it the thermal network has still to take in)
        if (multirate) {
            if (active == slow) for (int i : slowRows) e += slow.heatIn[i] * (t - slow.t);
            else for (int i : slowRows) e += heatAccum[i] + emHeat[i] + partHeat[i];
            if (em != null) e += couplingTransit + plantTransit;
        }
        storedStale = false;
        return storedCache = e;
    }

    /** 1 if row i is a flow-like unknown whose delivery times its value is a power (a body's speed), else 0. */
    private double powerSign(int i) {
        return powerRow != null && powerRow[i] ? 1 : 0;
    }

    private boolean[] powerRow;

    private double totalStored() {
        double s = 0;
        for (Element e : elements) s += e.storedEnergy(this);
        return s;
    }

    private double totalExternalPower() {
        double s = 0;
        for (Element e : elements) s += e.externalPower(this);
        return s;
    }

    private double totalUntrackedLoss() {
        double s = 0;
        for (Element e : elements) s += e.untrackedLoss(this);
        return s;
    }

    // ------------------------------------------------------------------ results

    @Override
    public double x(int index) {
        double[] v = view;
        return index < 0 ? 0.0 : v != null ? v[index] : x[index];
    }

    /** While instruments sample along the circuits' steps with the plant a step ahead: what the circuits see. */
    private double[] view;

    @Override
    public double q(int slot) {
        return q0[slot];
    }

    @Override
    public double qdot(int slot) {
        return qd0[slot];
    }

    @Override
    public double time() {
        return t;
    }

    public double storedEnergy() {
        return storedNow();
    }

    public double externalEnergyIn() {
        return externalIn + emExternalIn + partExternalIn;
    }

    public double untrackedEnergyOut() {
        return untrackedOut + emUntrackedOut + partUntrackedOut;
    }

    /**
     * Energy conservation residual [J]: (E_stored − E_stored,0) − (∫P_external − ∫P_untracked).
     * Zero for an exact integration; its magnitude measures numerical energy drift.
     */
    public double energyError() {
        return (storedNow() - storedInitial) - (externalEnergyIn() - untrackedEnergyOut());
    }

    public long acceptedSteps() {
        return acceptedSteps.sum();
    }

    public long rejectedSteps() {
        return rejectedSteps.sum();
    }

    public long newtonIterations() {
        return newtonIterations.sum();
    }

    /** Number of unknowns (the size of each Newton step's linear system). */
    public int unknowns() {
        return n;
    }

    /** The stepping Jacobian's stored entries and its LU factors' off-diagonal entries (fill diagnostics; 0 if dense). */
    public int[] jacobianFill() {
        return active.jac instanceof dev.emergence.core.math.SparseLinearSystem s ? new int[]{s.nonZeros(), s.factorNonZeros()} : new int[]{0, 0};
    }

    /** Each phase's matrix: entries, LU fill and a refactorisation's multiply-adds (diagnostics). */
    public String phaseMatrices() {
        StringBuilder sb = new StringBuilder();
        String[] names = {"all", "plant", "thermal", "circuits"};
        Phase[] phs = {all, fast, slow, em};
        for (int i = 0; i < 4; i++) {
            if (phs[i] == null || !(phs[i].jac instanceof dev.emergence.core.math.SparseLinearSystem s)) continue;
            if (s.factorNonZeros() == 0) continue;
            sb.append(String.format("%s %d entries, fill %d, %d flops; ", names[i], s.nonZeros(), s.factorNonZeros(), s.factorFlops()));
        }
        return sb.toString();
    }

    /** The plant matrix's connected components over its free rows: size and a few unknowns each (diagnostics). */
    public String plantComponents() {
        return components(fast) + (em != null ? String.format("%n      circuits:") + components(em) : "");
    }

    private String components(Phase fast) {
        if (fast == null) return "";
        int[] comp = new int[n];
        for (int i = 0; i < n; i++) comp[i] = i;
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            public int applyAsInt(int i) {
                while (comp[i] != i) i = comp[i] = comp[comp[i]];
                return i;
            }
        };
        boolean[] fr = fast.frozen;
        fast.jac.visitPattern(0, (r, c) -> {
            if (fr != null && (fr[r] || fr[c])) return;
            int a = find.applyAsInt(r), b = find.applyAsInt(c);
            if (a != b) comp[Math.max(a, b)] = Math.min(a, b);
        });
        java.util.Map<Integer, java.util.List<Integer>> groups = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) if (fr == null || !fr[i]) groups.computeIfAbsent(find.applyAsInt(i), k -> new java.util.ArrayList<>()).add(i);
        StringBuilder sb = new StringBuilder();
        groups.values().stream().sorted((x, y) -> y.size() - x.size()).limit(12).forEach(g -> {
            sb.append(String.format("%n      %d unknowns:", g.size()));
            for (int k = 0; k < Math.min(6, g.size()); k++) sb.append(' ').append(net.unknownName(g.get(k * g.size() / Math.min(6, g.size()))));
        });
        sb.append(String.format("%n      (%d components)", groups.size()));
        return sb.toString();
    }

    /** The plant matrix's costliest pivots by unknown (diagnostics). */
    public String costliestPivots(int k) {
        if (!(fast != null && fast.jac instanceof dev.emergence.core.math.SparseLinearSystem s)) return "";
        StringBuilder sb = new StringBuilder();
        for (long[] p : s.costliestPivots(k))
            sb.append(String.format("%n      step %d %s: %d flops, L %d, U %d", p[4], net.unknownName((int) p[0]), p[1], p[2], p[3]));
        return sb.toString();
    }

    public long newtonFailures() {
        return newtonFailures.sum();
    }

    public double lastStepSize() {
        return hPrev;
    }
}
