package dev.emergence.core.units;

/**
 * A scalar physical quantity: an SI magnitude paired with its {@link Dimension}.
 * <p>
 * Quantities are used at API boundaries (data loading, coupling interfaces, instrument readouts,
 * tests). Inner numerical loops operate on raw SI doubles for performance; the dimension of each
 * such variable is fixed by the solver's domain definitions and validated when the model is built.
 */
public record Quantity(double value, Dimension dimension) {

    public static Quantity of(double siValue, Dimension dimension) {
        return new Quantity(siValue, dimension);
    }

    public static Quantity parse(String text) {
        return Units.parse(text);
    }

    public Quantity plus(Quantity o) {
        requireSame(o, "add");
        return new Quantity(value + o.value, dimension);
    }

    public Quantity minus(Quantity o) {
        requireSame(o, "subtract");
        return new Quantity(value - o.value, dimension);
    }

    public Quantity times(Quantity o) {
        return new Quantity(value * o.value, dimension.mul(o.dimension));
    }

    public Quantity times(double s) {
        return new Quantity(value * s, dimension);
    }

    public Quantity over(Quantity o) {
        return new Quantity(value / o.value, dimension.div(o.dimension));
    }

    public Quantity pow(int p) {
        return new Quantity(Math.pow(value, p), dimension.pow(p));
    }

    /** Returns the SI magnitude after asserting the expected dimension. */
    public double si(Dimension expected) {
        if (!dimension.equals(expected)) {
            throw new DimensionException("Expected " + expected + " but quantity has dimension " + dimension);
        }
        return value;
    }

    /** Returns the magnitude expressed in the given unit, e.g. {@code q.in(Units.unit("mm"))}. */
    public double in(Quantity unit) {
        if (!dimension.equals(unit.dimension)) {
            throw new DimensionException("Cannot express " + dimension + " in unit of dimension " + unit.dimension);
        }
        return value / unit.value;
    }

    private void requireSame(Quantity o, String op) {
        if (!dimension.equals(o.dimension)) {
            throw new DimensionException("Cannot " + op + " " + dimension + " and " + o.dimension);
        }
    }

    @Override
    public String toString() {
        return Units.format(value, dimension);
    }
}
