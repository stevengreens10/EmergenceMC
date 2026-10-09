package dev.emergence.core.nuclear;

import dev.emergence.core.data.Json;
import dev.emergence.core.materials.Material;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Decay data of radioactive nuclides and gamma attenuation coefficients (from {@code /emergence/nuclear/nuclear.json}). */
public final class NuclearData {
    /** A decay branch: daughter (null: stable or not tracked), fraction, energy deposited locally [J]. */
    public record Branch(String daughter, double fraction, double localEnergy) {}

    /** A gamma line: energy [keV] and photons per decay. */
    public record Gamma(double keV, double yield) {}

    public record Nuclide(String id, String name, double halfLife, List<Branch> decays, List<Gamma> gammas) {
        /** Decay constant λ = ln 2 / t½ [1/s]. */
        public double lambda() {
            return Math.log(2) / halfLife;
        }
    }

    private static volatile NuclearData standard;
    private final Map<String, Nuclide> nuclides = new LinkedHashMap<>();
    private final Map<Integer, String> codes = new HashMap<>();
    private double[] energies, gmEff;
    private final Map<String, double[]> attenuation = new HashMap<>();
    private final Map<String, String> materialClass = new HashMap<>();
    private double background;

    public static NuclearData standard() {
        NuclearData d = standard;
        if (d == null) {
            synchronized (NuclearData.class) {
                if (standard == null) {
                    NuclearData n = new NuclearData();
                    n.load(read("/emergence/nuclear/nuclear.json"));
                    standard = n;
                }
                d = standard;
            }
        }
        return d;
    }

    private static String read(String path) {
        try (InputStream in = NuclearData.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void load(String json) {
        Map<String, Object> root = Json.parseObject(json);
        for (Object o : (List<Object>) root.get("nuclides")) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<Branch> br = new ArrayList<>();
            for (Object b : (List<Object>) m.get("decays")) {
                Map<String, Object> bm = (Map<String, Object>) b;
                br.add(new Branch((String) bm.get("to"), (Double) bm.get("branch"), (Double) bm.get("local_keV") * 1.602176634e-16));
            }
            List<Gamma> gs = new ArrayList<>();
            for (Object g : (List<Object>) m.get("gammas")) {
                Map<String, Object> gm = (Map<String, Object>) g;
                gs.add(new Gamma((Double) gm.get("keV"), (Double) gm.get("yield")));
            }
            Nuclide n = new Nuclide((String) m.get("id"), (String) m.get("name"), (Double) m.get("half_life"), br, gs);
            nuclides.put(n.id(), n);
            codes.put(((Double) m.get("code")).intValue(), n.id());
        }
        energies = toArray((List<Object>) root.get("attenuation_keV"));
        for (var e : ((Map<String, Object>) root.get("attenuation")).entrySet()) attenuation.put(e.getKey(), toArray((List<Object>) e.getValue()));
        for (var e : ((Map<String, Object>) root.get("material_class")).entrySet()) materialClass.put(e.getKey(), (String) e.getValue());
        gmEff = toArray((List<Object>) root.get("gm_efficiency"));
        background = (Double) root.get("gm_background");
    }

    private static double[] toArray(List<Object> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = (Double) l.get(i);
        return a;
    }

    /** The nuclide with the given stable integer code (part parameter "nuclide"). */
    public Nuclide byCode(int code) {
        String id = codes.get(code);
        if (id == null) throw new IllegalArgumentException("Unknown nuclide code " + code);
        return nuclide(id);
    }

    public int code(String id) {
        for (var e : codes.entrySet()) if (e.getValue().equals(id)) return e.getKey();
        return -1;
    }

    public Nuclide nuclide(String id) {
        Nuclide n = nuclides.get(id);
        if (n == null) throw new IllegalArgumentException("Unknown nuclide " + id);
        return n;
    }

    public boolean has(String id) {
        return nuclides.containsKey(id);
    }

    public Collection<Nuclide> nuclides() {
        return Collections.unmodifiableCollection(nuclides.values());
    }

    /** The nuclide and every descendant reached through its decays, in order. */
    public List<Nuclide> chain(String id) {
        List<Nuclide> out = new ArrayList<>();
        Deque<String> todo = new ArrayDeque<>(List.of(id));
        while (!todo.isEmpty()) {
            Nuclide n = nuclide(todo.pop());
            if (out.contains(n)) continue;
            out.add(n);
            for (Branch b : n.decays()) if (b.daughter() != null) todo.add(b.daughter());
        }
        return out;
    }

    /** Log–log interpolation of a table over the attenuation energies, clamped at the ends. */
    private double interp(double[] table, double keV) {
        double e = Math.max(energies[0], Math.min(energies[energies.length - 1], keV));
        for (int i = 0; i < energies.length - 1; i++) {
            if (e <= energies[i + 1]) {
                double f = Math.log(e / energies[i]) / Math.log(energies[i + 1] / energies[i]);
                return Math.exp(Math.log(table[i]) + f * (Math.log(table[i + 1]) - Math.log(table[i])));
            }
        }
        return table[table.length - 1];
    }

    /** Mass attenuation coefficient of a material for photons of energy keV [m²/kg]. */
    public double massAttenuation(Material m, double keV) {
        String cls = materialClass.get(m.id());
        if (cls == null) {
            String cat = m.category().toString().toLowerCase(Locale.ROOT);
            cls = cat.contains("polymer") || cat.contains("composite") || m.id().equals("oak") ? "water"
                    : cat.contains("ceramic") || cat.contains("glass") || cat.contains("semiconductor") || cat.contains("insulator") ? "silica"
                    : cat.contains("fluid") ? "water" : "iron";
        }
        return interp(attenuation.get(cls), keV) * 0.1; // cm²/g → m²/kg
    }

    /** Intrinsic efficiency of a Geiger–Müller tube for gamma rays of energy keV. */
    public double gmEfficiency(double keV) {
        return interp(gmEff, keV);
    }

    /** Background count rate of a Geiger–Müller tube (cosmic rays, terrestrial radioactivity) [1/s]. */
    public double gmBackground() {
        return background;
    }
}
