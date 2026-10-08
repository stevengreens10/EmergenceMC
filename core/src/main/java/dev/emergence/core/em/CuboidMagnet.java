package dev.emergence.core.em;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.units.PhysicalConstants;

/**
 * A uniformly magnetised rectangular block (sintered NdFeB, ferrite, alnico...). Geometry is given by
 * its centre, an orthonormal local frame (e1, e2, e3) and half-extents; magnetisation M = B_r/μ0 along
 * a unit direction. Treating M as rigid (recoil permeability ≈ 1.05 for NdFeB) is accurate to a few
 * per cent for hard magnets.
 * <p>
 * Instances are immutable; {@link #rotated} produces the magnet carried by a rotating body.
 */
public final class CuboidMagnet implements FieldSource {
    final Vec3 center;
    final Vec3[] axes; // e1, e2, e3
    final double[] half;
    final double[] mLocal; // magnetisation components along e1..e3 [A/m]

    public CuboidMagnet(Vec3 center, Vec3 e1, Vec3 e2, Vec3 e3, double hx, double hy, double hz, Vec3 magnetization) {
        this.center = center;
        this.axes = new Vec3[]{e1, e2, e3};
        this.half = new double[]{hx, hy, hz};
        this.mLocal = new double[]{magnetization.dot(e1), magnetization.dot(e2), magnetization.dot(e3)};
    }

    /** Axis-aligned magnet with full size (sx, sy, sz) and remanence B_r along unit direction {@code dir}. */
    public static CuboidMagnet axisAligned(Vec3 center, double sx, double sy, double sz, Vec3 dir, double remanence) {
        return new CuboidMagnet(center, Vec3.X, Vec3.Y, Vec3.Z, sx / 2, sy / 2, sz / 2,
                dir.normalize().mul(remanence / PhysicalConstants.MU0));
    }

    public Vec3 center() {
        return center;
    }

    public double volume() {
        return 8 * half[0] * half[1] * half[2];
    }

    /** Magnetisation vector in world coordinates [A/m]. */
    public Vec3 magnetization() {
        return axes[0].mul(mLocal[0]).add(axes[1].mul(mLocal[1])).add(axes[2].mul(mLocal[2]));
    }

    /** Magnetic dipole moment m = M·V [A·m²]. */
    public Vec3 moment() {
        return magnetization().mul(volume());
    }

    /** Returns this magnet rigidly rotated by {@code angle} about the line through {@code pivot} along unit {@code axis}. */
    public CuboidMagnet rotated(Vec3 pivot, Vec3 axis, double angle) {
        if (angle == 0) return this;
        Vec3 c = center.sub(pivot).rotateAbout(axis, angle).add(pivot);
        Vec3 a0 = axes[0].rotateAbout(axis, angle), a1 = axes[1].rotateAbout(axis, angle), a2 = axes[2].rotateAbout(axis, angle);
        Vec3 m = a0.mul(mLocal[0]).add(a1.mul(mLocal[1])).add(a2.mul(mLocal[2]));
        return new CuboidMagnet(c, a0, a1, a2, half[0], half[1], half[2], m);
    }

    /** Scales the magnetisation (e.g. temperature dependence of B_r). */
    public CuboidMagnet scaled(double factor) {
        return new CuboidMagnet(center, axes[0], axes[1], axes[2], half[0], half[1], half[2], magnetization().mul(factor));
    }

    /** True if the world point lies inside the magnet body. */
    public boolean contains(double px, double py, double pz) {
        double dx = px - center.x(), dy = py - center.y(), dz = pz - center.z();
        for (int k = 0; k < 3; k++) {
            Vec3 a = axes[k];
            if (Math.abs(dx * a.x() + dy * a.y() + dz * a.z()) > half[k]) return false;
        }
        return true;
    }

    /**
     * Adds the magnetic flux density B [T] at world point p to {@code out}. Outside the magnet B = μ0·H;
     * inside, B = μ0·(H + M).
     */
    @Override
    public void addB(double px, double py, double pz, double[] out) {
        double dx = px - center.x(), dy = py - center.y(), dz = pz - center.z();
        double[] pl = new double[3];
        for (int k = 0; k < 3; k++) pl[k] = dx * axes[k].x() + dy * axes[k].y() + dz * axes[k].z();
        double[] hl = new double[3];
        double[] h3 = new double[3]; // small local arrays: scalar-replaced by the JIT, thread-safe
        for (int k = 0; k < 3; k++) {
            double mk = mLocal[k];
            if (mk == 0) continue;
            int iu = (k + 1) % 3, iv = (k + 2) % 3;
            for (int side = -1; side <= 1; side += 2) {
                h3[0] = h3[1] = h3[2] = 0;
                FieldMath.sheetH(side * mk, -half[iu], half[iu], -half[iv], half[iv], side * half[k],
                        pl[iu], pl[iv], pl[k], h3);
                hl[iu] += h3[0];
                hl[iv] += h3[1];
                hl[k] += h3[2];
            }
        }
        boolean inside = Math.abs(pl[0]) < half[0] && Math.abs(pl[1]) < half[1] && Math.abs(pl[2]) < half[2];
        if (inside) for (int k = 0; k < 3; k++) hl[k] += mLocal[k];
        double mu0 = PhysicalConstants.MU0;
        for (int k = 0; k < 3; k++) {
            double b = mu0 * hl[k];
            out[0] += b * axes[k].x();
            out[1] += b * axes[k].y();
            out[2] += b * axes[k].z();
        }
    }
}
