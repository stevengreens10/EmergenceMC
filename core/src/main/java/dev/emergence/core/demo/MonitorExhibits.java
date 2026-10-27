package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.digital.ChipTypes;
import dev.emergence.core.sim.elements.MsiChip;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of measurement and display: sensors, converters, chips of the player's design, screens. */
public final class MonitorExhibits {
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
}
