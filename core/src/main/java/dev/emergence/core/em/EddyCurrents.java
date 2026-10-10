package dev.emergence.core.em;

import dev.emergence.core.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Eddy-current drag on a conducting sheet moving through a magnetostatic field. A sheet of conductivity σ and
 * thickness t moving with velocity v across a field whose component normal to it is B_n carries the induced current
 * density J = σ v × B; where the currents can close on themselves along the motion's direction of symmetry (round a
 * tube about the axis it moves along) that is exact, and the drag force on an element of area dA is
 * σ t B_n² v_⊥ dA, v_⊥ the velocity in the sheet's plane. Under a pole in an extended plate the currents' return paths
 * reduce it: a shape factor η ≈ 0.5. The sheet is sampled at points, each standing for a piece of it.
 */
public final class EddyCurrents {
    private EddyCurrents() {
    }

    /** A piece of conducting sheet: its point, unit normal, thickness × area [m³] and shape factor. */
    public record Sample(Vec3 point, Vec3 normal, double volume, double shape) {
        public Sample moved(Vec3 pivot, Vec3 axis, double angle, Vec3 shift) {
            Vec3 p = point, n = normal;
            if (angle != 0) {
                p = p.sub(pivot).rotateAbout(axis, angle).add(pivot);
                n = n.rotateAbout(axis, angle);
            }
            return new Sample(p.add(shift), n, volume, shape);
        }
    }

    /** Shape factor of a plate under a pole (currents return through the plate around it). */
    public static final double PLATE = 0.5;
    /** Shape factor of a tube moving along its axis past a coaxial field (currents close round it). */
    public static final double TUBE = 1.0;

    /** A flat plate: centre, the axis index of its normal (0..2), its two other sizes, thickness; g × g samples. */
    public static List<Sample> plate(Vec3 centre, int normalAxis, double su, double sv, double thickness, int g) {
        List<Sample> out = new ArrayList<>();
        int u = (normalAxis + 1) % 3, v = (normalAxis + 2) % 3;
        double area = su * sv / (g * g);
        for (int i = 0; i < g; i++)
            for (int j = 0; j < g; j++) {
                double[] off = new double[3];
                off[u] = ((i + 0.5) / g - 0.5) * su;
                off[v] = ((j + 0.5) / g - 0.5) * sv;
                out.add(new Sample(centre.add(new Vec3(off[0], off[1], off[2])), Vec3.axis(normalAxis), thickness * area, PLATE));
            }
        return out;
    }

    /** A tube's wall: centre, axis, mid-wall radius, wall, length; nAround × nAlong samples, normals radial. */
    public static List<Sample> tube(Vec3 centre, Vec3 axis, double radius, double wall, double length, int nAround, int nAlong) {
        List<Sample> out = new ArrayList<>();
        Vec3 a = axis.normalize(), e1 = a.anyPerpendicular().normalize(), e2 = a.cross(e1);
        double area = 2 * Math.PI * radius * length / (nAround * nAlong);
        for (int k = 0; k < nAlong; k++) {
            double z = ((k + 0.5) / nAlong - 0.5) * length;
            for (int i = 0; i < nAround; i++) {
                double ph = 2 * Math.PI * i / nAround;
                Vec3 n = e1.mul(Math.cos(ph)).add(e2.mul(Math.sin(ph)));
                out.add(new Sample(centre.add(a.mul(z)).add(n.mul(radius)), n, wall * area, TUBE));
            }
        }
        return out;
    }

    /**
     * Drag coefficient per unit conductivity [N·s/m or N·m·s per S/m] of the samples in the field, for motion given by
     * {@code velocity}: the velocity of each sample point per unit speed (the sheet's own, or minus the field
     * pattern's): Σ η·t·A·B_n²·|v_⊥|².
     */
    public static double coefficient(List<Sample> samples, FieldSource field, java.util.function.Function<Vec3, Vec3> velocity) {
        double sum = 0;
        double[] b = new double[3];
        for (Sample s : samples) {
            b[0] = b[1] = b[2] = 0;
            Vec3 p = s.point(), n = s.normal();
            field.addB(p.x(), p.y(), p.z(), b);
            double bn = b[0] * n.x() + b[1] * n.y() + b[2] * n.z();
            if (bn == 0) continue;
            Vec3 v = velocity.apply(p);
            Vec3 vt = v.sub(n.mul(v.dot(n)));
            sum += s.shape() * s.volume() * bn * bn * vt.dot(vt);
        }
        return sum;
    }
}
