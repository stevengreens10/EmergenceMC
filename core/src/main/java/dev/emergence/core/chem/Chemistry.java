package dev.emergence.core.chem;

import dev.emergence.core.data.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The substance and gas-reaction database (data-driven, from {@code /emergence/chemistry/*.json}). */
public final class Chemistry {
    private static volatile Chemistry standard;

    private final Map<String, Species> species = new LinkedHashMap<>();
    private final Map<String, Reaction> reactions = new LinkedHashMap<>();

    public static Chemistry standard() {
        Chemistry c = standard;
        if (c == null) {
            synchronized (Chemistry.class) {
                if (standard == null) {
                    Chemistry n = new Chemistry();
                    n.loadSpecies(read("/emergence/chemistry/substances.json"));
                    n.loadReactions(read("/emergence/chemistry/reactions.json"));
                    standard = n;
                }
                c = standard;
            }
        }
        return c;
    }

    private static String read(String path) {
        try (InputStream in = Chemistry.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    void loadSpecies(String json) {
        Map<String, Object> root = Json.parseObject(json);
        Map<String, Object> masses = (Map<String, Object>) root.get("atomic_masses");
        for (Object o : (List<Object>) root.get("species")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Integer> formula = new LinkedHashMap<>();
            double mm = 0;
            for (var e : ((Map<String, Object>) m.get("formula")).entrySet()) {
                int n = ((Double) e.getValue()).intValue();
                formula.put(e.getKey(), n);
                Object am = masses.get(e.getKey());
                if (am == null) throw new IllegalArgumentException("No atomic mass for " + e.getKey());
                mm += n * (Double) am / 1000;
            }
            List<Object> cp = (List<Object>) m.get("cp");
            Species s = new Species((String) m.get("id"), (String) m.get("name"), (String) m.get("phase"), formula, mm,
                    (Double) m.get("hf"), (Double) m.get("s0"), (Double) cp.get(0), (Double) cp.get(1), (Double) cp.get(2));
            species.put(s.id(), s);
        }
    }

    @SuppressWarnings("unchecked")
    void loadReactions(String json) {
        Map<String, Object> root = Json.parseObject(json);
        for (Object o : (List<Object>) root.get("reactions")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Reaction r = new Reaction((String) m.get("id"), (String) m.get("equation"),
                    stoich((Map<String, Object>) m.get("reactants")), stoich((Map<String, Object>) m.get("products")),
                    m.containsKey("orders") ? stoich((Map<String, Object>) m.get("orders")) : null,
                    Boolean.TRUE.equals(m.get("reversible")), (Double) m.get("A"), (Double) m.get("b"), (Double) m.get("Ea"));
            reactions.put(r.id(), r);
        }
    }

    private Map<Species, Double> stoich(Map<String, Object> m) {
        Map<Species, Double> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) out.put(species(e.getKey()), (Double) e.getValue());
        return out;
    }

    public Species species(String id) {
        Species s = species.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown species " + id);
        return s;
    }

    public boolean hasSpecies(String id) {
        return species.containsKey(id);
    }

    public Collection<Species> allSpecies() {
        return Collections.unmodifiableCollection(species.values());
    }

    public Reaction reaction(String id) {
        return reactions.get(id);
    }

    public Collection<Reaction> reactions() {
        return Collections.unmodifiableCollection(reactions.values());
    }

    /** Reactions whose every species is among the given ones (those that can run in a mixture of them). */
    public List<Reaction> reactionsAmong(Collection<Species> present) {
        List<Reaction> out = new ArrayList<>();
        for (Reaction r : reactions.values()) {
            if (present.containsAll(r.species())) out.add(r);
        }
        return out;
    }
}
