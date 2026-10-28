package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.digital.ChipTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.emergence.core.assembly.PartKind.*;

/**
 * Dashboards: gauges built of miniature electronics, each in one block, drawing one measurement on the full-size
 * screen standing against its south face. A gauge takes its supply and its signals from the north face, where a
 * full-size ribbon cable runs: each miniature wire there joins the ribbon conductor of its colour.
 *
 * <p>Inside each block: a 10 kHz crystal, a 10-bit ADC, the analog front end the measurement needs, a gauge chip (the
 * shipped design {@code gauge}, in the chip language), a 256-byte ROM with the font, the label and the unit, and the
 * display controller standing against the south face.
 */
public final class DashboardExhibits {
    private DashboardExhibits() {
    }

    /** The ribbon's conductors, by colour. */
    public static final int GND = 0, VDD = 1, BOILER = 2, CONDENSER = 3, PULSE = 4, RETURN = 5, RETURN_REF = 6, FUEL = 7;

    /** What stands between the ribbon and the ADC. */
    public enum FrontEnd {
        /** The signal straight into the ADC (an amplified transducer, ratiometric to the supply). */
        DIRECT,
        /** Two silicon diodes fed through 10 kΩ from the supply: a fixed voltage read against the supply measures it. */
        REFERENCE,
        /**
         * An inverting amplifier (gain 50, a 0.2 s low-pass) on a 0.5 Ω shunt's voltage, sensed on two conductors of
         * its own (the signal: the shunt's far end; the second: its ground end), which carry no current — so neither
         * the ribbon's nor the ground's voltage drops enter the reading (Kelvin sensing).
         */
        CURRENT,
        /** A pulse train into the gauge chip's timer. */
        PULSE,
        /**
         * Power: the current amplifier (as CURRENT) into the first ADC, and a diode's drop (fed through 10 kΩ) into a
         * second ADC sharing its select and clock, whose data goes to the gauge chip's pulse pin: the chip's mode 3
         * takes k · current / reference², the current times the supply's voltage.
         */
        POWER
    }

    /**
     * One gauge: its label and unit (as the ROM holds them), its front end and the ribbon colours it reads (the second
     * only for the current amplifier's reference), and the gauge chip's parameters (see gauge.chip).
     */
    public record Gauge(String id, String label, String unit, FrontEnd frontEnd, int signal, int signal2,
                        int mode, int lo, int gain, int off, int k, int dp, int blo, int bgain, int alo, int ahi, int every) {
        /** Without alarms, every reading to the trend. */
        public Gauge(String id, String label, String unit, FrontEnd frontEnd, int signal, int signal2,
                     int mode, int lo, int gain, int off, int k, int dp, int blo, int bgain) {
            this(id, label, unit, frontEnd, signal, signal2, mode, lo, gain, off, k, dp, blo, bgain, 0, 0, 1);
        }
    }

    /**
     * The value a gauge's card shows, read off its screen as a person would: its five big glyphs (each a font glyph
     * drawn three times its size, slot i from column 4 + 18i, font row r on screen rows 16 + 3r to 18 + 3r), each
     * matched against the font. Null if any is not one of the font's glyphs — a garbled screen, or not a gauge's card.
     * {@code lit} answers whether pixel (x, y) is lit; a card shown inverted (an alarm) is read inverted.
     */
    public static String readValue(java.util.function.BiPredicate<Integer, Integer> lit) {
        java.util.function.BiPredicate<Integer, Integer> on = inked(lit);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            int[] cols = new int[5];
            for (int c = 0; c < 5; c++)
                for (int r = 0; r < 7; r++) if (on.test(4 + 18 * i + 3 * c + 1, 16 + 3 * r + 1)) cols[c] |= 1 << r;
            char ch = glyphOf(cols);
            if (ch == 0) return null;
            if (ch != ' ') s.append(ch);
        }
        return s.toString();
    }

    /** A gauge card's label, read off its screen (small glyphs along the top, from column 2, six apart); null if garbled. */
    public static String readLabel(java.util.function.BiPredicate<Integer, Integer> lit) {
        java.util.function.BiPredicate<Integer, Integer> on = inked(lit);
        StringBuilder s = new StringBuilder();
        for (int li = 0; li < 19; li++) {
            int[] cols = new int[5];
            for (int c = 0; c < 5; c++)
                for (int r = 0; r < 7; r++) if (on.test(2 + 6 * li + c, r + 1)) cols[c] |= 1 << r;
            char ch = glyphOf(cols);
            if (ch == 0) return null;
            s.append(ch);
        }
        return s.toString().trim();
    }

    /** The value a card's text means (its decimal point where it is drawn), or NaN if it is not a number. */
    public static double valueOf(String shown) {
        try {
            return shown == null || shown.isEmpty() ? Double.NaN : Double.parseDouble(shown);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A card drawn inverted (lit where it would be dark: an alarm) read as ink on paper. */
    private static java.util.function.BiPredicate<Integer, Integer> inked(java.util.function.BiPredicate<Integer, Integer> lit) {
        int n = 0;
        for (int y = 0; y < 64; y += 2) for (int x = 0; x < 128; x += 2) if (lit.test(x, y)) n++;
        return n > 128 * 64 / 8 ? (x, y) -> !lit.test(x, y) : lit;
    }

    /** The font glyph with these columns (' ' for none lit), or 0 if it is none of them. */
    private static char glyphOf(int[] cols) {
        boolean blank = true;
        for (int c : cols) blank &= c == 0;
        if (blank) return ' ';
        for (int g = 0; g < FONT.length; g++) if (java.util.Arrays.equals(FONT[g], cols)) return GLYPHS.charAt(g);
        return 0;
    }

    /** The 5x7 font: each glyph's five columns, bit 0 the top row. */
    static final String GLYPHS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ.%/-";
    static final int[][] FONT = {
            {0x3E, 0x51, 0x49, 0x45, 0x3E}, {0x00, 0x42, 0x7F, 0x40, 0x00}, {0x42, 0x61, 0x51, 0x49, 0x46}, {0x21, 0x41, 0x45, 0x4B, 0x31},
            {0x18, 0x14, 0x12, 0x7F, 0x10}, {0x27, 0x45, 0x45, 0x45, 0x39}, {0x3C, 0x4A, 0x49, 0x49, 0x30}, {0x01, 0x71, 0x09, 0x05, 0x03},
            {0x36, 0x49, 0x49, 0x49, 0x36}, {0x06, 0x49, 0x49, 0x29, 0x1E},
            {0x7E, 0x11, 0x11, 0x11, 0x7E}, {0x7F, 0x49, 0x49, 0x49, 0x36}, {0x3E, 0x41, 0x41, 0x41, 0x22}, {0x7F, 0x41, 0x41, 0x22, 0x1C},
            {0x7F, 0x49, 0x49, 0x49, 0x41}, {0x7F, 0x09, 0x09, 0x09, 0x01}, {0x3E, 0x41, 0x49, 0x49, 0x7A}, {0x7F, 0x08, 0x08, 0x08, 0x7F},
            {0x00, 0x41, 0x7F, 0x41, 0x00}, {0x20, 0x40, 0x41, 0x3F, 0x01}, {0x7F, 0x08, 0x14, 0x22, 0x41}, {0x7F, 0x40, 0x40, 0x40, 0x40},
            {0x7F, 0x02, 0x0C, 0x02, 0x7F}, {0x7F, 0x04, 0x08, 0x10, 0x7F}, {0x3E, 0x41, 0x41, 0x41, 0x3E}, {0x7F, 0x09, 0x09, 0x09, 0x06},
            {0x3E, 0x41, 0x51, 0x21, 0x5E}, {0x7F, 0x09, 0x19, 0x29, 0x46}, {0x46, 0x49, 0x49, 0x49, 0x31}, {0x01, 0x01, 0x7F, 0x01, 0x01},
            {0x3F, 0x40, 0x40, 0x40, 0x3F}, {0x1F, 0x20, 0x40, 0x20, 0x1F}, {0x3F, 0x40, 0x38, 0x40, 0x3F}, {0x63, 0x14, 0x08, 0x14, 0x63},
            {0x07, 0x08, 0x70, 0x08, 0x07}, {0x61, 0x51, 0x49, 0x45, 0x43},
            {0x00, 0x60, 0x60, 0x00, 0x00}, {0x23, 0x13, 0x08, 0x64, 0x62}, {0x20, 0x10, 0x08, 0x04, 0x02}, {0x08, 0x08, 0x08, 0x08, 0x08}};
    /** A space's code: nothing drawn. */
    static final int SPACE = 40;

    /** Text as the gauge's glyph codes (unknown characters as spaces). */
    static int[] codes(String s) {
        int[] c = new int[s.length()];
        for (int i = 0; i < s.length(); i++) {
            int g = GLYPHS.indexOf(Character.toUpperCase(s.charAt(i)));
            c[i] = g < 0 ? SPACE : g;
        }
        return c;
    }

    /** A gauge's ROM image as hex text (the ROM editor's format): the font, the label at C8, the unit at E0. */
    public static String gaugeRom(String label, String unit) {
        int[] img = new int[256];
        for (int g = 0; g < FONT.length; g++) System.arraycopy(FONT[g], 0, img, 5 * g, 5);
        int[] l = codes(label), u = codes(unit);
        for (int i = 0; i < 19; i++) img[0xC8 + i] = i < l.length ? l[i] : SPACE;
        for (int i = 0; i < 5; i++) img[0xE0 + i] = i < u.length ? u[i] : SPACE;
        StringBuilder sb = new StringBuilder("// Gauge ROM: \"" + label + "\" in " + unit + ". The 5x7 font, glyph g's five columns at 5g (bit 0 the top\n"
                + "// row): 0-9, A-Z, '.', '%', '/', '-' (40: a space); the label's glyph codes at C8, the unit's at E0.\n");
        for (int r = 0; r < 16; r++) {
            sb.append(String.format("@%02X ", r * 16));
            for (int i = 0; i < 16; i++) sb.append(String.format(" %02X", img[r * 16 + i]));
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Places a chip of {@code function} with its segments from (x, y, z) — along x ({@code upright} false: inputs on the
     * −z side, outputs +z) or up y (inputs −x, outputs +x) — and returns each pin's cell by name ("GND", "V_DD" on the
     * last segment), the first segment's index under "#".
     */
    static Map<String, int[]> chip(Exhibit e, int function, String text, int x, int y, int z, boolean upright, Object... kv) {
        var pins = ChipTypes.pins(function, text);
        if (pins.error() != null) throw new IllegalStateException("chip " + function + ": " + pins.error());
        Map<String, int[]> at = new HashMap<>();
        Object[] params = new Object[kv.length + 2];
        params[0] = "function";
        params[1] = function;
        System.arraycopy(kv, 0, params, 2, kv.length);
        for (int k = 0; k < pins.segments(); k++) {
            int sx = upright ? x : x + k, sy = upright ? y + k : y;
            int idx = e.add(CHIP, "silicon", sx, sy, z, upright ? Dir.UP : Dir.EAST, params);
            if (text != null) e.text(idx, text);
            if (k == 0) at.put("#", new int[]{idx});
            String[] io = pins.at(k);
            if (io[0] != null) at.put(io[0], upright ? new int[]{sx - 1, sy, z} : new int[]{sx, sy, z - 1});
            if (io[1] != null) at.put(io[1], upright ? new int[]{sx + 1, sy, z} : new int[]{sx, sy, z + 1});
        }
        return at;
    }

    /** One gauge's miniature block (see the class comment). */
    public static Exhibit gaugeBlock(Gauge g) {
        Exhibit e = new Exhibit(g.id(), g.label(), List.of(), "");
        e.miniature = true;
        String design = ChipLibrary.source("gauge"), rom = gaugeRom(g.label(), g.unit());
        Router r = new Router(e, 0, 0, 0, 15, 6, 15);
        // ---- in at the north face: ground, the supply and its signals, each on a wire of its ribbon colour; nothing
        // else may touch that face (a wire there would join the ribbon's conductor of its colour)
        int[] entries = {6, 8, 10, 12};
        int[] colours = {GND, VDD, g.signal(), g.signal2()};
        String[] nets = {"gnd", "vdd", "sig", "sig2"};
        // (the reference measures the supply itself; the current amplifier takes two sense lines)
        int used = g.frontEnd() == FrontEnd.REFERENCE ? 2 : g.frontEnd() == FrontEnd.CURRENT || g.frontEnd() == FrontEnd.POWER ? 4 : 3;
        for (int x = 0; x <= 15; x++) {
            boolean entry = false;
            for (int k = 0; k < used; k++) if (entries[k] == x) entry = true;
            r.keepClear(x, entry ? 1 : 0, 0, x, 6, 0);
        }
        // (each entry's cell inward too: its only way in, which another net's wire must not take)
        for (int k = 0; k < used; k++) r.freePin(nets[k], entries[k], 0, 0).freePin(nets[k], entries[k], 0, 1).colour(nets[k], colours[k]);
        // ---- the clock, the ADC, the gauge chip, its ROM, and the controller against the south face
        Map<String, int[]> clock = chip(e, 6, null, 13, 0, 2, false, "frequency", Integer.getInteger("diag.gaugeClock", 10_000));
        Map<String, int[]> adc = chip(e, 7, null, 1, 0, 4, false);
        Map<String, int[]> chip = chip(e, ChipTypes.DESIGN, design, 0, 0, 8, false,
                "p_mode", g.mode(), "p_lo", g.lo(), "p_gain", g.gain(), "p_off", g.off(), "p_k", g.k(), "p_dp", g.dp(),
                "p_blo", g.blo(), "p_bgain", g.bgain(), "p_alo", g.alo(), "p_ahi", g.ahi(), "p_every", g.every());
        // (the ROM's address and data pins in line with the chip's: each bus wire runs straight)
        Map<String, int[]> romPins = chip(e, ChipTypes.ROM256, rom, 0, 0, 12, false);
        Map<String, int[]> ctl = chip(e, 8, null, 13, 0, 15, true);
        e.name("clock", clock.get("#")[0]);
        e.name("adc", adc.get("#")[0]);
        e.name("gauge", chip.get("#")[0]);
        e.name("rom", romPins.get("#")[0]);
        e.name("controller", ctl.get("#")[0]);
        for (Map<String, int[]> c : List.of(clock, adc, chip, romPins, ctl)) {
            pin(r, "gnd", c.get("GND"));
            pin(r, "vdd", c.get("V_DD"));
        }
        pin(r, "vdd", ctl.get("rst"));
        pin(r, "clk", clock.get("out"));
        pin(r, "clk", chip.get("clk"));
        // the ADC's bus, the ROM's, the display's
        pin(r, "acs", chip.get("acs"));
        pin(r, "acs", adc.get("cs"));
        pin(r, "asclk", chip.get("asclk"));
        pin(r, "asclk", adc.get("sclk"));
        pin(r, "miso", adc.get("dout"));
        pin(r, "miso", chip.get("miso"));
        for (int i = 0; i < 8; i++) {
            pin(r, "a" + i, chip.get("a" + i));
            pin(r, "a" + i, romPins.get("A" + i));
            pin(r, "f" + i, romPins.get("D" + i));
            pin(r, "f" + i, chip.get("f" + i));
        }
        for (String s : new String[]{"cs", "dc", "sclk", "din"}) {
            pin(r, s, chip.get(s));
            pin(r, s, ctl.get(s));
        }
        // ---- the front end
        switch (g.frontEnd()) {
            case DIRECT -> pin(r, "sig", adc.get("vin"));
            case PULSE -> {
                pin(r, "sig", chip.get("pulse"));
                pin(r, "gnd", adc.get("vin")); // (the ADC unused: its input grounded)
            }
            case REFERENCE -> {
                // 10 kΩ from the supply into two diodes in series to ground (0.4 mA); their top is the reference
                e.add(RESISTOR, "alumina", 7, 0, 2, Dir.EAST, "resistance", 10_000);
                e.add(DIODE, "silicon", 9, 0, 4, Dir.EAST);
                e.add(DIODE, "silicon", 11, 0, 4, Dir.EAST);
                e.add(INSULATED_WIRE, "copper", 10, 0, 4, Dir.UP, "color", 9); // between the diodes
                r.pin("vdd", 6, 0, 2).pin("ref", 8, 0, 2).pin("ref", 8, 0, 4).pin("gnd", 12, 0, 4);
                pin(r, "ref", adc.get("vin"));
                r.keepClear(10, 1, 4, 10, 6, 4);
            }
            case CURRENT, POWER -> {
                // an inverting amplifier: the op-amp's + input on the shunt's ground end, its − input through 2 kΩ from
                // the shunt's other end and through 100 kΩ (with 10 µF across it: a 1 s low-pass, the generator's current
                // pulsing at each magnet's pass) from its output
                e.name("amp", e.add(OPAMP, "silicon", 9, 1, 3, Dir.EAST));
                e.add(RESISTOR, "alumina", 7, 0, 5, Dir.EAST, "resistance", 2_000);
                e.add(RESISTOR, "alumina", 11, 0, 4, Dir.EAST, "resistance", 100_000);
                // (a row clear of the 100 kΩ: single-cell parts also bring their leads out on their side faces, and side by
                // side the two were joined end to end, the amplifier's feedback shorted)
                e.add(CAPACITOR, "alumina", 11, 0, 6, Dir.EAST, "capacitance", 10e-6);
                r.pin("sig2", 8, 1, 3);                                  // + input (west): the shunt's ground end
                r.pin("inm", 9, 0, 3).pin("inm", 8, 0, 5).pin("inm", 10, 0, 4).pin("inm", 10, 0, 6);
                r.pin("sig", 6, 0, 5);                                    // the shunt's far end, into 2 kΩ
                r.pin("aout", 10, 1, 3).pin("aout", 12, 0, 4).pin("aout", 12, 0, 6);
                r.pin("vdd", 9, 1, 4).pin("gnd", 9, 1, 2);
                pin(r, "aout", adc.get("vin"));
                if (g.frontEnd() == FrontEnd.POWER) {
                    // the second ADC, upright against the east side, on the first's select and clock; the reference: a
                    // diode fed through 10 kΩ from the supply (its sides along its lead axis, x, kept clear)
                    Map<String, int[]> adc2 = chip(e, 7, null, 14, 0, 5, true);
                    e.name("adc2", adc2.get("#")[0]);
                    pin(r, "gnd", adc2.get("GND"));
                    pin(r, "vdd", adc2.get("V_DD"));
                    pin(r, "acs", adc2.get("cs"));
                    pin(r, "asclk", adc2.get("sclk"));
                    pin(r, "pulse2", adc2.get("dout"));
                    pin(r, "pulse2", chip.get("pulse"));
                    e.add(RESISTOR, "alumina", 7, 0, 2, Dir.EAST, "resistance", 10_000);
                    e.add(DIODE, "silicon", 5, 0, 4, Dir.SOUTH, "lead_axis", 0);
                    r.pin("ref", 6, 0, 2).pin("vdd", 8, 0, 2).pin("ref", 5, 0, 3).pin("gnd", 5, 0, 5);
                    pin(r, "ref", adc2.get("vin"));
                    r.keepClear(6, 0, 4, 6, 6, 4);
                } else {
                    pin(r, "gnd", chip.get("pulse")); // (the timer unused: its input grounded)
                }
            }
        }
        if (ROUTE) r.route();
        return e;
    }

    /** (Tests: false leaves a gauge block unrouted, to look at its layout.) */
    static boolean ROUTE = true;

    private static void pin(Router r, String net, int[] c) {
        if (c == null) throw new IllegalStateException("no pin for " + net);
        r.pin(net, c[0], c[1], c[2]);
    }

    /** The parts of a gauge block by kind (for tests). */
    static long count(Exhibit e, PartKind k) {
        return e.parts.stream().filter(d -> d.kind() == k).count();
    }
}
