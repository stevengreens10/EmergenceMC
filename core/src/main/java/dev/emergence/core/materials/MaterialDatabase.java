package dev.emergence.core.materials;

import dev.emergence.core.data.Json;
import dev.emergence.core.units.DimensionException;
import dev.emergence.core.units.Quantity;
import dev.emergence.core.units.Units;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-driven registry of engineering materials.
 * <p>
 * Materials are defined in JSON files under {@code /emergence/materials/} on the classpath; the file
 * {@code index.json} lists which files to load. Every property value is a string with explicit units
 * (e.g. {@code "8.96 g/cm^3"}) that is parsed and checked against {@link MaterialProperty#dimension}.
 */
public final class MaterialDatabase {
    private final Map<String, Material> materials = new LinkedHashMap<>();

    private static volatile MaterialDatabase standard;

    /** The built-in database shipped with the mod (lazily loaded, thread-safe). */
    public static MaterialDatabase standard() {
        MaterialDatabase db = standard;
        if (db == null) {
            synchronized (MaterialDatabase.class) {
                db = standard;
                if (db == null) {
                    db = new MaterialDatabase();
                    db.loadClasspath("/emergence/materials/");
                    standard = db;
                }
            }
        }
        return db;
    }

    public Material get(String id) {
        Material m = materials.get(id);
        if (m == null) throw new IllegalArgumentException("Unknown material: " + id);
        return m;
    }

    public Material getOrNull(String id) {
        return materials.get(id);
    }

    public Collection<Material> all() {
        return Collections.unmodifiableCollection(materials.values());
    }

    public void register(Material m) {
        if (materials.containsKey(m.id())) throw new IllegalArgumentException("Duplicate material id " + m.id());
        materials.put(m.id(), m);
    }

    @SuppressWarnings("unchecked")
    public void loadClasspath(String dir) {
        Map<String, Object> index = Json.parseObject(readResource(dir + "index.json"));
        for (Object f : (List<Object>) index.get("files")) {
            loadJson(readResource(dir + f), String.valueOf(f));
        }
    }

    @SuppressWarnings("unchecked")
    public void loadJson(String json, String sourceName) {
        Map<String, Object> root = Json.parseObject(json);
        List<Object> list = (List<Object>) root.get("materials");
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            String id = (String) m.get("id");
            try {
                register(parseMaterial(m));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid material '" + id + "' in " + sourceName + ": " + e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Material parseMaterial(Map<String, Object> m) {
        String id = (String) m.get("id");
        String name = (String) m.getOrDefault("name", id);
        MaterialCategory cat = MaterialCategory.valueOf(((String) m.get("category")).toUpperCase());
        Map<MaterialProperty, Double> props = new EnumMap<>(MaterialProperty.class);
        Map<String, Object> raw = (Map<String, Object>) m.get("properties");
        for (var e : raw.entrySet()) {
            MaterialProperty p = MaterialProperty.byKey(e.getKey());
            if (p == null) throw new IllegalArgumentException("Unknown property '" + e.getKey() + "'");
            Quantity q = e.getValue() instanceof Double d ? Quantity.of(d, dev.emergence.core.units.Dimension.DIMENSIONLESS)
                    : Units.parse((String) e.getValue());
            if (!q.dimension().equals(p.dimension)) {
                throw new DimensionException("Property " + p.key + " must have dimension " + p.dimension
                        + " but '" + e.getValue() + "' has " + q.dimension());
            }
            props.put(p, q.value());
        }
        List<String> sources = new ArrayList<>();
        Object src = m.get("sources");
        if (src instanceof List<?> l) for (Object s : l) sources.add(String.valueOf(s));
        int color = 0x808080;
        if (m.get("color") instanceof String c) color = Integer.parseInt(c.replace("#", ""), 16);
        return new Material(id, name, cat, props, sources, color);
    }

    private static String readResource(String path) {
        try (InputStream in = MaterialDatabase.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
