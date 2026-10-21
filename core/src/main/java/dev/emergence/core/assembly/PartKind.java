package dev.emergence.core.assembly;

/**
 * Primitive component kinds. None of these is a machine: each is a piece of material with geometry
 * whose behaviour follows from physics once assembled. "Manufactured" kinds (resistor, capacitor,
 * diode) stand for components whose internal micro-structure is below grid resolution; their
 * behaviour is still physical (device equations, thermal limits) and fully composable.
 */
public enum PartKind {
    /** Solid structural block of material filling its cells (frames, disks, mounting plates). */
    BLOCK(true, false),
    /** Round rod along its axis; transmits torque, rotates in bearings. */
    SHAFT(true, false),
    /** Rolling-element or plain bearing: lets a shaft passing along its axis rotate relative to its housing. */
    BEARING(true, false),
    /** Spur gear (disk perpendicular to its axis) mounted on a shaft. */
    GEAR(true, false),
    /** Hand crank: a lever arm on a shaft that a player can turn. */
    CRANK(true, false),
    /** Uniformly magnetised permanent magnet block. */
    MAGNET(false, false),
    /** Wound coil of enamelled wire with two leads at its axial ends. */
    COIL(false, true),
    /**
     * Bare conductor wire segment (contacts any conductor it touches). With "flexible" = 1 it is a flexible lead (the
     * tinsel lead of a loudspeaker's moving coil): it still conducts where the part it touches moves relative to it,
     * and exerts no force on it.
     */
    WIRE(false, true),
    /** Insulated wire segment (contacts only other wires and component leads). */
    INSULATED_WIRE(false, true),
    /** Discrete resistor (manufactured), two leads along its axis. */
    RESISTOR(false, true),
    /** Discrete capacitor (manufactured), two leads along its axis. */
    CAPACITOR(false, true),
    /** PN-junction diode (manufactured); anode on the negative-axis lead, cathode on the positive. */
    DIODE(false, true),
    /** Manual contact switch, two leads along its axis. */
    SWITCH(false, true),
    /** Graphite brush on a stationary holder; makes sliding contact with a rotating conductor. */
    BRUSH(false, true),
    /**
     * A body of liquid electrolyte (in a glass vessel). Conductors touching it become electrodes whose
     * interface reactions follow electrochemical kinetics.
     */
    ELECTROLYTE(false, false),
    /**
     * Pipe: a tube along its axis (bore and wall thickness as parameters) holding a fluid. Connects to fluid
     * parts at its ends and, as a tee, at its sides; an open end vents to the surrounding air. A shaft may
     * pass through it (gland seal).
     */
    PIPE(true, false),
    /** Pressure vessel: a hollow cell (or box of cells) of fluid, connecting to fluid parts on every face. */
    TANK(true, false),
    /** Valve: a pipe with a closure; right-click opens or shuts it. Placed shut. */
    VALVE(true, false),
    /**
     * Bladed rotor (fan, propeller, impeller or turbine wheel) mounted on a shaft inside a fluid passage:
     * it exchanges work with the flow along its axis (Euler turbomachine equation).
     */
    ROTOR(true, false),
    /**
     * Piston cylinder: a bore along its axis with a piston whose connecting rod reaches out of its +axis
     * face to a crank throw ({@link #ECCENTRIC}) on a shaft that the axis points at. The head (−axis) face
     * and the sides take pipes. Gas in it does work on the crank as the piston moves.
     */
    CYLINDER(true, false),
    /** Crank throw: a web and pin mounted on a shaft at radius "throw"; connecting rods of cylinders pointing at the shaft ride on it. */
    ECCENTRIC(true, false),
    /**
     * Discrete transistor (manufactured): "type" 0 NPN, 1 PNP, 2 n-channel MOSFET, 3 p-channel MOSFET.
     * Emitter/source on the −axis face, collector/drain on the +axis face, base/gate on the side faces of
     * its lead axis.
     */
    TRANSISTOR(false, true),
    /** Light-emitting diode (manufactured): anode on the −axis lead, cathode on the +axis lead; colour by wavelength. */
    LED(false, true),
    /**
     * CMOS logic chip (manufactured, 74HC class): "function" 0 buffer, 1 NOT, 2 NAND, 3 NOR, 4 AND, 5 OR, 6 XOR,
     * 7 Schmitt NOT, 8 D flip-flop. Input A (D) on the −axis face, output (Q) on the +axis face, GND and V_DD on
     * the −/+ lead-axis sides, input B (clock) on the −side of the remaining axis and Q̄ on its +side.
     */
    LOGIC(false, true),
    /**
     * Friction pad (brake shoe, Prony-brake block) whose +axis face presses with a spring force ("force", N)
     * against whatever turning body is in front of it, while applied (toggled). Its friction torque μ·F·r opposes
     * the motion and the work becomes heat in the pad; μ is the pad material's friction coefficient. Like a brush
     * it belongs to the stationary frame and never joins bodies.
     */
    BRAKE(false, false),
    /**
     * One segment (pin pair) of a medium-scale-integration chip (manufactured, 74HC class): "function" 0 4-bit
     * counter, 1 4-bit register, 2 4-bit adder, 3 16×8 ROM (contents "w0".."w15"). A chip is a row of segments
     * along their axis; segment k carries input k on its −lead-axis side and output k on its +lead-axis side, and
     * the last segment carries GND (−) and V_DD (+). See {@code MsiChip.Type} for the pin order.
     */
    CHIP(false, true),
    /**
     * Silicon photodiode / solar cell (manufactured): anode on the −axis lead, cathode on the +axis lead; its
     * sensitive face looks along "face" (a direction index: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east; default
     * up); "area" is the junction area [m²] (a BPW34 photodiode 7.5 mm², a solar cell a whole face).
     */
    PHOTODIODE(false, true),
    /**
     * Rotary potentiometer (manufactured): a housing that a shaft passes through along its axis, like a bearing;
     * the shaft turns a wiper on a resistive track ("resistance", default 10 kΩ; "travel" electrical angle, default
     * 270°). Track end A on the −lead-axis side, end B on the +lead-axis side, the wiper on the −side of the
     * remaining axis. The wiper is at the middle of the track when the shaft is at its placed orientation ("wiper": its
     * place then, 0 at A … 1 at B) and moves towards B as the shaft turns positively about the axis.
     */
    POTENTIOMETER(true, true),
    /**
     * Operational amplifier (manufactured, single, TL071 class): non-inverting input on the −axis face, output on
     * the +axis face, V− and V+ supplies on the −/+ lead-axis sides, inverting input on the −side of the remaining
     * axis. "gbw" gain–bandwidth [Hz] (default 3 MHz), "slew" [V/s] (default 13 V/µs).
     */
    OPAMP(false, true),
    /**
     * Sealed radioactive check source (manufactured): "nuclide" code (0 Cs-137, 1 Ba-137m, 2 Co-60, 3 Am-241,
     * 4 K-40), "activity" [Bq]; short-lived daughters start in equilibrium unless "fresh" is 1. Emits gamma rays
     * in all directions.
     */
    SOURCE(false, false),
    /**
     * Geiger–Müller tube (manufactured): anode on the +axis lead, cathode on the −axis lead; "area" sensitive
     * area facing the source [m²] (default 3 cm²), starting and extinction voltages "start" (350 V) and "stop"
     * (300 V). Needs a few hundred volts through a megohm anode resistor.
     */
    GM_TUBE(false, true),
    /**
     * Linear bearing (ball bushing): a shaft passing along its axis slides through it but cannot turn in it. The
     * shaft and what is fixed to it form a slider whose travel ends where the bearing reaches the shaft's ends, or at
     * stop collars on the shaft: "travel" [m] either way from where it was placed, or "travel_fwd" along the bushing's
     * axis and "travel_back" against it (0: a collar against the bushing, holding the slide on that side).
     */
    LINEAR_BEARING(true, false),
    /**
     * Helical spring along its axis, joining whatever touches its two end faces (a slider and the frame, or two
     * sliders): stiffness k = G·d⁴/(8·D³·N) from its wire diameter "wire" (default 2 mm), coil diameter "coil"
     * (default 60 % of the cell) and active turns "turns" (default 10).
     */
    SPRING(false, false),
    /**
     * Gear rack: a bar along its axis with straight teeth cut on one face ("face", a {@link Dir} ordinal, default UP).
     * A spur gear whose axis crosses the bar and whose pitch circle touches that face meshes with it (same tooth
     * module: "module", default that of a default gear of the bar's cell size), turning rotation into the bar's
     * travel. The bar must belong to a slide moving along its length; the gear must stay over the teeth.
     */
    RACK(true, false),
    /**
     * Nut on a threaded shaft (a lead screw): the shaft and the nut screw along one another — turning one by dθ
     * relative to the other moves the nut lead·dθ/2π along the shaft. "lead" [m per turn] (default 1/5 of the
     * shaft diameter, like Tr 20×4), "left" = 1 for a left-hand thread, "lubricated" (default 1). Trapezoidal
     * thread (30°); friction between the flanks makes it lose work and, with a fine lead, hold a load by itself.
     */
    NUT(true, false),
    /**
     * Mirror: a polished plate whose front faces along its axis — flat, or a spherical dish of radius "radius" [m]
     * (positive: concave, focusing at radius/2; negative: convex), its back at the rear face of its box. "tilt" [°]
     * leans the front over towards direction "tilt_toward" (a {@link Dir} ordinal), the flat face then a diagonal of the
     * box (45°: a periscope's mirror). Reflects as its material does polished (silver ~96 %, aluminium ~92 %).
     */
    MIRROR(true, false),
    /**
     * Lens: a disc of transparent material across its axis with spherical faces of radius "radius" (first face,
     * positive convex) and "radius2" (second face, default −radius: equiconvex; 0 flat), "thickness" on the axis
     * (default: just enough for a thin rim), filling its box's cross-section. Focal length from the lensmaker's
     * equation and its material's index (dispersion by its Abbe number).
     */
    LENS(true, false),
    /**
     * Prism: a block of transparent material cut to a triangle across its axis — its base on one face of the box,
     * its apex (angle "apex" [°], default 60) pointing along "face" (a {@link Dir} ordinal across the axis); a steep
     * apex is truncated by the box. Light crossing it is bent towards the base, blue more than red (dispersion).
     */
    PRISM(true, false),
    /**
     * Blade: a thin aerofoil plate, its span along the axis, its chord ("chord" [m], default 90 % of the box across)
     * set in "face" (a {@link Dir} ordinal across the axis; default the first axis across it) and turned "pitch" [°]
     * about the span, "thickness" [m] (default 10 % of the chord). Moving air (wind, or its own motion) lifts and drags
     * it; on a turning body a set of blades is a propeller, fan or windmill — momentum theory gives their inflow.
     * "drag": its profile drag coefficient at zero incidence (default 0.02).
     */
    BLADE(true, false),
    /**
     * Link: a rigid bar along its axis, pinned at its two end faces to whatever touches each — a crank arm, a
     * rocker, a slide, or the frame (if nothing that moves does). Link parts laid face to face make one bar: a path
     * from one pin to the other, the bar running straight between the path's two outer faces (a slanting bar is laid
     * as a path round to its far end). Whatever the two bodies do, the bar keeps the pins its built length apart,
     * pushing or pulling along itself: a connecting rod, a four-bar linkage's coupler, a governor's arm. "width" [m] (default 30 % of the cell across) and "thickness" (default half the width) size the
     * bar (its mass is carried by the bodies it joins, its pins are frictionless).
     */
    LINK(false, false),
    /**
     * Rechargeable battery (manufactured): "chemistry" 0 lead–acid (2.1 V a cell, default) or 1 nickel–metal hydride
     * (1.3 V a cell: four make a 5 V supply), "cells" in series (default 6), "capacity" [A·h] (default 7), "charge" as
     * delivered (default 1: full). Positive terminal on the +axis face, negative on the −axis face. Its voltage follows
     * what the charge drawn has done to its electrodes and electrolyte; its internal resistance warms it.
     */
    BATTERY(false, true);

    /** True for parts that hold a working fluid. */
    public boolean holdsFluid() {
        return this == PIPE || this == TANK || this == VALVE || this == ROTOR || this == CYLINDER;
    }

    /** Structural parts carry load and rigidly join every part they touch into one body. */
    public final boolean structural;
    /** Parts whose electrical role is defined by leads/terminals (as opposed to bulk conductors). */
    public final boolean leaded;

    PartKind(boolean structural, boolean leaded) {
        this.structural = structural;
        this.leaded = leaded;
    }
}
