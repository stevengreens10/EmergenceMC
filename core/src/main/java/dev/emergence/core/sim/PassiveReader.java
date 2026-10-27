package dev.emergence.core.sim;

/**
 * An element that reads some of the unknowns it depends on without acting on them — a pressure transducer's
 * diaphragm, whose volume is negligible beside the vessel's. Under multirate stepping the circuits may read such an
 * unknown from a part of the plant stepped on its own (its latest step's value) instead of holding that part to the
 * plant's steps: what is read is unaffected by the reading, so a reading a step old costs nothing but that step's age.
 */
public interface PassiveReader extends Element {
}
