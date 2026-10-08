package dev.emergence.core.units;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Unit registry and parser. Converts human-readable unit expressions such as
 * {@code "8.96 g/cm^3"}, {@code "401 W/(m*K)"} or {@code "1.68e-8 ohm*m"} into SI {@link Quantity}s.
 * <p>
 * Grammar: {@code number [unitExpr]}, where unitExpr is a product/quotient of unit atoms with optional
 * integer exponents ({@code ^n}) and parentheses. SI prefixes are supported on prefixable units.
 */
public final class Units {
    private Units() {}

    private static final Map<String, Quantity> UNITS = new HashMap<>();
    private static final Map<String, Double> PREFIXES = new HashMap<>();
    private static final Set<String> PREFIXABLE = new HashSet<>();

    static {
        PREFIXES.put("Y", 1e24); PREFIXES.put("Z", 1e21); PREFIXES.put("E", 1e18); PREFIXES.put("P", 1e15);
        PREFIXES.put("T", 1e12); PREFIXES.put("G", 1e9); PREFIXES.put("M", 1e6); PREFIXES.put("k", 1e3);
        PREFIXES.put("h", 1e2); PREFIXES.put("da", 1e1); PREFIXES.put("d", 1e-1); PREFIXES.put("c", 1e-2);
        PREFIXES.put("m", 1e-3); PREFIXES.put("u", 1e-6); PREFIXES.put("µ", 1e-6); PREFIXES.put("n", 1e-9);
        PREFIXES.put("p", 1e-12); PREFIXES.put("f", 1e-15);

        def("1", 1, Dimension.DIMENSIONLESS, false);
        def("m", 1, Dimension.LENGTH, true);
        def("g", 1e-3, Dimension.MASS, true);
        def("s", 1, Dimension.TIME, true);
        def("A", 1, Dimension.CURRENT, true);
        def("K", 1, Dimension.TEMPERATURE, true);
        def("mol", 1, Dimension.AMOUNT, true);
        def("cd", 1, Dimension.LUMINOUS_INTENSITY, false);
        def("N", 1, Dimension.FORCE, true);
        def("J", 1, Dimension.ENERGY, true);
        def("W", 1, Dimension.POWER, true);
        def("Pa", 1, Dimension.PRESSURE, true);
        def("V", 1, Dimension.VOLTAGE, true);
        def("ohm", 1, Dimension.RESISTANCE, true);
        def("Ω", 1, Dimension.RESISTANCE, true);
        def("S", 1, Dimension.CONDUCTANCE, true);
        def("F", 1, Dimension.CAPACITANCE, true);
        def("H", 1, Dimension.INDUCTANCE, true);
        def("Wb", 1, Dimension.MAGNETIC_FLUX, true);
        def("T", 1, Dimension.MAGNETIC_FLUX_DENSITY, true);
        def("C", 1, Dimension.CHARGE, true);
        def("Hz", 1, Dimension.FREQUENCY, true);
        def("L", 1e-3, Dimension.VOLUME, true);
        def("bar", 1e5, Dimension.PRESSURE, true);
        def("atm", PhysicalConstants.STANDARD_ATMOSPHERE, Dimension.PRESSURE, false);
        def("min", 60, Dimension.TIME, false);
        def("h", 3600, Dimension.TIME, false);
        def("rpm", 2 * Math.PI / 60.0, Dimension.ANGULAR_VELOCITY, false);
        def("rad", 1, Dimension.DIMENSIONLESS, false);
        def("deg", Math.PI / 180, Dimension.DIMENSIONLESS, false);
        def("eV", PhysicalConstants.ELEMENTARY_CHARGE, Dimension.ENERGY, true);
        def("Wh", 3600, Dimension.ENERGY, true);
        def("%", 0.01, Dimension.DIMENSIONLESS, false);
        def("ppm", 1e-6, Dimension.DIMENSIONLESS, false);
    }

    private static void def(String sym, double si, Dimension d, boolean prefixable) {
        UNITS.put(sym, new Quantity(si, d));
        if (prefixable) PREFIXABLE.add(sym);
    }

    /** Looks up a unit (with optional SI prefix), e.g. "mm", "kN", "uF". */
    public static Quantity unit(String symbol) {
        Quantity q = UNITS.get(symbol);
        if (q != null) return q;
        for (int plen = 2; plen >= 1; plen--) {
            if (symbol.length() <= plen) continue;
            String p = symbol.substring(0, plen);
            String rest = symbol.substring(plen);
            Double f = PREFIXES.get(p);
            if (f != null && PREFIXABLE.contains(rest)) {
                Quantity base = UNITS.get(rest);
                return new Quantity(base.value() * f, base.dimension());
            }
        }
        throw new IllegalArgumentException("Unknown unit: '" + symbol + "'");
    }

    /** Parses "value unitExpr". Celsius temperatures are supported as "25 degC" (converted to absolute K). */
    public static Quantity parse(String text) {
        String s = text.trim();
        int i = 0;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            char c = s.charAt(i);
            // 'e' only belongs to the number when followed by a digit or sign (so "1 eV" still parses)
            if ((c == 'e' || c == 'E') && (i + 1 >= s.length() || "+-0123456789".indexOf(s.charAt(i + 1)) < 0)) break;
            i++;
        }
        double value = Double.parseDouble(s.substring(0, i));
        String unitExpr = s.substring(i).trim();
        if (unitExpr.isEmpty()) return new Quantity(value, Dimension.DIMENSIONLESS);
        if (unitExpr.equals("degC")) {
            return new Quantity(value + PhysicalConstants.T_ZERO_CELSIUS, Dimension.TEMPERATURE);
        }
        Quantity u = new Parser(unitExpr).parseExpr();
        return new Quantity(value * u.value(), u.dimension());
    }

    private static final class Parser {
        private final String s;
        private int p;

        Parser(String s) {
            this.s = s.replace(" ", "");
        }

        Quantity parseExpr() {
            Quantity q = parseFactor();
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == '*' || c == '·') {
                    p++;
                    q = q.times(parseFactor());
                } else if (c == '/') {
                    p++;
                    q = q.over(parseFactor());
                } else if (c == ')') {
                    break;
                } else {
                    throw new IllegalArgumentException("Unexpected '" + c + "' in unit expression " + s);
                }
            }
            return q;
        }

        Quantity parseFactor() {
            Quantity base;
            if (s.charAt(p) == '(') {
                p++;
                base = parseExpr();
                if (p >= s.length() || s.charAt(p) != ')') throw new IllegalArgumentException("Missing ) in " + s);
                p++;
            } else {
                int start = p;
                while (p < s.length() && "*/·^()".indexOf(s.charAt(p)) < 0) p++;
                base = unit(s.substring(start, p));
            }
            if (p < s.length() && s.charAt(p) == '^') {
                p++;
                int start = p;
                if (p < s.length() && (s.charAt(p) == '-' || s.charAt(p) == '+')) p++;
                while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
                base = base.pow(Integer.parseInt(s.substring(start, p)));
            }
            return base;
        }
    }

    private static final Map<Dimension, String> NAMED = new HashMap<>();

    static {
        NAMED.put(Dimension.LENGTH, "m");
        NAMED.put(Dimension.TIME, "s");
        NAMED.put(Dimension.CURRENT, "A");
        NAMED.put(Dimension.TEMPERATURE, "K");
        NAMED.put(Dimension.FORCE, "N");
        NAMED.put(Dimension.ENERGY, "J");
        NAMED.put(Dimension.POWER, "W");
        NAMED.put(Dimension.PRESSURE, "Pa");
        NAMED.put(Dimension.VOLTAGE, "V");
        NAMED.put(Dimension.RESISTANCE, "Ω");
        NAMED.put(Dimension.CAPACITANCE, "F");
        NAMED.put(Dimension.INDUCTANCE, "H");
        NAMED.put(Dimension.MAGNETIC_FLUX, "Wb");
        NAMED.put(Dimension.MAGNETIC_FLUX_DENSITY, "T");
        NAMED.put(Dimension.CHARGE, "C");
        NAMED.put(Dimension.FREQUENCY, "Hz");
    }

    /** Formats an SI value with an engineering prefix where the dimension has a named unit. */
    public static String format(double value, Dimension d) {
        if (d.equals(Dimension.MASS)) return String.format("%.4g kg", value);
        String sym = NAMED.get(d);
        if (sym == null) return String.format("%.4g %s", value, d);
        return formatSI(value, sym);
    }

    /** Engineering notation, e.g. formatSI(0.0012, "A") gives "1.200 mA". */
    public static String formatSI(double value, String symbol) {
        if (value == 0 || !Double.isFinite(value)) return String.format("%.4g %s", value, symbol);
        double a = Math.abs(value);
        String[] pre = {"f", "p", "n", "µ", "m", "", "k", "M", "G", "T"};
        int idx = (int) Math.floor(Math.log10(a) / 3) + 5;
        idx = Math.max(0, Math.min(pre.length - 1, idx));
        double scaled = value / Math.pow(10, 3 * (idx - 5));
        return String.format("%.4g %s%s", scaled, pre[idx], symbol);
    }
}
