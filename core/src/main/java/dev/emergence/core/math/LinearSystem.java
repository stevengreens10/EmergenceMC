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
