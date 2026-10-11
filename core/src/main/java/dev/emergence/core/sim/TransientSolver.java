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
    private final List<java.util.function.Consumer<TransientSolver>> stepListeners = new java.util.ArrayList<>();
    private final int n, ns;
    private final LinearSystem jac;
    private final LoadContext ctx = new LoadContext();

    private final double[] x, xPrev, xTrial, f, dx;
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
        initializeHistory();
    }

    // ------------------------------------------------------------------ configuration

    /** Every element stamps the Jacobian and residual, each in a segment of its own (see SparseLinearSystem). */
    private void loadAll() {
        if (ctx.floor == null || ctx.floor.length != n) ctx.floor = new double[n];
        else Arrays.fill(ctx.floor, 0.0);
        for (int k = 0; k < elements.size(); k++) {
            jac.beginSegment(k);
            elements.get(k).load(ctx);
        }
        jac.beginSegment(-1);
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
    }

    /**
     * Signals a discontinuity (switch toggled, source stepped, parameter changed): the next step
     * restarts with backward Euler and a small step so the integrator does not extrapolate across it.
     */
    public void markDiscontinuity() {
        restart = true;
        validHistory = 0;
        hNext = Math.min(hNext, hRestart);
    }

    // ------------------------------------------------------------------ stepping

    /**
     * Advances the simulation by {@code duration} seconds with adaptive internal steps.
     *
     * @param maxSteps safety budget; if exceeded the solver stops early (the caller sees lag)
     * @return simulated time actually advanced
     */
    public double advance(double duration, int maxSteps) {
        double tStart = t;
        double tEnd = t + duration;
        int steps = 0;
        while (t < tEnd - 1e-12 * Math.max(1.0, Math.abs(tEnd))) {
            if (steps++ >= maxSteps) break;
            fireEvents();
            double h = Math.min(hNext, hMax);
            double remaining = tEnd - t;
            if (h >= remaining * 0.99) h = remaining;
            else if (remaining - h < 0.25 * h) h = 0.5 * remaining;
            // land exactly on the next event
            double te = nextEventTime();
            if (te > t && te - t < h) h = Math.max(te - t, hMin * 2);
            stepAdaptive(h);
        }
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
    private void stepAdaptive(double hTry) {
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
                restart = true;
                validHistory = 0;
                int rr = tryStep(Math.min(hr, Math.max(hTry, hMin)));
                tolScale = 1;
                skipLte = false;
                if (rr == 0) {
                    rescues++;
                    hNext = Math.max(hr, hNext);
                    return;
                }
                throw new SimulationException(String.format(
                        "Time step underflow at t=%.6g s (h=%.3g s). %s", t, h, worstNewtonDiagnostic()));
            }
            int r = tryStep(h);
            if (r == 0) return;
            if (r == 1) { // Newton failure
                newtonFailures++;
                restart = true;
                validHistory = 0;
                h *= 0.25;
            } else { // LTE rejection: hNext already set
                rejectedSteps++;
                h = Math.min(hNext, h * 0.9);
                // An estimate that still fails after the step has shrunk by orders of magnitude compares against
                // stale history (e.g. a derivative left by ringing across a fast edge): restart the integration
                // there, as after any discontinuity, rather than chase it down to underflow.
                if (h < 1e-4 * hTry || h < 1e3 * hMin) {
                    restart = true;
                    validHistory = 0;
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
    public static boolean TRACE = false;
    private int worstSlot = -1;
    private final java.util.Map<String, Integer> limiterCounts = new java.util.HashMap<>();

    /** How often each element's truncation error limited the step (diagnostics). */
    public java.util.Map<String, Integer> stepLimiters() {
        return limiterCounts;
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

    /** @return 0 accepted, 1 Newton failure, 2 LTE rejection */
    private int tryStep(double h) {
        boolean be = restart || forceBE || validHistory == 0 || method == IntegrationMethod.BACKWARD_EULER;
        boolean wasRestart = restart;
        IntegrationMethod m = be ? IntegrationMethod.BACKWARD_EULER : method;
        ctx.method = m;
        ctx.h = h;
        ctx.hPrev = hPrev;
        ctx.time = t + h;
        ctx.alpha = switch (m) {
            case BACKWARD_EULER -> 1.0 / h;
            case TRAPEZOIDAL -> 2.0 / h;
            case BDF2 -> {
                double w = h / hPrev;
                yield (1 + 2 * w) / ((1 + w) * h);
            }
        };
        // predictor: linear extrapolation of the solution
        if (!be) {
            double r = h / hPrev;
            for (int i = 0; i < n; i++) xTrial[i] = x[i] + r * (x[i] - xPrev[i]);
        } else {
            System.arraycopy(x, 0, xTrial, 0, n);
        }
        ctx.x = xTrial;

        boolean converged = false;
        for (int it = 0; it < maxNewton; it++) {
            newtonIterations++;
            Arrays.fill(f, 0.0);
            jac.clear();
            loadAll();
            for (int i = 0; i < n; i++) {
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
            boolean small = true;
            // damping: limit enormous Newton updates on nonlinear elements
            double scale = 1.0;
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
                StringBuilder sb = new StringBuilder("TRACE h=" + h + " it=" + it + ":");
                for (int k = 0; k < 5; k++) sb.append(" ").append(net.unknownName(order[k])).append("=").append(String.format("%.6g(dx %.3g)", xTrial[order[k]], dx[order[k]]));
                System.out.println(sb);
            }
            if (small && !limited) {
                converged = true;
                break;
            }
        }
        if (!converged) return 1;

        // Re-evaluate stored quantities at the converged point so the history is exact.
        Arrays.fill(f, 0.0);
        jac.clear();
        loadAll();

        // ---- local truncation error control
        double err = 0.0;
        int p = m.order;
        if (validHistory >= 1) {
            for (int s = 0; s < ns; s++) {
                double est;
                if (p == 1 || validHistory < 2) {
                    est = 0.5 * Math.abs(qdNew[s] - qd0[s]) * h; // ≈ h²/2 |q''|
                } else {
                    double q3 = 2.0 * ((qdNew[s] - qd0[s]) / h - (qd0[s] - qd1[s]) / hPrev) / (h + hPrev);
                    est = m.errorConstant * h * h * h * Math.abs(q3);
                }
                double tol = lteRelTol * Math.max(Math.abs(qNew[s]), Math.abs(q0[s])) + slotTol[s];
                if (est / tol > err) {
                    err = est / tol;
                    worstSlot = s;
                }
            }
        }
        double exponent = 1.0 / ((validHistory >= 2 ? p : 1) + 1);
        if (worstSlot >= 0 && err > 0.5) limiterCounts.merge(net.slotOwner(worstSlot) + "#" + worstSlot, 1, Integer::sum);
        if (err > 1.0 && !skipLte) {
            hNext = h * Math.max(0.1, 0.9 * Math.pow(err, -exponent));
            if (TRACE && h < 1e-8) System.out.println("TRACE LTE reject h=" + h + " err=" + err + " slot " + net.slotOwner(worstSlot)
                    + "#" + worstSlot + " q=" + qNew[worstSlot] + " qd " + qd0[worstSlot] + " -> " + qdNew[worstSlot] + " validHistory " + validHistory);
            return 2;
        }

        // ---- trapezoidal ringing detection (sawtooth in q̇ from an under-resolved stiff mode).
        // A well-resolved oscillation changes the sign of Δq̇ at most once per half period, so three
        // consecutive alternations above tolerance can only be numerical ringing; one damping
        // backward-Euler step then removes it (BE amplification → 0 for stiff modes).
        forceBE = false;
        if (m == IntegrationMethod.TRAPEZOIDAL && validHistory >= 2) {
            for (int s = 0; s < ns; s++) {
                double d1 = qdNew[s] - qd0[s], d0 = qd0[s] - qd1[s];
                double tol = (lteRelTol * Math.max(Math.abs(qNew[s]), Math.abs(q0[s])) + slotTol[s]) / h;
                if (d1 * d0 < 0 && Math.abs(d1) > tol && Math.abs(d0) > tol) {
                    if (++alternations[s] >= 3) {
                        forceBE = true;
                        alternations[s] = 0;
                    }
                } else {
                    alternations[s] = 0;
                }
            }
        }

        // ---- accept
        System.arraycopy(q0, 0, q1, 0, ns);
        System.arraycopy(qNew, 0, q0, 0, ns);
        System.arraycopy(qd0, 0, qd1, 0, ns);
        System.arraycopy(qdNew, 0, qd0, 0, ns);
        System.arraycopy(x, 0, xPrev, 0, n);
        System.arraycopy(xTrial, 0, x, 0, n);
        t += h;
        hPrev = h;
        // The first step after a restart absorbs any discontinuity (inconsistent initial conditions,
        // a switched source): its derivative q̇ spans the jump and must not seed the trapezoidal
        // history or the error estimator, so history counting begins with the following step.
        validHistory = wasRestart ? 0 : validHistory + 1;
        restart = false;
        double grow = err == 0.0 ? 2.0 : Math.min(2.0, 0.9 * Math.pow(err, -exponent));
        hNext = Math.max(h * Math.max(grow, 0.5), hMin * 10);
        acceptedSteps++;

        for (Element e : elements) e.acceptStep(this, t, h);
        updateLedger(h);
        for (int i = 0; i < stepListeners.size(); i++) stepListeners.get(i).accept(this);
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

    private void updateLedger(double h) {
        double pExt = totalExternalPower();
        double pLoss = totalUntrackedLoss();
        externalIn += 0.5 * (pExt + lastPext) * h;
        untrackedOut += 0.5 * (pLoss + lastPloss) * h;
        lastPext = pExt;
        lastPloss = pLoss;
        storedNow = totalStored();
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

    public long newtonFailures() {
        return newtonFailures;
    }

    public double lastStepSize() {
        return hPrev;
    }
}
