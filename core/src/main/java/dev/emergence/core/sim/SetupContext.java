package dev.emergence.core.sim;

/** Allocation API available to elements while a network is being compiled. */
public interface SetupContext {
    /**
     * Allocates an extra unknown (e.g. a branch current or an angle).
     *
     * @param name   diagnostic name
     * @param absTol absolute convergence tolerance in the variable's SI unit
     * @return the unknown's index
     */
    int allocUnknown(String name, double absTol);

    /**
     * Allocates an integration state slot for a stored quantity q whose time derivative the element
     * needs.
     *
     * @param absTol absolute local-truncation-error tolerance for q in its SI unit
     * @return slot index
     */
    int allocState(double absTol);

    /** Sets the initial value of an unknown (e.g. an inductor's initial current). */
    void setInitial(int index, double value);

    /**
     * Limits the change of an unknown per Newton iteration (junction/interface "voltage limiting" for
     * strongly exponential elements). The smallest limit requested for an unknown wins.
     */
    default void limitNewtonStep(int index, double maxDelta) {
    }
}
