package dev.emergence.core.assembly;

import dev.emergence.core.em.CoilGeometry;
import dev.emergence.core.em.CuboidMagnet;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.units.PhysicalConstants;

import static dev.emergence.core.assembly.Part.CELL;

/**
 * Derives physical geometry (dimensions, mass, inertia, surface area, field-model geometry) of a part
 * from its occupied cells and parameters. All values SI.
 */
public final class PartGeometry {
    private PartGeometry() {}

    // ----- default parameters
    public static final double DEFAULT_SHAFT_DIAMETER = 0.02;
    public static final double DEFAULT_WIRE_DIAMETER = 1.5e-3;
    public static final double DEFAULT_COIL_WIRE_DIAMETER = 0.5e-3;
    public static final int DEFAULT_COIL_TURNS = 200;
    public static final int DEFAULT_GEAR_TEETH = 24;

    /** Extent of the box along the part axis [m]. */
    public static double axialLength(Part p) {
        return p.boxSize(p.axis().axis());
    }

    /** Smallest transverse extent of the box [m]. */
    public static double transverseSize(Part p) {
        int a = p.axis().axis();
        return Math.min(p.boxSize((a + 1) % 3), p.boxSize((a + 2) % 3));
    }

    public static double shaftDiameter(Part p) {
        return Math.min(p.param("diameter", DEFAULT_SHAFT_DIAMETER), 0.95 * transverseSize(p));
    }

    public static double wireDiameter(Part p) {
        return Math.min(p.param("diameter", DEFAULT_WIRE_DIAMETER), 0.9 * CELL);
    }

    public static double wireArea(Part p) {
        double d = wireDiameter(p);
        return Math.PI * d * d / 4;
    }

    /** Physical dimensions of a magnet block (defaults to 80 % of its cells). */
    public static double[] magnetSize(Part p) {
        return new double[]{
                Math.min(p.param("sx", 0.8 * p.boxSize(0)), p.boxSize(0)),
                Math.min(p.param("sy", 0.8 * p.boxSize(1)), p.boxSize(1)),
                Math.min(p.param("sz", 0.8 * p.boxSize(2)), p.boxSize(2))};
    }

    public static CuboidMagnet magnet(Part p) {
        double[] s = magnetSize(p);
        double br = p.material().get(MaterialProperty.REMANENCE, 0.0);
        return CuboidMagnet.axisAligned(p.center(), s[0], s[1], s[2], p.axis().vec(), br);
    }

    /** Coil winding geometry: winding fills 90 % of the box; bore is a parameterised fraction. */
    public static CoilGeometry coil(Part p) {
        double outer = 0.45 * transverseSize(p);
        double inner = outer * p.param("bore_fraction", 0.35);
        double length = 0.9 * axialLength(p);
        int turns = (int) Math.round(p.param("turns", DEFAULT_COIL_TURNS));
        return new CoilGeometry(p.center(), p.axis().vec(), 0.5 * (outer + inner), length, outer - inner, turns);
    }

    public static double coilWireDiameter(Part p) {
        return p.param("wire_diameter", DEFAULT_COIL_WIRE_DIAMETER);
    }

    /** Fraction of the winding window filled with conductor (typical practical maximum ≈ 0.6–0.7). */
    public static double coilFillFactor(Part p) {
        CoilGeometry g = coil(p);
        double outer = 0.45 * transverseSize(p);
        double inner = outer * p.param("bore_fraction", 0.35);
        double window = (outer - inner) * g.length();
        double d = coilWireDiameter(p);
        return g.turns() * Math.PI * d * d / 4 / window;
    }

    public static double gearPitchRadius(Part p) {
        // half the occupied width: gears in touching cell boxes are exactly at r1 + r2 and mesh
        return p.param("pitch_radius", 0.5 * transverseSize(p));
    }

    /** Face width of a gear [m] (defaults to 80 % of its cell). */
    public static double gearThickness(Part p) {
        return Math.min(p.param("thickness", 0.8 * axialLength(p)), axialLength(p));
    }

    public static int gearTeeth(Part p) {
        return (int) Math.round(p.param("teeth", DEFAULT_GEAR_TEETH));
    }

    public static double crankRadius(Part p) {
        return p.param("radius", 0.15);
    }

    // ----- mass and inertia

    /** Volume of material actually present [m³]. */
    public static double volume(Part p) {
        return switch (p.kind()) {
            case BLOCK -> p.boxSize(0) * p.boxSize(1) * p.boxSize(2) * p.param("fill", 1.0);
            case SHAFT -> {
                double d = shaftDiameter(p);
                yield Math.PI * d * d / 4 * axialLength(p);
            }
            case BEARING -> 0.4 * p.boxSize(0) * p.boxSize(1) * p.boxSize(2) * 0.3;
            case GEAR -> {
                double r = gearPitchRadius(p) * 1.05;
                yield Math.PI * r * r * gearThickness(p);
            }
            case CRANK -> {
                double r = crankRadius(p);
                yield Math.PI * 0.006 * 0.006 * r + 1.5e-5; // 12 mm arm + handle
            }
            case MAGNET -> {
                double[] s = magnetSize(p);
                yield s[0] * s[1] * s[2];
            }
            case COIL -> {
                double d = coilWireDiameter(p);
                yield coil(p).wireLength() * Math.PI * d * d / 4;
            }
            case WIRE, INSULATED_WIRE -> wireArea(p) * CELL * Math.max(p.extent(0), Math.max(p.extent(1), p.extent(2)));
            case RESISTOR, DIODE, CAPACITOR, SWITCH -> p.param("mass", 0.002) / p.material().density();
            case BRUSH -> 0.6 * p.boxSize(0) * p.boxSize(1) * p.boxSize(2) * 0.3;
        };
    }

    public static double mass(Part p) {
        double m = volume(p) * p.material().density();
        if (p.kind() == PartKind.INSULATED_WIRE) m *= 1.3; // PVC jacket
        return Math.max(m, 1e-6);
    }

    /** Moment of inertia of the part about the line through {@code pivot} along unit {@code u} [kg·m²]. */
    public static double inertiaAbout(Part p, Vec3 pivot, Vec3 u) {
        double m = mass(p);
        Vec3 c = p.center();
        Vec3 d = c.sub(pivot);
        Vec3 perp = d.sub(u.mul(d.dot(u)));
        double parallel = m * perp.lengthSq();
        double icm;
        Vec3 a = p.axis().vec();
        double cosA = Math.abs(u.dot(a));
        switch (p.kind()) {
            case SHAFT -> icm = cylinder(m, shaftDiameter(p) / 2, axialLength(p), cosA);
            case GEAR -> icm = cylinder(m, gearPitchRadius(p), gearThickness(p), cosA);
            case COIL -> {
                CoilGeometry g = coil(p);
                double r = g.meanRadius();
                double ipar = m * r * r;
                double iperp = 0.5 * m * r * r + m * g.length() * g.length() / 12;
                icm = ipar * cosA * cosA + iperp * (1 - cosA * cosA);
            }
            case CRANK -> {
                double r = crankRadius(p);
                icm = m * r * r * 0.6; // arm + handle mass concentrated toward the grip
            }
            case MAGNET -> {
                double[] s = magnetSize(p);
                icm = box(m, s[0], s[1], s[2], u);
            }
            default -> icm = box(m, p.boxSize(0), p.boxSize(1), p.boxSize(2), u);
        }
        return icm + parallel;
    }

    private static double cylinder(double m, double r, double len, double cosA) {
        double ipar = 0.5 * m * r * r;
        double iperp = m * (3 * r * r + len * len) / 12;
        return ipar * cosA * cosA + iperp * (1 - cosA * cosA);
    }

    private static double box(double m, double sx, double sy, double sz, Vec3 u) {
        return m / 12 * ((sy * sy + sz * sz) * u.x() * u.x() + (sx * sx + sz * sz) * u.y() * u.y() + (sx * sx + sy * sy) * u.z() * u.z());
    }

    /** Exposed surface area for convection/radiation [m²]. */
    public static double surfaceArea(Part p) {
        return switch (p.kind()) {
            case SHAFT -> Math.PI * shaftDiameter(p) * axialLength(p);
            case WIRE, INSULATED_WIRE -> Math.PI * wireDiameter(p) * CELL;
            case COIL -> {
                CoilGeometry g = coil(p);
                yield 2 * Math.PI * (g.meanRadius() * 1.4) * g.length() + 2 * Math.PI * Math.pow(g.meanRadius() * 1.4, 2);
            }
            case MAGNET -> {
                double[] s = magnetSize(p);
                yield 2 * (s[0] * s[1] + s[1] * s[2] + s[0] * s[2]);
            }
            case RESISTOR, DIODE, CAPACITOR, SWITCH -> p.param("surface_area", 3e-4);
            default -> 2 * (p.boxSize(0) * p.boxSize(1) + p.boxSize(1) * p.boxSize(2) + p.boxSize(0) * p.boxSize(2)) * 0.5;
        };
    }

    /** Characteristic length for natural convection correlations [m]. */
    public static double convectionLength(Part p) {
        return switch (p.kind()) {
            case SHAFT -> shaftDiameter(p);
            case WIRE, INSULATED_WIRE -> wireDiameter(p) * (p.kind() == PartKind.INSULATED_WIRE ? 1.6 : 1.0);
            default -> Math.cbrt(Math.max(p.boxSize(0) * p.boxSize(1) * p.boxSize(2), 1e-9));
        };
    }

    /** Effective conduction cross-section through the part [m²] (for thermal contact estimates). */
    public static double conductionArea(Part p) {
        return switch (p.kind()) {
            case SHAFT -> Math.PI * Math.pow(shaftDiameter(p) / 2, 2);
            case WIRE, INSULATED_WIRE -> wireArea(p);
            case COIL -> 1e-5;
            case RESISTOR, DIODE, CAPACITOR, SWITCH -> 1e-6;
            case BLOCK -> CELL * CELL * p.param("fill", 1.0);
            default -> CELL * CELL * 0.5;
        };
    }

    /** Effective emissivity for radiation (the jacket for insulated wire). */
    public static double emissivity(Part p) {
        if (p.kind() == PartKind.INSULATED_WIRE) return 0.9;
        if (p.kind() == PartKind.COIL) return 0.85; // enamel
        return p.material().get(MaterialProperty.EMISSIVITY, 0.5);
    }

    /** Temperature at which the part is destroyed (melts, chars or its insulation fails) [K]. */
    public static double failureTemperature(Part p) {
        double melt = p.material().get(MaterialProperty.MELTING_POINT, 2000);
        return switch (p.kind()) {
            case INSULATED_WIRE -> Math.min(melt, 433);       // PVC jacket decomposes
            case COIL -> Math.min(melt, 473);                 // class H enamel limit ~ 180–200 °C
            case MAGNET -> Math.min(melt, p.material().get(MaterialProperty.CURIE_TEMPERATURE, melt));
            case RESISTOR -> p.param("max_temperature", 523);
            case DIODE -> p.param("max_temperature", 448);    // 175 °C junction
            case CAPACITOR -> p.param("max_temperature", 398);
            default -> melt;
        };
    }

    public static double gravity() {
        return PhysicalConstants.STANDARD_GRAVITY;
    }
}
