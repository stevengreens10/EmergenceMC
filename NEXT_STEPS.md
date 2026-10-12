# Next steps (priority order)

Every new mechanic: core tests → in-world GameTest where it matters → demo exhibit (and showcase step) →
regenerate the demo world → screenshot tour → docs → commit.

1. **Control**: a centrifugal (flyball) governor on an engine — the weights on the spinning arms are in (slides on
   rotors), and a weight can work a contact on its rotor (the contact governor); it needs their travel to work a
   valve off the rotor (a sleeve sliding along the shaft: a slide on the rotor coupled to one on the frame by a thrust
   collar); contact bounce and arcing for the travel contacts; the EM-4
   closing a loop through a light-barrier encoder; integrator wind-up limits. (The servo is a PID loop, the
   electronic speed governor a PI loop.)
2. **Dynamics of structures**: (an unbalanced rotor on a sprung platform is in) a motor driving it (coils and magnets
   on a carried rotor), two-stage isolation; fatigue of the static frame under cyclic loads (rainflow
   counting); gear-mesh and belt forces in the shaft fatigue model.
3. **Light**: an in-game tool to aim mirrors; a clock-driven heliostat (moving optics are already traced for
   sunlight); scattering (frosted glass, diffuse reflection); hot non-circuit
   parts (a furnace's glowing plate) through optics.
4. **Acoustics**: turning bodies (fans, gear whine, an unbalanced rotor) and vibrating structures as sources; baffles
   and enclosures (a speaker cabinet turns a dipole into a monopole); impulsive sounds (impacts) beyond tones;
   faster audio-rate machines (their fast partition's own cost).
5. **Computing**: (RAM chip in) the EM-4 using RAM (load/store), an in-game ROM programmer, instruction-set growth (load/store, branch on carry),
   event-driven simulation for large gate counts.
6. **Steam and heat engines**: a condenser and feed pump so the boiler runs indefinitely; liquid level shown in
   tanks; multi-stage turbines and nozzles (a working Brayton cycle); the steam engine's cost (≈ 1.5× real time).
7. **Chemical engineering**: a distillation column (stages, reflux; liquid drains between vessels are in). A first
   try (three stacked wall-cooled stages over the still's pot) did not distil: with the mixture passages' reversal
   blend at 10⁻⁵ mol/s it failed when the vapour front reached the cold line (3·10⁻⁵ got through); then the pot sat
   above its bubble point without boiling while the lowest stage's gas overheated past 120 °C with its liquid pool
   hovering just below zero — look at negative liquid pools (their energy l·u_l) and at what stalls the vapour path,
   and make near-empty pools strictly non-negative. Then siphons and pressure-driven liquid transfer;
   more condensables (methanol, acetone, …) and activity models (Wilson, NRTL) for any pair; solutions of solids,
   crystallisation; a voltaic pile driving the DC motor. (Liquids, VLE and a still are in.)
8. **Magnetics**: hysteresis (and core loss), saturation of armatures and magnet–iron pulls; a saturable-reactor
   timer (needs a high-permeability alloy such as 80 % permalloy in the database); hinged armatures and plungers; coils adding to a magnet's MMF
   (polarised relays, electro-permanent releases); relay logic (latching relays, relay gates); core losses; a
   drag-cup speedometer. (Magnets attract iron on slides now.)
9. **Aerodynamics next**: twisted and cambered blades, wings on free bodies (gliders need free rigid bodies),
   propellers driving carts on slides, wind loads on structures.
10. **Performance**: async island compilation, factorisation reuse for linear islands, cheaper conductor loads (most
   of a circuit island's fast-step time); multirate is in (thermal apart);
   the steam engine and gas engine run slower than real time at speed.
11. **Broader science**: geology (ores, strata), environmental processes, biology — each connected to the existing
    chemistry, thermodynamics and fluids.
