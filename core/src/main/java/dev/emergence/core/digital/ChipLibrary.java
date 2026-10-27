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

    /** ROM images that come with the mod (resources {@code emergence/roms/<name>.hex}), for the 256 × 8 ROM. */
    public static final String[] ROMS = {"hex_font_3x5"};

    /** A ROM image's text (hex, with comments), or null. */
    public static String rom(String name) {
        try (InputStream in = ChipLibrary.class.getResourceAsStream("/emergence/roms/" + name + ".hex")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** An example's title: its first comment line up to the colon ("Chart recorder"). */
    public static String title(String name) {
        return titleOf(source(name), name);
    }

    public static String romTitle(String name) {
        return titleOf(rom(name), name);
    }

    private static String titleOf(String text, String name) {
        if (text != null && text.startsWith("//")) {
            int end = text.indexOf('\n');
            String line = text.substring(2, end < 0 ? text.length() : end).trim();
            int c = line.indexOf(':');
            if (c < 0) c = line.indexOf(',');
            return (c > 0 ? line.substring(0, c) : line).trim();
        }
        return name.replace('_', ' ');
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
