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

    /**
     * Grid axis (0 = x, 1 = y, 2 = z) along which a single-cell leaded part brings its leads out sideways:
     * the − lead on the −L face, the + lead on the +L face. Parameter "lead_axis"; by default x for
     * vertical parts and the horizontal axis perpendicular to horizontal parts... (x for z-axis parts,
     * z for x-axis parts).
     */
    public static int leadAxis(Part p) {
        int a = p.axis().axis();
        int def = a == 0 ? 2 : 0;
        int l = (int) Math.round(p.param("lead_axis", def));
        return l == a || l < 0 || l > 2 ? def : l;
    }

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

    // ----- fluid parts

    public static final double DEFAULT_PIPE_BORE = 0.025, DEFAULT_WALL = 0.002;

    /** Working fluid code (see FluidDatabase). */
    public static int fluidCode(Part p) {
        return (int) Math.round(p.param("fluid", 0));
    }

    /** Inner diameter of a pipe or valve [m] (parameter "bore", default 25 mm, at most 90 % of the cell). */
    public static double pipeBore(Part p) {
        return Math.min(p.param("bore", DEFAULT_PIPE_BORE), 0.9 * transverseSize(p) - 2 * wallThickness(p));
    }

    /** Wall thickness of a pipe, valve or tank [m] (parameter "wall", default 2 mm). */
    public static double wallThickness(Part p) {
        return Math.min(p.param("wall", DEFAULT_WALL), 0.2 * transverseSize(p));
    }

    /** Cross-section of the tube wall [m²]. */
    static double tubeWall(Part p) {
        double d = pipeBore(p), w = wallThickness(p);
        return Math.PI / 4 * ((d + 2 * w) * (d + 2 * w) - d * d);
    }

    /** Cylinder bore [m] (parameter "bore", default 40 mm, at most 85 % of the cell). */
    public static double cylinderBore(Part p) {
        return Math.min(p.param("bore", 0.04), 0.85 * transverseSize(p));
    }

    /** Crank throw of an eccentric [m] (parameter "throw", default 15 mm, at most 40 % of the cell). */
    public static double eccentricThrow(Part p) {
        return Math.min(p.param("throw", 0.015), 0.4 * transverseSize(p));
    }

    public static double rotorTipRadius(Part p) {
        return 0.45 * transverseSize(p);
    }

    public static double rotorHubRadius(Part p) {
        return 0.35 * rotorTipRadius(p);
    }

    /** Blade exit angle from the axial direction [rad] (parameter "blade_angle" in degrees, default 45°). */
    public static double rotorBladeAngle(Part p) {
        return Math.toRadians(Math.max(0, Math.min(80, p.param("blade_angle", 45))));
    }

    /** Free flow area through the part across its faces [m²]. */
    public static double passageArea(Part p) {
        return switch (p.kind()) {
            case PIPE, VALVE -> Math.PI * pipeBore(p) * pipeBore(p) / 4;
            case CYLINDER -> Math.PI * cylinderBore(p) * cylinderBore(p) / 4;
            case ROTOR -> {
                double rt = rotorTipRadius(p), rh = rotorHubRadius(p);
                yield Math.PI * (rt * rt - rh * rh);
            }
            case TANK -> {
                double w = wallThickness(p);
                yield Math.pow(CELL - 2 * w, 2);
            }
            default -> 0;
        };
    }

    /** Volume of fluid held [m³]. */
    public static double fluidVolume(Part p) {
        return switch (p.kind()) {
            case PIPE, VALVE -> passageArea(p) * axialLength(p);
            case CYLINDER -> passageArea(p) * axialLength(p) * 0.5; // piston at mid-stroke when not linked
            case ROTOR -> passageArea(p) * axialLength(p) * 0.85;
            case TANK -> {
                double w = wallThickness(p);
                yield (p.boxSize(0) - 2 * w) * (p.boxSize(1) - 2 * w) * (p.boxSize(2) - 2 * w);
            }
            default -> 0;
        };
    }

    /** Wetted wall area inside the part [m²]. */
    public static double wettedArea(Part p) {
        return switch (p.kind()) {
            // internal longitudinal fins ("fins" = fin area per bare-tube area) at 85 % fin efficiency
            case PIPE, VALVE -> Math.PI * pipeBore(p) * axialLength(p) * (1 + 0.85 * fins(p));
            // bore wall at mid-stroke, head and piston crown; fins on the head side
            case CYLINDER -> (Math.PI * cylinderBore(p) * 0.5 * axialLength(p) + 2 * passageArea(p)) * (1 + 0.85 * fins(p));
            case ROTOR -> 2 * Math.PI * (rotorTipRadius(p) + rotorHubRadius(p)) * axialLength(p);
            case TANK -> 2 * (p.boxSize(0) * p.boxSize(1) + p.boxSize(1) * p.boxSize(2) + p.boxSize(0) * p.boxSize(2));
            default -> 0;
        };
    }

    /** Internal fin area of a pipe relative to its bare bore surface (parameter "fins", default 0). */
    public static double fins(Part p) {
        return Math.max(0, Math.min(20, p.param("fins", 0)));
    }

    /** Hydraulic diameter 4A/P of the passage [m]. */
    public static double hydraulicDiameter(Part p) {
        return switch (p.kind()) {
            case PIPE, VALVE -> pipeBore(p) / (1 + fins(p)); // 4A/P with the fins' extra perimeter
            case CYLINDER -> cylinderBore(p) / (1 + fins(p));
            case ROTOR -> 2 * (rotorTipRadius(p) - rotorHubRadius(p));
            case TANK -> transverseSize(p) - 2 * wallThickness(p);
            default -> CELL;
        };
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
            case RESISTOR, DIODE, CAPACITOR, SWITCH, TRANSISTOR, LED, LOGIC -> p.param("mass", 0.002) / p.material().density();
            case BRUSH -> 0.6 * p.boxSize(0) * p.boxSize(1) * p.boxSize(2) * 0.3;
            // liquid volume: the vessel wall and headspace take the rest of the cells
            case ELECTROLYTE -> p.boxSize(0) * p.boxSize(1) * p.boxSize(2) * p.param("fill", 0.8);
            case PIPE -> tubeWall(p) * axialLength(p);
            case VALVE -> 2.5 * tubeWall(p) * axialLength(p); // body, seat and stem
            case TANK -> {
                double w = wallThickness(p), sx = p.boxSize(0), sy = p.boxSize(1), sz = p.boxSize(2);
                yield sx * sy * sz - (sx - 2 * w) * (sy - 2 * w) * (sz - 2 * w);
            }
            case CYLINDER -> {
                double d = cylinderBore(p), w = Math.max(wallThickness(p), 0.003);
                yield Math.PI / 4 * ((d + 2 * w) * (d + 2 * w) - d * d) * axialLength(p) + Math.PI / 4 * d * d * 0.012;
            }
            case ECCENTRIC -> {
                double r = eccentricThrow(p), t = 0.4 * axialLength(p);
                yield (2 * r + 0.02) * 0.03 * t + Math.PI * 0.006 * 0.006 * axialLength(p); // web and pin
            }
            case ROTOR -> {
                double rt = rotorTipRadius(p), rh = rotorHubRadius(p), l = axialLength(p) * 0.6;
                yield Math.PI * rh * rh * l + 0.12 * Math.PI * (rt * rt - rh * rh) * l; // hub + blades (12 % solidity·thickness)
            }
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
            case ROTOR -> icm = cylinder(m, 0.6 * rotorTipRadius(p), axialLength(p), cosA);
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
            case RESISTOR, DIODE, CAPACITOR, SWITCH, TRANSISTOR, LED, LOGIC -> p.param("surface_area", 3e-4);
            case PIPE -> Math.PI * (pipeBore(p) + 2 * wallThickness(p)) * axialLength(p);
            case VALVE -> 2 * Math.PI * (pipeBore(p) + 2 * wallThickness(p)) * axialLength(p);
            case ROTOR -> 1e-6; // inside its housing
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
            case RESISTOR, DIODE, CAPACITOR, SWITCH, TRANSISTOR, LED, LOGIC -> 1e-6;
            case BLOCK -> CELL * CELL * p.param("fill", 1.0);
            case PIPE, VALVE -> tubeWall(p);
            case CYLINDER -> Math.PI * (cylinderBore(p) + wallThickness(p)) * Math.max(wallThickness(p), 0.003);
            case TANK -> 4 * CELL * wallThickness(p);
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
            case DIODE, TRANSISTOR, LOGIC -> p.param("max_temperature", 448);    // 175 °C junction
            case LED -> p.param("max_temperature", 398);
            case CAPACITOR -> p.param("max_temperature", 398);
            default -> melt;
        };
    }

    public static double gravity() {
        return PhysicalConstants.STANDARD_GRAVITY;
    }
}
