package dev.emergence.core.optics;

import dev.emergence.core.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Geometric optics against textbook results: Fresnel, Snell, Cauchy, lensmaker, mirrors, slabs, light pipes. */
class OpticsTest {
    static final double CELL = 1.0 / 16;

    @Test
    void fresnelNormalBrewsterAndCritical() {
        assertEquals(0.04, Optics.fresnel(1, 1, 1.5), 1e-12, "((n−1)/(n+1))² at normal incidence");
        double brewster = Math.atan(1.5);
        double rs = Math.pow((Math.cos(brewster) - 1.5 * Math.cos(Math.asin(Math.sin(brewster) / 1.5)))
                / (Math.cos(brewster) + 1.5 * Math.cos(Math.asin(Math.sin(brewster) / 1.5))), 2);
        assertEquals(rs / 2, Optics.fresnel(Math.cos(brewster), 1, 1.5), 1e-12, "R_p vanishes at Brewster's angle");
        double crit = Math.asin(1 / 1.5);
        assertTrue(Optics.fresnel(Math.cos(crit - 1e-3), 1.5, 1) < 1);
        assertEquals(1, Optics.fresnel(Math.cos(crit + 1e-3), 1.5, 1), 0, "total internal reflection past the critical angle");
        assertNull(Optics.refract(new Vec3(Math.sin(crit + 1e-3), -Math.cos(crit + 1e-3), 0), Vec3.Y, 1.5, 1));
    }

    @Test
    void snellsLawAndCauchyDispersion() {
        double th = Math.toRadians(40);
        Vec3 t = Optics.refract(new Vec3(Math.sin(th), -Math.cos(th), 0), Vec3.Y, 1, 1.5);
        assertEquals(Math.sin(th) / 1.5, t.x(), 1e-12, "n₁ sin θ₁ = n₂ sin θ₂");
        // BK7: n_d 1.5168, V_d 64.17 → n_F − n_C = 0.00805 (Schott: 0.008054)
        double nf = Optics.cauchy(1.5168, 64.17, Optics.LAMBDA_F), nc = Optics.cauchy(1.5168, 64.17, Optics.LAMBDA_C);
        assertEquals(0.5168 / 64.17, nf - nc, 1e-12);
        assertEquals(1.5168, Optics.cauchy(1.5168, 64.17, Optics.LAMBDA_D), 1e-12);
        assertEquals(1.5224, Optics.cauchy(1.5168, 64.17, Optics.LAMBDA_F), 3e-4, "Schott n_F 1.52238");
    }

    /** Where a ray from {@code o} along {@code d} crosses the plane x = 0 after passing the scene (refracting). */
    static double axisCrossing(OpticalScene s, Vec3 o, Vec3 d, double nm) {
        OpticalScene.Obj inside = null;
        for (int k = 0; k < 10; k++) {
            OpticalScene.Hit h = s.trace(o, d, inside, Long.MIN_VALUE);
            if (h == null) break;
            if (h.obj.kind == OpticalScene.Kind.MIRROR) {
                d = Optics.reflect(d, h.normal);
            } else {
                double n = h.obj.index(nm);
                d = inside != null ? Optics.refract(d, h.normal, n, 1) : Optics.refract(d, h.normal, 1, n);
                inside = inside != null ? null : h.obj;
            }
            o = h.point;
        }
        double t = -o.x() / d.x();
        return o.y() + t * d.y();
    }

    @Test
    void thickLensFocusesWhereTheLensmakerSays() {
        // equiconvex lens, R = 0.25 m, n = 1.5168, 20 mm thick, axis +y, centred at the origin of the cell (0.5 cell)
        OpticalScene s = new OpticalScene(CELL);
        double r = 0.25, t = 0.02, nd = 1.5168;
        Vec3 c = new Vec3(0, 0, 0);
        OpticalScene.Obj lens = s.lens(1, 0, new double[]{-0.1, -0.02, -0.1}, new double[]{0.1, 0.02, 0.1}, c, Vec3.Y, r, -r, t, 0.05, nd, 0);
        assertEquals(t, OpticalScene.thickness(lens), 1e-12);
        double f = Optics.focalLength(nd, r, -r, t);
        double bfd = f * (1 - (nd - 1) * t / (nd * r)); // back focal distance from the second vertex
        // a paraxial ray parallel to the axis, 0.1 mm off it, from below
        Vec3 o = new Vec3(0.0001, -1, 0);
        double crossing = axisCrossing(s, o, Vec3.Y, Optics.LAMBDA_D);
        System.out.printf("lens: f = %.5f m, back focal distance %.5f m, traced %.5f m%n", f, bfd, crossing - t / 2);
        assertEquals(bfd, crossing - t / 2, 2e-5 * f, "paraxial focus at the back focal distance");
        // a marginal ray (45 mm off the axis) focuses short: spherical aberration
        double marginal = axisCrossing(s, new Vec3(0.045, -1, 0), Vec3.Y, Optics.LAMBDA_D) - t / 2;
        System.out.printf("  marginal ray at 45 mm: %.5f m (spherical aberration %.1f mm)%n", marginal, 1000 * (bfd - marginal));
        assertTrue(marginal < bfd - 0.001, "marginal rays focus nearer");
        // blue focuses nearer than red (dispersion, crown glass V 64)
        OpticalScene s2 = new OpticalScene(CELL);
        s2.lens(1, 0, new double[]{-0.1, -0.02, -0.1}, new double[]{0.1, 0.02, 0.1}, c, Vec3.Y, r, -r, t, 0.05, nd, 64.17);
        double blue = axisCrossing(s2, o, Vec3.Y, Optics.LAMBDA_F), red = axisCrossing(s2, o, Vec3.Y, Optics.LAMBDA_C);
        System.out.printf("  axial colour: F focuses %.2f mm nearer than C (f/V = %.2f mm)%n", 1000 * (red - blue), 1000 * f / 64.17);
        assertEquals(f / 64.17, red - blue, 0.05 * f / 64.17, "longitudinal chromatic aberration f/V");
    }

    @Test
    void concaveMirrorFocusesAtHalfItsRadius() {
        OpticalScene s = new OpticalScene(CELL);
        double rr = 1.0;
        // dish facing +y, vertex at the origin
        s.mirror(1, 0, new double[]{-0.2, -0.001, -0.2}, new double[]{0.2, 0.05, 0.2}, Vec3.Y, Vec3.ZERO, rr, 0.95, 0.5);
        double y = axisCrossing(s, new Vec3(0.001, 2, 0), new Vec3(0, -1, 0), 550);
        System.out.printf("mirror: paraxial focus %.5f m (R/2 = %.5f)%n", y, rr / 2);
        assertEquals(rr / 2, y, 1e-6);
        // a tilted flat mirror turns a beam through twice its tilt
        OpticalScene s2 = new OpticalScene(CELL);
        Vec3 n = new Vec3(1, -1, 0).normalize();
        s2.mirror(1, 0, new double[]{0, 0, 0}, new double[]{CELL, CELL, CELL}, n, new Vec3(CELL / 2, CELL / 2, CELL / 2), 0, 0.95, 0.5);
        OpticalScene.Hit h = s2.trace(new Vec3(CELL / 2, -1, CELL / 2), Vec3.Y, null, -1);
        Vec3 out = Optics.reflect(Vec3.Y, h.normal);
        assertEquals(1, out.x(), 1e-12, "a 45° mirror turns the beam through 90°");
        assertEquals(0, h.surface, "met from the front");
    }

    /** A glass plate passes 2n/(n² + 1) at normal incidence: Fresnel losses at both faces with all the internal bounces. */
    @Test
    void glassPlateTransmitsWithMultipleReflections() {
        double n = 1.5;
        OpticalScene s = new OpticalScene(CELL);
        s.glass(1, 0, new double[]{0, 3 * CELL, 0}, new double[]{4 * CELL, 3.2 * CELL, 4 * CELL}, n, 0);
        OpticalScene.Obj floor = s.absorber(2, 0, new double[]{-CELL, 0, -CELL}, new double[]{5 * CELL, CELL, 5 * CELL}, 0.9);
        s.aperture(floor, 3, 4 * CELL * CELL); // a window under the middle of the plate
        TubeTracer.Result r = TubeTracer.parallel(s, new Vec3(0, -1, 0), 550, 0.25 * CELL, 2, true);
        double through = r.light(2), area = 4 * CELL * CELL;
        double tExpected = 2 * n / (n * n + 1);
        System.out.printf("plate: through the glass %.5f W per W/m² over %.5f m² → T = %.4f (2n/(n²+1) = %.4f); %d tubes%n",
                through, area, through / area, tExpected, r.tubes);
        assertEquals(tExpected, through / area, 2e-3);
        assertEquals(1, r.accounted() / r.total, 1e-9, "every watt accounted for");
        assertTrue(r.heat(2) > 0, "the floor round the window is warmed");
    }

    /** Light launched into the end of a glass rod is trapped by total internal reflection and comes out of the far end. */
    @Test
    void glassRodGuidesLightByTotalInternalReflection() {
        OpticalScene s = new OpticalScene(CELL);
        // a rod 1 cell square, 12 long along +x; the source just off its west end, a receiver beyond its east end
        s.glass(1, 0, new double[]{CELL, 0, 0}, new double[]{13 * CELL, CELL, CELL}, 1.5, 0);
        OpticalScene.Obj rx = s.absorber(2, 0, new double[]{13.02 * CELL, 0, 0}, new double[]{14 * CELL, CELL, CELL}, 1);
        s.aperture(rx, 0, CELL * CELL);
        Vec3 pos = new Vec3(0.98 * CELL, 0.5 * CELL, 0.5 * CELL);
        TubeTracer.Result piped = TubeTracer.point(s, pos, Vec3.X, 1, 650, -1, 16, 6);
        // without the rod: the receiver's solid angle from the source (the direct path, computed analytically)
        double d = 13.02 * CELL - pos.x(), geometric = (1 + 1) / (2 * Math.PI) * CELL * CELL / (d * d);
        System.out.printf("light pipe: %.4f of the light out of the far end, %.5f without the rod%n", piped.light(2), geometric);
        // in the rod every ray steeper than the critical angle to the sides is trapped: about half the light enters
        // within the acceptance cone, minus Fresnel losses at the two ends
        assertTrue(piped.light(2) > 0.3, "most of the light is guided");
        assertTrue(piped.light(2) > 20 * geometric);
        assertEquals(1, piped.accounted() / piped.total, 1e-9, "every watt accounted for");
    }

    /** A lens images a point source: the image point receives far more than other points, where 1/s + 1/s' = 1/f puts it. */
    @Test
    void lensImagesAPointSource() {
        double r = 0.25, t = 0.012, nd = 1.5168, f = Optics.focalLength(nd, r, -r, t);
        double s0 = 0.6; // object distance from the lens centre
        double sImg = 1 / (1 / f - 1 / s0);
        double best = 0, bestY = 0;
        StringBuilder log = new StringBuilder();
        for (double y = 0.35; y <= 0.55; y += 0.01) {
            OpticalScene s = new OpticalScene(CELL);
            s.lens(1, 0, new double[]{-0.05, -0.01, -0.05}, new double[]{0.05, 0.01, 0.05}, Vec3.ZERO, Vec3.Y, r, -r, t, 0.04, nd, 0);
            OpticalScene.Obj pd = s.absorber(2, 0, new double[]{-0.01, y, -0.01}, new double[]{0.01, y + 0.01, 0.01}, 1);
            s.aperture(pd, 2, 1e-6); // 1 mm square, facing the lens
            TubeTracer.Result res = TubeTracer.point(s, new Vec3(0, -s0, 0), Vec3.Y, 20, Optics.LAMBDA_D, -1, 8, 8);
            double p = res.light(2);
            log.append(String.format(" %.2f:%.2e", y, p));
            if (p > best) {
                best = p;
                bestY = y;
            }
        }
        System.out.printf("lens image: f = %.4f, s = %.2f → s' = %.4f; brightest at %.2f%n  %s%n", f, s0, sImg, bestY, log);
        assertEquals(sImg, bestY, 0.025, "the image where the lens equation puts it (thin-lens, within aberrations)");
    }

    /** A point source seen in a 45° mirror: the window gets the source's intensity over the folded distance squared. */
    @Test
    void mirrorDeliversThePointSourcesIrradiance() {
        for (double m : new double[]{1, 6, 30}) {
            OpticalScene s = new OpticalScene(CELL);
            s.mirror(3, 0, new double[]{-0.5 * CELL, 0.5 - 0.5 * CELL, -0.5 * CELL}, new double[]{0.5 * CELL, 0.5 + 0.5 * CELL, 0.5 * CELL},
                    new Vec3(1, -1, 0).normalize(), new Vec3(0, 0.5, 0), 0, 1.0, 1);
            OpticalScene.Obj rx = s.absorber(2, 0, new double[]{0.5, 0.5 - 0.5 * CELL, -0.5 * CELL}, new double[]{0.5 + CELL, 0.5 + 0.5 * CELL, 0.5 * CELL}, 1);
            s.aperture(rx, 0, 1e-4);
            TubeTracer.Result r = TubeTracer.point(s, Vec3.ZERO, Vec3.Y, m, 630, -1, 16, 7);
            double expect = (m + 1) / (2 * Math.PI) * 1e-4; // folded path 1 m: I·A/d²
            System.out.printf("point source via a mirror, m = %.0f: %.5g (I·A/d² = %.5g), %d tubes; accounted %.6f of %.6f%n",
                    m, r.light(2), expect, r.tubes, r.accounted(), r.total);
            assertEquals(expect, r.light(2), 0.005 * expect);
            assertEquals(1, r.total, 0.03, "the tubes' powers sum to the source's (unrefined wide tubes: quadrature)");
            assertEquals(r.total, r.accounted(), 1e-9 * r.total, "every watt accounted for");
        }
    }
}
