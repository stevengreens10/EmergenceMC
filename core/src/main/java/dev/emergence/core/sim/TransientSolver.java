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
    private double hMin = 1e-10;
    private double hMax = 5e-3;
    /**
     * First step after a restart. Backward Euler damps oscillatory modes by ≈(ωh)² per step, so the
     * restart step is kept tiny; LTE control then grows h geometrically (×2 per step).
     */
    private double hRestart = 1e-7;
    private int maxNewton = 40;

    // energy ledger
    private double storedInitial, externalIn, untrackedOut, lastPext, lastPloss, storedNow;

    // statistics
    private long acceptedSteps, rejectedSteps, newtonFailures, newtonIterations;

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
        /** Slots it integrates; null: all. */
        int[] slots;
        /** Unknowns held fixed while it steps; null: none. */
        boolean[] frozen;
        /** Heat [W] delivered into each unknown's row from the other partition (slow phase only), or null. */
        double[] heatIn;
        /** Last external and untracked power of its elements, for the ledger's trapezoid. */
        double lastPext, lastPloss;
        /** Its Jacobian: under multirate each phase factorises only what moves in it (see LoadContext#frozen). */
        LinearSystem jac;
    }

    private final Phase all = new Phase();
    private Phase active = all;

    // ---- multirate (see #multirate)
    private boolean multirate;
    private Phase fast, slow;
    private int[] slowRows;
    /** ∫ heat delivered into each slow row by the fast partition since the last synchronisation [J], and its rate [W]. */
    private double[] heatAccum, heatRate;
    /** Per slow row: heat capacity [J/K] and the fast side's latest ∂(heat in)/∂T [W/K] (the coupling's stiffness). */
    private double[] capacity, coupling;
    private long slowSteps;
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

    // ------------------------------------------------------------------ configuration

    /** Every element stamps the Jacobian and residual, each in a segment of its own (see SparseLinearSystem). */
    private void loadAll() {
        if (ctx.floor == null || ctx.floor.length != n) ctx.floor = new double[n];
        else Arrays.fill(ctx.floor, 0.0);
        LinearSystem jac = active.jac;
        ctx.jac = jac;
        ctx.frozen = active.frozen;
        if (active.frozen != null) {
            if (ctx.frozenDiag == null) ctx.frozenDiag = new double[n];
            else Arrays.fill(ctx.frozenDiag, 0.0);
        }
        int[] el = active.elems;
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
        for (int k = 0; k < ne; k++) {
            ctx.touched = new boolean[n];
            ctx.touchedSlots = new boolean[ns];
            Arrays.fill(f, 0);
            jac.clear();
            jac.beginSegment(k);
            elements.get(k).load(ctx);
            jac.beginSegment(-1);
            rows[k] = ctx.touched;
            slotsOf[k] = ctx.touchedSlots;
        }
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
        heatAccum = new double[n];
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
        return this;
    }

    /** Whether multirate stepping is in effect. */
    public boolean isMultirate() {
        return multirate;
    }

    /** Thermal-network steps taken under multirate (diagnostics). */
    public long slowSteps() {
        return slowSteps;
    }

    /** Puts every phase at the current time with no history (after initialisation or a state change). */
    private void resetPhases() {
        for (Phase ph : new Phase[]{all, fast, slow}) {
            if (ph == null) continue;
            ph.t = t;
            ph.validHistory = 0;
            ph.restart = true;
            ph.hNext = ph == slow ? 1e-3 : hRestart;
        }
        active = multirate ? fast : all;
        if (multirate) {
            Arrays.fill(heatAccum, 0);
            fastHeatRate();
            fast.lastPext = power(fast, true);
            fast.lastPloss = power(fast, false);
            slow.lastPext = power(slow, true);
            slow.lastPloss = power(slow, false);
        }
    }

    /** The fast partition's heat into each slow row [W] at the current state, and the coupling stiffness. */
    private void fastHeatRate() {
        Phase saved = active;
        active = fast;
        ctx.x = x;
        ctx.time = t;
        ctx.h = Math.max(fast.hPrev, hRestart);
        ctx.hPrev = ctx.h;
        ctx.method = IntegrationMethod.BACKWARD_EULER;
        ctx.alpha = 1 / ctx.h;
        double[] q0s = q0.clone(), qd0s = qd0.clone();
        Arrays.fill(f, 0);
        fast.jac.clear();
        loadAll();
        for (int i : slowRows) {
            heatRate[i] = -f[i];
            coupling[i] = ctx.frozenDiag[i];
        }
        System.arraycopy(q0s, 0, q0, 0, ns);
        System.arraycopy(qd0s, 0, qd0, 0, ns);
        Arrays.fill(f, 0);
        fast.jac.clear();
        active = saved;
        ctx.jac = active.jac;
        ctx.frozen = active.frozen;
    }

    /** External (true) or untracked (false) power of a phase's elements. */
    private double power(Phase ph, boolean external) {
        double s = 0;
        if (ph.elems == null) {
            for (Element e : elements) s += external ? e.externalPower(this) : e.untrackedLoss(this);
        } else {
            for (int k : ph.elems) s += external ? elements.get(k).externalPower(this) : elements.get(k).untrackedLoss(this);
        }
        return s;
    }

    /**
     * Steps the thermal network from where it is to the fast partition's time, the heat the fast partition delivered
     * meanwhile entering as a constant power, in as many steps as its error control asks.
     */
    private void synchronise() {
        if (!multirate) return;
        double span = t - slow.t;
        if (span <= 1e-15 * Math.max(1, Math.abs(t))) return;
        for (int i : slowRows) slow.heatIn[i] = heatAccum[i] / span;
        // its input steps here: start afresh (backward Euler), not carrying the old input's derivative across
        slow.restart = true;
        active = slow;
        double end = t;
        while (slow.t < end - 1e-12 * Math.max(1, Math.abs(end))) {
            double h = Math.min(slow.hNext, end - slow.t);
            if (end - slow.t - h < 0.25 * h) h = end - slow.t;
            stepAdaptive(slow, h);
        }
        slow.t = end;
        active = fast;
        Arrays.fill(heatAccum, 0);
        storedNow = totalStored();
    }


    /** The interval after which the thermal network is brought up to date: its error control, the coupling's stability. */
    private double syncInterval() {
        double lim = Math.max(slow.hNext, fast.hPrev);
        for (int i : slowRows) {
            double g = Math.abs(coupling[i]);
            if (g > 0 && capacity[i] > 0) lim = Math.min(lim, 0.5 * capacity[i] / g);
        }
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
        storedInitial = storedNow = totalStored();
        externalIn = 0;
        untrackedOut = 0;
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
        Phase ph = multirate ? fast : all;
        ph.restart = true;
        ph.validHistory = 0;
        ph.hNext = Math.min(ph.hNext, hRestart);
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
        while (t < tEnd - 1e-12 * Math.max(1.0, Math.abs(tEnd))) {
            if (steps++ >= maxSteps) break;
            if (deadlineNanos != 0 && steps > 1 && System.nanoTime() - deadlineNanos > 0) break;
            fireEvents();
            double h = Math.min(ph.hNext, hMax);
            double remaining = tEnd - t;
            if (h >= remaining * 0.99) h = remaining;
            else if (remaining - h < 0.25 * h) h = 0.5 * remaining;
            // land exactly on the next event
            double te = nextEventTime();
            if (te > t && te - t < h) h = Math.max(te - t, hMin * 2);
            stepAdaptive(ph, h);
            if (multirate && t - slow.t >= syncInterval()) synchronise();
        }
        synchronise();
        fireEvents();
        return t - tStart;
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
        boolean changed = false;
        double eps = 1e-12 * Math.max(1, Math.abs(t)) + hMin;
        for (EventSource e : eventSources) {
            if (e.nextEvent(t - eps) <= t + eps) changed |= e.fireEvent(this, t);
        }
        if (changed) markDiscontinuity();
    }

    public double advance(double duration) {
        return advance(duration, 1_000_000);
    }

    /** Takes one accepted step of at most {@code hTry}, shrinking on Newton/LTE failure. */
    private void stepAdaptive(Phase ph, double hTry) {
        active = ph;
        double h = hTry;
        while (true) {
            if (h < hMin) {
                // Last resort before giving up: Newton stalls at round-off level on an ill-conditioned variable (a
                // nearly closed valve's flow, a floating node) can collapse the step although the state is sound.
                // Take one backward-Euler step of 1 µs with the Newton tolerances relaxed 100× and no truncation-
                // error test; it is accepted only if Newton converges, and counted.
                double hr = Math.max(1e-6, hMin * 1e4);
                tolScale = 100;
                skipLte = true;
                ph.restart = true;
                ph.validHistory = 0;
                int rr = tryStep(ph, Math.min(hr, Math.max(hTry, hMin)));
                tolScale = 1;
                skipLte = false;
                if (rr == 0) {
                    rescues++;
                    ph.hNext = Math.max(hr, ph.hNext);
                    return;
                }
                throw new SimulationException(String.format(
                        "Time step underflow at t=%.6g s (h=%.3g s)%s. %s", ph.t, h, ph == slow ? " in the thermal network" : "",
                        worstNewtonDiagnostic()));
            }
            int r = tryStep(ph, h);
            if (r == 0) return;
            if (r == 1) { // Newton failure
                newtonFailures++;
                ph.restart = true;
                ph.validHistory = 0;
                h *= 0.25;
            } else { // LTE rejection: hNext already set
                rejectedSteps++;
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
    private double tolScale = 1;
    private boolean skipLte;
    private long rescues;

    /** Steps accepted by the underflow rescue (relaxed-tolerance backward Euler) — should stay rare. */
    public long rescuedSteps() {
        return rescues;
    }
    /** Diagnostics: print the worst unknowns of each Newton iteration once the step has collapsed. */
    public static boolean TRACE = Boolean.getBoolean("diag.trace");
    private int worstSlot = -1;
    private final java.util.Map<String, Integer> limiterCounts = new java.util.HashMap<>();

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
    /** Diagnostics ({@code diag.lagTrace=<name part>}): each iteration that unknown holds Newton back, its value, update, residual and diagonal (400 lines). */
    private static final String LAG_TRACE = System.getProperty("diag.lagTrace");
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
    private void updateDatums(LinearSystem js) {
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
        LinearSystem jac = ph.jac;
        boolean be = ph.restart || ph.forceBE || ph.validHistory == 0 || method == IntegrationMethod.BACKWARD_EULER;
        boolean wasRestart = ph.restart;
        double hPrev = ph.hPrev;
        boolean[] frozen = ph.frozen;
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
            for (int i = 0; i < n; i++) xTrial[i] = frozen != null && frozen[i] ? x[i] : x[i] + r * (x[i] - xPrev[i]);
        } else {
            System.arraycopy(x, 0, xTrial, 0, n);
        }
        ctx.x = xTrial;

        boolean converged = false, damped;
        int bounces = 0;
        for (int it = 0; it < maxNewton; it++) {
            newtonIterations++;
            Arrays.fill(f, 0.0);
            jac.clear();
            loadAll();
            if (circuitOf != null) updateDatums(jac);
            if (ph.heatIn != null) for (int i : slowRows) f[i] -= ph.heatIn[i];
            if (frozen != null) {
                // held unknowns: their rows (stamped with nothing else) say "do not move"
                for (int i = 0; i < n; i++) {
                    if (!frozen[i]) continue;
                    f[i] = 0;
                    jac.add(i, i, 1.0);
                }
            }
            for (int i = 0; i < n; i++) {
                if (frozen != null && frozen[i]) continue;
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
            if (CHECK_JACOBIAN && it == 8 && jac instanceof dev.emergence.core.math.DenseLinearSystem dj) checkJacobian(dj);
            for (int i = 0; i < n; i++) dx[i] = -f[i];
            try {
                jac.solve(dx);
            } catch (SingularMatrixException e) {
                lastBadUnknown = "singular in column of " + net.unknownName(Math.min(e.row(), n - 1));
                lastBadIndex = -1;
                return 1;
            }
            if (frozen != null) for (int i = 0; i < n; i++) if (frozen[i]) dx[i] = 0;
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
            damped = bounces >= 3;
            if (it >= 3 && !damped) {
                double dot = 0, n1 = 0, n2 = 0, far = 0;
                for (int i = 0; i < n; i++) {
                    dot += dx[i] * dxPrev[i];
                    n1 += dx[i] * dx[i];
                    n2 += dxPrev[i] * dxPrev[i];
                    far = Math.max(far, Math.abs(dx[i]) / (relTol * Math.abs(xTrial[i]) + absTol[i]));
                }
                damped = far > 1e3 && dot < -0.9 * Math.sqrt(n1 * n2);
                if (damped) bounces++;
            }
            System.arraycopy(dx, 0, dxPrev, 0, n);
            double scale = damped ? 0.5 : 1.0;
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(dx[i])) {
                    lastBadUnknown = net.unknownName(i) + " (non-finite" + nonFiniteSource() + ")";
                    lastBadIndex = -1;
                    return 1;
                }
            }
            boolean limited = false;
            for (int i = 0; i < n; i++) {
                if (Math.abs(dx[i]) > stepLimit[i]) {
                    dx[i] = Math.copySign(stepLimit[i], dx[i]);
                    limited = true;
                }
            }
            for (int i = 0; i < n; i++) {
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
                break;
            }
            if (NEWTON_LAGGARDS && it >= 2 && lastBadIndex >= 0) newtonLaggards.merge(net.unknownName(lastBadIndex), 1, Integer::sum);
            if (LAG_TRACE != null && lagLines < 400 && lastBadIndex >= 0 && net.unknownName(lastBadIndex).contains(LAG_TRACE)) {
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
        loadAll();
        ctx.residualOnly = false;

        // ---- local truncation error control
        double err = 0.0;
        int p = m.order;
        int[] sl = ph.slots;
        int nsl = sl == null ? ns : sl.length;
        int validHistory = ph.validHistory;
        worstSlot = -1;
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
                double tol = (lteRelTol * Math.max(Math.abs(qNew[s]), Math.abs(q0[s])) + slotTol[s]) * LTE_SCALE;
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
        if (worstSlot >= 0 && err > 0.5) limiterCounts.merge(net.slotOwner(worstSlot) + "#" + worstSlot, 1, Integer::sum);
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
                double tol = (lteRelTol * Math.max(Math.abs(qNew[s]), Math.abs(q0[s])) + slotTol[s]) * LTE_SCALE / h;
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
            for (int i = 0; i < n; i++) {
                if (frozen[i]) continue;
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
            updateLedger(ph, h, m);
            return 0;
        }
        if (ph == fast) {
            // the heat the fast elements delivered into each thermal node over this step (f is their residual at
            // the converged point: −heat in), and the coupling's stiffness there
            for (int i : slowRows) {
                double rate = -f[i];
                // the quadrature of the step taken: the end point after backward Euler, else the trapezoid
                heatAccum[i] += (m == IntegrationMethod.BACKWARD_EULER ? rate : 0.5 * (rate + heatRate[i])) * h;
                heatRate[i] = rate;
                coupling[i] = ctx.frozenDiag[i];
            }
        }
        t = ph.t;
        hPrev = h;
        acceptedSteps++;

        for (Element e : elements) e.acceptStep(this, t, h);
        updateLedger(ph, h, m);
        for (var l : stepListeners) l.accept(this);
        return 0;
    }

    /** Diagnostic after a non-finite Newton update: which elements produce non-finite residuals at the iterate. */
    private String nonFiniteSource() {
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
        double pExt = power(ph, true);
        double pLoss = power(ph, false);
        boolean be = m == IntegrationMethod.BACKWARD_EULER;
        externalIn += (be ? pExt : 0.5 * (pExt + ph.lastPext)) * h;
        untrackedOut += (be ? pLoss : 0.5 * (pLoss + ph.lastPloss)) * h;
        ph.lastPext = pExt;
        ph.lastPloss = pLoss;
        storedNow = totalStored();
        // heat delivered by the fast partition, not yet in the thermal network (during a synchronisation, the part of
        // it the thermal network has still to take in)
        if (multirate) {
            if (ph == slow) for (int i : slowRows) storedNow += slow.heatIn[i] * (t - slow.t);
            else for (int i : slowRows) storedNow += heatAccum[i];
        }
    }

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
        return index < 0 ? 0.0 : x[index];
    }

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
        return storedNow;
    }

    public double externalEnergyIn() {
        return externalIn;
    }

    public double untrackedEnergyOut() {
        return untrackedOut;
    }

    /**
     * Energy conservation residual [J]: (E_stored − E_stored,0) − (∫P_external − ∫P_untracked).
     * Zero for an exact integration; its magnitude measures numerical energy drift.
     */
    public double energyError() {
        return (storedNow - storedInitial) - (externalIn - untrackedOut);
    }

    public long acceptedSteps() {
        return acceptedSteps;
    }

    public long rejectedSteps() {
        return rejectedSteps;
    }

    public long newtonIterations() {
        return newtonIterations;
    }

    /** Number of unknowns (the size of each Newton step's linear system). */
    public int unknowns() {
        return n;
    }

    /** The stepping Jacobian's stored entries and its LU factors' off-diagonal entries (fill diagnostics; 0 if dense). */
    public int[] jacobianFill() {
        return active.jac instanceof dev.emergence.core.math.SparseLinearSystem s ? new int[]{s.nonZeros(), s.factorNonZeros()} : new int[]{0, 0};
    }

    public long newtonFailures() {
        return newtonFailures;
    }

    public double lastStepSize() {
        return hPrev;
    }
}
