package dev.emergence.core.sim.elements;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

import java.util.List;

/**
 * Water on the parts of a body turning about a fixed axis: a paddle wheel dipping into a stream, a float on a hinged arm.
 * Each part, carried round by the body (its offset from the axis turned by θ, as in {@link GravityTorque}), feels at
 * its place
 * <ul>
 *     <li>Archimedes' upthrust ρ·g·V over its submerged fraction (its height spread evenly: the water's surface less
 *     its bottom over its height, clamped C¹ to [0, 1]);</li>
 *     <li>the water's drag against its motion relative to the water's own (a stream's current): a plate, normal to its
 *     face only, ½·ρ·C_d·A·|v_n|·v_n with C_d = 2 (a flat plate broadside on); any other part as a bluff body,
 *     ½·ρ·A·|v|·v over its frontal area (V^⅔).</li>
 * </ul>
 * The torque about the axis is n·Σ r × F. The water is an external reservoir — a pond, a river — so everything it
 * does to the body is external work (a stream driving the wheel, the drag it takes back). Not modelled: the water a
 * paddle pushes being slowed (an undershot wheel in a narrow race slows its stream; here the stream keeps its speed),
 * the waves it makes, its added mass, splashing.
 */
public final class WaterOnRotor implements Element {
    /**
     * One part: its centre's offset from a point on the axis at θ = 0, displaced volume, height, and for a plate its
     * face's area and normal at θ = 0 (else area 0 and the frontal area of a bluff body).
     */
    public record Wetted(Vec3 offset, double volume, double height, double plateArea, Vec3 normal, double frontal) {}

    /** The water about the body. */
    public interface Field {
        /** The height [m] of the water's surface over the point, NaN if it stands over no water. */
        double surface(Vec3 p);

        /** The water's velocity at the point [m/s] (zero in still water). */
        Vec3 velocity(Vec3 p);
    }

    private static final double PLATE_CD = 2.0, KNEE = 0.02, V_SMOOTH = 1e-3;
    private final Node omega;
    private final AngleTracker angle;
    private final Vec3 axis, pivot;
    private final double rho, g;
    private final List<Wetted> parts;
    private final Field water;

    public WaterOnRotor(Node omega, AngleTracker angle, Vec3 axis, Vec3 pivot, double rho, double g, List<Wetted> parts, Field water) {
        this.omega = omega;
        this.angle = angle;
        this.axis = axis;
        this.pivot = pivot;
        this.rho = rho;
        this.g = g;
        this.parts = List.copyOf(parts);
        this.water = water;
    }

    private Vec3 turn(Vec3 v, double th) {
        Vec3 along = axis.mul(v.dot(axis)), across = v.sub(along);
        return along.add(across.mul(Math.cos(th))).add(axis.cross(across).mul(Math.sin(th)));
    }

    /** C¹ clamp of u to [0, 1], its corners rounded over KNEE. */
    private static double clamp01(double u) {
        return ramp(u) - ramp(u - 1);
    }

    private static double ramp(double u) {
        if (u <= 0) return 0;
        if (u < KNEE) return u * u / (2 * KNEE);
        return u - KNEE / 2;
    }

    /** Torque of the water about the axis with the body at angle th turning at w [N·m]. */
    double torque(double th, double w) {
        double tq = 0;
        for (Wetted p : parts) {
            Vec3 r = turn(p.offset(), th), at = pivot.add(r);
            double surface = water.surface(at);
            if (Double.isNaN(surface)) continue;
            double frac = clamp01((surface - (at.y() - p.height() / 2)) / p.height());
            if (frac <= 0) continue;
            Vec3 f = new Vec3(0, rho * g * p.volume() * frac, 0);
            Vec3 rel = axis.cross(r).mul(w).sub(water.velocity(at));
            if (p.plateArea() > 0) {
                Vec3 n = turn(p.normal(), th);
                double vn = rel.dot(n);
                f = f.add(n.mul(-0.5 * rho * PLATE_CD * p.plateArea() * frac * vn * Math.sqrt(vn * vn + V_SMOOTH * V_SMOOTH)));
            } else {
                double speed = Math.sqrt(rel.dot(rel) + V_SMOOTH * V_SMOOTH);
                f = f.add(rel.mul(-0.5 * rho * p.frontal() * frac * speed));
            }
            tq += axis.dot(r.cross(f));
        }
        return tq;
    }

    @Override
    public void load(LoadContext ctx) {
        int ia = angle.angleIndex(), iw = omega.index();
        double th = ctx.x(ia), w = ctx.x(iw), tq = torque(th, w);
        double dth = 1e-4, dw = 1e-4 * Math.max(1, Math.abs(w));
        double dtqdth = (torque(th + dth, w) - torque(th - dth, w)) / (2 * dth);
        double dtqdw = (torque(th, w + dw) - torque(th, w - dw)) / (2 * dw);
        ctx.addF(iw, -tq);
        ctx.addJ(iw, ia, -dtqdth);
        ctx.addJ(iw, iw, -dtqdw);
    }

    /** The water's work on the body: from (or, braking it, into) the pond or stream. */
    @Override
    public double externalPower(StateView s) {
        double w = s.x(omega);
        return torque(s.x(angle.angleIndex()), w) * w;
    }

    @Override
    public String label() {
        return "WaterOnRotor(" + parts.size() + " parts)";
    }
}
