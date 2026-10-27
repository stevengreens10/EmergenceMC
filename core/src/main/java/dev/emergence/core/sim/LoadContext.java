package dev.emergence.core.sim;

import dev.emergence.core.math.LinearSystem;

/**
 * The assembly context passed to {@link Element#load}. Elements read the current Newton iterate,
 * add residual contributions F and Jacobian entries ∂F/∂x, and obtain discretised time derivatives
 * of their stored quantities through {@link #ddt}.
 */
public final class LoadContext implements StateView {
    double[] x;
    double[] f;
    LinearSystem jac;
    double time;
    double h;
    double hPrev;
    IntegrationMethod method;
    boolean initializing;
    double alpha;
    // integration history (owned by the solver)
    double[] q0, q1, qd0, qNew, qdNew;
    /** Per unknown: the smallest Newton update that is resolvable at all (see {@link #noiseFloor}). */
    double[] floor;
    /** When set, every row an element writes is marked (the solver's partitioning dry run), and every slot it keeps. */
    boolean[] touched, touchedSlots, touchedCols;
    /**
     * Multirate: unknowns held fixed in the current phase. Jacobian entries in their rows or columns are left out of the
     * phase's own matrix (its factorisation covers only what moves); a held row's diagonal is summed in
     * {@link #frozenDiag} (the coupling's stiffness).
     */
    boolean[] frozen;
    /** Residuals wanted, not a Jacobian (the held rows' coupling is still recorded): stamps go nowhere. */
    boolean residualOnly;
    double[] frozenDiag;
    /**
     * Multirate: which of the plant's independent parts each unknown belongs to (0 the plant phase's own, k for its
     * k-th part, −1 none), and the part now stepping (−2: the circuits, which may read only the plant phase's own; −1:
     * no check). An element stamping into another part's unknowns — a crank taken up, gears coming into mesh — sets
     * {@link #crossed}: the parts are no longer independent.
     */
    int[] partOf;
    int part = -1;
    boolean crossed;

    private void reach(int i) {
        int p = partOf[i];
        if (p >= 0 && p != part && (part >= 0 || p > 0)) crossed = true;
    }

    @Override
    public double x(int index) {
        return index < 0 ? 0.0 : x[index];
    }

    /**
     * Declares that unknown {@code index} cannot be resolved more finely than {@code v} in this state: the rounding
     * of the terms of its own equation moves it by that much (a flow through a wide passage between two volumes at
     * high pressure, whose few-ulp pressure noise means a sizeable flow). The solver does not demand Newton updates
     * below it.
     */
    public void noiseFloor(int index, double v) {
        if (index >= 0 && floor != null && v > floor[index]) floor[index] = v;
    }

    /**
     * Whether unknown {@code index} is held fixed in this step (multirate: a temperature while the fast partition steps).
     * An element may then skip working out derivatives with respect to it, which would be discarded.
     */
    public boolean held(int index) {
        return frozen != null && index >= 0 && frozen[index];
    }

    /** Adds v to residual row {@code row}; reference rows (index &lt; 0) are ignored. */
    public void addF(int row, double v) {
        if (row >= 0) {
            f[row] += v;
            if (touched != null) touched[row] = true;
            if (partOf != null && part != -1 && frozen != null && frozen[row] && v != 0) reach(row);
        }
    }

    /** Adds ∂F_row/∂x_col. Entries touching reference nodes are ignored. */
    /**
     * As {@link #addJ}, for a derivative on a value read passively (see PassiveReader): where that value is held in this
     * phase the entry is dropped, without the parts it joins counting as having met.
     */
    public void addJRead(int row, int col, double v) {
        if (frozen != null && col >= 0 && frozen[col]) return;
        addJ(row, col, v);
    }

    /** As {@link #addFlowJ}, for a derivative on a value read passively (see {@link #addJRead}). */
    public void addFlowJRead(Node a, Node b, int col, double d) {
        addJRead(a.index, col, d);
        addJRead(b.index, col, -d);
    }

    public void addJ(int row, int col, double v) {
        if (touched != null && row >= 0) touched[row] = true;
        if (touchedCols != null && col >= 0) touchedCols[col] = true;
        // (a zero still goes to the matrix: an element whose derivative is zero this time — a passage's downstream side
        // — keeps its place in the matrix's assembly replay, which a skipped entry made it lose for a hash lookup at
        // every following entry; the matrix creates no entry for a zero)
        if (row < 0 || col < 0) return;
        if (frozen != null && (frozen[row] || frozen[col])) {
            if (row == col && frozenDiag != null) frozenDiag[row] += v;
            if (partOf != null && part != -1 && v != 0) {
                reach(row);
                reach(col);
            }
            return;
        }
        if (residualOnly) return;
        jac.add(row, col, v);
    }

    /**
     * Stamps a through-flow {@code flow} leaving node {@code a} and entering node {@code b},
     * i.e. a two-terminal element carrying flow from a to b, with derivatives {@code dFlow/dx_k}.
     */
    public void addFlow(Node a, Node b, double flow) {
        addF(a.index, flow);
        addF(b.index, -flow);
    }

    /** Adds Jacobian entries for a flow a→b depending on unknown {@code col} with slope {@code d}. */
    public void addFlowJ(Node a, Node b, int col, double d) {
        addJ(a.index, col, d);
        addJ(b.index, col, -d);
    }

    /**
     * Conductance-like stamp for flow = g·(x_a − x_b) from a to b (the classic MNA two-terminal stamp).
     */
    public void stampConductance(Node a, Node b, double g) {
        int ia = a.index, ib = b.index;
        addJ(ia, ia, g);
        addJ(ia, ib, -g);
        addJ(ib, ia, -g);
        addJ(ib, ib, g);
    }

    /**
     * Returns the discretised time derivative dq/dt of the stored quantity {@code q} in {@code slot}
     * according to the active integration method. The partial derivative of the returned value with
     * respect to q is {@link #alpha()}.
     */
    public double ddt(int slot, double q) {
        qNew[slot] = q;
        if (touchedSlots != null) touchedSlots[slot] = true;
        double qd;
        if (initializing) {
            qd = 0.0;
        } else {
            switch (method) {
                case BACKWARD_EULER -> qd = (q - q0[slot]) / h;
                case TRAPEZOIDAL -> qd = 2.0 * (q - q0[slot]) / h - qd0[slot];
                default -> {
                    double w = h / hPrev;
                    double a0 = (1 + 2 * w) / ((1 + w) * h);
                    double a1 = -(1 + w) / h;
                    double a2 = w * w / ((1 + w) * h);
                    qd = a0 * q + a1 * q0[slot] + a2 * q1[slot];
                }
            }
        }
        qdNew[slot] = qd;
        return qd;
    }

    /** ∂(dq/dt)/∂q for the current step: the factor multiplying dq/dx in Jacobian entries. */
    public double alpha() {
        return alpha;
    }

    /** Accepted value of a stored quantity at the start of the step. */
    @Override
    public double q(int slot) {
        return q0[slot];
    }

    @Override
    public double qdot(int slot) {
        return qd0[slot];
    }

    /** Time at the end of the step being solved. */
    @Override
    public double time() {
        return time;
    }

    public double dt() {
        return h;
    }

    public boolean isInitializing() {
        return initializing;
    }
}
