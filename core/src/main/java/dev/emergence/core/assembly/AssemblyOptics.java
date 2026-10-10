package dev.emergence.core.assembly;

import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.optics.OpticalScene;

import java.util.HashMap;
import java.util.Map;

import static dev.emergence.core.assembly.Part.CELL;

/**
 * What each part of an assembly does to light, as an {@link OpticalScene}: mirrors reflect, blocks and lenses of
 * transparent material refract, photodiodes absorb into their junction's window, everything else opaque absorbs what
 * its surface does not scatter. Thin wires and shafts, liquids, and transparent vessels and pipes let light by.
 * Parts on a moving body sit in that body's frame.
 */
final class AssemblyOptics {
    private AssemblyOptics() {
    }

    /** Wavelength standing for sunlight in mirrors' reflectance and surfaces' absorptance [nm] (the solar spectrum's median). */
    static final double SUN_NM = 700;

    /** True for parts that turn light: mirrors, and blocks and lenses of transparent material. */
    static boolean redirects(Part p) {
        return p.kind() == PartKind.MIRROR
                || (p.kind() == PartKind.LENS || p.kind() == PartKind.BLOCK) && p.material().transparent();
    }

    /** True for parts light passes by untouched. */
    static boolean passes(Part p) {
        return switch (p.kind()) {
            case WIRE, INSULATED_WIRE, ELECTROLYTE -> true;
            case SHAFT -> PartGeometry.shaftDiameter(p) < 0.4 * CELL || p.material().transparent();
            case BLOCK, LENS, MIRROR -> false;
            default -> p.material().transparent();
        };
    }

    /**
     * Fraction of arriving light a matt surface absorbs (the rest it scatters): its emissivity (a grey body), and at
     * least what the material does not reflect when polished.
     */
    static double absorptance(Part p, double nm) {
        double e = p.material().get(MaterialProperty.EMISSIVITY, 0.8), r = p.material().reflectance(nm);
        return Double.isNaN(r) || p.material().transparent() ? e : Math.max(e, 1 - r);
    }

    /** The scene at a wavelength [nm], bodies in the poses {@code pose} gives (body → angle or displacement). */
    static OpticalScene scene(Assembly a, CompiledAssembly c, double nm, java.util.function.ToDoubleFunction<CompiledAssembly.Body> pose) {
        return scene(a, c, nm, pose, new HashMap<>());
    }

    /** As above, filling {@code frames} with each moving body's frame index. */
    static OpticalScene scene(Assembly a, CompiledAssembly c, double nm, java.util.function.ToDoubleFunction<CompiledAssembly.Body> pose,
                              Map<CompiledAssembly.Body, Integer> frames) {
        OpticalScene s = new OpticalScene(CELL);
        for (Part p : a.parts()) {
            if (passes(p)) continue;
            CompiledAssembly.Body b = c.bodyOfPart.get(p.id());
            int frame = 0;
            if (b != null && !b.isStatic) {
                frame = frames.computeIfAbsent(b, k -> {
                    int f = s.addFrame(k.pivot, k.axis, k.slider);
                    s.setPose(f, pose.applyAsDouble(k));
                    return f;
                });
            }
            double[] lo = {p.minX() * CELL, p.minY() * CELL, p.minZ() * CELL};
            double[] hi = {(p.maxX() + 1) * CELL, (p.maxY() + 1) * CELL, (p.maxZ() + 1) * CELL};
            double nd = p.material().get(MaterialProperty.REFRACTIVE_INDEX, Double.NaN);
            double vd = p.material().get(MaterialProperty.ABBE_NUMBER, 0.0);
            switch (p.kind()) {
                case MIRROR -> {
                    Vec3 n = PartGeometry.mirrorNormal(p);
                    double r = p.param("radius", 0);
                    // a dish's vertex lies behind the centre of its box, its rim rising round it
                    Vec3 v = p.center().sub(n.mul(Math.signum(r) * PartGeometry.mirrorVertexOffset(p)));
                    double refl = p.material().reflectance(nm);
                    s.mirror(p.id(), frame, lo, hi, n, v, r, Double.isNaN(refl) ? 0.04 : refl, absorptance(p, nm));
                }
                case LENS -> {
                    if (Double.isNaN(nd)) s.absorber(p.id(), frame, lo, hi, absorptance(p, nm));
                    else s.lens(p.id(), frame, lo, hi, p.center(), p.axis().vec(), PartGeometry.lensRadius1(p),
                            PartGeometry.lensRadius2(p), PartGeometry.lensThickness(p), PartGeometry.lensRim(p), nd, vd);
                }
                case BLOCK -> {
                    // a plate thinner than its cells fills only its thickness about the middle
                    int ax = p.axis().axis();
                    double half = 0.5 * p.boxSize(ax) * Math.max(0.01, Math.min(1, p.param("fill", 1.0)));
                    double mid = p.center().get(ax);
                    lo[ax] = mid - half;
                    hi[ax] = mid + half;
                    if (Double.isNaN(nd)) s.absorber(p.id(), frame, lo, hi, absorptance(p, nm));
                    else s.glass(p.id(), frame, lo, hi, nd, vd);
                }
                case PHOTODIODE -> {
                    OpticalScene.Obj o = s.absorber(p.id(), frame, lo, hi, absorptance(p, nm));
                    Vec3 f = PartGeometry.facing(p);
                    int ax = Math.abs(f.x()) > 0.5 ? 0 : Math.abs(f.y()) > 0.5 ? 1 : 2;
                    double area = c.deviceOfPart.get(p.id()) instanceof dev.emergence.core.sim.elements.Photodiode pd ? pd.area() : 7.5e-6;
                    s.aperture(o, 2 * ax + (f.get(ax) > 0 ? 1 : 0), area);
                }
                default -> s.absorber(p.id(), frame, lo, hi, absorptance(p, nm));
            }
        }
        return s;
    }
}
