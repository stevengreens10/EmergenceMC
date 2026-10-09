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
                List.of("SERVO ARM", "Turn the knob: the", "arm follows it.", "PD control, op-amp"),
                "Closed-loop position control from parts. Turning the knob moves the wiper of its potentiometer; a "
                        + "second potentiometer on the arm's shaft reports where the arm is. The op-amp amplifies the "
                        + "difference ten times and two transistors (NPN up, PNP down) pass that voltage, with amps of "
                        + "current, to two coils above and below a magnet on the shaft: torque i·dΨ/dθ turns the arm "
                        + "until the two wipers agree. Holding the arm up against gravity needs a steady current, so a "
                        + "steady error remains (proportional droop); the capacitor across the feedback resistor "
                        + "makes the drive respond to the arm's speed too, damping the swing. Powered by two 12 V "
                        + "supercapacitor banks.");
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
        e.add(RESISTOR, "alumina", 14, f, 11, Dir.EAST, "resistance", 100_000);   // in+ → 0 V
        e.add(RESISTOR, "alumina", 14, f, 4, Dir.EAST, "resistance", 10_000);     // position → in−
        e.name("cd", e.add(CAPACITOR, "alumina", 14, f, 2, Dir.EAST, "capacitance", 4.7e-6)); // derivative
        e.add(RESISTOR, "alumina", 22, f, 5, Dir.EAST, "resistance", 100_000);    // drive → in−
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
        r.pin("gnd", 23, f, 2).pin("gnd", 29, f, 2).pin("gnd", 15, f, 11)           // banks' 0 V, in+ divider
                .pin("gnd", 2, y + 1, z);                                            // motor return
        r.pin("set", 11, ky, kz - 1).pin("set", 13, f, 8);
        r.pin("pos", 7, y, z - 1).pin("pos", 13, f, 4).pin("pos", 13, f, 2);
        r.pin("inP", 15, f, 8).pin("inP", 13, f, 11).pin("inP", 17, f, 8);
        r.pin("inN", 15, f, 4).pin("inN", 15, f, 2).pin("inN", 21, f, 5).pin("inN", 18, 0, 8);
        r.pin("out", 19, f, 8).pin("out", 22, f, 9).pin("out", 22, f, 12);
        r.pin("drive", 21, f, 10).pin("drive", 21, f, 13).pin("drive", 23, f, 5).pin("drive", 4, y, z + 1);
        // coils in series, the drive entering each at its + lead: south, north, down, up
        r.pin("c1", 4, y + 1, z).pin("c1", 2, y - 1, z);
        r.pin("c2", 4, y - 1, z).pin("c2", 2, y, z - 1);
        r.pin("c3", 4, y, z - 1).pin("c3", 2, y, z + 1);
        // the arm sweeps the plane x = 9 and the magnet the cells round the shaft at x = 3; the knob's crank turns at x = 13
        r.keepClear(9, 0, 1, 9, 8, 9);
        r.keepClear(3, y - 1, z - 1, 3, y + 1, z + 1);
        r.keepClear(13, 0, kz - 2, 13, ky + 2, 15);
        r.route();
        return e.view(15, 7.5, 13, 20, 140);
    }
}
