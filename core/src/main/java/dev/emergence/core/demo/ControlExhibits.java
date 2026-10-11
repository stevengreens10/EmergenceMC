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
                        + "damping the swing (derivative action): a PID controller. Powered by two 12 V supercapacitor "
                        + "banks.");
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
        int bp = e.add(CAPACITOR, "alumina", 24, f, 2, Dir.EAST, "capacitance", 3000);
        int bn = e.add(CAPACITOR, "alumina", 28, f, 2, Dir.EAST, "capacitance", 3000);
        e.name("batteryP", bp).name("batteryN", bn);
        e.init(bp, new Exhibit.Initial(Double.NaN, 0, 12));
        e.init(bn, new Exhibit.Initial(Double.NaN, 0, 12));
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
        r.pin("gnd", 23, f, 2).pin("gnd", 29, f, 2).pin("gnd", 18, f, 11)           // banks' 0 V, in+ divider
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
        int shaft = e.fill(SHAFT, st, 8, 1, 8, 8, 14, 8, Dir.UP, "diameter", 0.02);
        e.name("rotor", shaft);
        // it starts at its governed speed, the tacho and integrator capacitors charged to match (as if it had been running)
        e.init(shaft, new Exhibit.Initial(Double.NaN, 25.5, 0, 0.3));
        e.add(BEARING, st, 8, 1, 8, Dir.UP);
        e.add(BEARING, st, 8, 12, 8, Dir.UP);
        e.fill(BLOCK, ny, 6, 6, 6, 10, 6, 10, Dir.UP, "fill", 0.3);
        e.add(COIL, "copper", 10, 5, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3);
        e.add(COIL, "copper", 6, 5, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3, "lead_axis", 2);
        e.add(BLOCK, ny, 8, 8, 8, Dir.UP);
        e.fill(BLOCK, "copper", 7, 8, 9, 9, 8, 9, Dir.UP, "fill", 0.6);
        e.fill(BLOCK, "copper", 7, 8, 7, 9, 8, 7, Dir.UP, "fill", 0.6);
        e.add(WIRE, "copper", 8, 9, 9, Dir.UP);
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 10}, {8, 8, 10}, {8, 7, 10}, {9, 7, 10}, {10, 7, 10}, {11, 7, 10},
                {11, 6, 10}, {11, 5, 10}, {11, 5, 9}, {11, 5, 8}});
        e.path(INSULATED_WIRE, "copper", new int[][]{{9, 5, 8}, {9, 5, 7}, {8, 5, 7}, {7, 5, 7}, {6, 5, 7}});
        e.add(WIRE, "copper", 8, 9, 7, Dir.UP);
        e.path(INSULATED_WIRE, "copper", new int[][]{{8, 9, 6}, {8, 8, 6}, {8, 7, 6}, {7, 7, 6}, {6, 7, 6}, {5, 7, 6},
                {5, 6, 6}, {5, 6, 7}, {5, 6, 8}, {5, 6, 9}, {5, 5, 9}, {6, 5, 9}});
        e.add(BRUSH, "graphite", 10, 8, 8, Dir.WEST);
        e.fill(BLOCK, ny, 10, 9, 8, 10, 11, 8, Dir.UP);
        e.add(BRUSH, "graphite", 6, 8, 8, Dir.EAST);
        e.fill(BLOCK, ny, 6, 9, 8, 6, 11, 8, Dir.UP);
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
        // ---- electronics on the floor in front (y = 1, z ≥ 15)
        int f = 1;
        int bat = e.add(CAPACITOR, "alumina", 2, f, 16, Dir.EAST, "capacitance", 3000);
        e.name("battery", bat);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 24));
        boolean closed = Double.isNaN(openLoop);
        if (closed) {
            e.name("opamp", e.add(OPAMP, "silicon", 10, f, 19, Dir.EAST));
            e.add(RESISTOR, "alumina", 6, f, 21, Dir.EAST, "resistance", 47_000);        // reference: +24 V → in+
            e.add(RESISTOR, "alumina", 6, f, 23, Dir.EAST, "resistance", 10_000);        // in+ → 0 V
            e.add(RESISTOR, "alumina", 6, f, 17, Dir.EAST, "resistance", 10_000);        // tacho → in−
            e.add(RESISTOR, "alumina", 13, f, 16, Dir.EAST, "resistance", 100_000);      // in− → feedback capacitor
            e.name("ci", e.add(CAPACITOR, "alumina", 16, f, 16, Dir.EAST, "capacitance", 10e-6));
            e.init(e.named.get("ci"), new Exhibit.Initial(Double.NaN, 0, 5.8));
            e.name("npn", e.add(TRANSISTOR, "silicon", 14, f, 20, Dir.EAST, "type", 0, "beta", 60, "saturation_current", 1e-12,
                    "surface_area", 0.03));
            // a driver transistor feeding the power transistor's base (a Darlington pair): the op-amp could not source the
            // ~50 mA of base current amps of motor current need
            e.name("driver", e.add(TRANSISTOR, "silicon", 17, f, 20, Dir.EAST, "type", 0, "beta", 100, "saturation_current", 1e-14));
            // across the motor: a suppression capacitor and a freewheel diode take the commutator's switching surges
            e.add(CAPACITOR, "alumina", 13, f, 23, Dir.EAST, "capacitance", 470e-6);
            e.add(DIODE, "silicon", 17, f, 23, Dir.EAST);
        } else {
            e.init(bat, new Exhibit.Initial(Double.NaN, 0, openLoop));
            e.name("switch", e.add(SWITCH, "brass", 5, f, 16, Dir.EAST));
        }
        // the tacho's rectifier: a diode charging a capacitor, a resistor bleeding it (τ = 2 s)
        e.add(DIODE, "silicon", 6, f, 25, Dir.EAST);
        e.name("ct", e.add(CAPACITOR, "alumina", 10, f, 25, Dir.EAST, "capacitance", 20e-6));
        e.init(e.named.get("ct"), new Exhibit.Initial(Double.NaN, 0, 4.15));
        e.add(RESISTOR, "alumina", 14, f, 25, Dir.EAST, "resistance", 100_000);
        Router r = new Router(e, 0, 0, 0, 18, 18, 27);
        if (closed) {
            r.pin("vp", 3, f, 16).pin("vp", 10, f, 20).pin("vp", 5, f, 21).pin("vp", 15, f, 20).pin("vp", 18, f, 20);
            r.pin("gnd", 10, f, 18).pin("gnd", 7, f, 23);
            r.pin("inP", 7, f, 21).pin("inP", 5, f, 23).pin("inP", 9, f, 19);
            r.pin("inN", 7, f, 17).pin("inN", 10, 0, 19).pin("inN", 12, f, 16);
            r.pin("fb", 14, f, 16).pin("fb", 15, f, 16);
            r.pin("out", 11, f, 19).pin("out", 17, f, 16).pin("out", 17, f, 19);
            r.pin("b2", 16, f, 20).pin("b2", 14, f, 19);
            r.pin("mot", 13, f, 20).pin("mot", 11, 8, 8).pin("mot", 14, f, 23).pin("mot", 18, f, 23);
            r.pin("gnd", 12, f, 23).pin("gnd", 16, f, 23);
            r.pin("tacho", 5, f, 17);
        } else {
            r.pin("vb", 3, f, 16).pin("vb", 4, f, 16);
            r.pin("vp", 6, f, 16).pin("vp", 11, 8, 8);
        }
        r.pin("gnd", 1, f, 16).pin("gnd", 5, 8, 8);
        r.pin("tacho", 7, f, 25).pin("tacho", 11, f, 25).pin("tacho", 13, f, 25);
        r.pin("coil", 5, f, 25);
        r.pin("coil", 9, 16, 9).pin("gnd", 9, 16, 7);
        r.pin("gnd", 9, f, 25).pin("gnd", 15, f, 25);
        // the rotor, its wiring and the tacho disc sweep the cylinder round the shaft; no wire may touch the rotor's
        // wiring either (it would hold it to the frame)
        r.keepClear(4, 1, 3, 12, 7, 13);
        r.keepClear(6, 8, 4, 10, 8, 12);
        r.keepClear(11, 8, 9, 12, 8, 11);
        r.keepClear(4, 8, 5, 5, 8, 7);
        r.keepClear(5, 9, 4, 11, 15, 12);
        r.route();
        return e.view(9, 13, 30, 18, 180);
    }
}
