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
✅ Rotating rigid bodies on bearings, shafts, bearings (ball/plain), spur gears, cranks
✅ Conductors (T-dependent), resistors, capacitors, inductors, switches, diodes
✅ Magnetic elements: permanent magnets, coils, field-derived electromechanical coupling
✅ **Milestone: player-built generator** (demo exhibit, tests) — motor action demonstrated in core
   (synchronous motor driven by a rotating current pattern)
✅ Player-built brushed DC motor: angle-dependent brush/segment contacts → commutation emerges (exhibit)
⬜ Ferromagnetic cores (magnetic equivalent circuits / induced magnetisation), eddy currents
⬜ Translational bodies, linkages, free rigid bodies, gravity loads

## Phase 3 — Thermal and fluid physics
🟡 Heat transfer: conduction, natural convection, radiation, thermal masses ✅; forced convection ⬜
✅ Fluid networks: control volumes, pipes (Darcy–Weisbach), tanks, valves (choked orifices), atmosphere
   vents, wall heat transfer, ideal-gas and liquid equations of state, gravity head
✅ Bladed rotors by blade-element theory: fans, pumps, turbines, windmills from one part
✅ Piston cylinders and crank throws (slider-crank, variable-volume thermodynamics)
⬜ Phase transitions (boiling/condensation), free surfaces, valve gear, free translational bodies
✅ **Milestone: heat engine driving a generator** — an alpha Stirling engine from parts (hot and cold
   cylinders 90° apart, transfer pipe, flywheel) runs on a hot block and drives magnets past a coil

## Phase 4 — Electronics and computation
🟡 Diode ✅; BJT/MOSFET, LEDs, photodiodes ⬜
⬜ Digital logic hierarchy (transistor → event-driven), memory, clocks, CPU; sensors and actuators
⬜ Milestone: programmable computer controlling a machine

## Phase 5 — Chemistry and manufacturing
✅ Electrochemistry: Butler–Volmer/Nernst electrodes, galvanic cells, corrosion, displacement plating,
   electrolysis, Faraday bookkeeping (exhibits: Daniell cell, plating, galvanic corrosion)
⬜ Substance database, general reaction networks, equilibrium, processing, manufacturing

## Phase 6 — Advanced multiphysics
⬜ Structural analysis, advanced EM, aerodynamics, control systems, robotics

## Phase 7–8 — Broad scientific expansion, continuous expansion
⬜ Optics, acoustics, geology, environmental, nuclear (non-actionable), biology, instruments

## Demo world
✅ Gallery generator (`./gradlew generateDemoWorld`), `/emergence demo`, automated screenshot tour.
Exhibits (block scale): hand generator, gear train, capacitor glow, fuse, conduction race, bearing
friction, DC motor, Daniell cell, displacement plating, galvanic corrosion; plus a true-scale miniature
generator; compressed-air turbine, fan-and-windmill fluid coupling, heated pressure vessel, Stirling
engine with generator.
New mechanics must add an exhibit (core `Exhibits` + `DemoExhibitsTest`).
