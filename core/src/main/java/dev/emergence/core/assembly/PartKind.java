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
    /** Bare conductor wire segment (contacts any conductor it touches). */
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
    LOGIC(false, true);

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
