package dev.emergence.mod.client;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.Part;
import dev.emergence.core.assembly.PartGeometry;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.sim.elements.Battery;
import dev.emergence.core.units.Units;
import dev.emergence.mod.part.PartSpec;
import dev.emergence.mod.part.PlacedPart;

/**
 * What a part's settings come to, in a line for the part editor: a spring's stiffness, a coil's resistance, a lens's
 * focal length, a battery's voltage — worked out by the simulation's own geometry for the part one cell in size.
 */
final class PartSummary {
    private PartSummary() {}

    static String of(PartKind kind, PartSpec spec) {
        try {
            Part p = new PlacedPart(0, kind, spec, 0, 0, 0, 0, 0, 0, Dir.UP, false).toCore(net.minecraft.core.BlockPos.ZERO, 16);
            var m = spec.resolveMaterial();
            var v = spec.params();
            return switch (kind) {
                case WIRE, INSULATED_WIRE -> {
                    double rho = m.get(MaterialProperty.ELECTRICAL_RESISTIVITY, Double.NaN);
                    yield "Resistance per block: " + Units.formatSI(rho * Part.CELL / PartGeometry.wireArea(p), "Ω");
                }
                case COIL -> {
                    var g = PartGeometry.coil(p);
                    double d = PartGeometry.coilWireDiameter(p), rho = m.get(MaterialProperty.ELECTRICAL_RESISTIVITY, Double.NaN);
                    double fill = PartGeometry.coilFillFactor(p);
                    yield String.format("Winding %s, %.0f m of wire; fills %.0f %% of its space%s", Units.formatSI(rho * g.wireLength() / (Math.PI * d * d / 4), "Ω"),
                            g.wireLength(), 100 * fill, fill > 0.7 ? " — too much to wind!" : "");
                }
                case SPRING -> "Stiffness " + Units.formatSI(PartGeometry.springRate(p), "N/m");
                case GEAR -> String.format("Tooth module %.2f mm: it meshes with gears of the same module", 2000 * PartGeometry.gearPitchRadius(p) / PartGeometry.gearTeeth(p));
                case LENS -> {
                    double n = m.get(MaterialProperty.REFRACTIVE_INDEX, 1.5);
                    double f = dev.emergence.core.optics.Optics.focalLength(n, PartGeometry.lensRadius1(p), PartGeometry.lensRadius2(p), PartGeometry.lensThickness(p));
                    yield String.format("Focal length %s (n = %.3f)", Units.formatSI(f, "m"), n);
                }
                case MIRROR -> v.getOrDefault("radius", 0.0) == 0 ? "Flat" : "Focal length " + Units.formatSI(v.get("radius") / 2, "m")
                        + (v.get("radius") > 0 ? " (concave)" : " (convex)");
                case BATTERY -> {
                    var chem = Battery.Chemistry.of(v.getOrDefault("chemistry", 0.0));
                    int cells = (int) Math.round(v.getOrDefault("cells", 6.0));
                    double cap = v.getOrDefault("capacity", 7.0), soc = v.getOrDefault("charge", 1.0);
                    double e = cells * Battery.emf(chem, soc)[0];
                    yield String.format("%.2f V open circuit as placed, %.0f W·h stored when full", e, cells * Battery.emf(chem, 0.5)[0] * cap);
                }
                case PIPE, VALVE -> "Bore " + Units.formatSI(PartGeometry.pipeBore(p), "m") + ", "
                        + dev.emergence.core.fluids.FluidDatabase.standard().byCode((int) Math.round(v.getOrDefault("fluid", 0.0))).name();
                case CRANK -> String.format("Arm %s: a hand turns it with up to %.1f N·m", Units.formatSI(PartGeometry.crankRadius(p), "m"),
                        dev.emergence.core.assembly.HumanEffort.crankTorque(PartGeometry.crankRadius(p), 0, false));
                case SHAFT -> "Diameter " + Units.formatSI(PartGeometry.shaftDiameter(p), "m") + ", " + spec.length() + " cells long";
                case LED -> {
                    double nm = v.getOrDefault("wavelength", 630.0);
                    yield nm >= 700 ? "Infrared: invisible" : nm >= 620 ? "Red" : nm >= 590 ? "Orange / amber" : nm >= 560 ? "Yellow" : nm >= 495 ? "Green" : nm >= 450 ? "Blue" : "Violet";
                }
                case MAGNET -> String.format("Remanence %.2f T", m.get(MaterialProperty.REMANENCE, 0));
                case ELECTROLYTE, BLOCK -> m.name();
                default -> m.name();
            };
        } catch (RuntimeException e) {
            return spec.resolveMaterial().name();
        }
    }
}
