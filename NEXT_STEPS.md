## In progress (uncommitted at end of session)
* Water/steam in the assembly compiler: phase-aware ports, steam vents, check/relief valves (`relief`), and
  cam-worked valves (a VALVE whose stem lines up with an ECCENTRIC). The changes are in AssemblyCompiler,
  CompiledAssembly and FluidBranch. They compile, but the full core suite has not been run on them yet.
* A draft steam-engine exhibit and its test are in tools/draft_steam_engine_exhibit.py (not applied). Next: run
  the core tests, apply the draft, tune the cam phases and the firebox until the engine runs, then add a GameTest,
  a showcase step, the renderer liquid level and the docs, and commit.

# Next steps (priority order)

1. **Computer milestone (Phase 4)**: a small programmable sequencer/CPU from logic chips (register,
   ROM/diode matrix, counter) whose output drives a MOSFET switching a motor or valve; event-driven
   simulation for large gate counts; photodiodes so light can be sensed.
2. **Phase change** (water ↔ steam, saturation pressure, two-phase volumes) → boiler; with **valve gear**
   (a cam/eccentric-driven slide valve part) → a steam engine from parts.
2. Check valves (one-way) → piston air compressor and pump exhibits.
3. Brayton cycle: an experiment showed a single 5.6 cm blade row cannot sustain a gas turbine (pressure
   ratio ~1.01; real micro-turbines need ≥ 3) — needs flow kinetic energy (impulse stages, nozzles) and
   multi-stage rotors before it can work.
4. Dynamo exhibit: a cranked commutated machine charging the DC motor's capacitor (DC generation).
2. **Ferromagnetic materials**: induced magnetisation of soft-iron parts (magnetic equivalent circuit or
   volume-integral method) → iron-cored coils, transformers, reluctance torque, magnet attraction to steel;
   exhibit "transformer".
3. **Voltaic pile / battery bank** exhibit: series Daniell cells driving the DC motor (sustained DC source
   built from the electrochemistry already in place); electroplating with an external source.
4. **Performance for large circuits**: factorisation reuse for linear islands, accuracy levels, async island
   compilation, multirate thermal stepping (PERFORMANCE.md plan).
5. **Instruments**: two-probe voltmeter, oscilloscope/data logger with CSV export.
6. **Fluids/thermo (Phase 3)**: lumped pipe networks, ideal-gas volumes, pistons/cylinders (translational
   bodies), valves; then phase change (water/steam) → heat-engine milestone.
7. Semiconductor devices (BJT/MOSFET Ebers–Moll/Shichman–Hodges) and logic.

Every new mechanic: core tests → in-world GameTest → demo exhibit → regenerate demo world → screenshot tour.
