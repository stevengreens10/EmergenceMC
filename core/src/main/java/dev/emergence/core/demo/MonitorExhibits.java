package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.digital.ChipTypes;
import dev.emergence.core.sim.elements.MsiChip;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of measurement and display: sensors, converters, chips of the player's design, screens. */
public final class MonitorExhibits {
    /** The counter display design's font: three columns of five bits per digit, bit 0 the top row. */
    static final int[] COUNTER_FONT = {0x1F, 0x11, 0x1F, 0x12, 0x1F, 0x10, 0x1D, 0x15, 0x17, 0x15, 0x15, 0x1F, 0x07, 0x04, 0x1F,
            0x17, 0x15, 0x1D, 0x1F, 0x15, 0x1D, 0x01, 0x01, 0x1F, 0x1F, 0x15, 0x1F, 0x17, 0x15, 0x1F};

    /**
     * The count a counter display's screen shows, read off it as a person would: its two big digits (eight pixels a dot,
     * pages 1–5, from columns 36 and 68), each matched against the design's font. −1 if unreadable (garbled, blank).
     */
    public static int readCount(java.util.function.BiPredicate<Integer, Integer> lit) {
        int t = bigDigit(lit, 36), u = bigDigit(lit, 68);
        if (u < 0 || t == -2 || t > 1) return -1;
        return (t == 1 ? 10 : 0) + u;
    }

    /** The big digit whose cell starts at column x0: 0–9, −1 blank, −2 unreadable. */
    static int bigDigit(java.util.function.BiPredicate<Integer, Integer> lit, int x0) {
        int[] cols = new int[3];
        for (int c = 0; c < 3; c++) for (int r = 0; r < 5; r++) if (lit.test(x0 + 8 * c + 4, 8 + 8 * r + 4)) cols[c] |= 1 << r;
        if (cols[0] == 0 && cols[1] == 0 && cols[2] == 0) return -1;
        for (int n = 0; n < 10; n++) if (cols[0] == COUNTER_FONT[3 * n] && cols[1] == COUNTER_FONT[3 * n + 1] && cols[2] == COUNTER_FONT[3 * n + 2]) return n;
        return -2;
    }

    private MonitorExhibits() {
    }

    /**
     * Chart recorder: a sealed air vessel against a hot copper block, a pressure transducer on its top and a
     * thermistor on its far wall. Each sensor feeds a 10-bit SPI ADC; a chart-recorder chip (the shipped design, in
     * the chip language) reads it and sweeps the reading across a 4×2-block OLED screen. One crystal clock drives both.
     */
    public static Exhibit chartRecorder() {
        Exhibit e = new Exhibit("chartrecorder", "Chart recorder",
                List.of("CHART RECORDER", "Pressure (left) and", "temperature (right)", "of a heated vessel"),
                "Two monitors graph a heated vessel. A sealed steel tank of air stands against a copper block at "
                        + "500 K; a pressure transducer on its top (a strain-gauge diaphragm and its amplifier) gives "
                        + "a voltage in proportion to the pressure, and a thermistor on its far wall, in a divider with "
                        + "a 1 kΩ resistor, one that rises as it warms. Each voltage goes into a 10-bit ADC, read bit by "
                        + "bit over a serial bus by a chart-recorder chip — a design written in the chip language, "
                        + "simulated as events, like any chip a player designs — which draws the reading as a trace "
                        + "sweeping across its screen, one column at a time, through the display's controller. Each "
                        + "screen is eight display panels driven by one controller. Probe a chip to see its design's "
                        + "registers; edit the design in the chip designer.");
        e.plots = 2; // the vessel stands past the first plot
        String si = "silicon";
        String design = ChipLibrary.source("chart_recorder");
        // ---- the vessel at the back: copper at 500 K against a sealed steel tank of air
        for (int y = 0; y <= 1; y++) {
            int hot = e.add(BLOCK, "copper", 16, y, 1, Dir.UP);
            e.init(hot, new Exhibit.Initial(500, 0, 0));
            int t = e.add(TANK, "steel_1045", 17, y, 1, Dir.UP);
            if (y == 0) e.name("tank", t);
        }
        e.name("heater", 0);
        // the transducer on top of the tank (port down), its leads north and south; the thermistor on the far wall
        e.name("psensor", e.add(PRESSURE_SENSOR, "stainless_304", 17, 2, 1, Dir.UP, "pmin", 0, "pmax", 1e5, "lead_axis", 2));
        e.name("thermistor", e.add(RESISTOR, "alumina", 18, 1, 1, Dir.SOUTH, "resistance", 10_000, "ntc_beta", 3950));
        e.add(RESISTOR, "alumina", 18, 1, 3, Dir.SOUTH, "resistance", 1_000);
        // ---- the supply: a 5 V pack of four NiMH cells (+ on its east end)
        e.name("battery", e.add(BATTERY, "pvc", 9, 1, 1, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 2));
        // ---- the clock (2 kHz: a sweep of the screen in about 45 s)
        e.name("clock", e.add(CHIP, si, 7, 1, 5, Dir.EAST, "function", 6, "frequency", 2000));
        e.add(CHIP, si, 8, 1, 5, Dir.EAST, "function", 6, "frequency", 2000);
        // ---- two channels: ADC (z 5), recorder (z 9), display controller (z 13) and its screen (z 14)
        int[] xs = {1, 11};
        double[][] scale = {{100, 32}, {64, 18}}; // {lo, gain}: pressure 0–0.65 bar gauge; temperature ambient to ~480 K
        for (int ch = 0; ch < 2; ch++) {
            int x = xs[ch];
            String n = ch == 0 ? "P" : "T";
            for (int k = 0; k < 4; k++) {
                int a = e.add(CHIP, si, x + k, 1, 5, Dir.EAST, "function", 7);
                if (k == 0) e.name("adc" + n, a);
            }
            for (int k = 0; k < 7; k++) {
                int r = e.add(CHIP, si, x + k, 1, 9, Dir.EAST, "function", 5, "p_lo", scale[ch][0], "p_gain", scale[ch][1]);
                e.text(r, design);
                if (k == 0) e.name("recorder" + n, r);
            }
            for (int k = 0; k < 6; k++) {
                int d = e.add(CHIP, si, x + k, 1, 13, Dir.EAST, "function", 8);
                if (k == 0) e.name("controller" + n, d);
            }
            for (int y = 1; y <= 2; y++) {
                for (int k = 0; k < 4; k++) {
                    int s = e.add(DISPLAY, "glass", x + k, y, 14, Dir.SOUTH);
                    if (y == 1 && k == 0) e.name("screen" + n, s);
                }
            }
        }
        Router r = new Router(e, 0, 0, 0, 19, 5, 15);
        for (int x : xs) r.keepClear(x, 0, 15, x + 3, 3, 15); // the view of each screen
        // supply: the battery's + (10, 1, 1) and − (8, 1, 1) to every chip's V_DD (+lead side of its last segment) and
        // GND (−lead side), the sensors, and the displays' reset pins (held high)
        r.pin("vdd", 10, 1, 1).pin("gnd", 8, 1, 1);
        r.pin("vdd", 8, 1, 6).pin("gnd", 8, 1, 4);                       // the clock
        for (int x : xs) {
            r.pin("vdd", x + 3, 1, 6).pin("gnd", x + 3, 1, 4);           // ADC
            r.pin("vdd", x + 6, 1, 10).pin("gnd", x + 6, 1, 8);          // recorder
            r.pin("vdd", x + 5, 1, 14).pin("gnd", x + 5, 1, 12);         // controller
            r.pin("vdd", x + 4, 1, 12);                                  // its rst, tied high
            r.pin("clk", x, 1, 8);                                       // the recorder's clock
        }
        r.pin("clk", 7, 1, 6);
        r.pin("vdd", 17, 2, 2).pin("gnd", 17, 2, 0);                     // the transducer
        r.pin("vinP", 17, 3, 1).pin("vinP", xs[0] + 2, 1, 4);
        r.pin("vdd", 18, 1, 0);                                          // the thermistor's top
        r.pinShared("vinT", 18, 1, 2).pin("vinT", xs[1] + 2, 1, 4);           // its bottom, the 10 kΩ's top: the divider
        r.pin("gnd", 18, 1, 4);
        for (int ch = 0; ch < 2; ch++) {
            int x = xs[ch];
            String n = ch == 0 ? "P" : "T";
            r.pin("acs" + n, x, 1, 10).pin("acs" + n, x, 1, 4);
            r.pin("asclk" + n, x + 1, 1, 10).pin("asclk" + n, x + 1, 1, 4);
            r.pin("miso" + n, x, 1, 6).pin("miso" + n, x + 1, 1, 8);
            r.pin("cs" + n, x + 2, 1, 10).pin("cs" + n, x, 1, 12);
            r.pin("dc" + n, x + 3, 1, 10).pin("dc" + n, x + 1, 1, 12);
            r.pin("sclk" + n, x + 4, 1, 10).pin("sclk" + n, x + 2, 1, 12);
            r.pin("din" + n, x + 5, 1, 10).pin("din" + n, x + 3, 1, 12);
        }
        r.route();
        return e.view(8, 5, 22, 20);
    }

    /**
     * Counter display: a 1 Hz crystal clocks a 4-bit counter chip (a 74HC163); its outputs light four LEDs in binary
     * and go into a chip of the player's design that draws the count on a screen as a decimal number, with the binary
     * underneath, reading the digits' shapes from a font in a ROM chip.
     */
    public static Exhibit counterDisplay() {
        Exhibit e = new Exhibit("counterdisplay", "Counter display",
                List.of("COUNTER DISPLAY", "A 4-bit counter,", "its count in binary", "and on the screen"),
                "A crystal clock ticks once a second into a 4-bit counter chip, which counts 0 to 15 and round again. "
                        + "Its four outputs light the LEDs on the right in binary (the most significant bit on the left). "
                        + "The same four wires go into a chip of the player's design, written in the chip language. It "
                        + "works out the count's tens and units and sends the screen's controller a whole new picture, "
                        + "byte by byte over its serial bus, about thirty times a second: the number drawn eight times the "
                        + "font's size, and its binary underneath. The digits' shapes come from the ROM chip beside it, "
                        + "which holds a font of the hex digits 0 to F (three columns of five dots each): for every byte it "
                        + "draws, the display chip puts the address of the font column it needs on six wires and reads the "
                        + "column back on five. Open the ROM in the ROM editor to see the font, or change it; open the "
                        + "display chip in the chip designer to read how it works. Nothing in the screen knows about "
                        + "numbers: it only shows the dots it is sent.");
        e.plots = 2; // the font ROM stands beside the display chip
        String si = "silicon";
        // ---- the supply (+ on its east end) and the two clocks: 1 Hz for the counter, 500 kHz for the display chip
        e.name("battery", e.add(BATTERY, "pvc", 5, 1, 1, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 2));
        e.name("tick", e.add(CHIP, si, 11, 1, 1, Dir.EAST, "function", 6, "frequency", 1));
        e.add(CHIP, si, 12, 1, 1, Dir.EAST, "function", 6, "frequency", 1);
        e.name("clock", e.add(CHIP, si, 12, 1, 5, Dir.EAST, "function", 6, "frequency", 500_000));
        e.add(CHIP, si, 13, 1, 5, Dir.EAST, "function", 6, "frequency", 500_000);
        // ---- the counter (z 5), the display chip (z 9) and its font ROM (z 9, east), the display controller (z 13)
        // and its screen (z 14)
        e.name("counter", ComputerExhibit.chip(e, MsiChip.Type.COUNTER4, 1, 1, 5));
        String design = ChipLibrary.source("counter_display");
        for (int k = 0; k < 11; k++) {
            int c = e.add(CHIP, si, 1 + k, 1, 9, Dir.EAST, "function", ChipTypes.DESIGN);
            e.text(c, design);
            if (k == 0) e.name("chip", c);
        }
        String font = ChipLibrary.rom("hex_font_3x5");
        for (int k = 0; k < 9; k++) {
            int c = e.add(CHIP, si, 14 + k, 1, 9, Dir.EAST, "function", ChipTypes.ROM256);
            e.text(c, font);
            if (k == 0) e.name("rom", c);
        }
        for (int k = 0; k < 6; k++) {
            int d = e.add(CHIP, si, 1 + k, 1, 13, Dir.EAST, "function", 8);
            if (k == 0) e.name("controller", d);
        }
        for (int y = 1; y <= 2; y++) {
            for (int k = 0; k < 4; k++) {
                int sc = e.add(DISPLAY, "glass", 1 + k, y, 14, Dir.SOUTH);
                if (y == 1 && k == 0) e.name("screen", sc);
            }
        }
        // ---- the LEDs, Q3 (the most significant) on the left as seen from the front
        for (int k = 0; k < 4; k++) {
            int x = 14 - 2 * k;
            e.name("led" + k, e.add(LED, si, x, 1, 13, Dir.SOUTH, "wavelength", 630, "face", 1, "beam", 20));
            e.add(RESISTOR, "alumina", x, 1, 14, Dir.SOUTH, "resistance", 470);
        }
        Router r = new Router(e, 0, 0, 0, 25, 6, 15);
        r.keepClear(1, 0, 15, 4, 5, 15); // the view of the screen
        r.pin("vdd", 6, 1, 1).pin("gnd", 4, 1, 1);
        r.pin("vdd", 12, 1, 2).pin("gnd", 12, 1, 0);                 // the 1 Hz clock
        r.pin("vdd", 13, 1, 6).pin("gnd", 13, 1, 4);                 // the 500 kHz clock
        r.pin("vdd", 9, 1, 6).pin("gnd", 9, 1, 4);                   // the counter
        r.pin("vdd", 6, 1, 4).pin("vdd", 7, 1, 4).pin("vdd", 8, 1, 4); // its CLR̄ and LOAD̄ high, EN high (P0–P3 unused)
        r.pin("vdd", 11, 1, 10).pin("gnd", 11, 1, 8);                // the display chip
        r.pin("vdd", 22, 1, 10).pin("gnd", 22, 1, 8);                // the ROM (A6, A7 unused: low)
        r.pin("vdd", 6, 1, 14).pin("gnd", 6, 1, 12);                 // the controller
        r.pin("vdd", 5, 1, 12);                                      // its rst, tied high
        r.pin("tick", 11, 1, 2).pin("tick", 5, 1, 4);
        r.pin("clk", 12, 1, 6).pin("clk", 1, 1, 8);
        for (int k = 0; k < 4; k++) {
            int x = 14 - 2 * k;
            r.pin("q" + k, 1 + k, 1, 6).pin("q" + k, x, 1, 12).pin("q" + k, 2 + k, 1, 8);
            r.pin("gnd", x, 1, 15);
        }
        for (int k = 0; k < 6; k++) r.pin("a" + k, 5 + k, 1, 10).pin("a" + k, 14 + k, 1, 8);  // the font address
        for (int k = 0; k < 5; k++) r.pin("f" + k, 14 + k, 1, 10).pin("f" + k, 6 + k, 1, 8); // the font column
        r.pin("cs", 1, 1, 10).pin("cs", 1, 1, 12);
        r.pin("dc", 2, 1, 10).pin("dc", 2, 1, 12);
        r.pin("sclk", 3, 1, 10).pin("sclk", 3, 1, 12);
        r.pin("din", 4, 1, 10).pin("din", 4, 1, 12);
        r.route();
        return e.view(11, 5, 22, 22);
    }

    /**
     * The counter display's circuit at true scale, in one block of 6.25 cm cells — 1 Hz crystal, 74HC163 counter,
     * four LEDs, the counter-display chip and its font ROM, the display controller — driving a full-size screen of
     * 4 × 2 block-scale panels behind it: the controller stands against the block's north face, and a display
     * controller drives the screen across the face it lies against, whatever the scale (the game's display links).
     * Its counter's outputs and ground leave the block through a full-size ribbon cable on its east face: each on a
     * miniature wire of its own colour touching that face, joined to the ribbon conductor of that colour, tapped off
     * further along by full-size wires of the same colours to four full-size LEDs. Built with the precision tweezers;
     * the screen, ribbon and LEDs as any block-scale parts.
     */
    public static Exhibit miniatureCounterDisplay() {
        Exhibit e = new Exhibit("minicounter", "Miniature counter display",
                List.of("MINIATURE", "Counter display:", "its circuit in one", "block, a big screen"),
                "The counter display's whole circuit at true scale, built with the precision tweezers inside a single "
                        + "block of 6.25 cm cells: the same chips, the same design and font ROM, a 4-bit counter ticking "
                        + "once a second and its LEDs. Its display controller stands against the back of the block, and "
                        + "drives the full-size screen behind it across that face: a controller drives the screen it "
                        + "touches, whatever the scale. The counter's four outputs and ground leave by the east face on "
                        + "miniature wires of five colours, into a full-size ribbon cable: each wire joins the ribbon's "
                        + "conductor of its own colour. Further along, full-size wires of the same colours tap the ribbon "
                        + "and light the four big LEDs in binary. (A full-size part is the same 6.25 cm part as a miniature "
                        + "one, drawn sixteen times larger: here the circuit is drawn at its true size.)");
        e.miniature = true;
        String si = "silicon";
        // ---- the back row: the supply (+ east), the 1 Hz clock, the 500 kHz clock
        e.name("battery", e.add(BATTERY, "pvc", 1, 0, 1, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 0.3));
        e.name("tick", e.add(CHIP, si, 4, 0, 1, Dir.EAST, "function", 6, "frequency", 1));
        e.add(CHIP, si, 5, 0, 1, Dir.EAST, "function", 6, "frequency", 1);
        e.name("clock", e.add(CHIP, si, 8, 0, 1, Dir.EAST, "function", 6, "frequency", 500_000));
        e.add(CHIP, si, 9, 0, 1, Dir.EAST, "function", 6, "frequency", 500_000);
        // ---- the counter (z 4), the display chip (z 8), the ROM (z 12), the controller (z 14) and the screen (z 15)
        e.name("counter", ComputerExhibit.chip(e, MsiChip.Type.COUNTER4, 1, 0, 4));
        String design = ChipLibrary.source("counter_display");
        for (int k = 0; k < 11; k++) {
            int c = e.add(CHIP, si, k, 0, 8, Dir.EAST, "function", ChipTypes.DESIGN);
            e.text(c, design);
            if (k == 0) e.name("chip", c);
        }
        String font = ChipLibrary.rom("hex_font_3x5");
        for (int k = 0; k < 9; k++) {
            int c = e.add(CHIP, si, 6 + k, 0, 12, Dir.EAST, "function", ChipTypes.ROM256);
            e.text(c, font);
            if (k == 0) e.name("rom", c);
        }
        // the display controller standing upright against the block's north face (its leads east and west), and the
        // full-size screen behind it, 4 × 2 blocks facing the viewer
        for (int k = 0; k < 6; k++) {
            int d = e.add(CHIP, si, 13, k, 0, Dir.UP, "function", 8);
            if (k == 0) e.name("controller", d);
        }
        for (int y = 0; y <= 1; y++) {
            for (int dx = -2; dx <= 1; dx++) {
                int sc = e.blockPart(DISPLAY, "glass", dx, y, -1, Dir.SOUTH);
                if (y == 0 && dx == 0) e.namedBlock.put("screen", sc);
            }
        }
        // ---- out by the east face into a full-size ribbon: Q0–Q3 on colours 1–4, ground on 0; the ribbon runs east,
        // tapped every other block by full-size wires of those colours to the LEDs (Q3 on the left, seen from the
        // south), their ground returning over them onto the ribbon's end
        for (int dx = 1; dx <= 7; dx++) {
            int rb = e.blockPart(RIBBON, "copper", dx, 0, 0, Dir.EAST);
            if (dx == 1) e.namedBlock.put("ribbon", rb);
        }
        String cu = "copper";
        for (int k = 0; k < 4; k++) {
            int dx = 1 + 2 * (3 - k);
            e.blockPart(INSULATED_WIRE, cu, dx, 0, 1, Dir.UP, "color", k + 1);
            e.namedBlock.put("led" + k, e.blockPart(LED, si, dx, 0, 2, Dir.SOUTH, "wavelength", 630, "face", 1, "beam", 20));
            e.blockPart(RESISTOR, "alumina", dx, 0, 3, Dir.SOUTH, "resistance", 470);
        }
        for (int dx = 1; dx <= 7; dx++) e.blockPart(INSULATED_WIRE, cu, dx, 0, 4, Dir.UP, "color", 0);
        for (int dz = 0; dz <= 4; dz++) e.blockPart(INSULATED_WIRE, cu, 7, 1, dz, Dir.UP, "color", 0);
        Router r = new Router(e, 0, 0, 0, 15, 6, 15);
        // the east face carries only the five wires into the ribbon (any other wire touching it would join a conductor)
        r.keepClear(15, 1, 0, 15, 6, 15).keepClear(15, 0, 0, 15, 0, 3).keepClear(15, 0, 8, 15, 0, 8).keepClear(15, 0, 10, 15, 0, 15);
        for (int k = 0; k < 4; k++) r.colour("q" + k, k + 1);
        r.colour("gnd", 0);
        r.pin("vdd", 2, 0, 1).pin("gnd", 0, 0, 1);
        r.pin("vdd", 5, 0, 2).pin("gnd", 5, 0, 0);                   // the 1 Hz clock
        r.pin("vdd", 9, 0, 2).pin("gnd", 9, 0, 0);                   // the 500 kHz clock
        r.pin("vdd", 9, 0, 5).pin("gnd", 9, 0, 3);                   // the counter
        r.pin("vdd", 6, 0, 3).pin("vdd", 7, 0, 3).pin("vdd", 8, 0, 3); // CLR̄, LOAD̄, EN high
        r.pin("vdd", 10, 0, 9).pin("gnd", 10, 0, 7);                 // the display chip
        r.pin("vdd", 14, 0, 13).pin("gnd", 14, 0, 11);               // the ROM
        r.pin("vdd", 14, 5, 0).pin("gnd", 12, 5, 0);                 // the controller
        r.pin("vdd", 12, 4, 0);                                      // its rst, tied high
        r.pin("tick", 4, 0, 2).pin("tick", 5, 0, 3);
        r.pin("clk", 8, 0, 2).pin("clk", 0, 0, 7);
        for (int k = 0; k < 4; k++) r.pin("q" + k, 1 + k, 0, 5).pin("q" + k, 15, 0, 4 + k).pin("q" + k, 1 + k, 0, 7);
        r.pin("gnd", 15, 0, 9);
        for (int k = 0; k < 6; k++) r.pin("a" + k, 4 + k, 0, 9).pin("a" + k, 6 + k, 0, 11);
        for (int k = 0; k < 5; k++) r.pin("f" + k, 6 + k, 0, 13).pin("f" + k, 5 + k, 0, 7);
        for (int k = 0; k < 4; k++) r.pin(new String[]{"cs", "dc", "sclk", "din"}[k], k, 0, 9).pin(new String[]{"cs", "dc", "sclk", "din"}[k], 12, k, 0);
        r.route();
        return e.view(9.5, 3.4, 15.0, 20);
    }
}
