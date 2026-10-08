package dev.emergence.core.math;

/** Immutable 3-vector for geometry set-up code (hot loops use raw doubles). */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 X = new Vec3(1, 0, 0);
    public static final Vec3 Y = new Vec3(0, 1, 0);
    public static final Vec3 Z = new Vec3(0, 0, 1);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 mul(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public Vec3 normalize() {
        double l = length();
        return l == 0 ? this : mul(1.0 / l);
    }

    /** Component of {@code this} along unit vector {@code u}. */
    public Vec3 projectOnto(Vec3 u) {
        return u.mul(dot(u));
    }

    /** Any unit vector perpendicular to this (unit) vector. */
    public Vec3 anyPerpendicular() {
        Vec3 t = Math.abs(x) < 0.9 ? X : Y;
        return cross(t).normalize();
    }

    /** Rodrigues rotation of this vector about unit axis {@code k} by angle {@code a}. */
    public Vec3 rotateAbout(Vec3 k, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        Vec3 kxv = k.cross(this);
        double kdv = k.dot(this);
        return new Vec3(
                x * c + kxv.x * s + k.x * kdv * (1 - c),
                y * c + kxv.y * s + k.y * kdv * (1 - c),
                z * c + kxv.z * s + k.z * kdv * (1 - c));
    }

    public double get(int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    public static Vec3 axis(int a) {
        return a == 0 ? X : a == 1 ? Y : Z;
    }
}
