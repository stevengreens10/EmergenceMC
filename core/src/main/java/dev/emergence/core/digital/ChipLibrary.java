package dev.emergence.core.digital;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The example chip designs that come with the mod (resources {@code emergence/chips/<name>.chip}): ready to fabricate
 * as they are, and to open, read and change in the chip designer. They are designs like any a player writes, not
 * built-in machines.
 */
public final class ChipLibrary {
    /** The examples, in the order the designer lists them. */
    public static final String[] NAMES = {"chart_recorder", "counter_display"};

    private ChipLibrary() {
    }

    /** An example's source, or null if there is none by that name. */
    public static String source(String name) {
        try (InputStream in = ChipLibrary.class.getResourceAsStream("/emergence/chips/" + name + ".chip")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Every example: name to source. */
    public static Map<String, String> all() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String n : NAMES) {
            String s = source(n);
            if (s != null) m.put(n, s);
        }
        return m;
    }

    /** An example compiled (it is the mod's own: an error in it is a bug). */
    public static ChipDesign compile(String name) {
        String s = source(name);
        if (s == null) throw new IllegalArgumentException("no example chip " + name);
        try {
            return ChipDesign.compile(s);
        } catch (ChipDesign.ChipError e) {
            throw new IllegalStateException("example chip " + name + ": " + e.getMessage(), e);
        }
    }
}
