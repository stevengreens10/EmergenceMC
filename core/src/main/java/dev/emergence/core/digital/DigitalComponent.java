package dev.emergence.core.digital;

import java.util.List;

/**
 * A chip's inside as the digital domain runs it: logic levels in, logic levels out after the chip's own delays,
 * driven by events rather than by the circuit solver (see DigitalDomain). What it is made of is behind this; what it
 * costs is told by {@link #energyPerEdge} and {@link #leakage}, and paid at the chip's supply pins (DigitalChip).
 */
public interface DigitalComponent {
    /** What a component may ask of the domain while it handles an event. */
    interface Ctx {
        /** Output pin k goes to {@code level} after {@code delay} [s] (a later drive of the same pin supersedes it). */
        void drive(int k, boolean level, double delay);

        /** Calls {@link #wake} at time t (a component's own timing: an oscillator's next edge, a conversion's end). */
        void wakeAt(double t);

        /** The supply voltage across the chip now [V]. */
        double supply();

        /** An analog input's voltage at time t [V] (between the solver's steps, interpolated). */
        double analog(int k, double t);
    }

    List<String> inputs();

    List<String> outputs();

    /** Input pin k changed to {@code level} at time t. */
    void input(int k, boolean level, double t, Ctx ctx);

    /** A wake-up it asked for. */
    default void wake(double t, Ctx ctx) {
    }

    /** Powered up at t (the supply came up): from its starting state; drive the outputs' starting levels. */
    void powerUp(double t, Ctx ctx);

    /** Output pin k's level as the component holds it (before the delay to the pin). */
    boolean output(int k);

    /** Input pin k's level as the component last saw it (for probes; false where it does not keep them). */
    default boolean inputLevel(int k) {
        return false;
    }

    /** Energy the inside switches per clock edge at supply v [J]. */
    double energyPerEdge(double v);

    /** Energy the inside switches when input k changes to {@code level} [J] (by default a clock edge's). */
    default double inputEnergy(int k, boolean level, double v) {
        return energyPerEdge(v);
    }

    /** Leakage current at supply v [A]. */
    double leakage(double v);

    /** Analog inputs (sampled, not thresholded: an ADC's), as input pin indices; none by default. */
    default int[] analogInputs() {
        return new int[0];
    }

    /** Its state for saving, and restoring it (an empty array where there is none). */
    default long[] save() {
        return new long[0];
    }

    default void restore(long[] state) {
    }
}
