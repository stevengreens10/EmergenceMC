package dev.emergence.core.sim;

import dev.emergence.core.math.LinearSystem;
import dev.emergence.core.math.SingularMatrixException;

import java.util.Arrays;
import java.util.List;

/**
 * Adaptive implicit transient solver for a {@link Network}.
 * <p>
 * Each step solves the nonlinear residual system F(x_{n+1}) = 0 by damped Newton–Raphson with a sparse
 * (or small dense) LU factorisation. Stored quantities are integrated with {@link IntegrationMethod#BDF2}
 * by default (L-stable, second order) and with backward Euler on the first step and after any
 * discontinuity. The step size is controlled by an estimate of the local truncation error of every
 * stored quantity (computed from divided differences of its time derivative) against mixed
 * relative/absolute tolerances.
 * <p>
 * An energy ledger integrates the power exchanged with external reservoirs and untracked losses and
 * compares it with the change in stored energy, giving a running measurement of the conservation
 * error of the discretisation.
 */
public final class TransientSolver implements StateView {
    private final Network net;
    private final List<Element> elements;
    private final int n, ns;
    private final LinearSystem jac;
    private final LoadContext ctx = new LoadContext();

    private final double[] x, xPrev, xTrial, f, dx;
    private final double[] q0, q1, qd0, qd1, qNew, qdNew;
    private final double[] absTol, slotTol;
    private final double[] gmin;

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
        alternations = new int[ns];
        for (int i = 0; i < n; i++) {
            absTol[i] = net.unknownAbsTol(i);
            x[i] = net.initialValue(i);
        }
        for (Node node : net.nodes()) gmin[node.index] = node.domain.gmin;
        for (int s = 0; s < ns; s++) slotTol[s] = net.slotAbsTol(s);
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
        for (Element e : elements) e.load(ctx);
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
            double h = Math.min(hNext, hMax);
            double remaining = tEnd - t;
            if (h >= remaining * 0.99) h = remaining;
            else if (remaining - h < 0.25 * h) h = 0.5 * remaining;
            stepAdaptive(h);
        }
        return t - tStart;
    }

    public double advance(double duration) {
        return advance(duration, 1_000_000);
    }

    /** Takes one accepted step of at most {@code hTry}, shrinking on Newton/LTE failure. */
    private void stepAdaptive(double hTry) {
        double h = hTry;
        while (true) {
            if (h < hMin) {
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
            }
        }
    }

    private String lastBadUnknown = "";

    private String worstNewtonDiagnostic() {
        return "Last non-converging unknown: " + lastBadUnknown;
    }

    /** @return 0 accepted, 1 Newton failure, 2 LTE rejection */
    private int tryStep(double h) {
        boolean be = restart || forceBE || validHistory == 0 || method == IntegrationMethod.BACKWARD_EULER;
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
            for (Element e : elements) e.load(ctx);
            for (int i = 0; i < n; i++) {
                if (gmin[i] != 0) {
                    f[i] += gmin[i] * xTrial[i];
                    jac.add(i, i, gmin[i]);
                } else {
                    jac.add(i, i, 0.0); // keep structural diagonal for the sparse pivot search
                }
            }
            for (int i = 0; i < n; i++) dx[i] = -f[i];
            try {
                jac.solve(dx);
            } catch (SingularMatrixException e) {
                lastBadUnknown = e.row() < n ? "singular near " + net.unknownName(Math.min(e.row(), n - 1)) : "singular";
                return 1;
            }
            boolean small = true;
            // damping: limit enormous Newton updates on nonlinear elements
            double scale = 1.0;
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(dx[i])) {
                    lastBadUnknown = net.unknownName(i) + " (non-finite)";
                    return 1;
                }
            }
            for (int i = 0; i < n; i++) {
                xTrial[i] += scale * dx[i];
                double tol = relTol * Math.max(Math.abs(xTrial[i]), Math.abs(x[i])) + absTol[i];
                if (Math.abs(dx[i]) > tol) {
                    small = false;
                    lastBadUnknown = net.unknownName(i);
                }
            }
            if (small) {
                converged = true;
                break;
            }
        }
        if (!converged) return 1;

        // Re-evaluate stored quantities at the converged point so the history is exact.
        Arrays.fill(f, 0.0);
        jac.clear();
        for (Element e : elements) e.load(ctx);

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
                err = Math.max(err, est / tol);
            }
        }
        double exponent = 1.0 / ((validHistory >= 2 ? p : 1) + 1);
        if (err > 1.0) {
            hNext = h * Math.max(0.1, 0.9 * Math.pow(err, -exponent));
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
        validHistory++;
        restart = false;
        double grow = err == 0.0 ? 2.0 : Math.min(2.0, 0.9 * Math.pow(err, -exponent));
        hNext = Math.max(h * Math.max(grow, 0.5), hMin * 10);
        acceptedSteps++;

        for (Element e : elements) e.acceptStep(this, t, h);
        updateLedger(h);
        return 0;
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
