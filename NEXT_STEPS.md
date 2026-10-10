# Next steps (priority order)

Every new mechanic: core tests → in-world GameTest where it matters → demo exhibit (and showcase step) →
regenerate the demo world → screenshot tour → docs → commit.

1. **Control**: a speed governor (centrifugal or electronic) holding an engine's speed against its brake; the EM-4
   closing a loop through a light-barrier encoder; integrator wind-up limits. (The servo is a PID loop now.)
2. **Dynamics of structures**: vibration and resonance — an unbalanced rotor on a sprung platform (needs a turning
   body carried by a sliding one), vibration isolation; fatigue of the static frame under cyclic loads (rainflow
   counting); gear-mesh and belt forces in the shaft fatigue model.
3. **Light**: a spectrometer that sorts colours onto photodiodes (the prism is in); hot parts' light through mirrors
   and lenses (a lamp in a reflector); an in-game tool to aim mirrors; a clock-driven heliostat (moving optics are
   already traced for sunlight); phototransistors and optocouplers.
4. **Acoustics**: sound from vibrating parts (radiation from a piston or cone, ρcS·v²), propagation to listening
   parts, a loudspeaker and microphone built from coil, magnet and membrane; audible playback in the world.
5. **Computing**: RAM chip, in-game ROM programmer, instruction-set growth (load/store, branch on carry),
   event-driven simulation for large gate counts.
6. **Steam and heat engines**: a condenser and feed pump so the boiler runs indefinitely; liquid level shown in
   tanks; multi-stage turbines and nozzles (a working Brayton cycle); the steam engine's cost (≈ 1.5× real time).
7. **Chemical engineering**: liquid mixtures and vapour–liquid equilibrium (Raoult's law) → distillation; solutions,
   crystallisation; a voltaic pile driving the DC motor.
8. **Magnetics**: iron saturation and hysteresis; hinged armatures and plungers, magnet–iron attraction; relay
   logic and a buzzer (a relay interrupting its own coil); core losses; a drag-cup speedometer.
9. **Performance**: async island compilation, multirate thermal stepping, factorisation reuse for linear islands;
   the steam engine and gas engine run slower than real time at speed.
10. **Broader science**: geology (ores, strata), environmental processes, biology — each connected to the existing
    chemistry, thermodynamics and fluids.
