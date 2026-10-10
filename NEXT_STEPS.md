# Next steps (priority order)

0. **Servo island fails intermittently on world load** (~1 in 3 full tours): from a benign restored state
   (T ≤ 293 K, coil currents ≤ 0.64 A, 12 V capacitors, all finite) the first step at t = 0 ends in a time-step
   underflow with non-finite Joule/thermal terms (wires, the power BJT, a coil). The restart without coil
   currents always works. It depends on chunk-load order (fragment islands merging), not reproduced in core.
   Run tours with `-Demergence.dumpFailures=true`: the island's parts and start state are written to
   `run/island-failure-*.txt` for replay in a core test.

0. **Control/robotics**: integral action (PI/PID with an op-amp integrator) to remove the servo's droop; a
   two-joint arm (needs joints on moving bodies); the EM-4 closing a loop through a light-barrier encoder;
   data logger/oscilloscope instrument to plot the step response.

0. **Light**: an in-game tool to aim mirrors (click a target: cant the facet), prisms (a spectrometer from a
   flint prism and photodiodes), incandescent sources (hot parts emit Planck spectra), phototransistors and
   optocouplers; a sun-tracking heliostat (mirror on a clock-driven shaft — moving optics are already traced for
   sunlight); use the light barrier as an encoder for closed-loop motor control by the EM-4.

0. **Structures**: Euler buckling of slender columns (linear buckling eigenproblem with geometric
   stiffness), pressure-vessel bursting (hoop stress p·r/t vs strength — a boiler that over-pressures
   ruptures), dynamic loads/resonance; a crane or bridge exhibit.

1. ~~Computer milestone (Phase 4)~~ done (EM-4 + stepper). Next in computing: an in-game ROM programmer,
   RAM chip, instruction-set growth (load/store, conditional on carry), event-driven simulation for large
   gate counts, photodiodes so light can be sensed. Previously: a small programmable sequencer/CPU from logic chips (register,
   ROM/diode matrix, counter) whose output drives a MOSFET switching a motor or valve; event-driven
   simulation for large gate counts; photodiodes so light can be sensed.
2. Steam: show the liquid level in tanks; a condenser (Watt) and a feed pump with check valves so the
   boiler can run indefinitely; steam-engine cost (≈ 1.5× real time) — cheaper superheat joins.
2. Check valves (one-way) are in: piston air compressor and pump exhibits.
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
