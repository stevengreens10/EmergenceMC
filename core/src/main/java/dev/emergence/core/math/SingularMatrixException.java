package dev.emergence.core.math;

public final class SingularMatrixException extends RuntimeException {
    private final int row;

    public SingularMatrixException(int row) {
        super("Matrix is singular at row/column " + row);
        this.row = row;
    }

    public int row() {
        return row;
    }
}
