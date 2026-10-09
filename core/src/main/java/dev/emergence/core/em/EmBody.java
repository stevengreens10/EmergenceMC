package dev.emergence.core.em;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.elements.AngleTracker;

/**
 * A rigid body carrying electromagnetic parts. Either static (fixed to the world frame) or a rotor
 * turning about a fixed axis line through {@code pivot}. The rotor's rotational node supplies ω and its
 * {@link AngleTracker} supplies θ; geometry of attached parts is always given at θ = 0.
 */
public final class EmBody {
    final String name;
    final Vec3 pivot, axis;
    final Node node;             // null for static
    final AngleTracker tracker;  // null for static
    final boolean slider;        // translates along axis (tracker: displacement) instead of turning
    final double lo, hi;         // a slider's travel [m]

    private EmBody(String name, Vec3 pivot, Vec3 axis, Node node, AngleTracker tracker) {
        this(name, pivot, axis, node, tracker, false, 0, 0);
    }

    private EmBody(String name, Vec3 pivot, Vec3 axis, Node node, AngleTracker tracker, boolean slider, double lo, double hi) {
        this.slider = slider;
        this.lo = lo;
        this.hi = hi;
        this.name = name;
        this.pivot = pivot;
        this.axis = axis == null ? null : axis.normalize();
        this.node = node;
        this.tracker = tracker;
    }

    public static EmBody fixed(String name) {
        return new EmBody(name, Vec3.ZERO, null, null, null);
    }

    public static EmBody rotor(String name, Vec3 pivot, Vec3 axis, Node node, AngleTracker tracker) {
        return new EmBody(name, pivot, axis, node, tracker);
    }

    /** A body sliding along {@code axis} (unit, fixed to the world) over the travel [lo, hi]. */
    public static EmBody slider(String name, Vec3 axis, Node node, AngleTracker tracker, double lo, double hi) {
        return new EmBody(name, Vec3.ZERO, axis, node, tracker, true, lo, hi);
    }

    public boolean isSlider() {
        return slider;
    }

    public boolean isStatic() {
        return node == null;
    }

    int angleIndex() {
        return tracker == null ? -1 : tracker.angleIndex();
    }

    int nodeIndex() {
        return node == null ? -1 : node.index();
    }

    public String name() {
        return name;
    }

    public Node node() {
        return node;
    }

    public Vec3 axis() {
        return axis;
    }

    public Vec3 pivot() {
        return pivot;
    }
}
