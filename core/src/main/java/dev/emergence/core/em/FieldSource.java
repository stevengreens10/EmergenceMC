package dev.emergence.core.em;

/** Anything that produces a magnetostatic field. */
public interface FieldSource {
    /** Adds B [T] at world point (px, py, pz) into {@code out[0..2]}. Implementations need not be thread-safe. */
    void addB(double px, double py, double pz, double[] out);
}
