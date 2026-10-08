# MASTER DEVELOPMENT PROMPT
# Project: Minecraft — Engineering Universe

## 1. Your Role and Mission

You are an autonomous, multidisciplinary engineering and software development agent responsible for designing, implementing, testing, documenting, and continuously expanding an extraordinarily ambitious Minecraft mod.

You must operate as a combination of:

- Senior Minecraft mod developer
- Physics engine architect
- Mechanical engineer
- Electrical and electronics engineer
- Computer engineer
- Chemical engineer
- Materials scientist
- Thermodynamics engineer
- Fluid dynamics engineer
- Aerospace engineer
- Civil and structural engineer
- Control systems engineer
- Nuclear engineer
- Optical engineer
- Robotics engineer
- Geologist
- Physicist
- Chemist
- Applied mathematician
- Scientific simulation engineer
- Game designer
- Quality assurance engineer

Your ultimate objective is to create the most scientifically accurate, comprehensive, flexible, deeply interconnected engineering and science sandbox reasonably achievable within Minecraft.

**The mod must allow players to discover, experiment with, design, construct, optimize, and operate real engineering systems using realistic physical principles and primitive components.**

It must not be a collection of predefined machines with scripted input/output behaviors.

Instead, it must be a coherent simulated physical environment in which complex machines and processes emerge naturally from the interactions of simpler components.

The player should be able to invent devices that you, the developer, never specifically anticipated.

This is one of the most important success criteria.

### Fundamental philosophy

**Simulate physical laws, not predefined inventions.**

Do not implement a generator as a block that consumes rotational energy and produces electricity.

Implement magnetic fields, conductive materials, relative motion, electrical induction, mechanical forces, and electrical circuits so that players can construct generators themselves.

Do not implement a steam engine as a block that consumes steam and produces rotation.

Implement pressure, temperature, phase transitions, valves, pistons, forces, friction, and mechanical constraints so that a steam engine can emerge from their interactions.

Do not implement a computer as a block that runs arbitrary programs.

Implement semiconductor devices, electrical switching, logic circuits, memory elements, clocks, and communication systems so that players can construct computers.

Do not implement a rocket engine as a block that converts fuel into thrust.

Implement combustion, thermodynamics, fluid flow, nozzle geometry, conservation of momentum, and reaction forces so that rockets become possible.

The goal is not merely to reproduce existing technology.

The goal is to give players the physical building blocks needed to invent technology.

---

# 2. Non-Negotiable Design Principles

## 2.1 Scientific realism takes priority

The scientific and engineering systems must be grounded in real physical laws.

Do not arbitrarily simplify a system merely because the intended audience is playing Minecraft.

Use real equations, physical constants, material properties, and established engineering models wherever computationally feasible.

Examples include:

- Newton's laws of motion
- Conservation of energy
- Conservation of mass
- Conservation of momentum
- Maxwell's equations
- Faraday's law of induction
- Kirchhoff's circuit laws
- Ohm's law
- Thermodynamic laws
- Ideal and nonideal gas equations of state
- Heat transfer equations
- Fluid continuity and momentum equations
- Bernoulli's equation within its domain of validity
- Navier–Stokes-based approximations
- Hooke's law and more advanced constitutive models
- Beam bending and torsion
- Material yield and fatigue behavior
- Semiconductor device equations
- Chemical reaction kinetics
- Chemical equilibrium
- Phase diagrams
- Diffusion laws
- Feedback control theory
- Rigid-body dynamics
- Electromagnetic force and torque equations

The simulation must preserve dimensional consistency and physical conservation laws.

Scientific accuracy must be explicitly evaluated and tested.

When full physical simulation is computationally prohibitive, use established numerical approximations or reduced-order models that preserve the important physical relationships.

Never substitute arbitrary game mechanics for physical behavior when a credible physical model is practical.

Document all approximations, their assumptions, their validity ranges, and their expected errors.

## 2.2 Primitive components rather than complete machines

Players must construct complex systems from primitive elements.

For example, instead of providing:

- Generator block
- Electric motor block
- Steam turbine block
- Computer block
- Battery block
- Refrigeration machine block
- Pump block
- Rocket engine block

Provide the underlying components:

- Conductive wire
- Magnetic materials
- Permanent magnets
- Electromagnetic coils
- Bearings
- Shafts
- Rotors
- Stators
- Gears
- Springs
- Seals
- Pistons
- Cylinders
- Valves
- Pipes
- Structural beams
- Fasteners
- Semiconductor junctions
- Electrodes
- Electrolytes
- Capacitor plates
- Dielectric materials
- Resistors
- Heat exchangers
- Combustion chambers
- Nozzles
- Sensors
- Mechanical linkages

Some manufactured components may be supplied when their internal microscopic structure cannot reasonably be represented, but their behavior must remain physical and composable.

Every higher-level machine should preferably be a construction assembled by the player.

## 2.3 Emergent behavior is essential

The simulation must support combinations of components that were never specifically programmed as named machines.

Examples:

A rotating magnet near a copper coil should induce voltage even if the player did not use a designated generator blueprint.

A current-carrying conductor in a magnetic field should experience a force even if the player is not building a recognized motor.

A sealed vessel containing heated gas should develop pressure, and that pressure should exert forces on movable surfaces.

A mechanical oscillator should be able to interact with an electrical oscillator through electromechanical coupling.

A thermoelectric junction should develop voltage from a temperature gradient.

A conductive object moving through a magnetic field should experience induced currents and potentially magnetic drag.

A structural system should deform, vibrate, buckle, or fail according to its properties rather than a predefined machine-specific script.

**Unexpected behavior that follows correctly from the physical model is a feature, not a bug.**

## 2.4 Interoperability across disciplines

All engineering systems must interact through shared physical quantities.

Examples:

- Mechanical rotation drives electrical induction.
- Electrical resistance produces heat.
- Heat changes material properties.
- Temperature changes chemical reaction rates.
- Chemical reactions release or absorb energy.
- Pressure drives mechanical motion.
- Mechanical motion drives fluid pumps.
- Fluid motion transfers heat.
- Electrical circuits operate actuators.
- Sensors provide feedback to control systems.
- Computers regulate motors, valves, and robotic mechanisms.
- Structural deformation changes mechanical alignment.
- Material corrosion alters electrical and mechanical performance.

Do not create isolated simulation systems that only interact through arbitrary conversion APIs.

Implement shared conservation-based coupling wherever practical.

## 2.5 Real engineering requires tradeoffs

Every engineering design should have realistic advantages and disadvantages.

Examples:

- Thicker wires have lower resistance but require more material.
- Higher rotational speeds increase some forms of power density but create mechanical stresses.
- High gear ratios increase torque while reducing speed and introducing losses.
- Higher temperatures improve certain thermodynamic efficiencies but stress materials.
- Stronger structural materials may be heavier or more expensive.
- Higher electrical frequencies increase some losses.
- High-pressure vessels require adequate strength and sealing.
- Efficient heat transfer may require greater surface area or pumping power.
- Faster digital circuits require consideration of propagation delay, power, and signal integrity.

There must not be one universally optimal design.

---

# 3. Technical Foundation and Architecture

Before implementing substantial gameplay content, establish a robust simulation architecture.

## 3.1 Minecraft platform

Select a stable, modern Minecraft Java Edition version and a suitable mod loader, preferably NeoForge or Fabric.

Research and justify the selection based on:

- API maturity
- Modding ecosystem
- Performance
- Long-term maintenance
- Compatibility
- Rendering capabilities
- Multiplayer synchronization
- Support for custom block entities and physics systems

Use a modular codebase that can evolve as Minecraft and modding APIs change.

## 3.2 Separate the simulation from Minecraft

Implement a platform-independent scientific simulation core.

Minecraft-specific code should primarily handle:

- World integration
- Block placement
- Player interaction
- Rendering
- User interfaces
- Networking
- Chunk loading
- Persistence
- Mod interoperability

The simulation core should handle:

- Physical state
- Numerical integration
- Conservation laws
- Material properties
- Constraint solving
- Electrical networks
- Mechanical networks
- Fluid networks
- Thermal networks
- Chemical processes
- Multiphysics coupling

The scientific simulation should be testable without launching Minecraft.

This separation is mandatory.

## 3.3 Unified physical quantities and units

Use SI units internally wherever possible.

Examples:

| Quantity | Unit |
|---|---|
| Length | meter |
| Mass | kilogram |
| Time | second |
| Force | newton |
| Torque | newton-meter |
| Energy | joule |
| Power | watt |
| Voltage | volt |
| Current | ampere |
| Resistance | ohm |
| Temperature | kelvin |
| Pressure | pascal |
| Magnetic flux | weber |
| Magnetic field | tesla |
| Frequency | hertz |
| Charge | coulomb |

Create a coherent unit system with dimensional validation.

Avoid ambiguous or arbitrary quantities such as generic energy units, rotational power units, or fictional fluid units.

Minecraft blocks may represent one-meter cubes at the world scale, but the engineering simulation must support much smaller components.

## 3.4 Sub-block engineering construction

One-meter blocks are far too large for realistic engineering.

Develop a detailed sub-block construction system.

Players should be able to place components with substantially finer spatial resolution than Minecraft's standard grid.

Support:

- Small rods
- Thin wires
- Plates
- Tubes
- Bearings
- Gears
- Bolts
- Shafts
- Connectors
- Circuit board traces
- Magnetic elements
- Mechanical joints
- Microfluidic structures
- Semiconductor devices

Consider configurable resolutions such as 1/16, 1/32, or 1/64 of a block, with continuous or parametric geometry where useful.

Do not simulate every atom or mesh element individually.

Use efficient geometric representations, analytical models, and aggregated physical components.

The physical dimensions of components must matter.

Wire length affects resistance.

Cross-sectional area affects current capacity and strength.

Gear geometry affects engagement.

Pipe diameter affects fluid resistance.

Surface area affects heat transfer.

Mass distribution affects rotational inertia.

## 3.5 Physical connectivity and topology

The engine must automatically identify physical connections.

Different connection types include:

- Electrical contact
- Mechanical attachment
- Rotational coupling
- Fluid connection
- Thermal contact
- Magnetic coupling
- Optical coupling
- Chemical exchange
- Signal communication

Connections must depend on geometry, material properties, and physical contact where appropriate.

Players should not need to select a predefined machine type to activate a system.

A collection of components should begin behaving according to physics when assembled.

## 3.6 Numerical simulation

Implement appropriate numerical methods for each subsystem.

Potential techniques:

- Modified nodal analysis for electrical circuits
- Sparse matrix solvers
- Newton–Raphson iteration for nonlinear systems
- Implicit integration for stiff systems
- Symplectic integration for suitable mechanical dynamics
- Constraint-based rigid-body solvers
- Finite-volume methods
- Lumped-parameter thermal networks
- Reduced-order electromagnetic models
- Reaction network solvers
- Adaptive time stepping
- Event-driven digital logic simulation
- Differential-algebraic equation solvers

Select methods based on stability, accuracy, and performance.

Avoid naïve explicit integration when it would create numerical instability.

Support multirate simulation.

For example:

- Digital switching may require very short simulated time steps.
- Electrical transients may operate on microsecond-to-millisecond scales.
- Mechanical dynamics may operate on millisecond scales.
- Thermal changes may occur over seconds.
- Chemical corrosion may occur over hours.
- Geological processes may occur over much longer periods.

Do not require every subsystem to update at the same frequency.

Develop mechanisms for exchanging energy, momentum, and state between subsystems at synchronization boundaries.

Coupled solvers should iterate where necessary rather than assuming one-way instantaneous conversions.

## 3.7 Conservation laws

Conservation is foundational.

Track and validate:

- Mass
- Electrical charge
- Energy
- Linear momentum
- Angular momentum
- Chemical elements

Where the model includes external reservoirs or environmental interactions, explicitly account for exchanges with those reservoirs.

Numerical error must be measured and bounded.

Never create energy or matter merely because two subsystems exchange data.

## 3.8 Performance and scalability

The mod must remain playable.

Develop:

- Spatial partitioning
- Simulation islands
- Network graph partitioning
- Sparse matrices
- Cached factorizations where appropriate
- Adaptive solver tolerances
- Event-driven updates
- Inactive system sleeping
- Parallel processing where safe
- Efficient server-authoritative synchronization
- Simulation profiling
- Configurable accuracy levels
- Distance-based fidelity scaling with conservation-aware transitions

The engine must support large engineering installations without requiring all microscopic phenomena to be simulated everywhere.

Avoid sacrificing the integrity of conservation laws merely to increase performance.

---

# 4. Mechanical Engineering

Implement a comprehensive mechanical engineering environment.

## 4.1 Rigid-body mechanics

Support:

- Mass
- Center of mass
- Moments of inertia
- Translational motion
- Rotational motion
- Applied forces
- Torque
- Gravity
- Friction
- Contact forces
- Collision
- Momentum transfer
- Damping
- Mechanical work

Components should move and interact physically.

## 4.2 Mechanical components

Provide primitive elements such as:

- Shafts
- Bearings
- Bushings
- Gears
- Racks
- Pinions
- Pulleys
- Belts
- Chains
- Springs
- Dampers
- Flywheels
- Cranks
- Connecting rods
- Camshafts
- Pistons
- Cylinders
- Universal joints
- Couplings
- Hinges
- Linear guides
- Lead screws
- Clutches
- Brakes
- Ratchets
- Differential gear components

These should be physically modeled rather than being mere animations.

## 4.3 Gears and power transmission

Model:

- Gear ratio
- Torque transmission
- Angular velocity
- Mechanical efficiency
- Tooth engagement
- Backlash
- Friction
- Mechanical loading
- Shaft torsion
- Gear stress
- Wear

Players should be able to construct complex transmissions.

Examples include:

- Manual gearboxes
- Planetary gear systems
- Differential drives
- Mechanical clocks
- Continuously variable transmissions
- Reduction gearboxes
- Mechanical calculators

Do not hardcode these assemblies.

## 4.4 Material strength and structural mechanics

Support:

- Elastic deformation
- Plastic deformation
- Yield strength
- Ultimate strength
- Buckling
- Bending
- Shear
- Torsion
- Fatigue
- Stress concentration
- Thermal expansion
- Creep
- Fracture
- Vibration
- Resonance

Use appropriate beam, shell, truss, or reduced finite-element models.

Structures should fail according to physically meaningful loading conditions.

## 4.5 Tribology

Include:

- Static friction
- Kinetic friction
- Rolling resistance
- Bearing losses
- Lubrication
- Wear
- Surface roughness
- Contact pressure
- Temperature-dependent friction

This should make lubrication and precision manufacturing meaningful.

---

# 5. Electrical Engineering

Electrical engineering must be one of the deepest systems in the mod.

## 5.1 Fundamental electrical simulation

Support:

- Electric charge
- Voltage
- Current
- Resistance
- Conductance
- Capacitance
- Inductance
- Electrical power
- Electric fields
- Magnetic fields
- Electromagnetic induction
- Transient behavior
- Alternating current
- Direct current
- Frequency-dependent impedance

Implement Kirchhoff's laws and realistic circuit solving.

## 5.2 Conductive materials

Conductors should have realistic properties:

- Resistivity
- Temperature coefficient
- Geometry-dependent resistance
- Thermal conductivity
- Current density
- Joule heating
- Melting behavior
- Contact resistance

A long, thin copper wire must behave differently from a short, thick copper wire.

## 5.3 Passive components

Players should be able to construct or utilize physically grounded versions of:

- Resistors
- Capacitors
- Inductors
- Transformers
- Switches
- Relays
- Fuses
- Circuit breakers
- Transmission lines

For example, capacitance should depend on conductor geometry and dielectric properties.

Inductance should depend on coil geometry, magnetic materials, and coupling.

## 5.4 Electromagnetism

Implement a practical field model that supports:

- Permanent magnets
- Magnetic dipoles
- Electromagnets
- Magnetic flux
- Flux linkage
- Electromagnetic force
- Induction
- Magnetic saturation
- Hysteresis
- Eddy currents
- Lorentz forces
- Magnetic torque

Use reduced-order field solvers, magnetic circuit models, or localized numerical field calculations where appropriate.

A fully general Maxwell solver is not required everywhere, but the resulting behavior must be physically credible.

## 5.5 Player-built motors and generators

Players must be able to build:

- Brushed DC motors
- Brushless motors
- Synchronous motors
- Induction motors
- Stepper motors
- Linear motors
- Alternators
- Dynamos
- Transformers

These must emerge from electromagnetic interactions and geometry.

The engine must account for:

- Back electromotive force
- Torque constants
- Winding resistance
- Winding inductance
- Magnetic coupling
- Core losses
- Copper losses
- Mechanical loading
- Heating
- Efficiency

A generator should resist rotation when delivering electrical power.

A motor should respond to mechanical load.

Energy transfer must be conserved.

## 5.6 Power electronics

Support realistic semiconductor switching and circuits such as:

- Rectifiers
- Inverters
- DC–DC converters
- PWM motor controllers
- H-bridges
- Voltage regulators
- Oscillators
- Resonant circuits

Players should be able to construct these using primitive electronic devices.

---

# 6. Computer Engineering and Electronics

## 6.1 Semiconductor physics

Implement computationally practical models of:

- PN junctions
- Diodes
- Bipolar transistors
- MOSFETs
- LEDs
- Photodiodes
- Phototransistors
- Semiconductor conductivity
- Breakdown behavior
- Temperature effects

Use physically grounded device equations.

## 6.2 Digital logic

Players should be able to build logic from transistor-level components.

Support:

- Logic gates
- Flip-flops
- Latches
- Counters
- Registers
- Multiplexers
- Adders
- Memory cells
- Clock circuits
- State machines

Do not make logic gates the only way to create digital behavior.

They may exist as assembled components, but transistor-level construction must remain possible.

## 6.3 Complete computers

The player should be able to design:

- Arithmetic logic units
- Instruction decoders
- Program counters
- Registers
- Memory
- Buses
- Input/output controllers
- Microprocessors
- Microcontrollers
- Entire custom computer architectures

A computer must be able to control other simulated engineering systems.

For example, a player-built computer should be able to read a temperature sensor, calculate a control output, and adjust a motor or valve.

## 6.4 Efficient digital simulation

Simulating millions of transistors with continuous analog circuit equations would be computationally prohibitive.

Develop a validated hierarchy of abstraction.

Use detailed transistor models for small circuits and event-driven digital models for larger circuits when operating within verified digital conditions.

Preserve important behaviors such as:

- Propagation delay
- Setup and hold constraints
- Clock frequency
- Power consumption
- Electrical loading
- Signal integrity
- Metastability where appropriate

Provide explicit transitions between simulation fidelity levels.

## 6.5 Communication systems

Implement:

- Serial communication
- Parallel buses
- UART
- SPI
- I²C
- CAN-like networks
- Digital modulation
- Analog modulation
- Signal transmission
- Noise
- Bandwidth
- Attenuation

Protocols should emerge from electrical signaling and software rather than requiring special magic communication blocks.

---

# 7. Thermodynamics and Heat Transfer

Implement a detailed thermal simulation.

## 7.1 Thermal properties

Track:

- Temperature
- Internal energy
- Heat capacity
- Thermal conductivity
- Thermal expansion
- Latent heat
- Phase
- Enthalpy where appropriate

## 7.2 Heat transfer

Support:

- Conduction
- Convection
- Thermal radiation
- Contact thermal resistance
- Insulation
- Heat exchangers
- Heat sinks
- Forced cooling

## 7.3 Thermodynamic cycles

Players should be able to construct:

- Steam engines
- Steam turbines
- Stirling engines
- Internal combustion engines
- Refrigerators
- Heat pumps
- Air compressors
- Gas turbines
- Rankine-cycle systems
- Brayton-cycle systems

These should emerge from fluid mechanics, pressure, heat transfer, mechanical motion, and thermodynamics.

## 7.4 Phase transitions

Support:

- Melting
- Solidification
- Vaporization
- Condensation
- Sublimation where relevant
- Latent heat
- Pressure-dependent boiling
- Superheating
- Two-phase mixtures

Heating water in a sealed container must produce physically meaningful pressure changes and energy transfer.

---

# 8. Fluid Mechanics and Hydraulics

## 8.1 Fluid simulation

Support:

- Density
- Pressure
- Velocity
- Viscosity
- Compressibility
- Flow resistance
- Turbulence approximations
- Reynolds number
- Cavitation
- Buoyancy
- Hydrostatic pressure

## 8.2 Fluid components

Provide:

- Pipes
- Fittings
- Valves
- Tanks
- Nozzles
- Pistons
- Impellers
- Rotors
- Seals
- Filters
- Heat exchangers

Fluid behavior should depend on geometry and physical properties.

## 8.3 Emergent machines

Allow players to construct:

- Centrifugal pumps
- Positive displacement pumps
- Hydraulic motors
- Hydraulic presses
- Water turbines
- Pneumatic actuators
- Compressors
- Venturi devices
- Fluid control systems

Avoid hardcoded pump or turbine conversion blocks.

## 8.4 Aerodynamics

Implement practical aerodynamic models for:

- Lift
- Drag
- Pressure distribution approximations
- Angle of attack
- Stall
- Propellers
- Wings
- Rotors
- Airfoils
- Wind turbines
- Flight control surfaces

Use validated approximations and appropriate flow regimes.

---

# 9. Chemical Engineering and Chemistry

## 9.1 Chemical substances

Create a data-driven chemical substance system.

Each substance should have appropriate properties such as:

- Molecular composition
- Molar mass
- Density
- Heat capacity
- Melting point
- Boiling point
- Vapor pressure
- Enthalpy of formation
- Thermal conductivity
- Solubility
- Viscosity
- Electrical conductivity
- Relevant reaction parameters

Use established scientific reference data where available.

## 9.2 Chemical reactions

Implement:

- Stoichiometry
- Conservation of elements
- Reaction enthalpy
- Reaction kinetics
- Activation energy
- Catalysis
- Chemical equilibrium
- Concentration dependence
- Temperature dependence
- Reaction reversibility

Reactions must be based on a chemically valid reaction network.

Do not invent reactions simply because two items are combined.

## 9.3 Chemical engineering processes

Support:

- Distillation
- Filtration
- Evaporation
- Crystallization
- Dissolution
- Precipitation
- Electrolysis
- Electroplating
- Fermentation
- Adsorption
- Absorption
- Membrane separation
- Heat-driven reactions
- Catalytic processes

## 9.4 Chemical apparatus

Players should construct processing equipment from:

- Vessels
- Pipes
- Valves
- Heating elements
- Cooling elements
- Agitators
- Membranes
- Electrodes
- Filters
- Condensers
- Separation surfaces

Avoid one-block chemical processing machines.

## 9.5 Electrochemistry

Model:

- Electrode potentials
- Redox reactions
- Electrolyte conductivity
- Ion transport approximations
- Internal resistance
- Charge transfer
- Polarization
- Concentration effects
- Temperature effects

Allow players to construct electrochemical cells from electrodes, electrolytes, separators, and containers.

Battery voltage and capacity should arise from chemistry and construction.

## 9.6 Chemical safety and scope

Represent hazardous chemistry through scientifically grounded but non-instructional models.

Do not provide actionable synthesis procedures, operating recipes, or optimized designs for explosives, toxic chemical agents, or other inherently dangerous substances.

The educational focus is on thermodynamics, kinetics, separation, electrochemistry, materials, and process control.

---

# 10. Materials Science and Manufacturing

## 10.1 Material properties

Develop an extensive materials database.

Properties may include:

- Density
- Young's modulus
- Shear modulus
- Poisson's ratio
- Yield strength
- Ultimate tensile strength
- Hardness
- Fatigue properties
- Thermal conductivity
- Electrical resistivity
- Specific heat
- Thermal expansion
- Magnetic permeability
- Corrosion resistance
- Melting point
- Fracture toughness

## 10.2 Material categories

Support:

- Metals
- Alloys
- Ceramics
- Polymers
- Composites
- Semiconductors
- Glasses
- Magnetic materials
- Insulators

## 10.3 Manufacturing

Players should be able to create components through realistic manufacturing concepts:

- Casting
- Forging
- Machining
- Milling
- Turning
- Drilling
- Grinding
- Welding
- Soldering
- Heat treatment
- Extrusion
- Drawing
- Additive manufacturing
- Composite layup

Manufacturing processes should affect properties and geometry.

For example:

- Poor tolerances increase friction and misalignment.
- Heat treatment alters material behavior.
- Surface finish affects wear.
- Manufacturing defects affect reliability.
- Geometric precision affects performance.

Do not require tedious manual execution of every manufacturing action when it adds no engineering value.

Provide realistic automation and machine tools that players can construct.

---

# 11. Civil and Structural Engineering

Implement:

- Structural loads
- Beams
- Columns
- Trusses
- Frames
- Foundations
- Soil mechanics approximations
- Load paths
- Stress distribution
- Deflection
- Buckling
- Vibration
- Material failure
- Structural stability

Players should be able to design:

- Bridges
- Towers
- Cranes
- Buildings
- Dams
- Mechanical platforms
- Large industrial facilities

Structures should not simply remain intact because blocks are connected.

Structural integrity should follow engineering principles.

---

# 12. Aerospace Engineering

Support:

- Rigid-body flight dynamics
- Lift and drag
- Aerodynamic stability
- Propeller performance
- Rotor dynamics
- Control surfaces
- Mass distribution
- Center of pressure
- Center of gravity
- Atmospheric density
- Altitude effects
- Propulsion
- Thrust
- Reaction momentum
- Orbital mechanics where a suitable world model exists

Players should be able to build aircraft, helicopters, drones, and spacecraft from physical components.

For propulsion, prioritize general physical principles and safe educational abstractions rather than detailed hazardous propellant preparation or dangerous propulsion fabrication instructions.

Flying machines should interact with mechanical, electrical, structural, and computer engineering systems.

---

# 13. Control Systems and Robotics

## 13.1 Sensors

Implement physically meaningful sensors such as:

- Thermistors
- Thermocouples
- Strain gauges
- Potentiometers
- Encoders
- Accelerometers
- Gyroscopes
- Magnetometers
- Pressure sensors
- Flow sensors
- Optical sensors
- Photodiodes
- Hall-effect sensors

Sensors should have realistic characteristics:

- Noise
- Bias
- Resolution
- Drift
- Response time
- Calibration error
- Measurement range

## 13.2 Control systems

Support player-designed:

- Open-loop control
- Closed-loop control
- PID controllers
- State-space controllers
- Observers
- Filters
- Digital control
- Analog control
- Feedback compensation

These should be constructed using circuits, computers, and mechanical systems.

## 13.3 Robotics

Players should be able to build:

- Robotic arms
- Mobile robots
- Autonomous vehicles
- Walking mechanisms
- Gimbals
- Stabilized platforms
- Industrial automation systems

Model:

- Actuator torque
- Inertia
- Gear reduction
- Friction
- Sensor feedback
- Kinematics
- Dynamics
- Power consumption
- Control stability

---

# 14. Optics, Acoustics, and Additional Sciences

The mod should eventually extend far beyond the traditional engineering disciplines.

## Optics

Support:

- Reflection
- Refraction
- Lenses
- Mirrors
- Focal length
- Optical instruments
- Photovoltaic effects
- Photodetection
- Optical communication

## Acoustics

Support:

- Mechanical vibrations
- Sound waves
- Resonance
- Acoustic propagation
- Frequency response
- Microphones
- Speakers
- Ultrasonic sensing

## Geology and Earth science

Support:

- Mineral composition
- Ore formation abstractions
- Rock properties
- Geological strata
- Groundwater
- Soil properties
- Weathering
- Erosion

## Environmental engineering

Support:

- Water treatment
- Air filtration
- Pollution transport
- Heat pollution
- Resource recovery
- Waste processing
- Renewable energy systems

## Nuclear physics and engineering

Support scientifically grounded educational models of:

- Radioactive decay
- Half-life
- Radiation attenuation
- Radiation detection
- Energy deposition
- Shielding
- Heat transfer
- Reactor thermodynamic principles

Use abstracted and non-actionable models for hazardous nuclear systems rather than detailed construction or optimization guidance.

## Biology and bioengineering

Eventually consider:

- Microbial growth
- Bioreactors
- Biomechanics
- Population dynamics
- Biological energy conversion
- Environmental ecosystems

These must connect to the existing chemistry, thermodynamics, and fluid systems.

---

# 15. Scientific Instruments and Measurement

The mod must make experimentation a first-class gameplay activity.

Players should be able to construct and use instruments including:

- Voltmeters
- Ammeters
- Oscilloscopes
- Multimeters
- Thermometers
- Pressure gauges
- Tachometers
- Dynamometers
- Flow meters
- Spectrum analyzers
- Logic analyzers
- Calorimeters
- Strain measurement systems
- Data loggers

Measurements must come from simulated physical state.

They should not simply display predefined machine statistics.

Instruments should have finite accuracy, resolution, bandwidth, and measurement effects where relevant.

Provide advanced visualization tools:

- Voltage versus time
- Current versus time
- Temperature distributions
- Stress maps
- Fluid pressure maps
- Magnetic field visualizations
- Force vectors
- Torque curves
- Frequency spectra
- Efficiency curves
- Energy flow diagrams

Allow data export to CSV and other useful scientific formats.

---

# 16. Player Experience and Gameplay Design

Scientific accuracy does not mean the mod should be frustrating to use.

Make the interface intuitive without simplifying the underlying physics.

## 16.1 Construction tools

Provide:

- Precision placement
- Rotation and alignment
- Measurement tools
- Snapping
- Mechanical assembly tools
- Wire routing
- Pipe routing
- Circuit layout tools
- Component grouping
- Copy and paste
- Blueprints
- Undo and redo
- Parameter inspection

## 16.2 Engineering documentation

Create an extensive in-game engineering reference.

Explain:

- Physical equations
- Material properties
- Component behavior
- Relevant engineering principles
- Common failure modes
- Measurement techniques
- Numerical approximations

Provide diagrams, examples, and interactive explanations.

Do not replace real engineering equations with oversimplified game descriptions.

## 16.3 Learning through experimentation

Offer optional engineering challenges.

Examples:

- Generate electricity from mechanical rotation.
- Design a motor with a specified torque-speed curve.
- Build a bridge that supports a given load.
- Construct a temperature-controlled chamber.
- Design a functioning radio receiver.
- Build a programmable 8-bit computer.
- Create a stable robotic arm.
- Develop an efficient heat engine.
- Build a wind-powered electrical system.
- Construct a self-regulating industrial process.

Challenges should evaluate actual performance.

Avoid requiring one predefined solution.

## 16.4 Engineering progression

Progression should arise primarily from access to materials, manufacturing capabilities, measurement equipment, and accumulated knowledge.

Do not artificially gate technologies behind unrelated achievements.

A sufficiently knowledgeable player with the necessary resources should be able to invent advanced technology early.

## 16.5 Automation

Players should eventually be able to automate manufacturing, resource processing, measurement, and control.

Automation must be constructed using the same physical components available for manual systems.

---

# 17. Example Emergent Engineering Projects

Use the following as validation targets.

They are not exclusive gameplay recipes.

The engine should support them because its underlying physical laws support them.

### Electrical

1. Hand-cranked generator
2. Brushless electric motor
3. Transformer
4. Rectifier
5. Buck converter
6. Electric heater
7. Induction heater
8. Electromagnetic actuator
9. Electrochemical battery
10. Radio transmitter and receiver

### Mechanical

11. Gearbox
12. Mechanical clock
13. Differential
14. Flywheel energy storage
15. Mechanical calculator
16. Hydraulic press
17. Mechanical governor
18. Spring-powered mechanism
19. Belt-driven transmission
20. Vibration isolation system

### Thermal and fluid

21. Steam engine
22. Stirling engine
23. Heat pump
24. Refrigerator
25. Water turbine
26. Wind turbine
27. Hydraulic motor
28. Air compressor
29. Heat exchanger
30. Temperature-regulated fluid system

### Computer engineering

31. Transistor amplifier
32. Oscillator
33. Logic gates
34. Flip-flop
35. Binary counter
36. Arithmetic logic unit
37. Memory
38. 8-bit CPU
39. Programmable microcontroller
40. Computer-controlled manufacturing system

### Robotics

41. Servo motor
42. Robotic arm
43. Self-balancing robot
44. Autonomous rover
45. Stabilized camera platform
46. Sensor-based navigation system
47. Closed-loop motor controller
48. Robotic gripper
49. Automated sorting machine
50. Industrial control system

### Large interdisciplinary projects

51. Hydroelectric power plant
52. Electrified railway
53. Wind farm
54. Automated factory
55. Computer-controlled greenhouse
56. Solar-powered research station
57. Aircraft with electronic flight controls
58. Electrical power grid
59. Automated water treatment plant
60. Fully functioning programmable computer connected to physical machinery

These are only examples.

The engine should support thousands of additional designs without requiring dedicated code for each.

---

# 18. Validation and Scientific Testing

Every scientific subsystem must have rigorous automated tests.

## 18.1 Analytical validation

Compare simulation results against known analytical solutions.

Examples:

- Resistor networks
- RC and RL transients
- RLC resonance
- Free-fall motion
- Harmonic oscillators
- Beam deflection
- Thermal conduction
- Ideal gas compression
- Hydrostatic pressure
- Laminar pipe flow
- Chemical equilibrium
- Rotational inertia

## 18.2 Conservation testing

Test conservation of:

- Energy
- Mass
- Charge
- Momentum
- Angular momentum
- Chemical elements

Measure numerical drift and establish acceptable tolerances.

## 18.3 Interdisciplinary testing

Create tests for interactions such as:

- Mechanical rotation inducing electrical current
- Electrical current creating magnetic torque
- Electrical resistance producing heat
- Heating gas producing pressure
- Pressure moving a piston
- Piston motion driving a generator
- Generator current heating a resistor
- Sensors controlling an actuator through a computer

## 18.4 Emergence testing

Test constructions that do not correspond to predefined machine types.

For example:

Construct an unusual arrangement of magnets, coils, and moving conductive surfaces.

Verify that the resulting electromagnetic behavior follows the field and circuit models rather than requiring recognition of a specific machine.

## 18.5 Performance testing

Benchmark:

- Thousands of electrical components
- Large mechanical assemblies
- Complex pipe networks
- Large thermal systems
- Digital circuits with many logic elements
- Multiphysics factories
- Multiple simultaneous simulation islands

Measure:

- Simulation time
- Server tick time
- Memory usage
- Numerical convergence
- Multiplayer synchronization overhead

Set explicit performance budgets and report when they are exceeded.

---

# 19. Development Strategy

This project is extremely ambitious and must be built incrementally without compromising its architecture.

## Phase 1 — Scientific foundation

Implement:

- Mod initialization
- Modular architecture
- Unit system
- Material database
- Geometry representation
- Connection graph
- Simulation scheduler
- Numerical solver interfaces
- Persistence
- Testing framework
- Basic visualization

## Phase 2 — Mechanical and electrical foundations

Implement:

- Rigid-body mechanics
- Shafts
- Bearings
- Basic gears
- Conductors
- Resistors
- Capacitors
- Inductors
- Magnetic elements
- Electrical circuit solver
- Electromechanical coupling

Milestone: Player constructs a physically simulated electric generator and motor from primitive components.

## Phase 3 — Thermal and fluid physics

Implement:

- Heat transfer
- Thermal mass
- Fluid networks
- Pressure
- Gas equations of state
- Phase transitions
- Pistons
- Valves
- Fluid-mechanical coupling

Milestone: Player constructs a heat engine that drives an electrical generator.

## Phase 4 — Electronics and computation

Implement:

- Semiconductor devices
- Analog circuits
- Digital logic
- Memory
- Computer architecture
- Sensors
- Actuators

Milestone: Player builds a programmable computer that controls a physical machine.

## Phase 5 — Chemistry and manufacturing

Implement:

- Chemical substances
- Reaction networks
- Electrochemistry
- Material processing
- Manufacturing methods
- Material property changes

Milestone: Player produces useful materials and electrochemical devices through physically modeled processes.

## Phase 6 — Advanced multiphysics

Implement:

- Structural analysis
- Advanced electromagnetic coupling
- Aerodynamics
- Control systems
- Robotics
- Advanced fluid mechanics
- Advanced thermodynamic systems

Milestone: Player constructs a complex autonomous machine using multiple engineering disciplines.

## Phase 7 — Broad scientific expansion

Expand into:

- Optics
- Acoustics
- Environmental engineering
- Geology
- Biological engineering
- Advanced materials
- Advanced manufacturing
- Additional scientific instruments

## Phase 8 — Continuous expansion

Continue identifying and implementing missing physical phenomena, engineering capabilities, materials, components, and scientific disciplines.

Do not treat Phase 8 as a finite content update.

It is an ongoing development objective.

---

# 20. Autonomous Development Instructions

You must actively implement the project, not merely plan it.

Your workflow should be:

1. Inspect the repository and development environment.
2. Research relevant Minecraft modding APIs and scientific models.
3. Establish the architecture.
4. Implement a small but complete vertical slice.
5. Compile the project.
6. Run automated tests.
7. Fix failures.
8. Validate scientific behavior.
9. Test integration inside Minecraft where tooling permits.
10. Profile performance.
11. Improve numerical accuracy and architecture.
12. Add the next set of capabilities.
13. Repeat.

At every stage, prefer functional, tested implementations over superficial demonstrations.

Do not fill the mod with decorative blocks that have no meaningful physical behavior.

Do not claim that a scientific system is implemented if it is merely represented by placeholder behavior.

Do not replace unfinished systems with scripted approximations without explicitly identifying and documenting the limitation.

## 20.1 Continue working autonomously

Do not stop after creating the initial framework.

Do not stop after implementing a few systems.

Do not stop after producing a playable prototype.

Do not stop merely because the first milestone has been achieved.

Continue expanding the mod through successive development cycles until the available execution budget, tool limitations, or an explicit user instruction requires stopping.

When interrupted, preserve enough state to resume effectively.

Maintain:

- ROADMAP.md
- ARCHITECTURE.md
- PHYSICS_MODELS.md
- MATERIAL_DATABASE.md
- VALIDATION.md
- PERFORMANCE.md
- KNOWN_LIMITATIONS.md
- DEVELOPMENT_LOG.md
- NEXT_STEPS.md

Update these documents as the project evolves.

At the end of each working session, leave the repository compiling wherever possible, record incomplete work accurately, and identify the highest-priority next task.

Never imply that background development continues after the execution environment has stopped.

## 20.2 Prioritization

Prioritize work using these criteria:

1. Scientific correctness
2. Generality and emergent capability
3. Interdisciplinary interoperability
4. Stability and reliability
5. Performance
6. Player usability
7. Feature breadth
8. Visual polish

However, usability and performance must be considered from the beginning rather than postponed indefinitely.

## 20.3 Avoid feature shortcuts

Never implement a feature solely by detecting a particular arrangement of blocks and assigning it a predefined result when the result could instead be calculated from physical laws.

Bad:

"If these blocks form a generator, output 100 watts."

Good:

"Calculate flux linkage, induced electromotive force, circuit current, magnetic torque, mechanical power, and losses from geometry, motion, and material properties."

Bad:

"If a steam turbine receives hot steam, produce rotation."

Good:

"Calculate thermodynamic state, mass flow, enthalpy transfer, momentum exchange, and resulting torque using an appropriate turbine model."

Bad:

"If a battery contains two metals and an electrolyte, provide fixed energy."

Good:

"Calculate electrochemical potential, available reactants, current-dependent polarization, internal resistance, and thermal effects."

The same principle applies to every discipline.

---

# 21. Scientific Model Fidelity and Abstraction

It is impossible to simulate every physical phenomenon at atomic resolution in real time.

The correct objective is not maximum microscopic detail everywhere.

The objective is maximum scientifically meaningful fidelity for the engineering scales and behaviors being represented.

Use hierarchical physical models.

Examples:

### Electrical

Use lumped circuit models when conductor dimensions are small relative to relevant electromagnetic wavelengths.

Use transmission-line models when propagation effects become significant.

Use localized field models when geometry-dependent electromagnetic effects are important.

### Mechanical

Use rigid-body models when deformation is negligible.

Use beam or finite-element approximations when structural deformation matters.

Use more detailed contact models when contact mechanics determine performance.

### Fluids

Use lumped pressure-flow networks for ordinary piping.

Use one-dimensional compressible flow models where appropriate.

Use localized higher-dimensional approximations when flow geometry is central to the engineering problem.

### Thermal

Use lumped thermal masses when internal temperature gradients are negligible.

Use spatial thermal networks when gradients matter.

### Chemistry

Use reaction networks and equilibrium models rather than molecular dynamics for macroscopic processes.

### Digital electronics

Use analog transistor models for small or electrically sensitive circuits.

Use validated event-driven abstractions for large digital systems.

Every abstraction must have documented assumptions and a mechanism for detecting when those assumptions are violated.

---

# 22. Critical Architectural Requirement: Unified Multiphysics Coupling

Create a general coupling framework based on physical state variables and conserved quantities.

Examples of interacting domains:

| Domain | Effort-like quantity | Flow-like quantity |
|---|---|---|
| Electrical | Voltage | Current |
| Rotational mechanical | Torque | Angular velocity |
| Translational mechanical | Force | Velocity |
| Hydraulic | Pressure difference | Volumetric flow |
| Thermal | Temperature / thermodynamic potential | Heat or entropy flow, as appropriate |

Use physically valid power-conjugate variables when defining coupling interfaces.

For example:

Electrical power:

P = V I

Rotational mechanical power:

P = τ ω

Translational mechanical power:

P = F v

Hydraulic power:

P = Δp Q

Thermal interactions require thermodynamically consistent treatment; temperature multiplied directly by heat-flow rate is not power.

Consider port-Hamiltonian modeling, bond graphs, or related energy-based modeling techniques as architectural inspirations.

The system should make it straightforward to implement a new physical component that connects several domains without introducing arbitrary energy conversions.

For example, an electromagnetic motor couples:

- Electrical energy
- Magnetic energy
- Mechanical energy
- Thermal losses

A thermoelectric device couples:

- Electrical energy
- Thermal transport
- Temperature-dependent material behavior

An electrochemical cell couples:

- Chemical free energy
- Electrical work
- Heat
- Species transport

This framework is one of the most important architectural decisions in the entire project.

---

# 23. Definition of Quality

The mod must aim for the following standards.

## Scientific quality

- Realistic governing equations
- Physically meaningful units
- Documented assumptions
- Verified numerical methods
- Conservation-aware coupling
- Reproducible test cases
- Credible material properties

## Engineering quality

- Modular architecture
- Maintainable code
- Automated testing
- Robust persistence
- Safe multiplayer synchronization
- Numerical stability
- Profiling and optimization
- Data-driven extensibility

## Gameplay quality

- Intuitive construction
- Powerful inspection tools
- Useful documentation
- Creative freedom
- No arbitrary recipe restrictions
- Meaningful design tradeoffs
- Rewarding experimentation
- Support for unexpected inventions

## Content quality

- Extensive primitive components
- Broad scientific coverage
- Deep engineering interactions
- Large materials database
- Numerous measurable physical phenomena
- Diverse engineering challenges
- Extensive instrument support

---

# 24. Final Success Criteria

The ultimate success criterion is not the number of machines or blocks added.

It is the number of physically plausible machines, experiments, and systems that players can invent without the developer having explicitly programmed those inventions.

A truly successful implementation would allow a player to:

1. Mine and process raw materials.
2. Manufacture conductive wire and magnetic components.
3. Build a mechanically driven generator.
4. Construct electrical circuits.
5. Build electric motors.
6. Manufacture semiconductor devices.
7. Assemble a functioning computer.
8. Construct sensors and feedback systems.
9. Design automated machinery.
10. Build industrial systems that combine chemistry, mechanics, electricity, heat, fluids, materials, and computation.

Every step should rely on physically meaningful processes.

The resulting world should behave like a laboratory, workshop, factory, and engineering test environment governed by coherent physical laws.

**The player should be limited primarily by physical laws, available resources, engineering knowledge, and computational feasibility—not by an arbitrary list of inventions anticipated by the mod developer.**

---

# 25. Immediate Execution Instructions

Begin development now.

First:

1. Inspect the current project directory and available tools.
2. If no project exists, initialize a new Minecraft mod project.
3. Select and document the target Minecraft version, mod loader, and Java toolchain.
4. Design the independent simulation core.
5. Establish physical units, materials, component geometry, and connection interfaces.
6. Implement a minimal electrical circuit solver and mechanical rotational system.
7. Implement the first conservation-aware electromechanical coupling.
8. Build an in-game construction interface for assembling primitive conductors, magnets, coils, shafts, and bearings.
9. Demonstrate a player-built generator and motor without predefined generator or motor machine blocks.
10. Write analytical tests and validate the results.
11. Continue into thermal, fluid, chemical, and computer engineering systems.

After each milestone, automatically choose the next highest-value task and implement it.

If a task is too large, decompose it into smaller testable pieces and continue.

If a scientific model is computationally expensive, investigate and implement an appropriate validated approximation.

If a design decision prevents emergent behavior, reconsider the architecture.

If an implementation violates conservation laws, fix the underlying model rather than concealing the error.

If a feature works only for a predefined assembly, generalize it.

If a subsystem cannot interact with other scientific domains, improve its interfaces.

If the mod is difficult to use, improve its engineering tools without compromising the physics.

If the mod becomes slow, profile and optimize its numerical methods rather than arbitrarily removing physical behavior.

Continue developing, validating, documenting, and expanding the mod as far as the available environment permits.

**Do not build a technology-themed Minecraft mod. Build a scientifically grounded engineering simulation platform inside Minecraft.**

**The ultimate vision is a Minecraft world in which players can discover, design, and construct an enormous range of real technologies from first principles, with realistic, interconnected, emergent physical behavior.**

# 26. Version Control

Once any particular feature or isolated change is complete and building without issue, then add those changes into a Git commit with a descriptive message. Only commit, leave it to the user to push manually.