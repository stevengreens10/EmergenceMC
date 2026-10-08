package dev.emergence.core.chem;

import dev.emergence.core.data.Json;
import dev.emergence.core.units.Dimension;
import dev.emergence.core.units.Units;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Electrochemical reference data (half-reaction potentials, exchange current densities, electrolyte
 * compositions and conductivities), loaded from {@code /emergence/chemistry/electrochemistry.json}.
 */
public final class Electrochemistry {

    /**
     * Reduction half-reaction: stoich_ox·ox + n e⁻ → metal (solid) or gas, with cathodic and anodic transfer
     * coefficients of the overall reaction (α_a + α_c = n for a reversible couple).
     */
    public record HalfReaction(String id, String speciesOx, int stoichOx, int n, double e0, String metal, String gas,
                               boolean oxidationOnly, double alphaC, double alphaA) {}

    public record Solution(String id, String name, Map<String, Double> species, double conductivity) {}

    private final List<HalfReaction> reactions = new ArrayList<>();
    private final Map<String, Solution> solutions = new LinkedHashMap<>();
    private final Map<String, Map<String, Double>> i0 = new LinkedHashMap<>();
    private Map<String, Double> defaultI0 = Map.of();
    private final Map<String, double[]> absorbance = new LinkedHashMap<>();
    private double massTransfer = 1e-5;

    private static volatile Electrochemistry standard;

    public static Electrochemistry standard() {
        Electrochemistry e = standard;
        if (e == null) {
            synchronized (Electrochemistry.class) {
                if (standard == null) {
                    e = new Electrochemistry();
                    e.load(read("/emergence/chemistry/electrochemistry.json"));
                    standard = e;
                }
                e = standard;
            }
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    void load(String json) {
        Map<String, Object> root = Json.parseObject(json);
        for (Object o : (List<Object>) root.get("half_reactions")) {
            Map<String, Object> m = (Map<String, Object>) o;
            int n = ((Double) m.get("n")).intValue();
            double ac = m.containsKey("alpha_c") ? (Double) m.get("alpha_c") : 0.5 * n;
            double aa = m.containsKey("alpha_a") ? (Double) m.get("alpha_a") : n - ac;
            reactions.add(new HalfReaction((String) m.get("id"), (String) m.get("species_ox"),
                    ((Double) m.get("stoich_ox")).intValue(), n,
                    Units.parse((String) m.get("e0")).si(Dimension.VOLTAGE), (String) m.get("metal"), (String) m.get("gas"),
                    Boolean.TRUE.equals(m.get("oxidation_only")), ac, aa));
        }
        Map<String, Object> ex = (Map<String, Object>) root.get("exchange_currents");
        for (var en : ex.entrySet()) {
            Map<String, Double> vals = new LinkedHashMap<>();
            for (var v : ((Map<String, Object>) en.getValue()).entrySet()) vals.put(v.getKey(), (Double) v.getValue());
            if (en.getKey().equals("default_i0")) defaultI0 = vals;
            else i0.put(en.getKey(), vals);
        }
        Map<String, Object> abs = (Map<String, Object>) root.getOrDefault("ion_absorbance", Map.of());
        for (var en : abs.entrySet()) {
            List<Object> v = (List<Object>) en.getValue();
            absorbance.put(en.getKey(), new double[]{(Double) v.get(0), (Double) v.get(1), (Double) v.get(2)});
        }
        massTransfer = (Double) root.get("mass_transfer_coefficient");
        for (Object o : (List<Object>) root.get("solutions")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Double> sp = new LinkedHashMap<>();
            for (var v : ((Map<String, Object>) m.get("species")).entrySet()) sp.put(v.getKey(), (Double) v.getValue());
            solutions.put((String) m.get("id"), new Solution((String) m.get("id"), (String) m.get("name"), sp,
                    Units.parse((String) m.get("conductivity")).si(Dimension.CONDUCTANCE.div(Dimension.LENGTH))));
        }
    }

    public List<HalfReaction> reactions() {
        return Collections.unmodifiableList(reactions);
    }

    public HalfReaction reaction(String id) {
        for (HalfReaction r : reactions) if (r.id().equals(id)) return r;
        throw new IllegalArgumentException("Unknown half-reaction " + id);
    }

    /** The half-reaction whose solid is the given material (its own dissolution couple), or null. */
    public HalfReaction metalCouple(String materialId) {
        for (HalfReaction r : reactions) if (materialId.equals(r.metal())) return r;
        return null;
    }

    public Solution solution(String id) {
        return solutions.get(id);
    }

    /** Exchange current density [A/m²] of a reaction on an electrode material. */
    public double exchangeCurrent(String reaction, String material) {
        Map<String, Double> m = i0.get(reaction);
        if (m != null && m.containsKey(material)) return m.get(material);
        return defaultI0.getOrDefault(reaction, 1e-6);
    }

    /**
     * Colour of light transmitted through a solution of the given ion concentrations [mol/L] (5 cm path,
     * Beer–Lambert over three bands) as 0xRRGGBB, with water itself a very pale blue.
     */
    public int solutionColour(Map<String, Double> concentrations) {
        double[] a = {0.012, 0.004, 0.0};
        for (var e : concentrations.entrySet()) {
            double[] eps = absorbance.get(e.getKey());
            if (eps == null) continue;
            double c = Math.max(0, e.getValue());
            for (int k = 0; k < 3; k++) a[k] += eps[k] * c;
        }
        int rgb = 0;
        for (int k = 0; k < 3; k++) rgb = (rgb << 8) | (int) Math.round(242 * Math.pow(10, -a[k]));
        return rgb;
    }

    public double massTransferCoefficient() {
        return massTransfer;
    }

    private static String read(String path) {
        try (InputStream in = Electrochemistry.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
