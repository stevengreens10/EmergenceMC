# Roadmap

Status legend: ✅ done · 🟡 partial · ⬜ not started. Phases follow CLAUDE.md §19.

## Phase 1 — Scientific foundation
✅ Mod initialisation, modular two-layer architecture (Minecraft-free core enforced by the build)
✅ SI unit system with dimensional validation
✅ Data-driven materials database (37 materials, sourced, dimension-checked)
✅ Grid geometry representation (1/16 m cells, parametric dimensions)
✅ Connection graph inference (rigid, revolute, electrical, thermal, magnetic)
✅ Unified multi-domain network and adaptive implicit solver, sparse/dense LU
✅ Persistence (part lists and dynamic state per block entity)
✅ Testing framework (JUnit core suite, in-world GameTests, automated visual tour)
✅ Visualisation: Blender-authored part meshes and textures, procedural gears/shafts/wires, rotation,
   incandescence, chemical colours, bubbles; multimeter (incl. chemistry)
✅ Two build scales: block scale (default, 1 block = 6.25 cm cell) and miniature (tweezers, true scale)
🟡 Simulation scheduler: per-tick islands; multirate and async compilation pending

## Phase 2 — Mechanical and electrical foundations
✅ Rotating rigid bodies on bearings, shafts, bearings (ball/plain), spur gears, cranks; rotors carried by slides
   (rotating unbalance, vibration, resonance) and slides carried by rotors (centrifugal force, Coriolis, variable
   inertia)
✅ Conductors (T-dependent), resistors, capacitors, inductors, switches, diodes
✅ Magnetic elements: permanent magnets, coils, field-derived electromechanical coupling
✅ **Milestone: player-built generator** (demo exhibit, tests) — motor action demonstrated in core
   (synchronous motor driven by a rotating current pattern)
✅ Player-built brushed DC motor: angle-dependent brush/segment contacts → commutation emerges (exhibit)
🟡 Ferromagnetic cores: linear magnetic equivalent circuit for iron fixed relative to coils ✅ (Faraday ring);
   saturation ✅ (eigenmodes of the iron's inductance, tanh, energy-exact); hysteresis, moving iron (reluctance
   torque), eddy currents ⬜; iron attracting magnets ✅ (images)
✅ Slides on linear bearings, helical springs, end stops, gravity along the guide, magnetics on slides
✅ Rams: cylinders driving slides (hydraulic press)
✅ Check-valve pumps (hydraulic jack); cavitation in liquids
✅ Pressure vessels burst at their wall strength (burst test exhibit)
✅ Rotor–slide couplings: rack and pinion, lead screws (thread friction, self-locking)
⬜ Linkages, free rigid bodies, helical joints (a nut turning on a fixed screw)

## Phase 3 — Thermal and fluid physics
🟡 Heat transfer: conduction, natural convection, radiation, thermal masses ✅; forced convection from
   duct jets ✅ (flow over rotating parts ⬜)
✅ Fluid networks: control volumes, pipes (Darcy–Weisbach), tanks, valves (choked orifices), atmosphere
   vents, wall heat transfer, ideal-gas and liquid equations of state, gravity head
✅ Bladed rotors by blade-element theory: fans, pumps, turbines, windmills from one part
✅ Piston cylinders and crank throws (slider-crank, variable-volume thermodynamics)
✅ Phase transitions: IAPWS water/steam, boiling (Rohsenow/Zuber), film condensation, saturated
   two-phase volumes with gravity-separated ports
✅ Valve gear: cam-worked valves on eccentrics; spring-loaded relief/check valves; brake pads
✅ **Steam engine from parts**: boiler on a hearth, safety valve, throttle, cam-timed admission and exhaust,
   single-acting cylinder, flywheel, Prony brake load
⬜ Free surfaces in open vessels, free translational bodies, non-condensable gases mixed with steam
✅ **Milestone: heat engine driving a generator** — an alpha Stirling engine from parts (hot and cold
   cylinders 90° apart, transfer pipe, flywheel) runs on a hot block and drives magnets past a coil

## Structural mechanics (Phase 6, started early)
✅ Static frame analysis of every anchored structure: beam members from contact faces, gravity, held
   fluid and rotor weights; yield (ductile) and fracture (brittle) failure with progressive collapse
✅ Euler buckling (linear stability of the frame; buckling exhibit)
✅ Fatigue of turning shafts: rotating bending on a beam model, Basquin S–N lines, endurance limits, Miner's rule
✅ Eddy-current drag: moving conductors and magnets (Arago's disc, brakes, magnets in tubes), heat in the conductor
✅ Moving iron (armatures pulled across an air gap), contacts closed by a slide's travel: a relay from parts, and a trembler (buzzer) from the relay wired through its own contacts
🟡 Vibration and resonance of sprung masses (unbalanced rotors on slides) ✅; dynamic loads on structures (impacts,
   structural vibration), fatigue of the static frame, creep, plasticity, soil

## Phase 4 — Electronics and computation
✅ Diode, BJT (Ebers–Moll), MOSFET (EKV), LED, photodiode / solar cell, phototransistor (optocoupler exhibit)
✅ Optical links with occlusion (light barriers, choppers on rotors) and daylight
✅ Logic from transistors (CMOS NAND exhibit) and a validated chip-level model (gates, D flip-flop,
   Schmitt trigger): oscillator + 4-bit counter exhibit
✅ Thermoelectricity (Seebeck EMF and Peltier heat at every dissimilar contact): thermocouple exhibit
✅ NTC thermistor sensor; closed-loop thermostat exhibit (thermistor → Schmitt trigger → MOSFET → heater)
✅ MSI chips from multi-block packages: counter, register, 4-bit adder, programmable ROM
✅ **Milestone: a programmable computer controls a physical machine** — the EM-4 (PC, ROM program,
   adder, accumulator; add / jump / jump-if-SENSE) drives an axial-flux stepper motor through MOSFETs
✅ RAM (16 × 4 static memory chip, memory exhibit); chip contents survive recompiling and saving
⬜ A richer instruction set (load/store to RAM), more sensors and actuators; event-driven simulation for thousands of gates

## Phase 5 — Chemistry and manufacturing
✅ Electrochemistry: Butler–Volmer/Nernst electrodes, galvanic cells, corrosion, displacement plating,
   electrolysis, Faraday bookkeeping (exhibits: Daniell cell, plating, galvanic corrosion)
✅ Substance database (JANAF thermochemistry), gas reactions with Arrhenius kinetics and Gibbs-energy
   equilibrium, reacting gas volumes conserving energy and elements (N₂O₄ equilibrium, methane combustion,
   Semenov thermal explosion)
✅ Sealed gas vessels in builds: equilibrium (brown-gas exhibit) and thermal explosion (heated can of methane–air)
✅ Flowing gas mixtures: choked orifices, natural draft, well-stirred combustion; gas burner exhibit
✅ Internal-combustion engine (four-stroke gas engine: cam-timed valves, half-speed camshaft, glow-plug ignition)
✅ Liquids in gas vessels with vapour–liquid equilibrium (Antoine, van Laar, Clausius–Clapeyron): boiling,
   condensation, evaporative cooling; distillation (a still, and a reflux column of weired trays); liquid flows
   between vessels (drains, weirs, communicating vessels)
⬜ Solutions of solids, crystallisation, filtration, other separation processes,
   manufacturing

## Phase 6 — Advanced multiphysics
✅ Multirate stepping: the thermal network apart from the electrical/mechanical/fluid partition, energy-exact coupling
✅ Control parts: potentiometers (angle sensors/knobs with end stops), op-amps (single pole, slew, rails),
   gravity torque on unbalanced rotating bodies; a servo arm exhibit (PID position control); an electronic speed
   governor (tachogenerator, PI op-amp loop, Darlington drive) holding a brushed motor's speed against a brake; a
   contact governor (a centrifugal switch on the rotor — a preloaded spring sets the speed)
🟡 Aerodynamics: blades (flat-plate blade elements, all quadrants) on rotors and slides, momentum-theory inflow
   (Glauert/Buhl; the Betz limit emerges), a modelled wind from the weather ✅; a wind turbine exhibit; wings on free
   bodies, finite-span effects, cambered aerofoils, propeller thrust on vehicles ⬜
🟡 Joints on moving bodies (a rotor on a slide, a slide on a rotor) ✅; multi-joint robots, linkages ⬜; state-space
   control ⬜; advanced EM ⬜

## Phase 7–8 — Broad scientific expansion, continuous expansion
✅ Data logger / oscilloscope: 1 kHz sampling of any part, on-screen trace, CSV export
✅ Radioactivity (non-actionable, sealed check sources): decay chains, gamma attenuation, Geiger–Müller counter
✅ Coloured (absorbing) glass: Beer–Lambert with spectral absorption edges; a colour-filters exhibit
✅ Ray optics: mirrors (flat, tilted, canted, concave), spherical lenses, glass blocks; Snell, Fresnel, total internal
   reflection, dispersion; ray-tube transport for LEDs and sunlight; sunlight heats every part (periscope, lens, solar
   furnace exhibits)
✅ Incandescence: hot parts light photodiodes with Planck's spectrum weighted by silicon's response (lamp exhibit)
✅ Hot parts' light through mirrors, lenses and prisms, band by band: a spectrometer (collimator, prism, three
   channels) shows a filament's glow shifting from infrared to blue as it heats (Wien)
✅ Prisms (dispersion exhibit)
🟡 Acoustics: compact-dipole sound from bodies on slides, retarded near/induction/far field, receivers pushed by
   the air; a loudspeaker heard by a microphone (moving coils on flexible leads, an op-amp amplifier);
   in-game audio (each sounding body's tone synthesised at its simulated pitch and the listener's pressure);
   ⬜ turning bodies, enclosures and baffles, rooms
⬜ Geology, environmental, biology; diffuse reflection; spectrum analyser,
   logic analyser, multi-channel loggers

## Demo world
✅ Gallery generator (`./gradlew generateDemoWorld`), `/emergence demo`, automated screenshot tour.
Exhibits (block scale): hand generator, gear train, capacitor glow, fuse, conduction race, bearing
friction, DC motor, Daniell cell, displacement plating, galvanic corrosion; plus a true-scale miniature
generator; compressed-air turbine, fan-and-windmill fluid coupling, heated pressure vessel, Stirling
engine with generator.
New mechanics must add an exhibit (core `Exhibits` + `DemoExhibitsTest`).
