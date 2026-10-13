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
5a. **Power electronics**: (a bridge rectifier, a boost converter and an H-bridge are in) a buck converter (a
   P-channel high-side switch driven through an NPN level shifter); why chip-level logic fails Newton hundreds of times a
   second (the boost, PWM and inverter exhibits all pay 2–6× real time for it): probably each Schmitt edge — a fold in
   the hysteresis, resolved by a 10 ns relaxation — halving the step from milliseconds to nanoseconds, a Newton failure
   per halving (the boost's oscillator failed so even with nothing on its output); an event-aware step at logic edges
   (predict the threshold crossing, step to it, restart) would make an edge cost a handful of steps; the EM-4
   setting the PWM duty (a DAC from resistors) for closed-loop speed control; why a diode-steered RC oscillator
   (reverse-biased diode nodes) fails Newton ~1 500 times a second;
   the cost of switching edges (≈ 140 steps an edge: the gate capacitances and snubber set the step) — an averaged
   switch model for fast converters, with a detector for when its assumptions fail.
5. **Computing**: (RAM chip in) the EM-4 using RAM (load/store), an in-game ROM programmer, instruction-set growth (load/store, branch on carry),
   event-driven simulation for large gate counts.
6. **Steam and heat engines**: (a condensing engine is in — Watt's separate condenser; and the engines run on a
   sustained fire with a jacketed cylinder) a feed pump to return the condensate to the boiler (a crank-driven
   plunger between check valves: the closed Rankine cycle). First try (a 6 mm plunger on its own eccentric under the
   crankshaft, suction from the condenser's floor, delivery into the boiler's back): every elbow's straight-on end
   vented to the air until capped (the boiler blew down through the feed line at 10 g/s); a check valve's lift had a
   slope jump at the seat (a C¹ clamp helped); then the delivery line's flows two-cycled near zero flow — the upwind
   density in the flow law switching between liquid and vapour where a line pipe beside the heater flashed — and the
   pump cavitated: saturated 40 °C condensate at 7 kPa with 19 cm of head vapour-locks it (real condensate pumps have
   metres of suction head, or the condensate is subcooled). Needs: a smooth density blend across flow reversal in
   two-phase pipes, a subcooled hotwell below the condenser, and the pump well below it; a water-cooled condenser (its copper blocks only soak up
   the heat, so it warms over minutes); liquid level shown in tanks; multi-stage turbines and nozzles (a working
   Brayton cycle); the engines' cost (≈ 1.5× real time at ~110 rad/s, some 80 steps a revolution).
7. **Chemical engineering**: (a still and a reflux column at total reflux are in — trays are tanks with a "weir")
   a distillate draw-off (a side outlet off the condenser, setting a reflux ratio) and a feed tray; tray efficiency
   (the vapour passes over the pool, not through it — the exchange is κ·A only); counter-current gas exchange through
   wide openings (one branch carries one flow: a cold vessel over a warm one cannot swap gas both ways); siphons and
   pressure-driven liquid transfer; more condensables (methanol, acetone, …) and activity models (Wilson, NRTL) for
   any pair; solutions of solids, crystallisation; a voltaic pile driving the DC motor.
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
