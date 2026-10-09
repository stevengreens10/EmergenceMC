package dev.emergence.core.sim;

/**
 * An element with discrete events: discontinuities at times it knows in advance (a random ionisation in a detector,
 * a timer) or that it detects after a step (a discharge dying out). The solver lands a step exactly on the next
 * event, lets the element apply it, and restarts its integration there.
 */
public interface EventSource extends Element {
    /** Time of this element's next event after {@code time}; {@code time} itself for one due now; ∞ for none. */
    double nextEvent(double time);

    /** Applies the events due at {@code time}; returns true if the element's behaviour changed. */
    boolean fireEvent(StateView s, double time);
}
