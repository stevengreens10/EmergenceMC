package dev.emergence.core.sim;

/** Read-only access to the current solution, used by instruments, the energy ledger and elements. */
public interface StateView {
    /** Value of unknown {@code index}; reference nodes (index &lt; 0) read as 0. */
    double x(int index);

    default double x(Node n) {
        return x(n.index);
    }

    /** Across-variable difference a − b. */
    default double across(Node a, Node b) {
        return x(a.index) - x(b.index);
    }

    /** Most recently accepted value of the stored quantity in a state slot. */
    double q(int slot);

    /** Most recently accepted time derivative of the stored quantity in a state slot. */
    double qdot(int slot);

    double time();
}
