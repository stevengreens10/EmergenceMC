package dev.emergence.core.digital;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each chip part ("function") is: its name, its pins in package order and how long a row of segments it takes,
 * and the component that runs it. One place for the compiler, the item's tooltip, the pin labels a player sees and
 * the chip designer.
 * <ul>
 *   <li>0–4: the MSI chips (counter, register, adder, 16 × 8 ROM, 16 × 4 RAM) — {@link MsiComponent};</li>
 *   <li>5: a chip of the player's design, its source the part's text — {@link DesignComponent};</li>
 *   <li>6: crystal clock; 7: 10-bit SPI ADC; 8: 128 × 64 display controller;</li>
 *   <li>9: 256 × 8 ROM, its contents the part's text in hex.</li>
 * </ul>
 * A chip with i inputs and o outputs is a row of max(i, o) + 1 segments: segment k carries input k on its −lead side
 * and output k on its +lead side; the last carries GND (−) and V_DD (+).
 */
public final class ChipTypes {
    private ChipTypes() {
    }

    public static final int FUNCTIONS = 10;
    /** The function of a chip of the player's design, and of the 256-byte ROM (their text is the design, the image). */
    public static final int DESIGN = 5, ROM256 = 9;

    /** A chip's pins, or why it cannot be made. */
    public record Pins(String label, List<String> inputs, List<String> outputs, String error) {
        public int segments() {
            return Math.max(inputs.size(), outputs.size()) + 1;
        }

        /** The pin names on segment k: {input or null, output or null}, the last segment {"GND", "V_DD"}. */
        public String[] at(int k) {
            if (k == segments() - 1) return new String[]{"GND", "V_DD"};
            return new String[]{k < inputs.size() ? inputs.get(k) : null, k < outputs.size() ? outputs.get(k) : null};
        }
    }

    /** Short names, by function. */
    public static String name(int function) {
        return switch (function) {
            case 0 -> "4-bit counter";
            case 1 -> "4-bit register";
            case 2 -> "4-bit adder";
            case 3 -> "16 × 8 ROM";
            case 4 -> "16 × 4 RAM";
            case 5 -> "custom chip";
            case 6 -> "crystal clock";
            case 7 -> "10-bit ADC";
            case 8 -> "display controller";
            case 9 -> "256 × 8 ROM";
            default -> "chip " + function;
        };
    }

    /** Whether the part's text means something to this chip (a design, a ROM image). */
    public static boolean hasText(int function) {
        return function == DESIGN || function == ROM256;
    }

    /** Compiled designs, by their source (the same design on many chips, and on every rebuild, compiled once). */
    private static final Map<String, Object> DESIGNS = new ConcurrentHashMap<>();

    /** The design compiled from {@code source}, or the error it gave. */
    public static Object design(String source) {
        return DESIGNS.computeIfAbsent(source, src -> {
            try {
                return ChipDesign.compile(src);
            } catch (ChipDesign.ChipError e) {
                return e;
            }
        });
    }

    public static Pins pins(int function, String text) {
        return switch (function) {
            case 0, 1, 2, 3, 4 -> {
                var t = MsiComponent.Type.values()[function];
                yield new Pins(name(function), t.inputs, t.outputs, null);
            }
            case 5 -> {
                if (text == null || text.isBlank()) yield new Pins(name(function), List.of(), List.of(), "it has no design yet");
                Object d = design(text);
                if (d instanceof ChipDesign.ChipError e) yield new Pins(name(function), List.of(), List.of(), "its design does not compile: " + e.getMessage());
                ChipDesign cd = (ChipDesign) d;
                yield new Pins(cd.name(), cd.inputPins(), cd.outputPins(), null);
            }
            case 6 -> new Pins(name(function), List.of(), List.of("out"), null);
            case 7 -> new Pins(name(function), List.of("cs", "sclk", "vin"), List.of("dout"), null);
            case 8 -> new Pins(name(function), List.of("cs", "dc", "sclk", "din", "rst"), List.of(), null);
            case 9 -> {
                var t = MsiComponent.Type.ROM256X8;
                yield new Pins(name(function), t.inputs, t.outputs, null);
            }
            default -> new Pins(name(function), List.of(), List.of(), "there is no chip " + function);
        };
    }

    /**
     * The component running a chip, or null with {@link #pins}' error. {@code params} are the first segment's,
     * {@code rom16} the 16-byte ROM's words (from its "w0".."w15" parameters).
     */
    public static DigitalComponent create(int function, Map<String, Double> params, String text, int[] rom16) {
        double delay = params.getOrDefault("delay", 10e-9);
        return switch (function) {
            case 0, 1, 2, 3, 4 -> new MsiComponent(MsiComponent.Type.values()[function], rom16, delay);
            case 5 -> {
                if (!(text != null && design(text) instanceof ChipDesign cd)) yield null;
                Map<String, Double> overrides = new java.util.HashMap<>();
                for (var en : params.entrySet()) {
                    if (en.getKey().startsWith(DESIGN_PARAM)) overrides.put(en.getKey().substring(DESIGN_PARAM.length()), en.getValue());
                }
                yield new DesignComponent(cd, overrides);
            }
            case 6 -> new ClockOscillator(params.getOrDefault("frequency", 10_000.0), params.getOrDefault("ppm", 0.0));
            case 7 -> new SpiAdc(10);
            case 8 -> new DisplayPanel();
            case 9 -> {
                int[] image;
                try {
                    image = MsiComponent.parseHex(text, 256);
                } catch (IllegalArgumentException e) {
                    image = new int[256];
                }
                yield new MsiComponent(MsiComponent.Type.ROM256X8, image, params.getOrDefault("access_time", 120e-9));
            }
            default -> null;
        };
    }

    /** Why a 256-byte ROM's text is not a ROM image, or null if it is. */
    public static String romError(String text) {
        try {
            MsiComponent.parseHex(text, 256);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** The prefix of a chip part's design parameters: "p_lo" overrides the {@code param lo} of its design. */
    public static final String DESIGN_PARAM = "p_";
}
