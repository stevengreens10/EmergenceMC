package dev.emergence.core.materials;

import dev.emergence.core.units.PhysicalConstants;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * An engineering material with SI-valued physical properties loaded from the data-driven database.
 * Missing properties are genuinely unknown/not applicable; callers must handle their absence
 * (e.g. an insulator has no meaningful resistivity entry and is treated as non-conducting).
 */
public final class Material {
    private final String id;
    private final String name;
    private final MaterialCategory category;
    private final Map<MaterialProperty, Double> props;
    private final List<String> sources;
    private final int color;

    public Material(String id, String name, MaterialCategory category, Map<MaterialProperty, Double> props,
                    List<String> sources, int color) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.props = Collections.unmodifiableMap(new EnumMap<>(props));
        this.sources = List.copyOf(sources);
        this.color = color;
    }

    public String id() { return id; }
    public String name() { return name; }
    public MaterialCategory category() { return category; }
    public List<String> sources() { return sources; }
    /** Display colour as 0xRRGGBB (purely cosmetic). */
    public int color() { return color; }
    public Map<MaterialProperty, Double> properties() { return props; }

    public boolean has(MaterialProperty p) {
        return props.containsKey(p);
    }

    public OptionalDouble opt(MaterialProperty p) {
        Double v = props.get(p);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    /** Returns the property, throwing if the database lacks it. */
    public double get(MaterialProperty p) {
        Double v = props.get(p);
        if (v == null) throw new IllegalStateException("Material " + id + " has no " + p.key);
        return v;
    }

    public double get(MaterialProperty p, double fallback) {
        Double v = props.get(p);
        return v == null ? fallback : v;
    }

    public double density() { return get(MaterialProperty.DENSITY); }

    /** True for materials light passes through (those with a refractive index). */
    public boolean transparent() {
        return props.containsKey(MaterialProperty.REFRACTIVE_INDEX);
    }

    /**
     * Normal-incidence reflectance of the polished material at a wavelength [nm]: interpolated between its values at
     * 550 and 1000 nm (held beyond them); for a transparent material Fresnel's ((n − 1)/(n + 1))²; NaN if unknown.
     */
    public double reflectance(double nm) {
        Double r = props.get(MaterialProperty.REFLECTANCE);
        if (r == null) {
            Double n = props.get(MaterialProperty.REFRACTIVE_INDEX);
            return n == null ? Double.NaN : ((n - 1) / (n + 1)) * ((n - 1) / (n + 1));
        }
        double ir = props.getOrDefault(MaterialProperty.REFLECTANCE_IR, r);
        double f = Math.max(0, Math.min(1, (nm - 550) / 450));
        return r + f * (ir - r);
    }

    /**
     * Bulk absorption coefficient at a wavelength [1/m] (Beer–Lambert: light falls as e^{−α·path} inside): 0 for clear
     * materials; α_max/(1 + e^{(λ − λ_c)/w}) across a coloured glass's edge (w > 0 long-pass, w < 0 short-pass).
     */
    public double absorptionCoefficient(double nm) {
        Double a = props.get(MaterialProperty.ABSORPTION_COEFFICIENT);
        if (a == null) return 0;
        Double edge = props.get(MaterialProperty.ABSORPTION_EDGE), w = props.get(MaterialProperty.ABSORPTION_EDGE_WIDTH);
        if (edge == null || w == null || w == 0) return a;
        double x = (nm * 1e-9 - edge) / w;
        return a / (1 + Math.exp(Math.max(-500, Math.min(500, x))));
    }

    public boolean isConductor() {
        return props.containsKey(MaterialProperty.ELECTRICAL_RESISTIVITY)
                && props.get(MaterialProperty.ELECTRICAL_RESISTIVITY) < 1e-3;
    }

    /**
     * Electrical resistivity at temperature T using the linear model ρ(T) = ρ20 (1 + α (T − 293.15)).
     * Valid for metals over roughly 200–600 K (error typically &lt;5 %). Materials with a
     * {@link MaterialProperty#RESISTIVITY_EXPONENT} (e.g. tungsten) instead use ρ = ρ20 (T/293.15)^n, which
     * follows measured filament data (Forsythe &amp; Worthing 1925) to within ~5 % up to 3000 K.
     */
    public double resistivity(double temperatureK) {
        double rho20 = get(MaterialProperty.ELECTRICAL_RESISTIVITY, 1e12);
        Double n = props.get(MaterialProperty.RESISTIVITY_EXPONENT);
        if (n != null) return rho20 * Math.pow(Math.max(temperatureK, 20.0) / PhysicalConstants.T_REF, n);
        double alpha = get(MaterialProperty.RESISTIVITY_TEMP_COEFF, 0.0);
        double dT = temperatureK - PhysicalConstants.T_REF;
        double f = 1.0 + alpha * dT;
        return rho20 * Math.max(f, 0.05);
    }

    /** dρ/dT at temperature T. */
    public double resistivityDerivative(double temperatureK) {
        double rho20 = get(MaterialProperty.ELECTRICAL_RESISTIVITY, 1e12);
        Double n = props.get(MaterialProperty.RESISTIVITY_EXPONENT);
        if (n != null) {
            if (temperatureK <= 20.0) return 0.0;
            return rho20 * n * Math.pow(temperatureK / PhysicalConstants.T_REF, n - 1) / PhysicalConstants.T_REF;
        }
        double alpha = get(MaterialProperty.RESISTIVITY_TEMP_COEFF, 0.0);
        double f = 1.0 + alpha * (temperatureK - PhysicalConstants.T_REF);
        return f > 0.05 ? rho20 * alpha : 0.0;
    }

    @Override
    public String toString() {
        return id;
    }
}
