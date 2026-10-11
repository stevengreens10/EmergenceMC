package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of light: an optical light barrier on a turning vane, and a solar panel. */
public final class OpticsExhibits {
    private OpticsExhibits() {
    }

    /**
     * A light barrier: an infrared LED shines up to a photodiode; a vane on a hand-cranked rotor cuts the beam once
     * per turn. The photocurrent through 2.2 MΩ makes a voltage that a Schmitt trigger squares up; its output drives
     * an indicator LED and clocks a D flip-flop wired to toggle — the counting stage of an optical encoder.
     */
    public static Exhibit lightBarrier() {
        Exhibit e = new Exhibit("lightbarrier", "Light barrier",
                List.of("LIGHT BARRIER", "IR LED -> photodiode.", "Crank: the vane", "cuts the beam"),
                "An infrared LED (850 nm) shines straight up at a silicon photodiode four blocks above. Light that "
                        + "arrives frees electrons in the junction: about 0.55 A of photocurrent per watt. A few microamps "
                        + "through 2.2 MΩ hold the Schmitt trigger's input high; turn the crank and the steel vane on the "
                        + "rotor cuts the beam once per turn, the input drops, and the trigger's output flips — lighting "
                        + "the yellow LED and clocking a flip-flop that toggles the green one: an optical encoder from "
                        + "parts. Glass would not stop the beam; any opaque part does.");
        e.plots = 2; // the toggle flip-flop reaches past a single plot
        String st = "steel_1045";
        int sx = 4, sz = 6;
        // rotor: vertical shaft on a bearing, crank on top, a nylon hub with a steel vane reaching over the beam
        int shaft = e.fill(SHAFT, st, sx, 1, sz, sx, 4, sz, Dir.UP, "diameter", 0.012);
        e.name("rotor", shaft);
        e.add(BEARING, st, sx, 1, sz, Dir.UP);
        e.fill(BLOCK, st, sx, 0, sz - 1, sx, 1, sz - 1, Dir.UP);
        e.fill(BLOCK, st, sx, 0, sz + 1, sx, 1, sz + 1, Dir.UP);
        e.add(BLOCK, "nylon_66", sx, 3, sz, Dir.UP, "fill", 0.5);
        e.fill(BLOCK, st, sx + 1, 3, sz, sx + 2, 3, sz, Dir.UP, "fill", 0.2);
        e.name("crank", e.add(CRANK, st, sx, 4, sz, Dir.UP, "radius", 0.12));
        // the beam: IR LED below the vane's path, photodiode above it facing down
        e.name("irled", e.add(LED, "silicon", sx + 2, 1, sz, Dir.EAST, "wavelength", 850, "efficiency", 0.3, "beam", 20));
        e.name("pd", e.add(PHOTODIODE, "silicon", sx + 2, 5, sz, Dir.EAST, "face", 0));
        // electronics on the floor to the east
        int bat = e.add(CAPACITOR, "alumina", 12, 1, 2, Dir.EAST, "capacitance", 10);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        e.add(RESISTOR, "alumina", 9, 1, 2, Dir.EAST, "resistance", 150);       // IR LED current
        e.add(RESISTOR, "alumina", 9, 1, 10, Dir.EAST, "resistance", 2.2e6);    // photodiode load
        e.name("schmitt", e.add(LOGIC, "silicon", 13, 1, 10, Dir.EAST, "function", 7));
        e.name("toggle", e.add(LOGIC, "silicon", 17, 1, 10, Dir.EAST, "function", 8));
        e.name("beamLed", e.add(LED, "silicon", 13, 1, 13, Dir.EAST, "wavelength", 590, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", 16, 1, 13, Dir.EAST, "resistance", 470);
        e.name("countLed", e.add(LED, "silicon", 17, 1, 6, Dir.EAST, "wavelength", 565, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", 14, 1, 6, Dir.EAST, "resistance", 470);
        Router r = new Router(e, 0, 0, 0, 19, 7, 14);
        r.pin("vdd", 13, 1, 2).pin("vdd", 8, 1, 2).pin("vdd", sx + 3, 5, sz)       // supply, IR resistor, PD cathode
                .pin("vdd", 13, 1, 11).pin("vdd", 17, 1, 11);                       // chip supplies
        r.pin("irA", 10, 1, 2).pin("irA", sx + 1, 1, sz);                            // resistor → IR LED anode
        r.pin("gnd", 11, 1, 2).pin("gnd", sx + 3, 1, sz).pin("gnd", 10, 1, 10)       // supply −, IR LED cathode, load
                .pin("gnd", 13, 1, 9).pin("gnd", 17, 1, 9).pin("gnd", 17, 1, 13).pin("gnd", 13, 1, 6);
        r.pin("sense", sx + 1, 5, sz).pin("sense", 8, 1, 10).pin("sense", 12, 1, 10); // PD anode → load, Schmitt in
        r.pin("dark", 14, 1, 10).pin("dark", 17, 0, 10).pin("dark", 12, 1, 13);     // Schmitt out → DFF clock, yellow LED
        r.pin("beamLedK", 14, 1, 13).pin("beamLedK", 15, 1, 13);                    // yellow LED → 470 Ω
        r.pin("q", 18, 1, 10).pin("q", 16, 1, 6);                                    // Q → green LED anode
        r.pin("qbar", 17, 2, 10).pin("qbar", 16, 1, 10);                             // Q̄ → D (toggle)
        r.pin("countK", 18, 1, 6).pin("countK", 15, 1, 6);                           // green LED → 470 Ω
        r.route();
        return e.view(10, 9, 22, 35);
    }

    /**
     * A solar panel: eight large silicon cells face the sky, each one's cathode face against the next one's anode, so they
     * form a series string (≈ 0.6 V per cell open-circuit). In daylight the string lights an LED through 100 Ω; at
     * night, or shaded, it goes dark.
     */
    public static Exhibit solarPanel() {
        Exhibit e = new Exhibit("solar", "Solar panel",
                List.of("SOLAR PANEL", "8 silicon cells", "in series light", "an LED by day"),
                "Each block is one solar cell: a large silicon PN junction facing the sky. Sunlight (up to about 1000 W/m² "
                        + "with the sun overhead, less when it is low or it rains) drives about 0.5 A per watt of light "
                        + "across the junction; open-circuited a cell settles near 0.6 V, where its own diode current "
                        + "balances the photocurrent. The cells touch end to end, + to −, so their voltages add: the "
                        + "string lights the LED through 100 Ω. Cover a cell and the whole string falls — a series "
                        + "string is only as strong as its darkest cell. At night everything is dark.");
        for (int x = 2; x <= 9; x++) {
            int k = e.add(PHOTODIODE, "silicon", x, 1, 6, Dir.EAST, "area", 0.0035, "face", 1);
            if (x == 2) e.name("cell", k);
        }
        e.fill(BLOCK, "aluminium_6061", 2, 0, 6, 9, 0, 6, Dir.UP, "fill", 0.5);
        e.name("led", e.add(LED, "silicon", 6, 1, 10, Dir.EAST, "wavelength", 590, "face", 3, "beam", 6));
        e.add(RESISTOR, "alumina", 10, 1, 10, Dir.EAST, "resistance", 100);
        Router r = new Router(e, 0, 0, 0, 13, 5, 13);
        // a lit cell drives current out of its anode: the string's + end is the first cell's anode side
        r.pin("plus", 1, 1, 6).pin("plus", 5, 1, 10);
        r.pin("ledK", 7, 1, 10).pin("ledK", 9, 1, 10);
        r.pin("minus", 10, 1, 6).pin("minus", 11, 1, 10);
        r.route();
        return e.view(6, 6, 16, 35);
    }

    /**
     * A receiver: the photodiode (anode and cathode pin cells given) feeds a load resistor whose voltage a Schmitt
     * trigger squares up; the trigger's output sinks a green LED's current, so the LED lights while light arrives.
     * Laid out from (x, 1, z): load at x, trigger at x + 4, LED at (x + 4, z + 3), its resistor at (x + 7, z + 3).
     */
    static void receiver(Exhibit e, Router r, int x, int z, double load, int[] anode, int[] cathode) {
        e.add(RESISTOR, "alumina", x, 1, z, Dir.EAST, "resistance", load);
        e.name("schmitt", e.add(LOGIC, "silicon", x + 4, 1, z, Dir.EAST, "function", 7));
        e.name("lit", e.add(LED, "silicon", x + 4, 1, z + 3, Dir.EAST, "wavelength", 565, "face", 3, "beam", 20));
        e.add(RESISTOR, "alumina", x + 7, 1, z + 3, Dir.EAST, "resistance", 470);
        r.pin("vdd", cathode[0], cathode[1], cathode[2]).pin("vdd", x + 4, 1, z + 1).pin("vdd", x + 8, 1, z + 3);
        r.pin("gnd", x - 1, 1, z).pin("gnd", x + 4, 1, z - 1);
        r.pin("sense", anode[0], anode[1], anode[2]).pin("sense", x + 1, 1, z).pin("sense", x + 3, 1, z);
        r.pin("out", x + 5, 1, z).pin("out", x + 5, 1, z + 3);
        r.pin("litA", x + 3, 1, z + 3).pin("litA", x + 6, 1, z + 3);
    }

    /**
     * A periscope: a red LED shines straight up; a mirror leaning 45° over it turns the beam east, over a wall, onto a
     * second 45° mirror that turns it down onto a photodiode. The receiver lights its green LED while the beam arrives.
     */
    public static Exhibit periscope() {
        Exhibit e = new Exhibit("periscope", "Periscope",
                List.of("PERISCOPE", "Two 45° mirrors", "carry the light", "over the wall"),
                "Light leaves a mirror at the angle it arrived (the law of reflection): a mirror leaning at 45° turns a "
                        + "beam through a right angle. The red LED shines straight up; the first mirror sends its light "
                        + "east, over the wall; the second sends it down onto the photodiode, which lights the green LED "
                        + "through a Schmitt trigger. Polished aluminium reflects about 92 % of red light and warms with "
                        + "the rest. Take a mirror away and the light goes on up into the sky: the green LED goes out.");
        String st = "steel_1045";
        e.fill(BLOCK, "oak", 5, 0, 7, 6, 4, 9, Dir.UP);                               // the wall
        e.fill(BLOCK, st, 2, 0, 7, 2, 6, 7, Dir.UP);                                  // columns holding the mirrors
        e.fill(BLOCK, st, 9, 0, 7, 9, 6, 7, Dir.UP);
        e.name("led", e.add(LED, "silicon", 2, 1, 8, Dir.EAST, "wavelength", 630, "efficiency", 0.25, "beam", 30, "face", 1));
        e.name("mirrorA", e.add(MIRROR, "aluminium", 2, 6, 8, Dir.DOWN, "tilt", 45, "tilt_toward", Dir.EAST.ordinal()));
        e.name("mirrorB", e.add(MIRROR, "aluminium", 9, 6, 8, Dir.DOWN, "tilt", 45, "tilt_toward", Dir.WEST.ordinal()));
        e.name("pd", e.add(PHOTODIODE, "silicon", 9, 1, 8, Dir.EAST, "face", 1, "area", 1e-4));
        int bat = e.add(CAPACITOR, "alumina", 14, 1, 12, Dir.EAST, "capacitance", 10);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        e.add(RESISTOR, "alumina", 14, 1, 10, Dir.EAST, "resistance", 150);         // the red LED's current
        Router r = new Router(e, 0, 0, 7, 17, 7, 15);
        r.keepClear(2, 2, 8, 2, 5, 8).keepClear(3, 6, 8, 8, 6, 8).keepClear(9, 2, 8, 9, 5, 8);
        r.pin("vdd", 15, 1, 12).pin("vdd", 15, 1, 10);
        r.pin("gnd", 13, 1, 12).pin("gnd", 3, 1, 8);
        r.pin("ledA", 13, 1, 10).pin("ledA", 1, 1, 8);
        receiver(e, r, 3, 11, 2.2e6, new int[]{8, 1, 8}, new int[]{10, 1, 8});
        r.route();
        return e.view(6, 6, 22, 15);
    }

    /**
     * A lens: a red LED shines across the plot at a lens half a metre away, which gathers a cone of its light and
     * focuses it onto a photodiode at the image point, 156 mm behind. Without the lens the same photodiode catches
     * only the light that happens to head its way: the receiver's LED goes out.
     */
    public static Exhibit lens() {
        Exhibit e = new Exhibit("lens", "Lens",
                List.of("LENS", "Focusing light", "1/s + 1/s' = 1/f", "(take the lens away)"),
                "A lens bends light at each of its curved faces (Snell's law: n1 sin a1 = n2 sin a2). Light spreading "
                        + "from the LED is bent back together and meets again at an image point: for a lens of focal "
                        + "length f (here 118 mm: crown glass, faces of 120 mm radius, from the lensmaker's equation) "
                        + "an object s away images s' behind it where 1/s + 1/s' = 1/f. The LED is 481 mm away, so the "
                        + "image is 156 mm behind the lens, on the photodiode: it gathers a hundred times the light that "
                        + "would reach it otherwise, enough to light the green LED. Take the lens away and it goes dark.");
        String st = "steel_1045";
        e.name("led", e.add(LED, "silicon", 1, 1, 6, Dir.SOUTH, "wavelength", 630, "efficiency", 0.25, "beam", 30, "face", 5));
        e.add(BLOCK, st, 9, 0, 6, Dir.UP);
        e.name("lens", e.add(LENS, "glass_bk7", 9, 1, 6, Dir.EAST));
        e.name("pd", e.add(PHOTODIODE, "silicon", 12, 1, 6, Dir.SOUTH, "face", 4));
        int bat = e.add(CAPACITOR, "alumina", 4, 1, 3, Dir.EAST, "capacitance", 10);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        e.add(RESISTOR, "alumina", 1, 1, 3, Dir.SOUTH, "resistance", 150);
        Router r = new Router(e, 0, 0, 0, 17, 5, 15);
        r.keepClear(2, 1, 6, 11, 1, 6);
        r.pin("vdd", 5, 1, 3).pin("vdd", 1, 1, 2);
        r.pin("ledA", 1, 1, 4).pin("ledA", 1, 1, 5);
        r.pin("gnd", 3, 1, 3).pin("gnd", 1, 1, 7);
        receiver(e, r, 6, 10, 2.2e6, new int[]{12, 1, 5}, new int[]{12, 1, 7});
        r.route();
        return e.view(7, 4, 15, 15);
    }

    /**
     * A solar furnace: a dish of 169 canted concave aluminium facets, thirteen cells (0.81 m) across, faces the noon
     * sun; it gathers 0.66 m² of sunlight into a spot at its focus 0.75 m above, where a thin steel plate on a slender arm is
     * heated red-hot.
     */
    public static Exhibit solarFurnace() {
        Exhibit e = new Exhibit("furnace", "Solar furnace",
                List.of("SOLAR FURNACE", "A concave mirror", "focuses the sun", "on a steel plate"),
                "A concave mirror sends light arriving parallel to its axis through one point, its focus. This dish is "
                        + "169 small concave facets, each canted on its mount to send the sunlight striking it through the "
                        + "same focus 0.75 m above (as large solar concentrators are built). It catches 0.66 m² of noon "
                        + "sunlight (about 660 W) and its polished aluminium reflects 92 % of it into a spot "
                        + "a few centimetres across, on a thin steel plate held out on a slender rod. The plate heats "
                        + "until it radiates and convects away what it absorbs: red-hot. A spherical mirror's focus is "
                        + "sharp only for light along its axis: when the sun is off to one side the spot moves off the "
                        + "plate, which is why solar furnaces track the sun.");
        String st = "steel_1045";
        // the dish: 81 square facets, each a small concave mirror canted so that sunlight from straight above
        // striking its centre reflects through the focus 12 cells (0.75 m) above the middle facet, its radius of
        // curvature twice its distance to the focus (as real segmented concentrators are set up)
        e.add(BLOCK, st, 6, 0, 6, Dir.UP); // one pedestal: the facets touching one another are one rigid dish
        double fx = 6.5, fy = 13.5, fz = 6.5;
        for (int x = 0; x <= 12; x++)
            for (int z = 0; z <= 12; z++) {
                double dx = fx - (x + 0.5), dy = fy - 1.5, dz = fz - (z + 0.5), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double ax = dx / d, ay = 1 + dy / d, az = dz / d; // bisecting the sun's direction and the focus's
                int k = e.add(MIRROR, "aluminium", x, 1, z, Dir.UP, "aim_x", ax, "aim_y", ay, "aim_z", az, "radius", 2 * d / 16);
                if (x == 6 && z == 6) e.name("dish", k);
            }
        // the focus at y = 13.5 cells: the plate there, on a slender arm
        e.fill(SHAFT, st, 14, 0, 6, 14, 13, 6, Dir.UP, "diameter", 0.012);
        e.fill(SHAFT, st, 7, 13, 6, 13, 13, 6, Dir.EAST, "diameter", 0.008);
        e.name("target", e.add(BLOCK, st, 6, 13, 6, Dir.UP, "fill", 0.02));
        return e.view(6.5, 7, 27, 5);
    }

    /**
     * An incandescent lamp: a filament of 0.12 mm tungsten wire, 62.5 mm long, between two leads, lit from a 12 V bank
     * through a switch. Hot tungsten radiates Planck's spectrum; a photodiode four cells away catches some, and its receiver
     * lights a green LED while the lamp glows.
     */
    public static Exhibit lamp() {
        Exhibit e = new Exhibit("lamp", "Incandescent lamp",
                List.of("LAMP", "Hot tungsten glows:", "Planck radiation", "(close the switch)"),
                "Close the switch: 12 V drives two and a half amperes through a filament of tungsten wire an eighth "
                        + "of a millimetre thick. It heats until it radiates and convects away what the current puts in, "
                        + "near 2700 K: white-hot (its resistance rises fifteenfold as it heats, so it settles rather than "
                        + "running away). Every hot body radiates with Planck's spectrum; at this temperature only about a "
                        + "tenth of it is visible light, most is infrared. The photodiode facing it turns the part of "
                        + "that spectrum silicon absorbs (up to 1.1 um) into current, which lights the green LED: an "
                        + "electric eye. Its current rises steeply with the filament's temperature, which is how optical "
                        + "pyrometers measure furnaces. (A real lamp keeps its tungsten in a bulb of inert gas: in air it "
                        + "would burn; oxidation is not modelled here.)");
        // the lamp circuit: a 12 V bank, a switch, and the filament held up between its two leads
        int bank = e.add(CAPACITOR, "alumina", 2, 1, 2, Dir.EAST, "capacitance", 500);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 12));
        e.name("switch", e.add(SWITCH, "brass", 6, 1, 2, Dir.EAST));
        e.name("filament", e.add(WIRE, "tungsten", 7, 3, 6, Dir.EAST, "diameter", 1.2e-4));
        // the electric eye, facing the filament from four cells away
        e.name("pd", e.add(PHOTODIODE, "silicon", 7, 3, 10, Dir.EAST, "face", 2));
        int cell = e.add(CAPACITOR, "alumina", 13, 1, 12, Dir.EAST, "capacitance", 10);
        e.init(cell, new Exhibit.Initial(Double.NaN, 0, 5));
        Router r = new Router(e, 0, 0, 0, 15, 5, 16);
        // nothing may touch the bare filament but its two leads, nor stand between it and the eye
        r.keepClear(7, 4, 5, 7, 5, 9).keepClear(6, 3, 7, 8, 3, 9).keepClear(7, 2, 5, 7, 2, 7).keepClear(7, 3, 5, 7, 3, 5);
        r.pin("bat", 3, 1, 2).pin("bat", 5, 1, 2);
        r.pin("sw", 7, 1, 2).pin("sw", 6, 3, 6);    // the leads end against the filament's ends
        r.pin("ret", 8, 3, 6).pin("ret", 1, 1, 2);
        r.pin("vdd", 14, 1, 12).pin("gnd", 12, 1, 12);
        receiver(e, r, 2, 12, 470e3, new int[]{6, 3, 10}, new int[]{8, 3, 10});
        r.route();
        return e.view(1.5, 6.5, 13.5, 28, -140);
    }

    /**
     * A prism: red, green and blue LEDs one above another shine east into a column of dense flint glass cut to a 45°
     * prism. Each beam bends towards the prism's base by an angle set by the glass's index at its colour — blue most,
     * red least — so the three leave in a fan.
     */
    public static Exhibit prism() {
        Exhibit e = new Exhibit("prism", "Prism",
                List.of("PRISM", "Glass bends blue", "more than red", "(dispersion)"),
                "Light slows in glass, by a factor n, its refractive index, and bends where it crosses a surface "
                        + "at a slant (Snell's law). A prism bends a beam twice, both times towards its base. But n "
                        + "depends on colour: in dense flint glass it is 1.779 for red, 1.796 for green and 1.812 for "
                        + "blue (dispersion), so blue light is turned furthest: 51°, 54° and 57°. The three beams, one "
                        + "above the other, come out in a fan. Newton split sunlight into its spectrum this way; a "
                        + "spectrometer measures where each colour lands.");
        String st = "steel_1045";
        int[] nm = {630, 525, 465};
        String[] col = {"red", "green", "blue"};
        for (int k = 0; k < 3; k++) {
            e.name(col[k], e.add(LED, "silicon", 1, 1 + k, 8, Dir.SOUTH, "wavelength", nm[k], "efficiency", 0.25, "beam", 30, "face", 5));
        }
        e.add(BLOCK, st, 6, 0, 8, Dir.UP);
        e.name("prism", e.fill(PRISM, "glass_sf11", 6, 1, 8, 6, 3, 8, Dir.UP, "apex", 45, "face", Dir.NORTH.ordinal()));
        int bat = e.add(CAPACITOR, "alumina", 2, 1, 3, Dir.EAST, "capacitance", 10);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 9));
        e.add(RESISTOR, "alumina", 5, 1, 3, Dir.EAST, "resistance", 68);
        Router r = new Router(e, 0, 0, 0, 15, 5, 15);
        r.keepClear(2, 1, 8, 5, 3, 8).keepClear(7, 1, 8, 15, 3, 15);
        r.pin("bat", 3, 1, 3).pin("bat", 4, 1, 3);
        r.pin("r", 6, 1, 3).pin("r", 1, 1, 7);
        r.pin("rg", 1, 1, 9).pin("rg", 1, 2, 7);
        r.pin("gb", 1, 2, 9).pin("gb", 1, 3, 7);
        r.pin("ret", 1, 3, 9).pin("ret", 1, 1, 3);
        r.route();
        return e.view(7.5, 6.5, 13.5, 38);
    }

    /**
     * A spectrometer: a tungsten filament standing at the focus of a crown-glass lens, whose parallel beam crosses a
     * 40° flint prism; the spectrum fans out to three photodiodes 0.8 m on — blue, red and near-infrared — each with a
     * receiver lighting an indicator. Two switches take resistors out of the filament's circuit, raising its
     * temperature from red heat (only the infrared channel sees enough) through orange (red too) to white heat (blue
     * too): Planck's spectrum shifting to shorter wavelengths as the body gets hotter (Wien's law).
     */
    public static Exhibit spectrometer() {
        Exhibit e = new Exhibit("spectrometer", "Spectrometer",
                List.of("SPECTROMETER", "Hotter glows bluer:", "Planck & Wien", "(close the switches)"),
                "A spectrometer sorts light by wavelength. The tungsten filament stands at the focus of the crown-glass "
                        + "lens, which makes its light a parallel beam; the flint-glass prism bends each wavelength by its "
                        + "own angle (dispersion), so the colours land side by side 0.8 m on: violet, blue, then red, and "
                        + "past the red, invisible infrared. Three photodiodes sit in the blue, the red and the infrared; "
                        + "each lights its indicator (blue, red, deep red) when its light passes a threshold. Close the "
                        + "main switch: through two resistors the filament reaches only red heat, about 1500 K, and only "
                        + "the infrared channel lights — most of a warm body's light is infrared. Close the second switch "
                        + "(near 2000 K, orange) and the red channel lights too; the third (2700 K, white) and the blue "
                        + "one joins. Planck's law: a hotter body radiates more at every wavelength, but its peak moves "
                        + "to shorter ones (Wien: λ_max = 2898 µm·K / T), so the blue grows fastest of all.");
        e.plots = 2;
        String st = "steel_1045";
        // the source: a vertical filament (along the prism's edge, so its length does not blur the spectrum)
        e.name("filament", e.add(WIRE, "tungsten", 1, 2, 2, Dir.UP, "diameter", 1.2e-4));
        // the collimator: f = 0.25 m (4 cells), the filament at its focus
        e.add(BLOCK, st, 5, 0, 2, Dir.UP);
        e.add(BLOCK, st, 5, 1, 2, Dir.UP);
        e.name("lens", e.add(LENS, "glass_bk7", 5, 2, 2, Dir.EAST, "radius", 0.2584));
        e.add(BLOCK, st, 8, 0, 2, Dir.UP);
        e.name("prism", e.fill(PRISM, "glass_sf11", 8, 1, 2, 8, 3, 2, Dir.UP, "apex", 40, "face", Dir.NORTH.ordinal()));
        // a screen keeps the filament's direct light off the detectors
        e.fill(BLOCK, "oak", 6, 0, 5, 7, 4, 7, Dir.UP);
        // the detectors, facing back up the beam
        String[] ch = {"blue", "red", "infrared"};
        double[] ledNm = {465, 630, 700};
        for (int k = 0; k < 3; k++) e.name("pd_" + ch[k], e.add(PHOTODIODE, "silicon", 20 + k, 2, 13, Dir.UP, "face", 2, "area", 1e-3, "lead_axis", 2));
        // the lamp circuit: 12 V, the main switch, two resistors each bridged by a switch
        int bank = e.add(CAPACITOR, "alumina", 2, 1, 10, Dir.EAST, "capacitance", 3000);
        e.init(bank, new Exhibit.Initial(Double.NaN, 0, 12));
        e.name("switch", e.add(SWITCH, "brass", 5, 1, 10, Dir.EAST));
        e.add(RESISTOR, "alumina", 8, 1, 10, Dir.EAST, "resistance", 1.5);
        e.name("warmer", e.add(SWITCH, "brass", 8, 1, 12, Dir.EAST));
        e.add(RESISTOR, "alumina", 12, 1, 10, Dir.EAST, "resistance", 1.5);
        e.name("hotter", e.add(SWITCH, "brass", 12, 1, 12, Dir.EAST));
        // the receivers' 5 V supply
        int cell = e.add(CAPACITOR, "alumina", 16, 1, 15, Dir.EAST, "capacitance", 10);
        e.init(cell, new Exhibit.Initial(Double.NaN, 0, 5));
        int[] rx = {14, 25, 25}, rz = {1, 1, 7};
        for (int k = 0; k < 3; k++) {
            int x = rx[k], z = rz[k];
            e.add(RESISTOR, "alumina", x, 1, z, Dir.EAST, "resistance", 680e3);
            e.add(LOGIC, "silicon", x + 4, 1, z, Dir.EAST, "function", 7);
            e.name("lit_" + ch[k], e.add(LED, "silicon", x + 4, 1, z + 3, Dir.EAST, "wavelength", ledNm[k], "face", 1, "beam", 20));
            e.add(RESISTOR, "alumina", x + 7, 1, z + 3, Dir.EAST, "resistance", 470);
        }
        Router r = new Router(e, 0, 0, 0, 35, 6, 15);
        // nothing may touch the bare filament but its leads, nor cross the light's path (filament → lens → prism → detectors)
        r.keepClear(0, 2, 1, 0, 2, 3).keepClear(1, 2, 1, 1, 2, 1).keepClear(1, 2, 3, 1, 2, 3).keepClear(2, 1, 1, 4, 3, 3);
        r.keepClear(6, 1, 1, 7, 3, 3).keepClear(9, 2, 0, 23, 3, 12);
        r.pin("bat", 3, 1, 10).pin("bat", 4, 1, 10);
        r.pin("n1", 6, 1, 10).pin("n1", 7, 1, 10).pin("n1", 7, 1, 12);
        r.pin("n2", 9, 1, 10).pin("n2", 9, 1, 12).pin("n2", 11, 1, 10).pin("n2", 11, 1, 12);
        r.pin("n3", 13, 1, 10).pin("n3", 13, 1, 12).pin("n3", 1, 3, 2);
        r.pin("ret", 1, 1, 2).pin("ret", 1, 1, 10);
        r.pin("vdd", 17, 1, 15).pin("gnd", 15, 1, 15);
        for (int k = 0; k < 3; k++) {
            int x = rx[k], z = rz[k], px = 20 + k;
            r.pin("vdd", px, 3, 13).pin("vdd", x + 4, 1, z + 1).pin("vdd", x + 8, 1, z + 3);
            r.pin("gnd", x - 1, 1, z).pin("gnd", x + 4, 1, z - 1 < 0 ? z - 1 : z - 1);
            r.pin("sense_" + k, px, 1, 13).pin("sense_" + k, x + 1, 1, z).pin("sense_" + k, x + 3, 1, z);
            r.pin("out_" + k, x + 5, 1, z).pin("out_" + k, x + 5, 1, z + 3);
            r.pin("lit_" + k, x + 3, 1, z + 3).pin("lit_" + k, x + 6, 1, z + 3);
        }
        r.route();
        return e.view(15, 8, 21, 30, 180);
    }

    /**
     * An optocoupler: an infrared LED shining on a phototransistor right against it. The two circuits share no
     * conductor; the switch in one lights the indicator in the other, the signal crossing as light. The phototransistor's
     * photocurrent is its base current, multiplied by its gain.
     */
    public static Exhibit optocoupler() {
        Exhibit e = new Exhibit("optocoupler", "Optocoupler",
                List.of("OPTOCOUPLER", "Close the switch:", "light carries it", "across the gap"),
                "Two separate circuits, each with its own battery, joined by no wire. On the left, the switch sends "
                        + "current through an infrared LED (940 nm, invisible). Against it sits a phototransistor: a "
                        + "transistor whose collector-base junction lies under a window, so the light makes a tiny "
                        + "photocurrent there — and that photocurrent is the transistor's base current, amplified 150 "
                        + "times into collector current. Enough flows to light the green LED on the right. Signals cross "
                        + "this way between circuits at very different voltages (a mains-powered controller and a "
                        + "computer), since light needs no conductor.");
        // the infrared LED (leads top and bottom), facing the phototransistor's window to the east
        e.name("ir", e.add(LED, "silicon", 6, 1, 8, Dir.DOWN, "wavelength", 940, "face", Dir.EAST.ordinal(), "lead_axis", 2));
        // the phototransistor: collector up, emitter down, its base left unconnected; window facing west
        e.name("photo", e.add(TRANSISTOR, "silicon", 7, 1, 8, Dir.UP, "type", 0, "photo", 7.5e-6, "face", Dir.WEST.ordinal(),
                "lead_axis", 2));
        // input circuit: 3 V bank, switch, 100 ohm
        int bank1 = e.add(CAPACITOR, "alumina", 2, 0, 4, Dir.EAST, "capacitance", 50);
        e.init(bank1, new Exhibit.Initial(Double.NaN, 0, 3));
        e.name("switch", e.add(SWITCH, "brass", 2, 0, 12, Dir.EAST));
        e.add(RESISTOR, "alumina", 4, 0, 14, Dir.EAST, "resistance", 100);
        // output circuit: 5 V bank, green LED, 330 ohm, the phototransistor
        int bank2 = e.add(CAPACITOR, "alumina", 12, 0, 4, Dir.EAST, "capacitance", 50);
        e.init(bank2, new Exhibit.Initial(Double.NaN, 0, 5));
        e.name("green", e.add(LED, "silicon", 12, 0, 12, Dir.WEST, "wavelength", 530));
        e.add(RESISTOR, "alumina", 10, 0, 8, Dir.EAST, "resistance", 330);
        Router r = new Router(e, 0, 0, 2, 15, 3, 15);
        // the base's and the LED's side leads stay bare
        for (int x = 6; x <= 7; x++) {
            r.keepClear(x, 1, 7, x, 1, 7);
            r.keepClear(x, 1, 9, x, 1, 9);
        }
        r.pin("i1", 3, 0, 4).pin("i1", 3, 0, 12);
        r.pin("i2", 1, 0, 12).pin("i2", 3, 0, 14);
        r.pin("i3", 5, 0, 14).pin("i3", 6, 2, 8);
        r.pin("i4", 6, 0, 8).pin("i4", 1, 0, 4);
        r.pin("o1", 13, 0, 4).pin("o1", 13, 0, 12);
        r.pin("o2", 11, 0, 12).pin("o2", 11, 0, 8);
        r.pin("o3", 9, 0, 8).pin("o3", 7, 2, 8);
        r.pin("o4", 7, 0, 8).pin("o4", 11, 0, 4);
        r.route();
        return e.view(7, 4, 16, 18);
    }
}
