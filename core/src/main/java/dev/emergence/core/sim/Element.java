package dev.emergence.core.sim;

/**
 * A lumped physical element of the multiphysics network.
 * <p>
 * Elements contribute to a residual vector F(x, ẋ) = 0 that the transient solver drives to zero by
 * Newton iteration. For every terminal node an element adds the through-flow <em>leaving</em> the
 * node into the element (Kirchhoff current law generalised to every domain). Elements may own extra
 * unknowns (branch currents, angles, constraint forces) and the rows that define them.
 * <p>
 * Time derivatives are never hand-discretised by elements: an element expresses each conserved or
 * stored quantity q(x) (charge, flux linkage, momentum, internal energy, angle) and asks the
 * {@link LoadContext#ddt} for its time derivative, so the network-wide integration method
 * (backward Euler, trapezoidal, BDF2) applies consistently and energy accounting stays coherent.
 */
public interface Element {

    /** Allocates extra unknowns and integration state slots. Called once per network compilation. */
    default void setup(SetupContext ctx) {
    }

    /** Adds residual and Jacobian contributions at the current Newton iterate. */
    void load(LoadContext ctx);

    /** Energy currently stored in this element [J] (kinetic, electric, magnetic, thermal, elastic). */
    default double storedEnergy(StateView s) {
        return 0.0;
    }

    /**
     * Power entering the network from an external reservoir through this element [W]
     * (ideal sources, ambient heat sinks, player input). Negative when the network delivers power
     * to the reservoir.
     */
    default double externalPower(StateView s) {
        return 0.0;
    }

    /**
     * Power dissipated by this element that is <em>not</em> delivered to a modelled thermal node [W].
     * Elements wired to a thermal node deposit their losses there instead and return 0.
     */
    default double untrackedLoss(StateView s) {
        return 0.0;
    }

    /** Called after each accepted time step, e.g. to update discrete state (failure, hysteresis). */
    default void acceptStep(StateView s, double time, double dt) {
    }

    /** Human readable label for diagnostics. */
    default String label() {
        return getClass().getSimpleName();
    }
}
