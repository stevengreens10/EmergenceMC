package dev.emergence.core.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A multiphysics lumped network: nodes in several {@link Domain}s plus the elements connecting them.
 * Build the network, then {@link #compile()} it (done automatically by {@link TransientSolver}).
 * Topology is immutable after compilation; structural changes produce a new network, and the
 * Minecraft integration layer maps state across rebuilds by component identity.
 */
public final class Network {
    private final List<Node> nodes = new ArrayList<>();
    private final List<Element> elements = new ArrayList<>();
    private final Map<Domain, Node> references = new EnumMap<>(Domain.class);
    private boolean compiled;

    // compiled layout
    private int size;
    private final List<String> unknownNames = new ArrayList<>();
    private final List<Double> unknownAbsTol = new ArrayList<>();
    private final List<Double> initial = new ArrayList<>();
    private final List<Double> slotAbsTol = new ArrayList<>();
    private final List<String> slotOwner = new ArrayList<>();
    private final java.util.Map<Integer, Double> stepLimits = new java.util.HashMap<>();
    private final java.util.Map<Integer, java.util.function.DoubleBinaryOperator> limiters = new java.util.HashMap<>();
    private String currentOwner = "";

    public Node node(Domain d, String name) {
        return node(d, name, 0.0);
    }

    /** Creates a node whose across variable starts at {@code initialValue} (e.g. ambient temperature). */
    public Node node(Domain d, String name, double initialValue) {
        requireMutable();
        Node n = new Node(d, name, false);
        nodes.add(n);
        initialByNode.put(n, initialValue);
        return n;
    }

    private final Map<Node, Double> initialByNode = new java.util.HashMap<>();

    /** The reference node of a domain (ground / stationary frame / absolute zero). */
    public Node reference(Domain d) {
        return references.computeIfAbsent(d, dd -> new Node(dd, "ref", true));
    }

    private boolean electricalUngrounded;

    /**
     * Declares that no element connects to the electrical reference: every circuit floats, its potential defined only up
     * to a constant. The solver then grounds one node of each galvanically connected circuit (its datum), where by
     * Kirchhoff nothing flows; otherwise that constant is held only by the 10⁻¹² shunts every node has, a direction of
     * the matrix 10¹² times softer than the rest, along which Newton's updates are noise.
     */
    public void electricalUngrounded() {
        requireMutable();
        electricalUngrounded = true;
    }

    public boolean isElectricalUngrounded() {
        return electricalUngrounded;
    }

    private final java.util.Set<Node> datumHints = new java.util.HashSet<>();

    /**
     * Prefers this node as its circuit's datum (a supply's negative terminal, as a schematic is drawn). Every other
     * node's 10⁻¹² shunt returns through the datum, so the datum should be where the circuit is stiff: on a
     * high-impedance node (a photodiode's 2.2 MΩ load) the shunts of the wires elsewhere, microamps in all, swamp the
     * signal.
     */
    public void preferDatum(Node n) {
        requireMutable();
        if (n != null && !n.isReference()) datumHints.add(n);
    }

    public boolean isDatumHint(Node n) {
        return datumHints.contains(n);
    }

    public <E extends Element> E add(E e) {
        requireMutable();
        elements.add(e);
        return e;
    }

    public List<Element> elements() {
        return Collections.unmodifiableList(elements);
    }

    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public boolean isCompiled() {
        return compiled;
    }

    private void requireMutable() {
        if (compiled) throw new IllegalStateException("Network already compiled");
    }

    /** Assigns unknown indices to nodes and lets elements allocate their internal unknowns/state. */
    public void compile() {
        if (compiled) return;
        for (Node n : nodes) {
            n.index = size++;
            unknownNames.add(n.toString());
            unknownAbsTol.add(n.domain.acrossAbsTol);
            initial.add(initialByNode.getOrDefault(n, 0.0));
        }
        SetupContext ctx = new SetupContext() {
            @Override
            public int allocUnknown(String name, double absTol) {
                unknownNames.add(name);
                unknownAbsTol.add(absTol);
                initial.add(0.0);
                return size++;
            }

            @Override
            public int allocState(double absTol) {
                slotAbsTol.add(absTol);
                slotOwner.add(currentOwner);
                return slotAbsTol.size() - 1;
            }

            @Override
            public void setInitial(int index, double value) {
                initial.set(index, value);
            }

            @Override
            public void limitNewtonStep(int index, double maxDelta) {
                if (index >= 0) stepLimits.merge(index, maxDelta, Math::min);
            }

            @Override
            public void limitNewton(int index, java.util.function.DoubleBinaryOperator limiter) {
                if (index >= 0) limiters.put(index, limiter);
            }
        };
        for (Element e : elements) {
            currentOwner = e.label();
            e.setup(ctx);
        }
        compiled = true;
    }

    /** Overrides the initial value of an unknown (call after compile, before the first step). */
    public void setInitial(int index, double value) {
        initial.set(index, value);
    }

    public int size() {
        return size;
    }

    public int slotCount() {
        return slotAbsTol.size();
    }

    public String unknownName(int i) {
        return unknownNames.get(i);
    }

    double unknownAbsTol(int i) {
        return unknownAbsTol.get(i);
    }

    double initialValue(int i) {
        return initial.get(i);
    }

    /** Per-iteration Newton step limit of an unknown (+∞ if none). */
    java.util.function.DoubleBinaryOperator limiter(int i) {
        return limiters.get(i);
    }

    double stepLimit(int i) {
        return stepLimits.getOrDefault(i, Double.POSITIVE_INFINITY);
    }

    /** Label of the element owning an integration slot (diagnostics). */
    public String slotOwner(int s) {
        return slotOwner.get(s);
    }

    double slotAbsTol(int s) {
        return slotAbsTol.get(s);
    }
}
