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
✅ Basic visualisation (true-size rendering, rotation, incandescence) and multimeter
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
⬜ Fluid networks, pressure, pipes, valves, pumps; gas equations of state; phase transitions; pistons
⬜ Milestone: heat engine driving a generator

## Phase 4 — Electronics and computation
🟡 Diode ✅; BJT/MOSFET, LEDs, photodiodes ⬜
⬜ Digital logic hierarchy (transistor → event-driven), memory, clocks, CPU; sensors and actuators
⬜ Milestone: programmable computer controlling a machine

## Phase 5 — Chemistry and manufacturing
⬜ Substance database, reaction networks, equilibrium, electrochemistry (batteries), processing, manufacturing

## Phase 6 — Advanced multiphysics
⬜ Structural analysis, advanced EM, aerodynamics, control systems, robotics

## Phase 7–8 — Broad scientific expansion, continuous expansion
⬜ Optics, acoustics, geology, environmental, nuclear (non-actionable), biology, instruments

## Demo world
✅ Gallery generator (`./gradlew generateDemoWorld`), `/emergence demo`, automated screenshot tour.
Exhibits: hand generator, gear train, capacitor glow, fuse, conduction race, bearing friction, DC motor.
New mechanics must add an exhibit (core `Exhibits` + `DemoExhibitsTest`).
