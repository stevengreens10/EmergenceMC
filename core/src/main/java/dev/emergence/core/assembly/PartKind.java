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
    ROTOR(true, false);

    /** True for parts that hold a working fluid. */
    public boolean holdsFluid() {
        return this == PIPE || this == TANK || this == VALVE || this == ROTOR;
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
