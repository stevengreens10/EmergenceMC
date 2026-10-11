package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of sound. */
public final class AcousticsExhibits {
    private AcousticsExhibits() {
    }

    /**
     * One side of a moving-coil transducer facing along ±x at height 3, centred on z = 7: a thin aluminium plate (3×3
     * cells) on a nylon rod that slides in a linear bearing, a coil on the rod in front of a fixed magnet (its flexible
     * leads out to the sides), and a spring between the plate and a post. {@code dir} = +1 faces east (the plate at
     * x0 + 5), −1 west (mirrored).
     */
    static void transducer(Exhibit e, String name, int x0, int dir, double spring) {
        String st = "steel_1045";
        Dir along = dir > 0 ? Dir.EAST : Dir.WEST;
        int xb = x0, xm = x0 + 2 * dir, xc = x0 + 3 * dir, xr = x0 + 4 * dir, xp = x0 + 5 * dir, xs = x0 + 6 * dir, xa = x0 + 7 * dir;
        e.name(name + "Plate", e.fill(BLOCK, "aluminium_6061", xp, 2, 6, xp, 4, 8, Dir.EAST, "fill", 0.03));
        // a nylon rod: it runs through the coil, whose end faces would otherwise short through it
        e.name(name + "Rod", e.fill(SHAFT, "nylon_66", Math.min(xb, xr), 3, 7, Math.max(xb, xr), 3, 7, Dir.EAST, "diameter", 0.008));
        e.add(LINEAR_BEARING, st, xb, 3, 7, Dir.EAST, "travel", 0.002);
        e.fill(BLOCK, st, xb, 0, 7, xb, 2, 7, Dir.UP);
        e.name(name + "Magnet", e.add(MAGNET, "ndfeb_n42", xm, 3, 7, along));
        e.fill(BLOCK, st, xm, 0, 7, xm, 2, 7, Dir.UP);
        e.name(name + "Coil", e.add(COIL, "copper", xc, 3, 7, Dir.EAST, "turns", 200, "wire_diameter", 0.4e-3, "lead_axis", 2));
        // the moving coil's flexible leads
        e.add(WIRE, "copper", xc, 3, 6, Dir.UP, "diameter", 0.3e-3, "flexible", 1);
        e.add(WIRE, "copper", xc, 3, 8, Dir.UP, "diameter", 0.3e-3, "flexible", 1);
        e.name(name + "Spring", e.add(SPRING, st, xs, 3, 7, Dir.EAST, "wire", spring, "turns", 3));
        e.fill(BLOCK, st, xa, 0, 7, xa, 3, 7, Dir.UP);
    }

    /**
     * A loudspeaker and a microphone 0.6 m apart. A Schmitt-trigger oscillator switches current through the
     * speaker's coil; the coil, in its magnet's field, pushes the plate to and fro, and the moving plate pushes the air.
     * The sound reaches an identical plate tuned to the same note, which the air sets vibrating; its coil, moving in its
     * magnet's field, generates a voltage.
     */
    public static Exhibit loudspeaker() {
        return loudspeaker(true);
    }

    static Exhibit loudspeaker(boolean amplifier) {
        Exhibit e = new Exhibit("loudspeaker", "Loudspeaker and microphone",
                List.of("SOUND", "A loudspeaker sings,", "the air carries it,", "a microphone hears"),
                "Close the switch: a Schmitt-trigger oscillator switches current through the loudspeaker's coil 250 "
                        + "times a second. The coil sits in its magnet's field, so the current pushes it, and the light "
                        + "aluminium plate it carries, to and fro — a few hundredths of a millimetre, on a spring tuned to "
                        + "the note. The moving plate pushes the air: a sound wave, travelling at 343 m/s (about 70 dB "
                        + "here). Six hundred millimetres away an identical plate, tuned to the same note, is set "
                        + "vibrating by the air it reaches; its coil, moving in its magnet's field, makes a few tens of "
                        + "microvolts (Faraday). Two op-amp stages amplify that some 45 000 times, a diode and capacitor "
                        + "catch the peaks, and a comparator lights the green LED: the microphone has heard. Open the "
                        + "switch and it goes dark. (The note itself is not played in the game; the simulation runs "
                        + "slowly here, resolving every vibration.)");
        e.plots = 3;
        transducer(e, "spk", 1, +1, 0.0097);
        transducer(e, "mic", 21, -1, 0.0097);
        int f = 1;
        // supplies: 12 V for the speaker, 5 V for the logic
        int b12 = e.add(CAPACITOR, "alumina", 2, f, 13, Dir.EAST, "capacitance", 50);
        e.init(b12, new Exhibit.Initial(Double.NaN, 0, 12));
        int b5 = e.add(CAPACITOR, "alumina", 2, f, 11, Dir.EAST, "capacitance", 10);
        e.init(b5, new Exhibit.Initial(Double.NaN, 0, 5));
        e.name("switch", e.add(SWITCH, "brass", 6, f, 13, Dir.EAST));
        // the oscillator: a Schmitt inverter, its output fed back to its input through R, a capacitor to ground
        int sx = 10, sz = 11;
        e.name("osc", e.add(LOGIC, "silicon", sx, f, sz, Dir.EAST, "function", 7));
        e.add(RESISTOR, "alumina", sx, f, sz - 3, Dir.EAST, "resistance", 49_000);
        e.add(CAPACITOR, "alumina", sx - 3, f, sz, Dir.EAST, "capacitance", 0.1e-6);
        // the gate is fed through an RC (10 kΩ, 47 nF): the coil's current ramps over half a millisecond, not 10 ns
        e.add(RESISTOR, "alumina", 14, f, 9, Dir.EAST, "resistance", 10_000);
        e.add(CAPACITOR, "alumina", 17, f, 11, Dir.EAST, "capacitance", 47e-9);
        // the driver: a MOSFET switching the coil, through 22 Ω, a diode across the coil
        e.name("fet", e.add(TRANSISTOR, "silicon", 14, f, 13, Dir.EAST, "type", 2, "transconductance", 2.0));
        e.add(RESISTOR, "alumina", 10, f, 15, Dir.EAST, "resistance", 22);
        e.add(DIODE, "silicon", 14, f, 15, Dir.EAST);
        if (amplifier) {
            // the microphone's amplifier, on 12 V: a half-supply reference (two 10 kΩ, 10 µF); two non-inverting stages
            // each of gain 1 + 100 kΩ/470 Ω ≈ 214 at the note (their 22 µF capacitors keep the DC gain at one); a
            // peak detector (τ = 0.1 s); and a comparator lighting the LED when the peaks pass 7.1 V
            e.add(RESISTOR, "alumina", 24, f, 1, Dir.EAST, "resistance", 10_000);
            e.add(RESISTOR, "alumina", 28, f, 1, Dir.EAST, "resistance", 10_000);
            // (charged as they would be after the amplifier has been on a while: no start-up transient)
            e.init(e.add(CAPACITOR, "alumina", 32, f, 1, Dir.EAST, "capacitance", 10e-6), new Exhibit.Initial(Double.NaN, 0, 6));
            for (int k = 0; k < 2; k++) {
                int x0 = 24 + 12 * k;
                e.name("amp" + k, e.add(OPAMP, "silicon", x0, f, 6, Dir.EAST));
                e.add(RESISTOR, "alumina", x0 + 4, f, 4, Dir.EAST, "resistance", 100_000);  // out → in−
                e.add(RESISTOR, "alumina", x0 + 4, f, 8, Dir.EAST, "resistance", 470);      // in− → capacitor
                e.add(CAPACITOR, "alumina", x0 + 8, f, 8, Dir.EAST, "capacitance", 22e-6);  // → reference
            }
            e.add(DIODE, "silicon", 39, f, 11, Dir.EAST);
            e.name("hold", e.add(CAPACITOR, "alumina", 43, f, 11, Dir.EAST, "capacitance", 1e-6));
            e.init(e.named.get("hold"), new Exhibit.Initial(Double.NaN, 0, 5.4));
            e.add(RESISTOR, "alumina", 43, f, 13, Dir.EAST, "resistance", 100_000);
            e.name("comparator", e.add(OPAMP, "silicon", 48, f, 11, Dir.EAST));
            e.add(RESISTOR, "alumina", 48, f, 14, Dir.EAST, "resistance", 6_800);
            e.add(RESISTOR, "alumina", 52, f, 14, Dir.EAST, "resistance", 10_000);
            e.add(RESISTOR, "alumina", 52, f, 8, Dir.EAST, "resistance", 1_000);
            e.name("lit", e.add(LED, "silicon", 52, f, 11, Dir.EAST, "wavelength", 565, "face", 1, "beam", 20));
        } else {
            e.name("micLoad", e.add(RESISTOR, "alumina", 26, f, 13, Dir.EAST, "resistance", 1000));
        }
        Router r = new Router(e, 0, 0, 0, 55, 7, 15);
        // nothing may touch the moving parts or stand between the plates
        r.keepClear(1, 2, 5, 3, 5, 9).keepClear(5, 2, 5, 17, 5, 9).keepClear(19, 2, 5, 21, 5, 9);
        for (int x : new int[]{4, 18}) r.keepClear(x, 2, 5, x, 2, 9).keepClear(x, 4, 5, x, 5, 9);
        r.pin("v12", 3, f, 13).pin("v12", 5, f, 13);
        r.pin("sw", 7, f, 13).pin("sw", 9, f, 15).pin("sw", 15, f, 15);
        r.pin("coilP", 11, f, 15).pin("coilP", 4, 3, 9);
        r.pin("coilN", 4, 3, 5).pin("coilN", 15, f, 13).pin("coilN", 13, f, 15);
        r.pin("gnd", 1, f, 13).pin("gnd", 1, f, 11).pin("gnd", 13, f, 13).pin("gnd", sx - 4, f, sz).pin("gnd", sx, f, sz - 1);
        r.pin("v5", 3, f, 11).pin("v5", sx, f, sz + 1);
        r.pin("oscIn", sx - 2, f, sz).pin("oscIn", sx - 1, f, sz).pin("oscIn", sx - 1, f, sz - 3);
        r.pin("oscOut", sx + 1, f, sz).pin("oscOut", sx + 1, f, sz - 3).pin("oscOut", 13, f, 9);
        r.pin("gate", 15, f, 9).pin("gate", 18, f, 11).pin("gate", 14, f, 12);
        r.pin("gnd", 16, f, 11);
        if (amplifier) {
            r.pin("v12", 23, f, 1).pin("v12", 24, f, 7).pin("v12", 36, f, 7).pin("v12", 48, f, 12).pin("v12", 47, f, 14);
            r.pin("gnd", 29, f, 1).pin("gnd", 31, f, 1).pin("gnd", 24, f, 5).pin("gnd", 36, f, 5).pin("gnd", 42, f, 11)
                    .pin("gnd", 48, f, 10).pin("gnd", 53, f, 14).pin("gnd", 53, f, 11);
            r.pin("mid", 25, f, 1).pin("mid", 27, f, 1).pin("mid", 33, f, 1).pin("mid", 18, 3, 5).pin("mid", 33, f, 8)
                    .pin("mid", 45, f, 8).pin("mid", 42, f, 13);
            r.pin("micB", 18, 3, 9).pin("micB", 23, f, 6);
            for (int k = 0; k < 2; k++) {
                int x0 = 24 + 12 * k;
                r.pin("n" + k, x0, 0, 6).pin("n" + k, x0 + 3, f, 4).pin("n" + k, x0 + 3, f, 8);
                r.pin("g" + k, x0 + 5, f, 8).pin("g" + k, x0 + 7, f, 8);
            }
            r.pin("o0", 25, f, 6).pin("o0", 29, f, 4).pin("o0", 35, f, 6);
            r.pin("o1", 37, f, 6).pin("o1", 41, f, 4).pin("o1", 38, f, 11);
            r.pin("pk", 40, f, 11).pin("pk", 44, f, 11).pin("pk", 44, f, 13).pin("pk", 47, f, 11);
            r.pin("ref", 49, f, 14).pin("ref", 51, f, 14).pin("ref", 48, 0, 11);
            r.pin("cmp", 49, f, 11).pin("cmp", 51, f, 8);
            r.pin("ledA", 53, f, 8).pin("ledA", 51, f, 11);
        } else {
            r.pin("micA", 18, 3, 5).pin("micA", 25, f, 13);
            r.pin("micB", 18, 3, 9).pin("micB", 27, f, 13);
        }
        r.route();
        return e.view(27, 14, 27, 36, 180);
    }
}
