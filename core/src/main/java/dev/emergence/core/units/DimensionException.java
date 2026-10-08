package dev.emergence.core.units;

/** Thrown when quantities with incompatible dimensions are combined. */
public final class DimensionException extends RuntimeException {
    public DimensionException(String message) {
        super(message);
    }
}
