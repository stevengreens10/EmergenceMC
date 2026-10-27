package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.digital.ChipTypes;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of the EM-8, an 8-bit processor of the player's kind: a design in the chip language, its program in a ROM. */
public final class Em8Exhibits {
    private Em8Exhibits() {
    }

    /**
     * A computer-controlled thermostat: the EM-8 runs the shipped thermostat program from its ROM. It reads the plate's
     * thermistor through the 10-bit ADC, bit by bit over its serial bus (the program clocks it), shows the reading's
     * top bits on LEDs, and switches the heater under the plate through a MOSFET, holding it near 45 °C.
     */
    public static Exhibit thermostat() {
        Exhibit e = new Exhibit("em8thermostat", "Computer thermostat",
                List.of("COMPUTER THERMOSTAT", "An 8-bit CPU runs", "a program: reads the", "plate, drives heater"),
                "A computer controls a machine. The long chip is the EM-8, an 8-bit processor written in the chip "
                        + "language; the ROM below it holds its program, written in EM-8 assembly — right-click the ROM to "
                        + "read or change it, the processor to see its registers run. Four times a clock tick it fetches "
                        + "and executes an instruction: it selects the ADC and clocks its reading of the plate's thermistor "
                        + "in bit by bit through its input port, shows the reading's top four bits on the LEDs, and compares "
                        + "it with its setpoint: colder, and it turns the heater under the plate on through the MOSFET (the "
                        + "fifth LED); warm enough, off. The plate settles near 45 °C. Change SET in the program and write "
                        + "it to the ROM: the plate follows.");
        e.plots = 2;
        String si = "silicon", cu = "copper";
        e.name("battery", e.add(BATTERY, "pvc", 1, 1, 1, Dir.EAST, "chemistry", 1, "cells", 4, "capacity", 5));
        e.name("clock", e.add(CHIP, si, 4, 1, 1, Dir.EAST, "function", 6, "frequency", 100_000));
        e.add(CHIP, si, 5, 1, 1, Dir.EAST, "function", 6, "frequency", 100_000);
        // the processor (z 5) and its program ROM (z 9), the address bus straight between them
        String cpu = ChipLibrary.source("em8");
        for (int k = 0; k < 17; k++) {
            int c = e.add(CHIP, si, 1 + k, 1, 5, Dir.EAST, "function", ChipTypes.DESIGN);
            e.text(c, cpu);
            if (k == 0) e.name("cpu", c);
        }
        String program = ChipLibrary.rom("thermostat");
        for (int k = 0; k < 9; k++) {
            int c = e.add(CHIP, si, 1 + k, 1, 9, Dir.EAST, "function", ChipTypes.ROM256);
            e.text(c, program);
            if (k == 0) e.name("rom", c);
        }
        // the ADC
        for (int k = 0; k < 4; k++) {
            int a = e.add(CHIP, si, 20 + k, 1, 5, Dir.EAST, "function", 7);
            if (k == 0) e.name("adc", a);
        }
        // the plate: a heater under a thin aluminium plate, the thermistor clamped on top; its MOSFET and divider
        int x0 = 28;
        e.name("heater", e.add(RESISTOR, "alumina", x0, 0, 9, Dir.SOUTH, "resistance", 6));
        e.name("plate", e.add(BLOCK, "aluminium_6061", x0, 1, 9, Dir.UP, "fill", 0.05));
        e.name("thermistor", e.add(RESISTOR, "alumina", x0, 2, 9, Dir.SOUTH, "resistance", 10_000, "ntc_beta", 3950));
        for (String n : new String[]{"heater", "plate", "thermistor"}) e.init(e.named.get(n), new Exhibit.Initial(314, 0, 0));
        e.add(RESISTOR, "alumina", x0 + 2, 2, 11, Dir.SOUTH, "resistance", 5_100);
        e.name("mosfet", e.add(TRANSISTOR, si, x0 + 4, 0, 10, Dir.WEST, "type", 2, "transconductance", 2.0));
        // the LEDs: the reading's top bits (o7..o4) and the heater (o3)
        int[] ledX = {19, 17, 15, 13, 11}; // o3, o4, o5, o6, o7
        for (int k = 0; k < 5; k++) {
            int lx = ledX[k];
            e.name("led" + (k + 3), e.add(LED, si, lx, 1, 13, Dir.SOUTH, "wavelength", k == 0 ? 610 : 525, "face", 1, "beam", 20));
            e.add(RESISTOR, "alumina", lx, 1, 14, Dir.SOUTH, "resistance", 470);
        }
        Router r = new Router(e, 0, 0, 0, 35, 5, 15);
        r.pin("vdd", 2, 1, 1).pin("gnd", 0, 1, 1);
        r.pin("vdd", 5, 1, 2).pin("gnd", 5, 1, 0);                    // the clock
        r.pin("vdd", 17, 1, 6).pin("gnd", 17, 1, 4);                  // the processor
        r.pin("vdd", 9, 1, 10).pin("gnd", 9, 1, 8);                   // the ROM
        r.pin("vdd", 23, 1, 6).pin("gnd", 23, 1, 4);                  // the ADC
        r.pin("vdd", x0, 0, 8).pin("vdd", x0, 2, 8);                  // the heater's and thermistor's tops
        r.pin("gnd", x0 + 2, 2, 12).pin("gnd", x0 + 5, 0, 10);        // the divider's foot, the MOSFET's source
        r.pin("clk", 4, 1, 2).pin("clk", 1, 1, 4);
        for (int b = 0; b < 8; b++) r.pin("a" + b, 1 + b, 1, 6).pin("a" + b, 1 + b, 1, 8);
        for (int b = 0; b < 8; b++) r.pin("d" + b, 1 + b, 1, 10).pin("d" + b, 3 + b, 1, 4);
        r.pin("cs", 9, 1, 6).pin("cs", 20, 1, 4);
        r.pin("sclk", 10, 1, 6).pin("sclk", 21, 1, 4);
        r.pin("dout", 20, 1, 6).pin("dout", 11, 1, 4);
        r.pin("gate", 11, 1, 6).pin("gate", x0 + 4, 0, 9);
        for (int k = 0; k < 5; k++) {
            r.pin("o" + (k + 3), 12 + k, 1, 6).pin("o" + (k + 3), ledX[k], 1, 12);
            r.pin("gnd", ledX[k], 1, 15);
        }
        r.pin("sense", x0, 2, 10).pin("sense", x0 + 2, 2, 10).pin("sense", 22, 1, 4);
        r.pin("heaterLow", x0, 0, 10).pin("heaterLow", x0 + 3, 0, 10);
        r.route();
        return e.view(17, 9, 24, 32);
    }
}
