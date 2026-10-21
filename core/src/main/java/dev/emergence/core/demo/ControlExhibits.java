package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of feedback control and robotics. */
public final class ControlExhibits {
    private ControlExhibits() {
    }

    /**
     * A position servo from parts: a gravity-loaded arm on a shaft turned by a moving-magnet torque motor (a magnet on
     * the shaft between two fixed coils), a potentiometer on the shaft measuring its angle, and a knob potentiometer
     * setting the wanted angle. An op-amp difference amplifier drives a complementary transistor pair with ten times
     * the difference of the two wiper voltages; a capacitor across the feedback input resistor adds derivative action
     * (PD control), damping the swing.
     */
    public static Exhibit servo() {
        Exhibit e = new Exhibit("servo", "Servo arm",
                List.of("SERVO ARM", "Turn the knob: the", "arm follows it.", "PID control, op-amp"),
                "Closed-loop position control from parts. Turning the knob moves the wiper of its potentiometer; a "
                        + "second potentiometer on the arm's shaft reports where the arm is. The op-amp amplifies the "
                        + "difference ten times and two transistors (NPN up, PNP down) pass that voltage, with amps of "
                        + "current, to two coils above and below a magnet on the shaft: torque i·dΨ/dθ turns the arm "
                        + "until the two wipers agree. Holding the arm up against gravity needs a steady current; "
                        + "with only proportional gain that would leave a steady error (droop), but the capacitor in "
                        + "series with the feedback resistor integrates the error, building up whatever current the arm "
                        + "needs until the error is gone (integral action; its twin keeps the input network balanced). "
                        + "The capacitor across the position resistor makes the drive respond to the arm's speed too, "
                        + "damping the swing (derivative action): a PID controller. Powered by two 12 V lead–acid "
                        + "batteries.");
        e.plots = 2;
        String st = "steel_1045", ny = "nylon_66", cu = "copper";
        int y = 4, z = 5;
        // ---- arm shaft along x: ball bearing on a column at x = 1, the feedback potentiometer (also its second
        // bearing) at x = 7 held from the south side, the arm on the outboard end at x = 9
        int shaft = e.fill(SHAFT, st, 1, y, z, 9, y, z, Dir.EAST, "diameter", 0.012);
        e.name("arm", shaft);
        e.init(shaft, new Exhibit.Initial(Double.NaN, 0, 0, Math.PI / 2)); // hanging down
        e.add(BEARING, st, 1, y, z, Dir.EAST);
        e.fill(BLOCK, st, 1, 0, z, 1, y - 1, z, Dir.UP);
        e.name("feedback", e.add(POTENTIOMETER, "nylon_66", 7, y, z, Dir.EAST, "lead_axis", 1));
        e.fill(BLOCK, st, 7, 0, z + 1, 7, y, z + 1, Dir.UP);
        // ---- torque motor: a magnet on the shaft (magnetised along +z at θ = 0) surrounded by four coils in series,
        // two with vertical axes (flux ∝ −sin θ) and two with horizontal axes (flux ∝ cos θ): the total linkage
        // ∝ cos θ − sin θ, so the torque i·dΨ/dθ ∝ cos(θ − 45°) is strong over the arm's working range
        e.name("magnet", e.add(MAGNET, "ndfeb_n42", 3, y, z, Dir.SOUTH));
        e.name("coilUp", e.add(COIL, cu, 3, y + 1, z, Dir.UP, "turns", 300, "wire_diameter", 1.2e-3));
        e.name("coilDown", e.add(COIL, cu, 3, y - 1, z, Dir.UP, "turns", 300, "wire_diameter", 1.2e-3));
        e.name("coilNorth", e.add(COIL, cu, 3, y, z - 1, Dir.SOUTH, "turns", 300, "wire_diameter", 1.2e-3));
        e.name("coilSouth", e.add(COIL, cu, 3, y, z + 1, Dir.SOUTH, "turns", 300, "wire_diameter", 1.2e-3));
        e.fill(BLOCK, ny, 3, 0, z, 3, y - 2, z, Dir.UP);
        e.fill(BLOCK, ny, 3, y + 2, z - 2, 3, y + 2, z, Dir.UP);
        e.fill(BLOCK, st, 3, 0, z - 2, 3, y + 1, z - 2, Dir.UP);
        e.fill(BLOCK, ny, 3, 0, z + 2, 3, y, z + 2, Dir.UP);
        // ---- the arm: nylon hub on the shaft and a thin aluminium bar reaching south (horizontal at θ = 0)
        e.add(BLOCK, ny, 9, y, z, Dir.UP, "fill", 0.5);
        e.fill(BLOCK, "aluminium_6061", 9, y, z + 1, 9, y, z + 3, Dir.UP, "fill", 0.06);
        // ---- set-point knob: shaft in a silicone-damped bearing (it stays where it is left), the knob
        // potentiometer and a crank
        int ky = 2, kz = 13;
        int knob = e.fill(SHAFT, st, 10, ky, kz, 13, ky, kz, Dir.EAST, "diameter", 0.012);
        e.name("knob", knob);
        e.init(knob, new Exhibit.Initial(Double.NaN, 0, 0, 1.885)); // asking for the hanging arm
        e.add(BEARING, "bronze", 10, ky, kz, Dir.EAST, "type", 2, "viscosity", 2000);
        e.fill(BLOCK, st, 10, 0, kz, 10, ky - 1, kz, Dir.UP);
        e.name("setpoint", e.add(POTENTIOMETER, "nylon_66", 11, ky, kz, Dir.EAST, "lead_axis", 1));
        e.fill(BLOCK, st, 11, 0, kz + 1, 11, ky, kz + 1, Dir.UP);
        e.name("crank", e.add(CRANK, st, 13, ky, kz, Dir.EAST, "radius", 0.06));
        // the knob's track sits between 3 kΩ to +12 V and 11 kΩ to −12 V, so it spans +9 … −1 V: its 270° ask for
        // the arm's working range, from 10° above horizontal to 100° below (the arm's own track spans ±12 V)
        e.add(RESISTOR, "alumina", 11, 1, 6, Dir.SOUTH, "resistance", 3_000);
        e.add(RESISTOR, "alumina", 11, 1, 10, Dir.SOUTH, "resistance", 11_000);
        // ---- electronics on the floor (y = 1)
        int f = 1;
        int bp = e.add(BATTERY, "pvc", 24, f, 2, Dir.EAST, "cells", 6, "capacity", 20); // 6 lead–acid cells: 12.8 V full
        int bn = e.add(BATTERY, "pvc", 28, f, 2, Dir.EAST, "cells", 6, "capacity", 20); // 6 lead–acid cells: 12.8 V full
        e.name("batteryP", bp).name("batteryN", bn);
        e.name("opamp", e.add(OPAMP, "silicon", 18, f, 8, Dir.EAST));
        e.add(RESISTOR, "alumina", 14, f, 8, Dir.EAST, "resistance", 10_000);     // set → in+
        e.add(RESISTOR, "alumina", 14, f, 11, Dir.EAST, "resistance", 100_000);   // in+ → 0 V, through
        e.init(e.add(CAPACITOR, "alumina", 17, f, 11, Dir.EAST, "capacitance", 10e-6), // its twin (the network stays balanced)
                new Exhibit.Initial(Double.NaN, 0, -8));
        e.add(RESISTOR, "alumina", 14, f, 4, Dir.EAST, "resistance", 10_000);     // position → in−
        e.name("cd", e.add(CAPACITOR, "alumina", 14, f, 2, Dir.EAST, "capacitance", 4.7e-6)); // derivative
        e.add(RESISTOR, "alumina", 22, f, 5, Dir.EAST, "resistance", 100_000);    // drive → in−, through
        e.name("ci", e.add(CAPACITOR, "alumina", 25, f, 5, Dir.EAST, "capacitance", 10e-6)); // the integrator (PI)
        // at rest the integrator holds the set-point's 8 V (the arm hanging, the drive at 0 V)
        e.init(e.named.get("ci"), new Exhibit.Initial(Double.NaN, 0, -8));
        e.name("npn", e.add(TRANSISTOR, "silicon", 22, f, 10, Dir.EAST, "type", 0, "beta", 60, "saturation_current", 1e-12,
                "surface_area", 0.03));
        e.name("pnp", e.add(TRANSISTOR, "silicon", 22, f, 13, Dir.EAST, "type", 1, "beta", 60, "saturation_current", 1e-12,
                "surface_area", 0.03));
        Router r = new Router(e, 0, 0, 0, 31, 8, 15);
        // nets
        r.pin("vp", 25, f, 2).pin("vp", 23, f, 10).pin("vp", 18, f, 9)              // bank+ , NPN collector, op-amp V+
                .pin("vp", 7, y + 1, z).pin("vp", 11, 1, 5);                          // arm pot B, knob divider
        r.pin("vn", 27, f, 2).pin("vn", 23, f, 13).pin("vn", 18, f, 7)              // bank−, PNP collector, op-amp V−
                .pin("vn", 7, y - 1, z).pin("vn", 11, 1, 11);                         // arm pot A, knob divider
        r.pin("kB", 11, 1, 7).pin("kB", 11, ky + 1, kz);
        r.pin("kA", 11, 1, 9).pin("kA", 11, ky - 1, kz);
        r.pin("gnd", 23, f, 2).pin("gnd", 29, f, 2).pin("gnd", 18, f, 11)           // batteries' 0 V, in+ divider
                .pin("gI", 15, f, 11).pin("gI", 16, f, 11)
                .pin("gnd", 2, y + 1, z);                                            // motor return
        r.pin("set", 11, ky, kz - 1).pin("set", 13, f, 8);
        r.pin("pos", 7, y, z - 1).pin("pos", 13, f, 4).pin("pos", 13, f, 2);
        r.pin("inP", 15, f, 8).pin("inP", 13, f, 11).pin("inP", 17, f, 8);
        r.pin("inN", 15, f, 4).pin("inN", 15, f, 2).pin("inN", 21, f, 5).pin("inN", 18, 0, 8);
        r.pin("out", 19, f, 8).pin("out", 22, f, 9).pin("out", 22, f, 12);
        r.pin("drive", 21, f, 10).pin("drive", 21, f, 13).pin("drive", 26, f, 5).pin("drive", 4, y, z + 1);
        r.pin("fbI", 23, f, 5).pin("fbI", 24, f, 5);
        // coils in series, the drive entering each at its + lead: south, north, down, up
        r.pin("c1", 4, y + 1, z).pin("c1", 2, y - 1, z);
        r.pin("c2", 4, y - 1, z).pin("c2", 2, y, z - 1);
        r.pin("c3", 4, y, z - 1).pin("c3", 2, y, z + 1);
        // the arm sweeps the plane x = 9 and the magnet the cells round the shaft at x = 3; the knob's crank turns at x = 13
        r.keepClear(9, 0, 1, 9, 8, 9);
        r.keepClear(3, y - 1, z - 1, 3, y + 1, z + 1);
        r.keepClear(13, 0, kz - 2, 13, ky + 2, 15);
        r.route();
        return e.view(15, 8, 18, 18, 151);
    }

    /**
     * An electronic speed governor: the brushed DC motor's shaft carries a small disc with two magnets of opposite
     * polarity sweeping a fixed coil (a tachogenerator: its EMF grows with the speed); a diode charges a capacitor to
     * the coil's peaks, a voltage proportional to the speed. An op-amp compares it with a reference and, through a
     * proportional-plus-integral network, sets the base of an NPN emitter follower feeding the motor. A brake pad on
     * the disc loads it: the speed sags, the drive rises until the speed is back.
     */
    public static Exhibit governor() {
        return governor(Double.NaN);
    }

    /** @param openLoop if a number, the motor is fed this fixed voltage instead (for measuring the bare motor) */
    public static Exhibit governor(double openLoop) {
        Exhibit e = new Exhibit("governor", "Speed governor",
                List.of("SPEED GOVERNOR", "Apply the brake:", "the drive rises,", "the speed holds"),
                "A motor that holds its speed whatever the load. The motor's shaft carries a disc with two magnets "
                        + "sweeping past a fixed coil: a tachogenerator, whose EMF dΨ/dt grows with the speed. A diode "
                        + "charges a capacitor to its peaks, giving a voltage proportional to the speed. The op-amp "
                        + "compares that with the reference set by its divider and drives the transistor, which feeds "
                        + "the motor. Apply the brake pad (right-click it): the speed sags, the tacho voltage falls "
                        + "below the reference, and the op-amp raises the motor's voltage; the capacitor in its "
                        + "feedback integrates the error, so the speed returns to the set value and not just near it. "
                        + "Release the brake and it settles back. This is the electronic form of Watt's flyball "
                        + "governor.");
        e.plots = 2;
        String st = "steel_1045", ny = "nylon_66";
        // ---- the brushed DC motor (as in the DC motor exhibit), its shaft continuing above the frame
        int shaft = brushedMotor(e, 14);
        // it starts at its governed speed, the tacho and integrator capacitors charged to match (as if it had been running)
        e.init(shaft, new Exhibit.Initial(Double.NaN, 25.5, 0, 0.3));
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 10}, {8, 8, 10}, {8, 7, 10}, {9, 7, 10}, {10, 7, 10}, {11, 7, 10},
                {11, 6, 10}, {11, 5, 10}, {11, 5, 9}, {11, 5, 8}});
        // ---- above the frame: the tacho disc with two magnets of opposite polarity, the fixed tacho coil over their
        // path, and the brake pad against the disc's edge
        e.fill(BLOCK, "aluminium_6061", 7, 14, 7, 9, 14, 9, Dir.UP, "fill", 0.15);
        e.add(MAGNET, "ndfeb_n42", 9, 15, 8, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 7, 15, 8, Dir.DOWN);
        e.name("tacho", e.add(COIL, "copper", 9, 16, 8, Dir.UP, "turns", 1000, "wire_diameter", 0.3e-3, "lead_axis", 2));
        e.fill(BLOCK, st, 11, 13, 8, 11, 17, 8, Dir.UP);
        e.fill(BLOCK, st, 9, 17, 8, 10, 17, 8, Dir.UP);
        e.add(BLOCK, st, 6, 13, 8, Dir.UP);
        e.name("brake", e.add(BRAKE, "bronze", 6, 14, 8, Dir.EAST, "force", 10));
        // ---- electronics on the floor beside the motor (y = 1)
        int f = 1;
        // a lead–acid battery (its reference divider runs from it too: a supply that sagged would drag the set speed down)
        boolean closed = Double.isNaN(openLoop);
        int bat = e.add(BATTERY, "pvc", 16, f, 4, Dir.EAST, "cells", closed ? 11 : (int) Math.round(openLoop / 2.125), "capacity", 7);
        e.name("battery", bat);
        if (closed) {
            e.name("opamp", e.add(OPAMP, "silicon", 24, f, 7, Dir.EAST));
            e.add(RESISTOR, "alumina", 20, f, 9, Dir.EAST, "resistance", 47_000);        // reference: +24 V → in+
            e.add(RESISTOR, "alumina", 20, f, 11, Dir.EAST, "resistance", 10_000);        // in+ → 0 V
            e.add(RESISTOR, "alumina", 20, f, 5, Dir.EAST, "resistance", 10_000);        // tacho → in−
            e.add(RESISTOR, "alumina", 27, f, 4, Dir.EAST, "resistance", 100_000);      // in− → feedback capacitor
            e.name("ci", e.add(CAPACITOR, "alumina", 30, f, 4, Dir.EAST, "capacitance", 10e-6));
            e.init(e.named.get("ci"), new Exhibit.Initial(Double.NaN, 0, 5.8));
            e.name("npn", e.add(TRANSISTOR, "silicon", 28, f, 8, Dir.EAST, "type", 0, "beta", 60, "saturation_current", 1e-12,
                    "surface_area", 0.03));
            // a driver transistor feeding the power transistor's base (a Darlington pair): the op-amp could not source the
            // ~50 mA of base current amps of motor current need
            e.name("driver", e.add(TRANSISTOR, "silicon", 31, f, 8, Dir.EAST, "type", 0, "beta", 100, "saturation_current", 1e-14));
            // across the motor: a suppression capacitor and a freewheel diode take the commutator's switching surges
            e.add(CAPACITOR, "alumina", 27, f, 11, Dir.EAST, "capacitance", 470e-6);
            e.add(DIODE, "silicon", 31, f, 11, Dir.EAST);
        } else {
            e.name("switch", e.add(SWITCH, "brass", 19, f, 4, Dir.EAST));
        }
        // the tacho's rectifier: a diode charging a capacitor, a resistor bleeding it (τ = 2 s)
        e.add(DIODE, "silicon", 20, f, 13, Dir.EAST);
        e.name("ct", e.add(CAPACITOR, "alumina", 24, f, 13, Dir.EAST, "capacitance", 20e-6));
        e.init(e.named.get("ct"), new Exhibit.Initial(Double.NaN, 0, 4.15));
        e.add(RESISTOR, "alumina", 28, f, 13, Dir.EAST, "resistance", 100_000);
        Router r = new Router(e, 0, 0, 0, 35, 18, 15);
        if (closed) {
            r.pin("vp", 17, f, 4).pin("vp", 24, f, 8).pin("vp", 19, f, 9).pin("vp", 29, f, 8).pin("vp", 32, f, 8);
            r.pin("gnd", 24, f, 6).pin("gnd", 21, f, 11);
            r.pin("inP", 21, f, 9).pin("inP", 19, f, 11).pin("inP", 23, f, 7);
            r.pin("inN", 21, f, 5).pin("inN", 24, 0, 7).pin("inN", 26, f, 4);
            r.pin("fb", 28, f, 4).pin("fb", 29, f, 4);
            r.pin("out", 25, f, 7).pin("out", 31, f, 4).pin("out", 31, f, 7);
            r.pin("b2", 30, f, 8).pin("b2", 28, f, 7);
            r.pin("mot", 27, f, 8).pin("mot", 11, 8, 8).pin("mot", 28, f, 11).pin("mot", 32, f, 11);
            r.pin("gnd", 26, f, 11).pin("gnd", 30, f, 11);
            r.pin("tacho", 19, f, 5);
        } else {
            r.pin("vb", 17, f, 4).pin("vb", 18, f, 4);
            r.pin("vp", 20, f, 4).pin("vp", 11, 8, 8);
        }
        r.pin("gnd", 15, f, 4).pin("gnd", 5, 8, 8);
        r.pin("tacho", 21, f, 13).pin("tacho", 25, f, 13).pin("tacho", 27, f, 13);
        r.pin("coil", 19, f, 13);
        r.pin("coil", 9, 16, 9).pin("gnd", 9, 16, 7);
        r.pin("gnd", 23, f, 13).pin("gnd", 29, f, 13);
        // the rotor, its wiring and the tacho disc sweep the cylinder round the shaft; no wire may touch the rotor's
        // wiring either (it would hold it to the frame)
        r.keepClear(4, 1, 3, 12, 7, 13);
        r.keepClear(6, 8, 4, 10, 8, 12);
        r.keepClear(11, 8, 9, 12, 8, 11);
        r.keepClear(4, 8, 5, 5, 8, 7);
        r.keepClear(5, 9, 4, 11, 15, 12);
        r.route();
        return e.view(18, 15, 33, 22, 180);
    }
    /**
     * A contact governor (the Teletype's and the gramophone motor's): a weight on a short slide on the motor's own
     * rotor, held in against a contact by a preloaded spring, the contact in series with the rotor's coils. Above the
     * speed at which m·ω²·r beats the spring's preload the weight lifts off and the coils' current must pass a resistor
     * across the contact instead; the motor slows, the weight falls back, the contact closes: the speed hunts in a narrow
     * band about the set speed, whatever the supply.
     *
     * @param preload the spring's preload [m] (it sets the speed)
     */
    public static Exhibit contactGovernor(double preload) {
        return contactGovernor(preload, 10);
    }

    static Exhibit contactGovernor(double preload, double brakeForce) {
        Exhibit e = new Exhibit("contactgov", "Contact governor",
                List.of("CONTACT GOVERNOR", "Close the switch, then", "brake it: the speed", "holds"),
                "The DC motor again, on a full 24 V, with a centrifugal switch riding on its rotor (top). A brass weight "
                        + "slides on a short rod, pressed inwards against a contact wire by a spring fitted 8.7 mm shorter "
                        + "than it would like to be. The contact is in series with the rotor's coils. Close the switch: "
                        + "the motor speeds up until the weight's need for a force towards the axis, m·ω²·r, outgrows "
                        + "what the spring gives — near 37 radians a second — and the weight eases off its contact. The "
                        + "coils' current must then pass a 47 Ω resistor across the contact; the torque collapses and the "
                        + "speed can rise no further. Apply the brake (right-click the pad): the speed sags a hair, the "
                        + "weight sinks back onto the contact and the motor takes the current it needs — the speed holds "
                        + "within a fraction of a percent. Without the switch the same motor would race on past 70 rad/s. "
                        + "This is the governor of the Teletype and of clockwork gramophone motors (the resistor spares the "
                        + "contacts the spark of cutting the coils' current); a stiffer preload sets a higher speed, "
                        + "as its square root.");
        e.plots = 2;
        String st = "steel_1045", ny = "nylon_66";
        int shaft = brushedMotor(e, 16);
        // turning already (a coasting start, so the visit need not wait half a minute for it to come up to speed)
        e.init(shaft, new Exhibit.Initial(Double.NaN, 30, 0, 0.3));
        // ---- the centrifugal switch on the rotor, above the frame: a nylon beam (y = 14) clear of the frame below
        e.add(BLOCK, ny, 8, 14, 8, Dir.UP, "fill", 0.5);                                   // the hub, on the shaft
        e.add(BLOCK, ny, 8, 14, 9, Dir.UP, "fill", 0.5);
        e.fill(BLOCK, ny, 9, 14, 9, 9, 14, 13, Dir.UP, "fill", 0.5);
        e.fill(BLOCK, ny, 7, 14, 13, 8, 14, 13, Dir.UP, "fill", 0.5);
        e.add(BLOCK, ny, 8, 15, 9, Dir.UP);                                                  // spacer at the hub
        e.name("contact", e.add(WIRE, "copper", 8, 15, 10, Dir.UP, "diameter", 0.003));     // the fixed contact
        e.name("weight", e.add(BLOCK, "brass", 8, 15, 11, Dir.UP, "fill", 0.3));             // ~0.6 kg at 19 cm
        // (the bushing a cell clear of the weight: touching it, it would be mounted on the slide it guides)
        e.name("rod", e.fill(SHAFT, st, 8, 15, 11, 8, 15, 13, Dir.SOUTH, "diameter", 0.008));
        e.add(LINEAR_BEARING, st, 8, 15, 13, Dir.SOUTH, "travel", 0.002);
        e.add(BLOCK, ny, 7, 15, 11, Dir.UP, "fill", 0.5);                                   // an arm off the weight
        e.name("spring", e.add(SPRING, st, 7, 15, 12, Dir.SOUTH, "wire", 0.006, "turns", 10, "preload", preload));
        e.add(BLOCK, ny, 7, 15, 13, Dir.UP);                                                 // its seat
        e.add(WIRE, "copper", 8, 16, 11, Dir.UP, "flexible", 1);                             // a pigtail off the weight
        // segment A → up the rotor to the contact; the weight's pigtail → down the rotor to coil A
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 10}, {8, 10, 10}, {8, 11, 10}, {8, 12, 10}, {8, 13, 10}, {8, 14, 10}});
        e.path(INSULATED_WIRE, "copper", new int[][]{{9, 16, 11}, {10, 16, 11}, {10, 15, 11}, {10, 14, 11}, {10, 13, 11}, {9, 13, 11},
                {9, 12, 11}, {9, 11, 11}, {9, 10, 11}, {9, 9, 11}, {9, 8, 11}, {9, 7, 11}, {9, 7, 10}, {10, 7, 10}, {11, 7, 10},
                {11, 6, 10}, {11, 5, 10}, {11, 5, 9}, {11, 5, 8}});
        // the resistor across the contact, on the beam
        e.name("shunt", e.add(RESISTOR, "alumina", 9, 15, 10, Dir.EAST, "resistance", 47));
        e.add(INSULATED_WIRE, "copper", 10, 15, 10, Dir.UP);
        // ---- a brake: an aluminium disc on the shaft's top, a bronze pad on a post from the frame
        e.fill(BLOCK, "aluminium_6061", 7, 16, 7, 9, 16, 9, Dir.UP, "fill", 0.15);
        e.fill(BLOCK, st, 6, 13, 8, 6, 15, 8, Dir.UP);
        e.name("brake", e.add(BRAKE, "bronze", 6, 16, 8, Dir.EAST, "force", brakeForce));
        // ---- supply: a 24 V bank and a switch to the brushes
        int f = 1;
        int bat = e.add(BATTERY, "pvc", 16, f, 4, Dir.EAST, "cells", 11, "capacity", 20); // 11 lead–acid cells: 23.4 V full
        e.name("battery", bat);
        e.name("switch", e.add(SWITCH, "brass", 19, f, 4, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 22, 18, 15);
        r.pin("vb", 17, f, 4).pin("vb", 18, f, 4);
        r.pin("vp", 20, f, 4).pin("vp", 11, 8, 8);
        r.pin("gnd", 15, f, 4).pin("gnd", 5, 8, 8);
        // the rotor, its wiring and the switch on top sweep the cylinder round the shaft
        r.keepClear(4, 1, 3, 12, 7, 13);
        r.keepClear(6, 8, 4, 10, 8, 12);
        r.keepClear(11, 8, 9, 12, 8, 11);
        r.keepClear(4, 8, 5, 5, 8, 7);
        r.keepClear(4, 9, 4, 12, 17, 13);
        r.route();
        return e.view(8.5, 19, 17, 40);
    }

    public static Exhibit contactGovernor() {
        return contactGovernor(0.0087);
    }

    /**
     * Watt's flyball governor on the DC motor: two brass weights on radial slides of a cross on the motor's spindle,
     * held in by springs; slanting link bars from each weight to a sleeve that slides along the spindle's axis; a push
     * rod from the sleeve's top (pinned on the axis, so it need not turn with it) up to a bell crank on the shaft of a
     * rheostat in series with the motor. As the weights fly out the sleeve is drawn down, the bell crank turns and the
     * rheostat puts more resistance in the motor's way.
     */
    public static Exhibit flyball() {
        Exhibit e = new Exhibit("flyball", "Flyball governor",
                List.of("FLYBALL GOVERNOR", "Close the switch: the", "weights fly out and", "rein the motor in"),
                "James Watt's governor, built from parts. The DC motor's spindle carries a cross; on each arm a brass "
                        + "weight slides on a rod, held in towards the axis by a spring. Slanting bars link each weight "
                        + "to a sleeve that slides up and down the spindle's axis, and from the top of the sleeve a push "
                        + "rod rises to a bell crank — the rod's foot rides in a groove round the sleeve, on the axis, so "
                        + "the sleeve can turn under it. The bell crank turns the shaft of a rheostat in series with the "
                        + "motor. Close the switch: the motor speeds up; the weights, needing m·ω²·r towards the axis that "
                        + "only their springs can give, slide out, pull the sleeve down through their bars, and the push "
                        + "rod turns the rheostat towards more resistance — the motor's current and torque fall, and its "
                        + "speed settles where the two agree, near 30 radians a second with the weights 15 mm out. Brake it "
                        + "(right-click the pad): the weights sink in, the resistance falls and the motor takes more "
                        + "current, and the speed sags by only a few percent. (Below about 25 rad/s the springs, fitted "
                        + "16 mm short, hold the weights against their stops.) Unlike the electronic governor this one "
                        + "has no integrator: it holds the speed only roughly, a little lower under load (droop) — "
                        + "the price Watt's engines paid, and why later governors added a reset.");
        e.plots = 2;
        String st = "steel_1045", al = "aluminium_6061", ny = "nylon_66";
        int shaft = brushedMotor(e, 16);
        // turning already (a coasting start: the heavy rotor would take half a minute to come up to speed), just past
        // the dead point
        e.init(shaft, new Exhibit.Initial(Double.NaN, 28, 0, 0.3));
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 10}, {8, 8, 10}, {8, 7, 10}, {9, 7, 10}, {10, 7, 10}, {11, 7, 10},
                {11, 6, 10}, {11, 5, 10}, {11, 5, 9}, {11, 5, 8}});
        // ---- a brake: an aluminium disc on the spindle above the frame, a bronze pad on a post
        e.add(BLOCK, al, 8, 14, 8, Dir.UP, "fill", 0.15);
        e.fill(BLOCK, al, 7, 14, 7, 9, 14, 7, Dir.UP, "fill", 0.15);
        e.fill(BLOCK, al, 7, 14, 9, 9, 14, 9, Dir.UP, "fill", 0.15);
        e.add(BLOCK, al, 7, 14, 8, Dir.UP, "fill", 0.15);
        e.add(BLOCK, al, 9, 14, 8, Dir.UP, "fill", 0.15);
        e.fill(BLOCK, st, 5, 13, 8, 5, 14, 8, Dir.UP);
        e.name("brake", e.add(BRAKE, "bronze", 6, 14, 8, Dir.EAST, "force", 10));
        // ---- the governor on the spindle: a cross at y = 16, risers, the weights on their rods at y = 18
        e.add(BLOCK, al, 8, 16, 8, Dir.UP, "fill", 0.5);                                     // the hub
        e.fill(BLOCK, al, 2, 16, 8, 7, 16, 8, Dir.UP, "fill", 0.12);
        e.fill(BLOCK, al, 9, 16, 8, 14, 16, 8, Dir.UP, "fill", 0.12);
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? 1 : -1;                                                        // +1: the left arm
            // (the bushing a cell clear of the weight: touching it, it would be mounted on the slide it guides)
            int seat = 8 - 6 * s, spring = 8 - 5 * s, weight = 8 - 4 * s, bush = 8 - 2 * s;
            e.add(BLOCK, al, seat, 17, 8, Dir.UP, "fill", 0.12);
            e.add(BLOCK, al, seat, 18, 8, Dir.UP, "fill", 0.12);
            e.add(BLOCK, al, bush, 17, 8, Dir.UP, "fill", 0.12);
            // stop collars: none outwards for 30 mm, one against the bushing inwards — the spring holds the weight there
            e.add(LINEAR_BEARING, st, bush, 18, 8, s > 0 ? Dir.EAST : Dir.WEST, "travel_fwd", 0, "travel_back", 0.03);
            int rod = s > 0 ? e.fill(SHAFT, st, weight, 18, 8, 7, 18, 8, Dir.EAST, "diameter", 0.008)
                    : e.fill(SHAFT, st, 9, 18, 8, weight, 18, 8, Dir.EAST, "diameter", 0.008);
            e.name(side == 0 ? "rodA" : "rodB", rod);
            e.name(side == 0 ? "weightA" : "weightB", e.add(BLOCK, "brass", weight, 18, 8, Dir.UP, "fill", 0.3));
            e.name(side == 0 ? "springA" : "springB", e.add(SPRING, st, spring, 18, 8, Dir.EAST, "wire", 0.0037, "turns", 6,
                    "preload", 0.016));
            // the bar up from the weight's top and across to the sleeve's arm: it runs straight, at a slant
            e.name(side == 0 ? "barA" : "barB", e.fill(LINK, st, weight, 19, 8, weight, 22, 8, Dir.UP));
            e.fill(LINK, st, 8 - 3 * s, 22, 8, 8 - 2 * s, 22, 8, Dir.EAST);
        }
        // the sleeve: a rod on the axis sliding in a bushing held up from the hub by a bracket, a collar with two arms
        e.fill(BLOCK, al, 8, 16, 9, 8, 20, 9, Dir.UP, "fill", 0.5);
        e.add(LINEAR_BEARING, st, 8, 20, 8, Dir.UP, "travel", 0.03);
        e.name("sleeve", e.fill(SHAFT, st, 8, 19, 8, 8, 22, 8, Dir.UP, "diameter", 0.01));
        e.add(BLOCK, st, 8, 22, 8, Dir.UP, "fill", 0.5);
        e.fill(BLOCK, st, 7, 22, 8, 7, 22, 8, Dir.UP, "fill", 0.5);
        e.fill(BLOCK, st, 9, 22, 8, 9, 22, 8, Dir.UP, "fill", 0.5);
        // ---- off the spindle: the push rod up the axis to the bell crank on the rheostat's shaft
        e.name("pushRod", e.fill(LINK, st, 8, 23, 8, 8, 24, 8, Dir.UP));
        e.add(BLOCK, ny, 8, 25, 8, Dir.UP, "fill", 0.5);                                      // the crank's arm
        e.add(BLOCK, ny, 9, 25, 8, Dir.UP, "fill", 0.5);                                      // its hub
        // (the rheostat and its post behind the governor, out of the way of the view)
        e.name("crankShaft", e.fill(SHAFT, st, 9, 25, 6, 9, 25, 8, Dir.SOUTH, "diameter", 0.01));
        e.name("rheostat", e.add(POTENTIOMETER, ny, 9, 25, 6, Dir.SOUTH, "resistance", 6, "travel", 15, "wiper", 0));
        e.fill(BLOCK, st, 9, 26, 1, 9, 26, 6, Dir.UP);                                        // its bracket
        e.fill(BLOCK, st, 9, 0, 0, 9, 26, 0, Dir.UP);                                         // and post
        // ---- supply: a 24 V bank and a switch, through the rheostat (end A to the wiper) to the brushes
        int f = 1;
        int bat = e.add(BATTERY, "pvc", 16, f, 4, Dir.EAST, "cells", 11, "capacity", 20); // 11 lead–acid cells: 23.4 V full
        e.name("battery", bat);
        e.name("switch", e.add(SWITCH, "brass", 19, f, 4, Dir.EAST));
        Router r = new Router(e, 0, 0, 0, 22, 27, 15);
        r.pin("vb", 17, f, 4).pin("vb", 18, f, 4);
        r.pin("vp", 20, f, 4).pin("vp", 8, 25, 6);
        r.pin("vm", 9, 24, 6).pin("vm", 11, 8, 8);
        r.pin("gnd", 15, f, 4).pin("gnd", 5, 8, 8);
        // the rotor and its wiring sweep the cylinder round the spindle; the governor sweeps a wider one above
        r.keepClear(4, 1, 3, 12, 7, 13);
        r.keepClear(6, 8, 4, 10, 8, 12);
        r.keepClear(11, 8, 9, 12, 8, 11);
        r.keepClear(4, 8, 5, 5, 8, 7);
        r.keepClear(4, 9, 4, 12, 13, 13);
        r.keepClear(1, 14, 1, 15, 23, 15);
        r.keepClear(7, 23, 7, 9, 25, 9);
        r.route();
        return e.view(8.5, 21, 25, 22);
    }

    /**
     * An H-bridge: four MOSFETs put the DC motor across its supply either way round. Each side of the bridge is a
     * complementary pair (a P-channel to the supply, an N-channel to ground) with its gates joined, so its output is
     * the opposite of its input; two switches pull the inputs up. Forward or reverse: one side low, the other high.
     * Neither: both sides high, the motor shorted through the two P-channels — it brakes.
     */
    public static Exhibit hBridge() {
        return hBridge(false, 0);
    }

    /**
     * The H-bridge under PWM speed control: a Schmitt-trigger oscillator drives the bridge's left input; its timing
     * capacitor charges through one side of a knob potentiometer and discharges through the other (each through its own
     * diode), so the knob sets the fraction of each 40 ms period the motor is driven — and so its speed.
     *
     * @param knobAngle the knob's starting angle [rad] (0 = mid-travel)
     */
    public static Exhibit pwmMotor(double knobAngle) {
        return hBridge(true, knobAngle);
    }

    public static Exhibit pwmMotor() {
        return pwmMotor(0.4);
    }

    static Exhibit hBridge(boolean pwm, double knobAngle) {
        Exhibit e = pwm ? new Exhibit("pwm", "PWM speed control",
                List.of("PWM SPEED CONTROL", "Turn the knob: the", "pulses widen and", "the motor speeds up"),
                "The H-bridge again, but its left input is driven by pulses instead of a switch, about six times a "
                        + "second. A Schmitt-trigger oscillator's timing capacitor ramps up and down between 40 and 60 % "
                        + "of the supply; an op-amp compares that ramp with the knob's voltage and drives the bridge while "
                        + "the ramp is below it. Turning the knob raises or lowers the line the ramp is cut at, so the "
                        + "pulses widen or narrow while their rate stays the same. The motor is either on full or off — "
                        + "the transistors waste almost nothing — but its heavy rotor cannot follow the pulses, so it settles at "
                        + "the speed the average voltage (supply × fraction on) would give. Meter the bridge's output for "
                        + "the pulses, the rotor for its speed. This is how nearly every modern motor, LED and heater "
                        + "is dimmed: not by burning the difference in a resistor, but by switching.")
                : new Exhibit("hbridge", "H-bridge",
                List.of("H-BRIDGE", "FORWARD or REVERSE:", "four transistors swap", "the motor's leads"),
                "The brushed DC motor, run both ways by four transistors in an H. Each upright of the H is a "
                        + "complementary pair: a P-channel MOSFET from the 9.5 V supply and an N-channel one to ground, "
                        + "their gates joined — whatever voltage the gates get, one conducts and the other blocks, so the "
                        + "pair's output is the opposite of its input. The motor bridges the two outputs. Close FORWARD: "
                        + "the left input goes high, its output low, and current runs right to left through the motor. "
                        + "Close REVERSE instead and it runs the other way: the motor turns backwards — nothing but "
                        + "the direction of the current through its coils has changed (F = I·L × B). Open both and "
                        + "both outputs go high: the motor is shorted through the P-channels, and its own back-EMF drives a "
                        + "current that brakes it (dynamic braking — it slows with the same time constant it spun up "
                        + "with, J·R/K², far faster than friction alone would stop it). Close both and both go low — the "
                        + "same brake. "
                        + "Every electric car, drill and robot joint has bridges like this, switched thousands of times "
                        + "a second to set speed as well as direction.");
        e.plots = 2;
        int shaft = brushedMotor(e, 12);
        e.init(shaft, new Exhibit.Initial(Double.NaN, 0, 0, 0.3)); // just past the dead point
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 10}, {8, 8, 10}, {8, 7, 10}, {9, 7, 10}, {10, 7, 10}, {11, 7, 10},
                {11, 6, 10}, {11, 5, 10}, {11, 5, 9}, {11, 5, 8}});
        int f = 1;
        int bat = e.add(BATTERY, "pvc", 16, f, 2, Dir.EAST, "chemistry", 1, "cells", 7, "capacity", 10); // 7 nickel–metal hydride cells: 9.1 V at half charge
        e.name("battery", bat);
        Object[] power = {"transconductance", 5.0, "threshold", 2.0};
        e.name("pA", e.add(TRANSISTOR, "silicon", 20, f, 5, Dir.EAST, with(power, "type", 3)));
        e.name("nA", e.add(TRANSISTOR, "silicon", 22, f, 8, Dir.EAST, with(power, "type", 2)));
        e.name("pB", e.add(TRANSISTOR, "silicon", 26, f, 5, Dir.EAST, with(power, "type", 3)));
        e.name("nB", e.add(TRANSISTOR, "silicon", 28, f, 8, Dir.EAST, with(power, "type", 2)));
        e.add(RESISTOR, "alumina", 18, f, 11, Dir.EAST, "resistance", 10_000); // input pull-downs
        e.add(RESISTOR, "alumina", 24, f, 11, Dir.EAST, "resistance", 10_000);
        if (!pwm) e.name("forward", e.add(SWITCH, "brass", 18, f, 13, Dir.EAST));
        e.name("reverse", e.add(SWITCH, "brass", 24, f, 13, Dir.EAST));
        if (pwm) {
            // the oscillator: a Schmitt inverter with 220 kΩ feedback onto 1 µF (≈ 5.6 Hz — the heavy rotor averages far
            // slower; each switching edge costs the solver ~130 small steps, so slower is cheaper): its capacitor ramps between 0.4
            // and 0.6 of the supply. An op-amp compares the ramp with the knob's voltage: its output is high while the
            // ramp is below the knob, so the knob sets how much of each period the bridge drives the motor
            e.name("osc", e.add(LOGIC, "silicon", 32, f, 4, Dir.EAST, "function", 7));
            e.add(RESISTOR, "alumina", 32, f, 1, Dir.EAST, "resistance", 220_000);
            e.add(CAPACITOR, "alumina", 34, f, 7, Dir.SOUTH, "capacitance", 1e-6);
            e.name("comparator", e.add(OPAMP, "silicon", 32, f, 11, Dir.EAST));
            // the knob's 10 kΩ track between 15 kΩ to the supply and 15 kΩ to ground: it spans 0.375 … 0.625 of the supply
            e.add(RESISTOR, "alumina", 34, f, 13, Dir.EAST, "resistance", 15_000);
            e.add(RESISTOR, "alumina", 34, f, 15, Dir.EAST, "resistance", 15_000);
            int ky = 3, kz = 14;
            String st = "steel_1045";
            int knob = e.fill(SHAFT, st, 30, ky, kz, 33, ky, kz, Dir.EAST, "diameter", 0.012);
            e.name("knob", knob);
            e.init(knob, new Exhibit.Initial(Double.NaN, 0, 0, knobAngle));
            e.add(BEARING, "bronze", 30, ky, kz, Dir.EAST, "type", 2, "viscosity", 2000); // damped: it stays where it is left
            e.fill(BLOCK, st, 30, 0, kz, 30, ky - 1, kz, Dir.UP);
            e.name("pot", e.add(POTENTIOMETER, "nylon_66", 31, ky, kz, Dir.EAST, "lead_axis", 1, "resistance", 10_000));
            e.fill(BLOCK, st, 31, 0, kz + 1, 31, ky, kz + 1, Dir.UP);
            e.name("crank", e.add(CRANK, st, 33, ky, kz, Dir.EAST, "radius", 0.06));
        }
        Router r = new Router(e, 0, 0, 0, 35, 18, 15);
        r.pin("vdd", 17, f, 2).pin("vdd", 19, f, 5).pin("vdd", 25, f, 5).pin("vdd", 23, f, 13);
        r.pin("gnd", 15, f, 2).pin("gnd", 21, f, 8).pin("gnd", 27, f, 8).pin("gnd", 17, f, 11).pin("gnd", 23, f, 11);
        r.pin("inA", 20, f, 4).pin("inA", 22, f, 7).pin("inA", 19, f, 11);
        if (pwm) {
            r.pin("vdd", 32, f, 5).pin("gnd", 32, f, 3);                       // the Schmitt's supply
            r.pin("ct", 31, f, 4).pin("ct", 31, f, 1).pin("ct", 34, f, 6).pin("ct", 32, 0, 11);
            r.pin("osc", 33, f, 4).pin("osc", 33, f, 1);
            r.pin("gnd", 34, f, 8);
            r.pin("vdd", 32, f, 12).pin("gnd", 32, f, 10);                     // the op-amp's supply
            r.pin("knob", 31, f, 11).pin("knob", 31, 3, 13);                  // wiper → in+
            r.pin("inA", 33, f, 11);
            r.pin("trackP", 31, 4, 14).pin("trackP", 33, f, 13).pin("vdd", 35, f, 13);
            r.pin("trackN", 31, 2, 14).pin("trackN", 33, f, 15).pin("gnd", 35, f, 15);
            r.keepClear(32, 2, 13, 33, 4, 15); // the knob's crank turns there
        } else {
            r.pin("vdd", 17, f, 13).pin("inA", 19, f, 13);
        }
        r.pin("inB", 26, f, 4).pin("inB", 28, f, 7).pin("inB", 25, f, 11).pin("inB", 25, f, 13);
        r.pin("outA", 21, f, 5).pin("outA", 23, f, 8).pin("outA", 11, 8, 8);
        r.pin("outB", 27, f, 5).pin("outB", 29, f, 8).pin("outB", 5, 8, 8);
        // the rotor and its wiring sweep the cylinder round the shaft; no wire may touch the rotor's wiring
        r.keepClear(4, 1, 3, 12, 7, 13);
        r.keepClear(6, 8, 4, 10, 8, 12);
        r.keepClear(11, 8, 9, 12, 8, 11);
        r.keepClear(4, 8, 5, 5, 8, 7);
        r.keepClear(5, 9, 4, 11, 13, 12);
        r.route();
        return e.view(17, 12, 24, 30);
    }

    /**
     * An inverter: the H-bridge driven from both sides in turn by an oscillator and its inverse makes the 9.5 V supply
     * into a square alternating voltage across the primary of Faraday's ring; the 1000-turn secondary steps it up five
     * times, and two LEDs back to back on it light in turn, one for each half-cycle.
     */
    public static Exhibit inverter() {
        Exhibit e = new Exhibit("inverter", "Inverter and transformer",
                List.of("INVERTER", "DC in, AC out: the", "ring steps it up,", "the LEDs take turns"),
                "A transformer needs alternating current — a steady current makes a steady flux, and only a changing "
                        + "flux induces anything (Faraday's ring, next door, shows it with a switch). Here an H-bridge makes "
                        + "the alternation: an oscillator drives one side of the bridge and, through a second inverter, "
                        + "the other side the opposite way, so the 9.5 V supply is put across the ring's 200-turn primary "
                        + "first one way, then the other, two or three times a second. The flux in the iron ramps up and down "
                        + "between the reversals (V = N·dΦ/dt), and the 1000-turn secondary carries five times the "
                        + "voltage, reversing with it: two LEDs back to back take turns to light, one for each direction. "
                        + "The flux must turn round before the iron saturates — at a lower frequency it would ramp past "
                        + "the steel's 2 tesla; that is why mains transformers are built for their frequency. Each trip round "
                        + "the steel's hysteresis loop costs it about 0.4 J — the core warms — which is why transformer "
                        + "iron is a soft, low-coercivity steel. Every "
                        + "solar inverter and laptop charger does this, a thousand times faster, with a far smaller core.");
        e.plots = 2;
        String core = "electrical_steel";
        for (int i = 4; i <= 10; i++) {
            e.add(BLOCK, core, i, 1, 5, Dir.UP);
            e.add(BLOCK, core, i, 1, 11, Dir.UP);
        }
        for (int z = 6; z <= 10; z++) {
            if (z == 8) continue;
            e.add(BLOCK, core, 4, 1, z, Dir.UP);
            e.add(BLOCK, core, 10, 1, z, Dir.UP);
        }
        for (int[] c : new int[][]{{4, 5}, {10, 5}, {4, 11}, {10, 11}}) e.add(BLOCK, "oak", c[0], 0, c[1], Dir.UP);
        e.name("primary", e.add(COIL, "copper", 4, 1, 8, Dir.SOUTH, "turns", 200, "wire_diameter", 0.8e-3, "core", 1,
                "bore_fraction", 0.6, "lead_axis", 1));
        e.name("secondary", e.add(COIL, "copper", 10, 1, 8, Dir.SOUTH, "turns", 1000, "wire_diameter", 0.3e-3, "core", 1,
                "bore_fraction", 0.6, "lead_axis", 1));
        e.name("green", e.add(LED, "silicon", 13, 0, 6, Dir.EAST, "wavelength", 530));
        e.name("red", e.add(LED, "silicon", 13, 0, 10, Dir.WEST, "wavelength", 630));
        e.add(RESISTOR, "alumina", 13, 0, 13, Dir.EAST, "resistance", 4700);
        int f = 1;
        int bat = e.add(BATTERY, "pvc", 16, f, 2, Dir.EAST, "chemistry", 1, "cells", 7, "capacity", 10); // 7 nickel–metal hydride cells: 9.1 V at half charge
        e.name("battery", bat);
        Object[] power = {"transconductance", 5.0, "threshold", 2.0};
        e.add(TRANSISTOR, "silicon", 20, f, 5, Dir.EAST, with(power, "type", 3));
        e.add(TRANSISTOR, "silicon", 22, f, 8, Dir.EAST, with(power, "type", 2));
        e.add(TRANSISTOR, "silicon", 26, f, 5, Dir.EAST, with(power, "type", 3));
        e.add(TRANSISTOR, "silicon", 28, f, 8, Dir.EAST, with(power, "type", 2));
        // the oscillator (Schmitt inverter, 470 kΩ onto 1 µF: ≈ 2.6 Hz, the core's flux swinging ±1.1 T — and each switching
        // edge costs the solver dearly) and a second inverter for the other side
        e.name("osc", e.add(LOGIC, "silicon", 32, f, 4, Dir.EAST, "function", 7));
        e.add(RESISTOR, "alumina", 32, f, 1, Dir.EAST, "resistance", 470_000);
        e.add(CAPACITOR, "alumina", 34, f, 7, Dir.SOUTH, "capacitance", 1e-6);
        e.add(LOGIC, "silicon", 32, f, 10, Dir.EAST, "function", 7);
        Router r = new Router(e, 0, 0, 0, 35, 4, 15);
        r.pin("vdd", 17, f, 2).pin("vdd", 19, f, 5).pin("vdd", 25, f, 5).pin("vdd", 32, f, 5).pin("vdd", 32, f, 11);
        r.pin("gnd", 15, f, 2).pin("gnd", 21, f, 8).pin("gnd", 27, f, 8).pin("gnd", 32, f, 3).pin("gnd", 34, f, 8).pin("gnd", 32, f, 9);
        r.pin("ct", 31, f, 4).pin("ct", 31, f, 1).pin("ct", 34, f, 6);
        r.pin("inA", 33, f, 4).pin("inA", 33, f, 1).pin("inA", 31, f, 10).pin("inA", 20, f, 4).pin("inA", 22, f, 7);
        r.pin("inB", 33, f, 10).pin("inB", 26, f, 4).pin("inB", 28, f, 7);
        r.pin("outA", 21, f, 5).pin("outA", 23, f, 8).pin("outA", 4, 2, 8);
        r.pin("outB", 27, f, 5).pin("outB", 29, f, 8).pin("outB", 4, 0, 8);
        r.pin("s1", 10, 2, 8).pin("s1", 12, 0, 6).pin("s1", 12, 0, 10);
        r.pin("s2", 14, 0, 6).pin("s2", 14, 0, 10).pin("s2", 14, 0, 13);
        r.pin("s3", 12, 0, 13).pin("s3", 10, 0, 8);
        r.route();
        return e.view(16, 9, 22, 40);
    }

    private static Object[] with(Object[] base, Object... more) {
        Object[] o = java.util.Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, o, base.length, more.length);
        return o;
    }

    /**
     * The DC motor exhibit's brushed motor (frame, magnets, rotor coils, commutator and brushes, coil B's wiring) with its
     * shaft running up to {@code top}; segment A's lead stops at 8, 9, 9 for the caller to take on to coil A's outer
     * lead (11, 5, 8). The brushes' supply leads are at 11, 8, 8 and 5, 8, 8. Returns the shaft's part index.
     */
    static int brushedMotor(Exhibit e, int top) {
        String st = "steel_1045", ny = "nylon_66";
        e.fill(BLOCK, st, 9, 0, 8, 9, 1, 8, Dir.UP);
        e.fill(BLOCK, st, 7, 0, 8, 7, 1, 8, Dir.UP);
        e.fill(BLOCK, st, 11, 0, 12, 11, 11, 12, Dir.UP);
        e.fill(BLOCK, st, 5, 0, 12, 5, 11, 12, Dir.UP);
        e.fill(BLOCK, st, 9, 12, 8, 11, 12, 8, Dir.UP);
        e.fill(BLOCK, st, 11, 12, 9, 11, 12, 12, Dir.UP);
        e.fill(BLOCK, st, 5, 12, 8, 7, 12, 8, Dir.UP);
        e.fill(BLOCK, st, 5, 12, 9, 5, 12, 12, Dir.UP);
        e.fill(BLOCK, st, 10, 3, 8, 10, 3, 12, Dir.UP);
        e.fill(BLOCK, st, 6, 3, 8, 6, 3, 12, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 10, 4, 8, Dir.UP);
        e.add(MAGNET, "ndfeb_n42", 6, 4, 8, Dir.DOWN);
        int shaft = e.fill(SHAFT, st, 8, 1, 8, 8, top, 8, Dir.UP, "diameter", 0.02);
        e.name("rotor", shaft);
        e.add(BEARING, st, 8, 1, 8, Dir.UP);
        e.add(BEARING, st, 8, 12, 8, Dir.UP);
        e.fill(BLOCK, ny, 6, 6, 6, 10, 6, 10, Dir.UP, "fill", 0.3);
        e.add(COIL, "copper", 10, 5, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3);
        e.add(COIL, "copper", 6, 5, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3, "lead_axis", 2);
        e.add(BLOCK, ny, 8, 8, 8, Dir.UP);
        e.fill(BLOCK, "copper", 7, 8, 9, 9, 8, 9, Dir.UP, "fill", 0.6);
        e.fill(BLOCK, "copper", 7, 8, 7, 9, 8, 7, Dir.UP, "fill", 0.6);
        e.add(WIRE, "copper", 8, 9, 9, Dir.UP); // segment A's lead (the caller takes it on to coil A's outer lead at 11, 5, 8)
        e.path(INSULATED_WIRE, "copper", new int[][]{{9, 5, 8}, {9, 5, 7}, {8, 5, 7}, {7, 5, 7}, {6, 5, 7}});
        e.add(WIRE, "copper", 8, 9, 7, Dir.UP);
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 6}, {8, 8, 6}, {8, 7, 6}, {7, 7, 6}, {6, 7, 6}, {5, 7, 6},
                {5, 6, 6}, {5, 6, 7}, {5, 6, 8}, {5, 6, 9}, {5, 5, 9}, {6, 5, 9}});
        e.add(BRUSH, "graphite", 10, 8, 8, Dir.WEST);
        e.fill(BLOCK, ny, 10, 9, 8, 10, 11, 8, Dir.UP);
        e.add(BRUSH, "graphite", 6, 8, 8, Dir.EAST);
        e.fill(BLOCK, ny, 6, 9, 8, 6, 11, 8, Dir.UP);
        return shaft;
    }
}
