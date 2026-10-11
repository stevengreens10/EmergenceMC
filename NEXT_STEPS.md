# Next steps (priority order)

Every new mechanic: core tests → in-world GameTest where it matters → demo exhibit (and showcase step) →
regenerate the demo world → screenshot tour → docs → commit.

1. **Control**: a centrifugal (flyball) governor on an engine — the weights on the spinning arms are in (slides on
   rotors); it needs their travel to work a valve or contact (a sleeve sliding along the shaft: a slide on the rotor
   coupled to one on the frame by a thrust collar); the EM-4
   closing a loop through a light-barrier encoder; integrator wind-up limits. (The servo is a PID loop, the
   electronic speed governor a PI loop.)
2. **Dynamics of structures**: (an unbalanced rotor on a sprung platform is in) a motor driving it (coils and magnets
   on a carried rotor), two-stage isolation; fatigue of the static frame under cyclic loads (rainflow
   counting); gear-mesh and belt forces in the shaft fatigue model.
3. **Light**: an in-game tool to aim mirrors; a clock-driven heliostat (moving optics are already traced for
   sunlight); phototransistors and optocouplers; coloured glass (absorption spectra) as filters; hot non-circuit
   parts (a furnace's glowing plate) through optics.
4. **Acoustics**: turning bodies (fans, gear whine, an unbalanced rotor) and vibrating structures as sources; baffles
   and enclosures (a speaker cabinet turns a dipole into a monopole); impulsive sounds (impacts) beyond tones;
   faster audio-rate machines (their fast partition's own cost).
5. **Computing**: RAM chip, in-game ROM programmer, instruction-set growth (load/store, branch on carry),
   event-driven simulation for large gate counts.
6. **Steam and heat engines**: a condenser and feed pump so the boiler runs indefinitely; liquid level shown in
   tanks; multi-stage turbines and nozzles (a working Brayton cycle); the steam engine's cost (≈ 1.5× real time).
7. **Chemical engineering**: a distillation column (stages, reflux); liquid flows between vessels (drains, siphons);
   more condensables (methanol, acetone, …) and activity models (Wilson, NRTL) for any pair; solutions of solids,
   crystallisation; a voltaic pile driving the DC motor. (Liquids, VLE and a still are in.)
8. **Magnetics**: iron saturation and hysteresis; hinged armatures and plungers; coils adding to a magnet's MMF
   (polarised relays, electro-permanent releases); relay logic (latching relays, relay gates); core losses; a
   drag-cup speedometer. (Magnets attract iron on slides now.)
9. **Energy ledger of the magnet generator**: the wind-turbine exhibit's ledger closes only to 3·10⁻³ of the work
   in (the aerofoil element alone to 10⁻⁹): find the generator coupling's (or multirate's) residue.
   **Aerodynamics next**: twisted and cambered blades, wings on free bodies (gliders need free rigid bodies),
   propellers driving carts on slides, wind loads on structures.
10. **Performance**: async island compilation, factorisation reuse for linear islands, cheaper conductor loads (most
   of a circuit island's fast-step time); multirate is in (thermal apart);
   the steam engine and gas engine run slower than real time at speed.
11. **Broader science**: geology (ores, strata), environmental processes, biology — each connected to the existing
    chemistry, thermodynamics and fluids.
