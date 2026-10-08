package dev.emergence.core.fluids;

import dev.emergence.core.data.Json;
import dev.emergence.core.units.Dimension;
import dev.emergence.core.units.Quantity;
import dev.emergence.core.units.Units;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Working fluids loaded from {@code /emergence/fluids/fluids.json}, with dimension-checked values. */
public final class FluidDatabase {
    private final Map<String, Fluid> byId = new LinkedHashMap<>();
    private final Map<Integer, Fluid> byCode = new LinkedHashMap<>();

    private static volatile FluidDatabase standard;

    public static FluidDatabase standard() {
        FluidDatabase d = standard;
        if (d == null) {
            synchronized (FluidDatabase.class) {
                if (standard == null) {
                    FluidDatabase n = new FluidDatabase();
                    n.load(read("/emergence/fluids/fluids.json"));
                    standard = n;
                }
                d = standard;
            }
        }
        return d;
    }

    public Fluid get(String id) {
        Fluid f = byId.get(id);
        if (f == null) throw new IllegalArgumentException("Unknown fluid " + id);
        return f;
    }

    /** The fluid with this part code, or air if the code is unknown. */
    public Fluid byCode(int code) {
        Fluid f = byCode.get(code);
        return f != null ? f : byId.get("air");
    }

    public List<Fluid> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    @SuppressWarnings("unchecked")
    void load(String json) {
        Map<String, Object> root = Json.parseObject(json);
        for (Object o : (List<Object>) root.get("fluids")) {
            Map<String, Object> m = (Map<String, Object>) o;
            String id = (String) m.get("id"), name = (String) m.get("name");
            int code = ((Double) m.get("code")).intValue();
            Fluid f;
            if ("gas".equals(m.get("phase"))) {
                List<Object> cp = (List<Object>) m.get("cp_poly");
                double[] a = new double[cp.size()];
                for (int i = 0; i < a.length; i++) a[i] = (Double) cp.get(i);
                List<Object> mu = (List<Object>) m.get("mu_sutherland"), k = (List<Object>) m.get("k_sutherland");
                f = new IdealGas(id, name, code, q(m.get("molar_mass"), Dimension.MOLAR_MASS), a,
                        q(mu.get(0), Dimension.DYNAMIC_VISCOSITY), q(mu.get(1), Dimension.TEMPERATURE), q(mu.get(2), Dimension.TEMPERATURE),
                        q(k.get(0), Dimension.THERMAL_CONDUCTIVITY), q(k.get(1), Dimension.TEMPERATURE), q(k.get(2), Dimension.TEMPERATURE));
            } else {
                Liquid.ViscosityLaw law = m.containsKey("mu_vogel") ? Liquid.ViscosityLaw.VOGEL : Liquid.ViscosityLaw.ANDRADE;
                List<Object> mu = (List<Object>) m.get(law == Liquid.ViscosityLaw.VOGEL ? "mu_vogel" : "mu_andrade");
                double[] c = new double[mu.size()];
                for (int i = 0; i < c.length; i++) c[i] = (Double) mu.get(i);
                f = new Liquid(id, name, code, q(m.get("rho0"), Dimension.DENSITY), q(m.get("p0"), Dimension.PRESSURE),
                        q(m.get("t0"), Dimension.TEMPERATURE), q(m.get("bulk_modulus"), Dimension.PRESSURE),
                        q(m.get("expansion"), Dimension.INVERSE_TEMPERATURE), q(m.get("cp"), Dimension.SPECIFIC_HEAT),
                        q(m.get("k"), Dimension.THERMAL_CONDUCTIVITY), law, c);
            }
            if (byCode.put(code, f) != null) throw new IllegalStateException("Duplicate fluid code " + code);
            byId.put(id, f);
        }
    }

    private static double q(Object text, Dimension d) {
        Quantity v = Units.parse((String) text);
        return v.si(d);
    }

    private static String read(String path) {
        try (InputStream in = FluidDatabase.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
