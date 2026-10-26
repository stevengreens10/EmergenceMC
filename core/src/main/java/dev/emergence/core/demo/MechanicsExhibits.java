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
        return e.view(14.5, 6, 14.5, 18, 135); // from the south-east: past the coil holders
    }

    /**
     * Bramah's hydraulic press: a narrow water cylinder (1.2 cm bore) pressed by hand and a wide one (5 cm) lifting a
     * lead weight, joined by a pipe. The pressure is the same everywhere in the water (Pascal), so the ram pushes with
     * the hand's force times the ratio of the piston areas, ~17 — and moves 17 times less.
     */
    public static Exhibit hydraulicPress() {
        Exhibit e = new Exhibit("press", "Hydraulic press",
                List.of("HYDRAULIC PRESS", "Push the small", "piston: the big one", "lifts 17x the force"),
                "Two water-filled cylinders joined by a pipe. Push the small piston down (use its handle): the water "
                        + "cannot be squeezed, so its pressure rises — the same everywhere in it (Pascal's law) — and that "
                        + "pressure on the big piston, 17 times the area, pushes up with 17 times your force, lifting the "
                        + "lead weights. Nothing is free: the big piston rises only 1/17 as far as you pushed, so the work "
                        + "in equals the work out (less friction). Meter the cylinders for the pressure.");
        String st = "steel_1045";
        // base and the water pipe joining the cylinders' heads, capped at both ends
        e.fill(BLOCK, st, 3, 0, 8, 11, 0, 8, Dir.UP);
        e.add(BLOCK, st, 3, 1, 8, Dir.UP);
        e.add(BLOCK, st, 11, 1, 8, Dir.UP);
        e.fill(PIPE, st, 4, 1, 8, 10, 1, 8, Dir.EAST, "bore", 0.01, "fluid", 1);
        // the pump: 1.2 cm bore; its rod slides in a bearing held from a column, with a handle on top
        e.name("pump", e.add(CYLINDER, st, 4, 2, 8, Dir.UP, "bore", 0.012, "fluid", 1));
        int prod = e.fill(SHAFT, st, 4, 3, 8, 4, 6, 8, Dir.UP, "diameter", 0.01);
        e.name("pumpRod", prod);
        e.add(LINEAR_BEARING, st, 4, 5, 8, Dir.UP);
        e.fill(BLOCK, st, 5, 2, 8, 5, 5, 8, Dir.UP);              // a column standing on the pipe
        e.name("handle", e.add(BLOCK, "oak", 4, 7, 8, Dir.UP, "fill", 0.3));
        // the ram: 5 cm bore, carrying two lead weights (6.2 kg in all: 31 kPa under the ram, more than the pump's own
        // 0.2 kg presses on its 1.2 cm piston, 18 kPa — with one weight the pump sank under its own weight, lifting the ram
        // until it lay on its stop)
        e.name("ram", e.add(CYLINDER, st, 10, 2, 8, Dir.UP, "bore", 0.05, "fluid", 1));
        int rrod = e.fill(SHAFT, st, 10, 3, 8, 10, 6, 8, Dir.UP, "diameter", 0.02);
        e.name("ramRod", rrod);
        e.add(LINEAR_BEARING, st, 10, 5, 8, Dir.UP);
        e.fill(BLOCK, st, 9, 2, 8, 9, 5, 8, Dir.UP);
        e.name("weight", e.add(BLOCK, "lead", 10, 7, 8, Dir.UP));
        e.add(BLOCK, "lead", 10, 8, 8, Dir.UP);
        return e.view(7, 5, 15, 12);
    }

    /**
     * A hydraulic jack: a hand pump (2 cm bore) between two check valves — one letting water in from an open
     * reservoir, one letting it out to the ram (5 cm bore) carrying a lead weight. Each push drives water into the
     * ram; each pull refills the pump from the reservoir while the outlet valve holds the ram up. A hand release
     * valve lets the water back to the reservoir and the ram down.
     */
    public static Exhibit hydraulicJack() {
        Exhibit e = new Exhibit("jack", "Hydraulic jack",
                List.of("HYDRAULIC JACK", "Pump the handle:", "check valves ratchet", "the ram up"),
                "The pump draws water from the open reservoir on the left through one check valve as you pull its "
                        + "handle up, and pushes it through a second check valve into the ram as you push it down. The "
                        + "valves only let water one way, so every stroke adds a little water under the ram and it climbs "
                        + "— 6 times your force, 1/6 of your stroke, stroke after stroke, the way a car jack lifts a car. "
                        + "Open the release valve (in front) to let the water back to the reservoir and the ram down.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 1, 0, 8, 11, 0, 8, Dir.UP);
        e.fill(BLOCK, st, 1, 0, 10, 11, 0, 10, Dir.UP);
        // reservoir open to the air through a short standpipe
        e.add(TANK, st, 2, 1, 8, Dir.UP, "fluid", 1);
        e.add(PIPE, st, 2, 2, 8, Dir.UP, "bore", 0.02, "fluid", 1);
        // inlet check valve, the pump's foot, outlet check valve, the line to the ram
        e.name("inlet", e.add(VALVE, "brass", 3, 1, 8, Dir.EAST, "bore", 0.008, "relief", 3000, "fluid", 1));
        e.add(PIPE, st, 4, 1, 8, Dir.EAST, "bore", 0.01, "fluid", 1);
        e.name("outlet", e.add(VALVE, "brass", 5, 1, 8, Dir.EAST, "bore", 0.008, "relief", 3000, "fluid", 1));
        e.fill(PIPE, st, 6, 1, 8, 10, 1, 8, Dir.EAST, "bore", 0.01, "fluid", 1);
        e.add(BLOCK, st, 11, 1, 8, Dir.UP);
        // the pump: 2 cm bore, its rod in a bearing on a column, a handle on top
        e.name("pump", e.add(CYLINDER, st, 4, 2, 8, Dir.UP, "bore", 0.02, "fluid", 1));
        e.name("pumpRod", e.fill(SHAFT, st, 4, 3, 8, 4, 6, 8, Dir.UP, "diameter", 0.01));
        e.add(LINEAR_BEARING, st, 4, 5, 8, Dir.UP);
        e.fill(BLOCK, st, 4, 1, 7, 4, 5, 7, Dir.UP);
        e.name("handle", e.add(BLOCK, "oak", 4, 7, 8, Dir.UP, "fill", 0.3));
        // the ram: 5 cm bore, lead weight
        e.name("ram", e.add(CYLINDER, st, 10, 2, 8, Dir.UP, "bore", 0.05, "fluid", 1));
        e.name("ramRod", e.fill(SHAFT, st, 10, 3, 8, 10, 6, 8, Dir.UP, "diameter", 0.02));
        e.add(LINEAR_BEARING, st, 10, 5, 8, Dir.UP);
        e.fill(BLOCK, st, 10, 1, 7, 10, 5, 7, Dir.UP);
        e.name("weight", e.add(BLOCK, "lead", 10, 7, 8, Dir.UP));
        // release line: from the ram's foot round the front to the reservoir, with a hand valve
        e.add(PIPE, st, 10, 1, 9, Dir.SOUTH, "bore", 0.01, "fluid", 1);
        e.fill(PIPE, st, 3, 1, 10, 5, 1, 10, Dir.EAST, "bore", 0.01, "fluid", 1);
        e.fill(PIPE, st, 7, 1, 10, 10, 1, 10, Dir.EAST, "bore", 0.01, "fluid", 1);
        e.add(BLOCK, st, 1, 1, 10, Dir.UP);
        e.add(BLOCK, st, 11, 1, 10, Dir.UP);
        e.name("release", e.add(VALVE, "brass", 6, 1, 10, Dir.EAST, "bore", 0.008, "fluid", 1));
        e.add(PIPE, st, 2, 1, 10, Dir.EAST, "bore", 0.01, "fluid", 1);
        e.add(PIPE, st, 2, 1, 9, Dir.SOUTH, "bore", 0.01, "fluid", 1);
        return e.view(6, 5, 16, 15);
    }

    /**
     * Archimedes in a pond: three floats on vertical rods in linear bearings, standing in still water — an oak block,
     * a steel block and a thin steel can (a closed vessel of 0.5 mm sheet on a hub beside its rod). The water lifts each
     * by the weight of the water it displaces: the oak rides at about three-quarters of its depth, the steel block sinks
     * to the end of its rod, and the can — steel too, but mostly air inside — rides highest of all.
     */
    public static Exhibit floats() {
        Exhibit e = new Exhibit("floats", "Floats",
                List.of("FLOATS", "Oak floats, steel", "sinks — and a steel", "can floats highest"),
                "Three floats on vertical rods stand in the pond. Water pushes up on anything in it with the weight "
                        + "of the water it pushes aside (Archimedes). The oak block, three-quarters as dense as water, "
                        + "settles with about three-quarters of itself under. The steel block, nearly eight times "
                        + "denser, sinks to the end of its rod. The can on the right is steel as well, but a closed "
                        + "box of half-millimetre sheet: it displaces its whole volume and weighs a third as much, so it "
                        + "rides higher than the oak — why steel ships float. Push a float under and let it go: it bobs "
                        + "up and settles, its energy carried off by the ripples it makes.");
        String st = "steel_1045", al = "aluminium_6061";
        // the pond: glass walls, two blocks of still water
        e.scenery(0, 0, 4, 11, 2, 4, "glass");
        e.scenery(0, 0, 10, 11, 2, 10, "glass");
        e.scenery(0, 0, 5, 0, 2, 9, "glass");
        e.scenery(11, 0, 5, 11, 2, 9, "glass");
        e.scenery(1, 0, 5, 10, 1, 9, "water");
        String[] names = {"oak", "steel", "can"};
        int[] xs = {2, 5, 8};
        for (int k = 0; k < 3; k++) {
            int x = xs[k];
            // a light aluminium rod in a linear bearing above the water, free to travel 3 cm either way
            e.name(names[k] + "Rod", e.fill(SHAFT, al, x, 0, 7, x, 4, 7, Dir.UP, "diameter", 0.005));
            e.add(LINEAR_BEARING, st, x, 3, 7, Dir.UP, "travel_fwd", 0.03, "travel_back", 0.03);
            // the bearing's arm and post (thin plates, edge-on to the pond's front), a cell clear of the float
            // (touching it, the float would be fixed to them)
            e.add(BLOCK, st, x, 3, 8, Dir.EAST, "fill", 0.25);
            e.fill(BLOCK, st, x, 0, 9, x, 3, 9, Dir.EAST, "fill", 0.25);
        }
        e.name("oak", e.add(BLOCK, "oak", 2, 1, 7, Dir.UP));
        e.name("steel", e.add(BLOCK, st, 5, 1, 7, Dir.UP));
        // the can rides its rod on a small hub (a vessel in the rod's own cell would not grip it)
        e.add(BLOCK, al, 8, 1, 7, Dir.UP, "fill", 0.1);
        e.name("can", e.add(TANK, st, 9, 1, 7, Dir.UP, "wall", 0.0005));
        return e.view(5.5, 3, 13, 18);
    }

    /**
     * An undershot water wheel: a stream one block deep runs down a glass channel from its springs, and a wheel of four
     * paddles on an axle across it dips its lowest paddle into the middle of the current. The water pushes on the
     * paddle as it runs past (drag on a plate against the water's speed relative to it) and turns the wheel; the
     * paddle can never go faster than the stream.
     */
    public static Exhibit waterWheel() {
        Exhibit e = new Exhibit("waterwheel", "Water wheel",
                List.of("WATER WHEEL", "The stream pushes", "the lowest paddle:", "the wheel turns"),
                "A stream runs down the glass channel from the springs on the left, its level falling as it goes and "
                        + "its speed set by its slope and depth (about 2 m/s here, by Manning's formula). The wheel's "
                        + "lowest paddle stands in the current. The water pushes on it in proportion to the square of "
                        + "how fast it runs past, so the wheel speeds up until its paddles move nearly as fast as the "
                        + "water and the push dies away — an undershot wheel, the oldest water mill. Put a load on its "
                        + "axle and it turns slower, the water pushing harder: that difference is the power it delivers.");
        String st = "steel_1045", al = "aluminium_6061";
        // the channel: a stone bed and glass walls round three lanes, a block up (a part on the ground is fixed to it),
        // the springs at its upstream end; a pit in the bed under the lowest paddle, clear of it (a paddle resting on
        // the bed would be held by it, as a part touching any solid block is)
        e.scenery(0, 0, 5, 3, 0, 9, "smooth_stone");
        e.scenery(5, 0, 5, 9, 0, 9, "smooth_stone");
        e.scenery(4, 0, 5, 4, 0, 6, "smooth_stone");
        e.scenery(4, 0, 8, 4, 0, 9, "smooth_stone");
        e.scenery(0, 1, 5, 9, 1, 5, "glass");
        e.scenery(0, 1, 9, 9, 1, 9, "glass");
        e.scenery(0, 1, 6, 0, 1, 8, "glass");
        e.scenery(9, 1, 6, 9, 1, 8, "glass");
        e.stream(1, 1, 6, 8, 8); // it runs out seven blocks from its springs
        // the axle across the channel, in bearings on posts outside its walls
        e.name("axle", e.fill(SHAFT, st, 4, 3, 3, 4, 3, 11, Dir.SOUTH, "diameter", 0.01));
        e.add(BEARING, st, 4, 3, 3, Dir.SOUTH);
        e.add(BEARING, st, 4, 3, 11, Dir.SOUTH);
        e.fill(BLOCK, st, 4, 0, 3, 4, 2, 3, Dir.UP);
        e.fill(BLOCK, st, 4, 0, 11, 4, 2, 11, Dir.UP);
        // the wheel: a hub, four arms and four paddles of thin aluminium, the paddles tangent to it two blocks out
        e.add(BLOCK, al, 4, 3, 7, Dir.SOUTH, "fill", 0.5);
        for (int[] arm : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
            e.add(BLOCK, al, 4 + arm[0], 3 + arm[1], 7, Dir.SOUTH, "fill", 0.2);
            int paddle = e.add(BLOCK, al, 4 + 2 * arm[0], 3 + 2 * arm[1], 7, arm[0] == 0 ? Dir.EAST : Dir.UP, "fill", 0.2);
            if (arm[1] == -1) e.name("paddle", paddle);
        }
        return e.view(10, 5, 15, 25, 150);
    }

    /**
     * A throttle worked by a slide: a receiver of air at 10 bar discharges through a valve into a duct with a bladed
     * rotor. The valve's plug is on a rod in a linear bearing pointing into its side — pushed in, the plug sits in its
     * seat; drawn back, the valve opens in proportion to the travel, and the rotor spins up. Nothing turns a hand wheel:
     * whatever moves the rod sets the flow (a hand here; a governor's sleeve, a float).
     */
    public static Exhibit slideValve() {
        Exhibit e = new Exhibit("slidevalve", "Slide valve",
                List.of("SLIDE VALVE", "Pull the rod out:", "the air runs the rotor.", "Push it in: shut"),
                "The valve between the air receiver and the turbine's duct has no hand wheel. Its plug is on the end "
                        + "of the rod beside it, which slides in a linear bearing: pushed in, the plug sits in its seat "
                        + "and nothing passes; drawn back, the seat opens in proportion to how far, up to its full 10 mm "
                        + "bore at 3 cm. Air at 10 bar rushes through and spins the bladed rotor in the duct; push the rod "
                        + "part way back and it throttles the flow and the rotor slows. Friction in the bearing holds the "
                        + "rod wherever you leave it (its bearing is packed stiff, as a valve stem's gland is). Any moving thing can work such a rod — a flyball governor's sleeve "
                        + "throttling an engine as it speeds up, a float closing the inlet of a cistern as it fills.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 0, 0, 7, 3, 0, 9, Dir.UP);
        e.name("receiver", e.fill(TANK, st, 0, 1, 7, 3, 3, 9, Dir.UP, "pressure", 1e6, "wall", 0.004, "gauge_range", 16));
        e.name("valve", e.add(VALVE, "brass", 4, 2, 8, Dir.EAST, "bore", 0.010));
        e.add(PIPE, st, 5, 2, 8, Dir.EAST, "bore", 0.04);
        e.name("rotor", e.add(ROTOR, "aluminium_6061", 6, 2, 8, Dir.EAST, "blade_angle", 60, "pitch", 1));
        e.add(PIPE, st, 7, 2, 8, Dir.EAST, "bore", 0.04);
        e.add(PIPE, st, 7, 3, 8, Dir.UP, "bore", 0.04);
        e.name("shaft", e.fill(SHAFT, st, 6, 2, 8, 13, 2, 8, Dir.EAST, "diameter", 0.012));
        e.add(BEARING, st, 9, 2, 8, Dir.EAST);
        e.add(BEARING, st, 13, 2, 8, Dir.EAST);
        e.fill(BLOCK, st, 9, 0, 8, 9, 1, 8, Dir.UP);
        e.fill(BLOCK, st, 13, 0, 8, 13, 1, 8, Dir.UP);
        e.name("disk", e.add(BLOCK, "aluminium_6061", 11, 2, 8, Dir.EAST, "fill", 0.15));
        // the rod: across the valve's axis, a cell clear of it (touching, it would be welded to the valve), in a linear
        // bearing on a column; built pushed in, it may be drawn back 3 cm; an oak knob on its end. The bearing is packed
        // stiff, as a valve stem's gland is (a free ball bushing let the rod, pushed home hard, bounce off its collar
        // and roll back out)
        e.name("rod", e.fill(SHAFT, st, 4, 2, 10, 4, 2, 12, Dir.SOUTH, "diameter", 0.01));
        e.add(LINEAR_BEARING, st, 4, 2, 11, Dir.SOUTH, "travel_fwd", 0.03, "travel_back", 0, "viscosity", 30);
        e.fill(BLOCK, st, 4, 0, 11, 4, 1, 11, Dir.UP);
        e.name("knob", e.add(BLOCK, "oak", 4, 2, 13, Dir.UP, "fill", 0.3));
        return e.view(6.5, 6, 18, 22);
    }

    /**
     * Rack and pinion: a crank turns a pinion that meshes with a toothed bar on a vertical slide carrying a lead
     * weight. Every turn lifts the rack one pitch circumference; let go and the weight runs it back down.
     */
    public static Exhibit rackAndPinion() {
        Exhibit e = new Exhibit("rack", "Rack and pinion",
                List.of("RACK AND PINION", "Turn the crank:", "rotation becomes", "straight-line travel"),
                "The pinion's teeth mesh with the teeth cut along the bar (the rack), so turning the crank drives the "
                        + "bar straight up — one pitch circumference per turn, here about 10 cm. The weight on top pulls "
                        + "back on the teeth, and through them on the crank: let go and the rack runs down and spins the "
                        + "crank backwards, because nothing in the mesh stops it (compare the screw jacks next door).");
        String st = "steel_1045";
        e.fill(BLOCK, st, 3, 0, 4, 11, 0, 11, Dir.UP);
        // the pinion's shaft along z in two bearings on columns, running on past the front one to a crank
        e.fill(BLOCK, st, 6, 1, 5, 6, 5, 5, Dir.UP);
        e.fill(BLOCK, st, 6, 1, 10, 6, 5, 10, Dir.UP);
        e.fill(SHAFT, st, 6, 6, 5, 6, 6, 11, Dir.SOUTH, "diameter", 0.02);
        e.add(BEARING, st, 6, 6, 5, Dir.SOUTH);
        e.add(BEARING, st, 6, 6, 10, Dir.SOUTH);
        e.name("pinion", e.add(GEAR, st, 6, 6, 8, Dir.SOUTH));
        e.name("crank", e.add(CRANK, st, 6, 6, 12, Dir.SOUTH, "radius", 0.12));
        // the rack, teeth facing the pinion, on a guide rod sliding in a bushing; the weight on top
        e.name("rack", e.fill(RACK, st, 7, 3, 8, 7, 9, 8, Dir.UP, "face", Dir.WEST.ordinal()));
        e.name("rod", e.fill(SHAFT, st, 7, 10, 8, 7, 13, 8, Dir.UP, "diameter", 0.012));
        e.add(LINEAR_BEARING, st, 7, 12, 8, Dir.UP);
        e.add(BLOCK, st, 8, 12, 8, Dir.UP);
        e.fill(BLOCK, st, 9, 1, 8, 9, 12, 8, Dir.UP);
        e.name("weight", e.add(BLOCK, "lead", 7, 14, 8, Dir.UP));
        return e.view(1, 9, 15, 20, -135); // from the south-west: the pinion's face and the rack's teeth
    }

    /**
     * Two screw jacks: a crank turns a vertical lead screw in bearings; its nut is fixed to a weighted carriage on a
     * guide rod. The fine thread (4 mm lead) lifts slowly and holds the weight when let go; the steep four-start
     * thread (40 mm lead) lifts ten times faster but runs back down.
     */
    public static Exhibit screwJacks() {
        Exhibit e = new Exhibit("screws", "Lead screws",
                List.of("LEAD SCREWS", "Fine thread holds,", "steep thread", "runs back"),
                "Turning a screw in its nut moves the nut along it by one lead per turn. The left screw's lead is 4 mm: "
                        + "a light hand on the crank lifts the heavy carriage, slowly — and when you let go it stays "
                        + "put, because the friction between the thread flanks is greater than the push of the load "
                        + "along the gentle slope of the thread (a self-locking screw, like a car jack or a vice). The "
                        + "right screw has four threads side by side, a 40 mm lead: it lifts ten times faster for ten "
                        + "times the effort, and when you let go the weight screws itself back down, spinning the crank.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 0, 0, 8, 14, 0, 8, Dir.UP);
        String[] names = {"fine", "steep"};
        double[] leads = {0.004, 0.04};
        for (int k = 0; k < 2; k++) {
            int o = 8 * k;
            // the screw in two bearings on arms from a column (its foot clear of the base), a crank on top
            e.name(names[k] + "Screw", e.fill(SHAFT, st, o + 2, 2, 8, o + 2, 8, 8, Dir.UP, "diameter", 0.02));
            e.add(BEARING, "bronze", o + 2, 2, 8, Dir.UP);
            e.add(BEARING, "bronze", o + 2, 7, 8, Dir.UP);
            e.add(BLOCK, st, o + 1, 2, 8, Dir.UP);
            e.add(BLOCK, st, o + 1, 7, 8, Dir.UP);
            e.fill(BLOCK, st, o, 1, 8, o, 7, 8, Dir.UP);
            e.name(names[k] + "Crank", e.add(CRANK, st, o + 2, 9, 8, Dir.UP, "radius", 0.12));
            // the nut on the screw, fixed to the carriage: two blocks and a guide rod sliding in a bushing
            e.name(names[k] + "Nut", e.add(NUT, "bronze", o + 2, 4, 8, Dir.UP, "lead", leads[k]));
            e.add(BLOCK, st, o + 3, 4, 8, Dir.UP);
            e.add(BLOCK, st, o + 4, 4, 8, Dir.UP);
            e.name(names[k] + "Rod", e.fill(SHAFT, st, o + 4, 5, 8, o + 4, 9, 8, Dir.UP, "diameter", 0.012));
            e.add(LINEAR_BEARING, st, o + 4, 7, 8, Dir.UP);
            e.add(BLOCK, st, o + 5, 7, 8, Dir.UP);
            e.fill(BLOCK, st, o + 6, 1, 8, o + 6, 7, 8, Dir.UP);
            e.add(BLOCK, "lead", o + 4, 10, 8, Dir.UP);
        }
        return e.view(7.5, 7, 19, 15);
    }

    /**
     * An unbalanced rotor on a sprung platform: rotating unbalance excites the platform at the rotor's speed. Spun up
     * and left to coast down, the rotor passes through the platform's natural frequency: the shaking grows to a peak
     * there and dies away on either side — above it the platform hardly moves (the principle of vibration isolation).
     */
    public static Exhibit unbalance() {
        Exhibit e = new Exhibit("unbalance", "Unbalanced rotor",
                List.of("UNBALANCED ROTOR", "A lopsided wheel on", "springs: it shakes", "hardest at resonance"),
                "A steel platform rides on a rod in two oil-damped bushings, held between two springs. On it a "
                        + "wheel spins on a vertical shaft, with a lead weight bolted to one side: its centre of mass is "
                        + "off the axis, so as it turns it pulls the platform to and fro (rotating unbalance, force "
                        + "m·e·ω²). Fast, the wheel shakes the platform only a little — the springs cannot follow and "
                        + "the platform's own mass holds it nearly still (vibration isolation). As the wheel slows, it "
                        + "comes down to the platform's natural frequency, √(k/M) — about two turns a second — and the "
                        + "platform shakes hard; slower still it calms again. Crank the wheel to spin it back up. This "
                        + "is why washing machines thump as they spin up, and why machines sit on soft mounts.");
        String st = "steel_1045";
        int y = 3;
        // the frame: posts under the bushings, and stops for the springs at both ends
        e.fill(BLOCK, st, 4, 0, 8, 4, y - 1, 8, Dir.UP);
        e.fill(BLOCK, st, 10, 0, 8, 10, y - 1, 8, Dir.UP);
        e.fill(BLOCK, st, 0, 0, 8, 0, y, 8, Dir.UP);
        e.fill(BLOCK, st, 14, 0, 8, 14, y, 8, Dir.UP);
        // the platform: a rod through two damped ball bushings, three steel blocks on it, a bracket for the wheel's bearing
        int rod = e.fill(SHAFT, st, 2, y, 8, 12, y, 8, Dir.EAST, "diameter", 0.012);
        e.name("rod", rod);
        double damping = 5; // N·s/m each: ζ ≈ 0.03, a sharp resonance that does not reach the ends of the rod
        e.add(LINEAR_BEARING, st, 4, y, 8, Dir.EAST, "viscosity", damping);
        e.add(LINEAR_BEARING, st, 10, y, 8, Dir.EAST, "viscosity", damping);
        e.name("springA", e.add(SPRING, st, 1, y, 8, Dir.EAST, "wire", 0.0027)); // ≈ 1 kN/m each
        e.name("springB", e.add(SPRING, st, 13, y, 8, Dir.EAST, "wire", 0.0027));
        e.name("platform", e.fill(BLOCK, st, 6, y, 8, 8, y, 8, Dir.EAST));
        e.fill(BLOCK, st, 6, y + 1, 8, 6, y + 2, 8, Dir.UP);
        // the wheel: a vertical shaft in a plain bearing on the bracket, a steel disc and a lead weight to one side
        // a silicone-damped bearing: it slows the wheel steadily (about a minute's time constant on its own)
        e.add(BEARING, "bronze", 7, y + 2, 8, Dir.UP, "type", 2, "viscosity", 0.01);
        int shaft = e.fill(SHAFT, st, 7, y + 2, 8, 7, y + 4, 8, Dir.UP, "diameter", 0.012);
        e.name("shaft", shaft);
        e.name("disc", e.add(BLOCK, st, 7, y + 4, 8, Dir.UP));
        e.name("weight", e.add(BLOCK, "lead", 8, y + 4, 8, Dir.UP, "fill", 0.25));
        e.name("crank", e.add(CRANK, st, 7, y + 5, 8, Dir.UP, "radius", 0.1));
        return e.view(7, 4, 16, 14);
    }

    /**
     * Weights on a spinning beam: each slides along its arm, held in by a spring. Cranked faster, they fly out until
     * the springs supply their centripetal force (k·x = m·ω²·r); past √(k/m) (beyond a hand's speed here) no spring
     * force would be enough. Moving out they take angular momentum, so the beam slows (the spinning skater's arms).
     */
    public static Exhibit spinningWeights() {
        Exhibit e = new Exhibit("weights", "Spinning weights",
                List.of("SPINNING WEIGHTS", "Crank it: the weights", "fly out against", "their springs"),
                "Two lead weights ride on rods across a light spinning beam, each held in towards the middle by a "
                        + "spring. Nothing tells them to move: turning, each needs a force towards the axis to keep it on "
                        + "its circle (m·ω²·r), and only its spring can give it — so the faster the beam turns, the "
                        + "further out the weights go, until the squeezed springs push hard enough: at a hand's top speed, "
                        + "about 15 radians a second, some 5 cm. (Near √(k/m), 37 radians a second — faster than a hand "
                        + "can turn it — no squeeze would be enough and they would run out to their stops.) As they move out they "
                        + "take up more of the beam's angular momentum (it stays constant), so the beam slows — the "
                        + "spinning skater's arms. Speed governors on engines work like this.");
        String al = "aluminium_6061", st = "steel_1045";
        // the frame: a post holding the vertical shaft's bearing from the side
        e.fill(BLOCK, st, 7, 0, 9, 7, 2, 9, Dir.UP);
        e.add(BEARING, st, 7, 2, 8, Dir.UP);
        int shaft = e.fill(SHAFT, st, 7, 2, 8, 7, 4, 8, Dir.UP, "diameter", 0.015); // up past the bearing, clear of it
        e.name("shaft", shaft);
        // the beam: light aluminium plates through the shaft's top, risers to the bushings and the spring seats
        e.fill(BLOCK, al, 1, 4, 8, 6, 4, 8, Dir.UP, "fill", 0.25); // flat plates
        e.add(BLOCK, al, 7, 4, 8, Dir.UP, "fill", 0.25);
        e.fill(BLOCK, al, 8, 4, 8, 13, 4, 8, Dir.UP, "fill", 0.25);
        for (int x : new int[]{1, 5, 9, 13}) e.add(BLOCK, al, x, 5, 8, Dir.UP, "fill", 0.25);
        e.name("crank", e.add(CRANK, st, 7, 5, 8, Dir.UP, "radius", 0.2));
        // each weight on a rod in a bushing, a spring between it and the beam's end
        for (int k = 0; k < 2; k++) {
            int seat = k == 0 ? 1 : 13, bush = k == 0 ? 5 : 9, w = k == 0 ? 3 : 11, sp = k == 0 ? 2 : 12;
            e.add(BLOCK, al, seat, 6, 8, Dir.UP, "fill", 0.25);
            e.add(LINEAR_BEARING, st, bush, 6, 8, Dir.EAST, "viscosity", 20);
            int rod = k == 0 ? e.fill(SHAFT, st, 3, 6, 8, 6, 6, 8, Dir.EAST, "diameter", 0.01)
                    : e.fill(SHAFT, st, 8, 6, 8, 11, 6, 8, Dir.EAST, "diameter", 0.01);
            e.name(k == 0 ? "rodA" : "rodB", rod);
            e.name(k == 0 ? "weightA" : "weightB", e.add(BLOCK, "lead", w, 6, 8, Dir.UP));
            e.name(k == 0 ? "springA" : "springB", e.add(SPRING, st, sp, 6, 8, Dir.EAST, "wire", 0.0038));
        }
        return e.view(7, 4, 16, 14);
    }

    /**
     * A four-bar linkage: a crank on a flywheel shaft turns a short arm; a bar pinned to it and to the top of a longer
     * rocker on a second shaft makes the rocker swing to and fro — a crank-rocker, because the shortest link (the
     * crank) plus the longest (the frame between the shafts) is less than the other two (Grashof's condition).
     */
    public static Exhibit fourBar() {
        Exhibit e = new Exhibit("fourbar", "Four-bar linkage",
                List.of("FOUR-BAR LINKAGE", "The crank turns round;", "the rocker only", "swings to and fro"),
                "Four bars pinned in a loop: the frame between the two shafts (34 cm), the crank arm on the flywheel "
                        + "shaft (7 cm), the long coupler bar (25 cm) and the rocker on the right-hand shaft (19 cm). The "
                        + "coupler keeps its two pins exactly its length apart, so wherever the crank is, the rocker can only "
                        + "be where a circle round the crank pin meets the circle its own pin moves on — turning the crank "
                        + "round and round, the rocker swings through 44.5° and back each turn. Whether a crank can turn "
                        + "fully is Grashof's rule: the shortest bar plus the longest must be less than the other two "
                        + "(7 + 34 < 25 + 19 cm). Its swing ends where crank and coupler lie in one line, stretched out or "
                        + "folded back. The rocker stops and reverses twice a turn and is lifted and let down as it "
                        + "leans; the flywheel gives it that energy and takes it back, so its speed rises and falls. Linkages like this are in "
                        + "windscreen wipers, sewing machines, pumpjacks and the legs of walking toys.");
        String st = "steel_1045";
        e.fill(BLOCK, st, 2, 0, 4, 11, 0, 9, Dir.UP);
        // the crank: a shaft along z in a bearing on a post, a flywheel behind it, a handle at the front, an arm between
        e.fill(BLOCK, st, 4, 1, 5, 4, 3, 5, Dir.UP);
        e.add(BEARING, st, 4, 4, 5, Dir.SOUTH);
        int shaft = e.fill(SHAFT, st, 4, 4, 3, 4, 4, 10, Dir.SOUTH, "diameter", 0.02);
        e.name("crankShaft", shaft);
        e.fill(BLOCK, st, 3, 3, 3, 5, 5, 3, Dir.SOUTH, "fill", 0.3);                       // the flywheel
        e.name("crank", e.add(CRANK, st, 4, 4, 11, Dir.SOUTH, "radius", 0.15));
        // (plates thin along z: flat in the plane they turn in)
        e.add(BLOCK, st, 4, 4, 7, Dir.SOUTH, "fill", 0.5);                               // the crank arm's hub
        e.name("crankArm", e.add(BLOCK, st, 4, 5, 7, Dir.SOUTH, "fill", 0.5));
        // the coupler: pinned to the crank arm at its left end and to the rocker's top at its right
        e.name("coupler", e.fill(LINK, st, 5, 5, 7, 8, 5, 7, Dir.EAST));
        // the rocker: three cells up from a hub on the second shaft, in its bearing on a post
        e.fill(BLOCK, st, 9, 1, 5, 9, 1, 5, Dir.UP);
        e.add(BEARING, st, 9, 2, 5, Dir.SOUTH);
        int rockerShaft = e.fill(SHAFT, st, 9, 2, 5, 9, 2, 7, Dir.SOUTH, "diameter", 0.02);
        e.name("rockerShaft", rockerShaft);
        e.add(BLOCK, st, 9, 2, 7, Dir.SOUTH, "fill", 0.5);
        e.name("rocker", e.fill(BLOCK, st, 9, 3, 7, 9, 5, 7, Dir.SOUTH, "fill", 0.5));
        // turning already, the rocker moving as the coupler makes it (its pin a third as far from the coupler's line
        // as the crank's, it turns a third as fast)
        e.init(shaft, new Exhibit.Initial(Double.NaN, 6, 0));
        e.init(rockerShaft, new Exhibit.Initial(Double.NaN, 2, 0));
        return e.view(6.5, 4, 20, 10);
    }
}

