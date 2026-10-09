package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;

/** Exhibits of translational mechanics: slides, springs, oscillation. */
public final class MechanicsExhibits {
    private MechanicsExhibits() {
    }

    /**
     * A mass on a spring: a brass weight on the lower end of a vertical rod that slides in two linear bearings,
     * resting on a helical spring. Released with the spring unloaded, it falls, overshoots and bounces about the
     * point where the spring carries its weight — period 2π√(m/k) — slowly losing energy to bearing friction.
     */
    public static Exhibit springMass() {
        Exhibit e = new Exhibit("springmass", "Spring and mass",
                List.of("SPRING AND MASS", "Released: it bounces", "at 2π√(m/k), about", "where k·x = m·g"),
                "A 2 kg brass weight hangs on a steel rod that slides up and down in two ball bushings, and rests on a "
                        + "spring of 3 mm steel wire (k = G·d⁴/(8·D³·N) ≈ 1.5 kN/m: ten turns, 37 mm across). It was let "
                        + "go with the spring unloaded, so it drops, overshoots, and bounces about the height where the "
                        + "spring holds its weight (x = m·g/k ≈ 1.6 cm) about four times a second. Bearing friction "
                        + "takes the energy slowly. Meter the rod for its speed and position, or clip the data logger on "
                        + "it to see the sine wave and measure the period.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 10, 0, 8, 10, 7, 8, Dir.UP);                       // column, with brackets to the bearings
        e.add(BLOCK, st, 9, 5, 8, Dir.UP);
        e.add(BLOCK, st, 9, 7, 8, Dir.UP);
        int rod = e.fill(SHAFT, st, 8, 4, 8, 8, 8, 8, Dir.UP, "diameter", 0.012);
        e.name("rod", rod);
        e.add(LINEAR_BEARING, st, 8, 5, 8, Dir.UP);
        e.add(LINEAR_BEARING, st, 8, 7, 8, Dir.UP);
        e.name("weight", e.add(BLOCK, "brass", 8, 3, 8, Dir.UP));
        e.name("spring", e.add(SPRING, "steel_1045", 8, 2, 8, Dir.UP, "wire", 0.003));
        e.fill(BLOCK, st, 8, 0, 8, 8, 1, 8, Dir.UP);                         // the spring's seat
        return e.view(8.5, 4.5, 15, 10);
    }

    /**
     * A vibration energy harvester: the weight on the spring is a magnet, bouncing beside a coil. Its moving field
     * induces an EMF in the coil; with the switch closed, current flows through the load resistor and its field
     * pushes back on the magnet (Lenz), so the bouncing dies away much faster — its energy has gone into heat in the
     * resistor and the coil.
     */
    public static Exhibit harvester() {
        Exhibit e = new Exhibit("harvester", "Magnet on a spring",
                List.of("MAGNET ON A SPRING", "A bouncing magnet", "between two coils:", "energy harvesting"),
                "The weight on this spring is a neodymium magnet, bouncing between two coils of 1000 turns. Its moving "
                        + "field changes the flux through the coils and induces a voltage (Faraday) — a vibration energy "
                        + "harvester. Close the switch and current flows through the 10 Ω load; that current's field "
                        + "pushes back on the magnet (Lenz), taking exactly the energy the load turns into heat. Here the "
                        + "braking is gentle: a heavy magnet on a stiff spring stores far more energy each swing than a "
                        + "few coils can take from it, so it still rings for a long while — the reason real harvesters "
                        + "are tuned so carefully. Push the magnet (use it) to set it going; meter the load.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 10, 0, 8, 10, 7, 8, Dir.UP);                       // column, with brackets to the bearings
        e.add(BLOCK, st, 9, 5, 8, Dir.UP);
        e.add(BLOCK, st, 9, 7, 8, Dir.UP);
        int rod = e.fill(SHAFT, st, 8, 4, 8, 8, 8, 8, Dir.UP, "diameter", 0.012);
        e.name("rod", rod);
        e.add(LINEAR_BEARING, st, 8, 5, 8, Dir.UP);
        e.add(LINEAR_BEARING, st, 8, 7, 8, Dir.UP);
        e.name("magnet", e.add(MAGNET, "ndfeb_n42", 8, 3, 8, Dir.SOUTH)); // magnetised towards the coils
        e.name("spring", e.add(SPRING, st, 8, 2, 8, Dir.UP, "wire", 0.003));
        e.fill(BLOCK, st, 8, 0, 8, 8, 1, 8, Dir.UP);
        // two coils, one each side, a cell above the magnet's rest height where its flux through them changes fastest
        e.name("coil", e.add(COIL, "copper", 8, 4, 9, Dir.SOUTH, "turns", 1000, "wire_diameter", 0.8e-3));
        e.add(COIL, "copper", 8, 4, 7, Dir.SOUTH, "turns", 1000, "wire_diameter", 0.8e-3);
        // held from beyond, clear of the moving magnet
        e.fill(BLOCK, "nylon_66", 8, 0, 10, 8, 4, 10, Dir.UP);
        e.fill(BLOCK, "nylon_66", 8, 0, 6, 8, 4, 6, Dir.UP);
        e.name("switch", e.add(SWITCH, "brass", 4, 1, 12, Dir.EAST));
        e.name("load", e.add(RESISTOR, "alumina", 12, 1, 12, Dir.EAST, "resistance", 10));
        Router r = new Router(e, 0, 0, 0, 15, 6, 15);
        r.pin("a", 7, 4, 9).pin("a", 3, 1, 12);
        r.pin("b", 5, 1, 12).pin("b", 11, 1, 12);
        r.pin("c", 13, 1, 12).pin("c", 9, 4, 7);
        r.pin("d", 9, 4, 9).pin("d", 7, 4, 7);  // the coils in series, aiding
        r.route();
        return e.view(8.5, 4.5, 15, 10);
    }
}
