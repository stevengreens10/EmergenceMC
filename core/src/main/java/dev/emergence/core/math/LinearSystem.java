package dev.emergence.core.math;

/**
 * A square linear system J·Δx = b that is re-assembled and re-solved at every Newton iteration.
 * Implementations may cache the sparsity pattern and symbolic factorisation between solves.
 */
public interface LinearSystem {
    int size();

    /** Zeroes all matrix entries, keeping any cached structure. */
    void clear();

    /** Accumulates v into entry (row, col). */
    void add(int row, int col, double v);

    /**
     * The following adds come from stamping source {@code s} (an element's index; −1 for none): a system may record
     * each source's sequence of entries to replay it on the next assembly.
     */
    default void beginSegment(int s) {
    }

    /** Receives a structural entry (row, col). */
    interface EntryVisitor {
        void visit(int row, int col);
    }

    /**
     * Visits the entries that have held a nonzero value at some assembly, from the {@code from}-th on (entries only ever
     * join, in order), and returns how many there are now.
     */
    int visitPattern(int from, EntryVisitor v);

    /** Current value of diagonal entry (i, i) (0 if structurally absent). */
    double diagonal(int i);

    /**
     * Factorises the current matrix and solves in place: on return {@code rhs} holds the solution.
     *
     * @throws SingularMatrixException if the matrix is numerically singular
     */
    void solve(double[] rhs);

    static LinearSystem create(int n) {
        return n <= 48 || Boolean.getBoolean("emergence.denseSolver") ? new DenseLinearSystem(n) : new SparseLinearSystem(n);
    }
}
