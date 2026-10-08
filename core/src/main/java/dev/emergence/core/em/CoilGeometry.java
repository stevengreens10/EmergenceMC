package dev.emergence.core.em;

import dev.emergence.core.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of a cylindrical winding: N turns of wire distributed uniformly over an annular winding
 * cross-section (axial length ℓ, radial build t) around mean radius a, centred at {@code center} with
 * axis {@code axis}.
 * <p>
 * For field and flux calculations the winding is represented by n_a × n_r circular filaments, each
 * carrying N/(n_a·n_r) of the ampere-turns. Flux through a filament is integrated over its disk with
 * Gauss–Legendre quadrature in r and the (spectrally accurate) trapezoidal rule in φ.
 */
public final class CoilGeometry implements FieldSource {
    final Vec3 center, axis, e1, e2;
    final double meanRadius, length, build;
    final int turns;
    final int nAxial, nRadial;
    private final double[] filR, filZ; // filament radius and axial offset
    private double current = 1.0;

    // quadrature
    private static final double[] GL_X = {0.033765242898424, 0.169395306766868, 0.380690406958402,
            0.619309593041598, 0.830604693233132, 0.966234757101576};
    private static final double[] GL_W = {0.085662246189585, 0.180380786524069, 0.233956967286346,
            0.233956967286346, 0.180380786524069, 0.085662246189585};
    private static final int N_PHI = 16;

    public CoilGeometry(Vec3 center, Vec3 axis, double meanRadius, double length, double build, int turns) {
        this.center = center;
        this.axis = axis.normalize();
        this.e1 = this.axis.anyPerpendicular();
        this.e2 = this.axis.cross(e1);
        this.meanRadius = meanRadius;
        this.length = length;
        this.build = build;
        this.turns = turns;
        this.nAxial = Math.max(1, Math.min(8, (int) Math.ceil(length / (0.25 * meanRadius))));
        this.nRadial = build > 0.15 * meanRadius ? 3 : 1;
        int nf = nAxial * nRadial;
        filR = new double[nf];
        filZ = new double[nf];
        int k = 0;
        for (int ia = 0; ia < nAxial; ia++) {
            double z = nAxial == 1 ? 0 : -length / 2 + length * (ia + 0.5) / nAxial;
            for (int ir = 0; ir < nRadial; ir++) {
                double r = nRadial == 1 ? meanRadius : meanRadius - build / 2 + build * (ir + 0.5) / nRadial;
                filR[k] = r;
                filZ[k++] = z;
            }
        }
    }

    public Vec3 center() { return center; }
    public Vec3 axis() { return axis; }
    public double meanRadius() { return meanRadius; }
    public double length() { return length; }
    public int turns() { return turns; }

    /** Total length of wire in the winding [m]. */
    public double wireLength() {
        return turns * 2 * Math.PI * meanRadius;
    }

    public int filamentCount() {
        return filR.length;
    }

    public CoilGeometry rotated(Vec3 pivot, Vec3 k, double angle) {
        if (angle == 0) return this;
        Vec3 c = center.sub(pivot).rotateAbout(k, angle).add(pivot);
        return new CoilGeometry(c, axis.rotateAbout(k, angle), meanRadius, length, build, turns);
    }

    /**
     * Flux linkage λ = Σ_turns ∬ B·dA [Wb-turns] of this winding in the field of {@code src}.
     */
    public double fluxLinkage(FieldSource src) {
        double[] b = new double[3];
        double total = 0;
        double perFil = (double) turns / filR.length;
        for (int f = 0; f < filR.length; f++) {
            double r0 = filR[f];
            Vec3 c = center.add(axis.mul(filZ[f]));
            double flux = 0;
            for (int ir = 0; ir < GL_X.length; ir++) {
                double r = GL_X[ir] * r0;
                double wr = GL_W[ir] * r0 * r; // ∫ ... r dr
                double ring = 0;
                for (int ip = 0; ip < N_PHI; ip++) {
                    double phi = 2 * Math.PI * ip / N_PHI;
                    double cx = Math.cos(phi) * r, sy = Math.sin(phi) * r;
                    double px = c.x() + e1.x() * cx + e2.x() * sy;
                    double py = c.y() + e1.y() * cx + e2.y() * sy;
                    double pz = c.z() + e1.z() * cx + e2.z() * sy;
                    b[0] = b[1] = b[2] = 0;
                    src.addB(px, py, pz, b);
                    ring += b[0] * axis.x() + b[1] * axis.y() + b[2] * axis.z();
                }
                flux += wr * ring * (2 * Math.PI / N_PHI);
            }
            total += perFil * flux;
        }
        return total;
    }

    /** Sets the current used when this coil acts as a {@link FieldSource}. */
    public CoilGeometry withCurrent(double i) {
        this.current = i;
        return this;
    }

    @Override
    public void addB(double px, double py, double pz, double[] out) {
        double perFil = current * turns / filR.length;
        double[] brz = new double[2];
        for (int f = 0; f < filR.length; f++) {
            double cx = center.x() + axis.x() * filZ[f];
            double cy = center.y() + axis.y() * filZ[f];
            double cz = center.z() + axis.z() * filZ[f];
            double dx = px - cx, dy = py - cy, dz = pz - cz;
            double z = dx * axis.x() + dy * axis.y() + dz * axis.z();
            double rx = dx - z * axis.x(), ry = dy - z * axis.y(), rz = dz - z * axis.z();
            double rho = Math.sqrt(rx * rx + ry * ry + rz * rz);
            FieldMath.loopB(filR[f], perFil, rho, z, brz);
            out[0] += brz[1] * axis.x();
            out[1] += brz[1] * axis.y();
            out[2] += brz[1] * axis.z();
            if (rho > 0) {
                double s = brz[0] / rho;
                out[0] += s * rx;
                out[1] += s * ry;
                out[2] += s * rz;
            }
        }
    }

    /**
     * Self-inductance from the double sum of filament mutual inductances (Maxwell's formula) with each
     * filament's self-term from the geometric mean distance of its share of the winding cross-section
     * (GMD of a rectangle ≈ 0.2235·(w + h), Rosa &amp; Grover). Accurate to ~1–2 % for practical coils.
     */
    public double selfInductance(double wireDiameter) {
        // A finer filament discretisation than for flux integration: spacing ≤ 0.04·a so the discrete
        // loops approximate the continuous current distribution (sub-turn filaments are fine: the
        // ampere-turns are spread uniformly over the winding cross-section). Discretisation error of
        // the inductance scales like (Δ/a)²; at Δ = 0.04a it is below 0.3 %. Cost: n² elliptic integrals.
        int na = (int) Math.min(160, Math.max(1, Math.ceil(length / (0.04 * meanRadius))));
        int nr = (int) Math.min(Math.max(1, 400 / na), Math.max(1, Math.ceil(build / (0.04 * meanRadius))));
        int nf = na * nr;
        double[] r = new double[nf], z = new double[nf];
        int q = 0;
        for (int ia = 0; ia < na; ia++) {
            double zz = na == 1 ? 0 : -length / 2 + length * (ia + 0.5) / na;
            for (int ir = 0; ir < nr; ir++) {
                r[q] = nr == 1 ? meanRadius : meanRadius - build / 2 + build * (ir + 0.5) / nr;
                z[q++] = zz;
            }
        }
        double cellW = Math.max(length / na, wireDiameter), cellH = Math.max(build / nr, wireDiameter);
        double gmd = 0.2235 * (cellW + cellH);
        double sum = 0;
        for (int f = 0; f < nf; f++) {
            for (int g = 0; g < nf; g++) {
                if (f == g) sum += FieldMath.loopSelfInductance(r[f], gmd);
                else sum += FieldMath.coaxialLoopMutual(r[f], r[g], z[f] - z[g]);
            }
        }
        double perFil = (double) turns / nf;
        return perFil * perFil * sum;
    }

    /** Mutual inductance with another winding [H]. Uses Maxwell's formula when coaxial, flux integration otherwise. */
    public double mutualInductance(CoilGeometry other) {
        double par = axis.dot(other.axis);
        Vec3 d = other.center.sub(center);
        Vec3 perp = d.sub(axis.mul(d.dot(axis)));
        if (Math.abs(Math.abs(par) - 1) < 1e-9 && perp.length() < 1e-9 * meanRadius) {
            double sign = Math.signum(par);
            double sum = 0;
            for (int f = 0; f < filR.length; f++) {
                for (int g = 0; g < other.filR.length; g++) {
                    double zf = filZ[f];
                    double zg = d.dot(axis) + sign * other.filZ[g];
                    sum += FieldMath.coaxialLoopMutual(filR[f], other.filR[g], zg - zf);
                }
            }
            return sign * sum * ((double) turns / filR.length) * ((double) other.turns / other.filR.length);
        }
        CoilGeometry src = new CoilGeometry(other.center, other.axis, other.meanRadius, other.length, other.build, other.turns);
        src.current = 1.0;
        return fluxLinkage(src);
    }

    /** Filament positions (for rendering/debug). */
    public List<double[]> filaments() {
        List<double[]> l = new ArrayList<>();
        for (int f = 0; f < filR.length; f++) l.add(new double[]{filR[f], filZ[f]});
        return l;
    }
}
