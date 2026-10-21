package dev.emergence.core.assembly;

import dev.emergence.core.assembly.PartParameters.Param;

import java.util.Locale;

/**
 * Part parameters as people read and write them: "1.5 mm", "4.7 kΩ", "12 %", "630 nm" — an SI prefix where the unit
 * takes one, a fixed display unit where the schema gives one — and back from what they type ("4.7k", "220 mΩ", "12").
 */
public final class ParamText {
    private ParamText() {}

    static final java.util.Set<String> PREFIXED = java.util.Set.of("Ω", "F", "H", "A", "V", "Hz", "Bq", "m", "Pa", "s", "N", "S", "W",
            "N·m", "Pa·s", "N·s/m", "A/V²", "V/s", "mol", "N·m/rad");
    static final String PREFIXES = "pnµmkMG";
    static final int[] EXP = {-12, -9, -6, -3, 3, 6, 9};

    public static boolean prefixed(Param p) {
        return p.shownIn() == null && PREFIXED.contains(p.unit());
    }

    /** A value as people write it: "1.5 mm", "4.7 kΩ", "12 %", "630 nm". */
    public static String format(Param p, double v) {
        if (p.type() == PartParameters.Type.INTEGER) return String.valueOf(Math.round(v)) + (p.shownIn() != null ? " " + p.shownIn() : "");
        if (p.shownIn() != null) return number(v * p.shownFactor()) + (p.shownIn().equals("%") ? " %" : " " + p.shownIn());
        // small lengths in millimetres, as workshops give them ("0.5 mm wire", not "500 µm")
        if (p.unit().equals("m") && p.max() <= 0.1) return number(v * 1e3) + " mm";
        if (prefixed(p) && v != 0) {
            int e3 = (int) Math.floor(Math.log10(Math.abs(v)) / 3) * 3;
            e3 = Math.max(-12, Math.min(9, e3));
            String pre = e3 == 0 ? "" : String.valueOf(PREFIXES.charAt(indexOf(e3)));
            return number(v / Math.pow(10, e3)) + " " + pre + p.unit();
        }
        return number(v) + (p.unit().isEmpty() ? "" : p.unit().equals("°") ? "°" : " " + p.unit());
    }

    static int indexOf(int e3) {
        for (int i = 0; i < EXP.length; i++) if (EXP[i] == e3) return i;
        return 0;
    }

    /** Up to four significant figures, without trailing zeros or exponents for everyday sizes. */
    static String number(double v) {
        if (v == 0) return "0";
        double a = Math.abs(v);
        if (a >= 1e6 || a < 1e-3) return String.format(Locale.ROOT, "%.3g", v).replace("e+0", "e").replace("e-0", "e-");
        String s = new java.math.BigDecimal(v).round(new java.math.MathContext(4)).stripTrailingZeros().toPlainString();
        return s;
    }

    /**
     * Reads typed text: a number, then optionally an SI prefix and the unit ("4.7k", "220 mΩ", "1.5 mm", "12"). A bare
     * number is taken in the unit and prefix the value is being shown in. Null if it is not a number.
     */
    public static Double parse(Param p, String text, double shownValue) {
        var m = java.util.regex.Pattern.compile("^\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*(.*?)\\s*$").matcher(text.replace(',', '.'));
        if (!m.matches()) return null;
        double num = Double.parseDouble(m.group(1));
        String rest = m.group(2).replace('u', 'µ').replace("%", "").trim();
        if (p.type() == PartParameters.Type.INTEGER) return (double) Math.round(num / (p.shownIn() != null && !p.shownIn().equals("cells") ? p.shownFactor() : 1));
        if (p.shownIn() != null) return num / p.shownFactor();
        if (!prefixed(p)) return num;
        String unit = p.unit();
        if (rest.isEmpty()) {
            // in the prefix it is shown with
            String shown = format(p, shownValue);
            int sp = shown.indexOf(' ');
            String suffix = sp < 0 ? "" : shown.substring(sp + 1);
            if (suffix.length() > unit.length() && PREFIXES.indexOf(suffix.charAt(0)) >= 0) return num * Math.pow(10, EXP[PREFIXES.indexOf(suffix.charAt(0))]);
            return num;
        }
        if (rest.equals(unit) || rest.equalsIgnoreCase(unit)) return num;
        if (PREFIXES.indexOf(rest.charAt(0)) >= 0 && (rest.length() == 1 || rest.substring(1).equals(unit))) {
            return num * Math.pow(10, EXP[PREFIXES.indexOf(rest.charAt(0))]);
        }
        return null;
    }

    public static double niceRound(double v, int digits) {
        if (v == 0 || !Double.isFinite(v)) return v;
        return new java.math.BigDecimal(v).round(new java.math.MathContext(digits)).doubleValue();
    }

}
