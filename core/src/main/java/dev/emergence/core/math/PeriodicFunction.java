package dev.emergence.core.math;

/** A smooth 2π-periodic function with first and second derivatives. */
public interface PeriodicFunction {
    /** Writes {f(φ), f'(φ), f''(φ)} into out. */
    void eval(double phi, double[] out);

    /** Peak-to-peak range of the underlying data. */
    double range();
}
