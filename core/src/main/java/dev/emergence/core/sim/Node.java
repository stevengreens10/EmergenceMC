package dev.emergence.core.sim;

/**
 * A network node in one physical domain. Its unknown is the domain's across variable
 * (voltage, angular velocity, temperature, ...). The reference node of each domain has index -1
 * and a fixed across value of zero (electrical ground, stationary frame, absolute zero).
 */
public final class Node {
    final Domain domain;
    final String name;
    int index = -1;
    final boolean reference;

    Node(Domain domain, String name, boolean reference) {
        this.domain = domain;
        this.name = name;
        this.reference = reference;
    }

    public Domain domain() {
        return domain;
    }

    public String name() {
        return name;
    }

    /** Unknown-vector index, or -1 for the reference node. */
    public int index() {
        return index;
    }

    public boolean isReference() {
        return reference;
    }

    @Override
    public String toString() {
        return domain.id + ":" + name;
    }
}
