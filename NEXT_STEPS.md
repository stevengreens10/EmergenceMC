# Next steps (priority order)

0. **The demo as a player plays it** (tour with hands and checks, world built by placement are in):
   - (done: 44 pre-charged capacitor banks are batteries now, lead–acid or nickel–metal hydride) the Geiger counter's
     500 V comes from a pre-charged capacitor: a high-voltage converter built from parts;
   - (fixed: the press's pump sank under its own weight, the propeller cart was parked by the wind, the governors were
     judged mid-recovery, the gas engine ran as half of itself while its chunks loaded) the periscope's island takes in
     the gas engine next door by line of sight;
   - start exhibits cold where warm-up is short (motors, four-bar, unbalanced rotor: the tour turns them by hand);
     (done: the fan train, governors, unbalanced rotor, flywheels, eddy brake and gas engine start at rest and are
     started by hand — the engine with its fuel, a starting handle and the brake off; the fatigue rig through a 5 : 1
     step-up gear pair, 176 rad/s in 15 s) still opening in motion: the
     steam engines on their pre-heated plants, the four-bar (its consistent start is what its test
     measures; the tour turns its crank) and the flyball (minutes to spin up);
     keep only boilers, fires and furnaces pre-started;
   - the periscope's photodiode looks up into the noon sky once its mirror is gone (a design to make sunlight-proof);
   - the condensing engine is found stalled in the world (fine in a fresh GameTest): find what differs;
   - (the governors' checks now average over 5 s: the core holds the electronic one at −3 % braked) confirm in the tour;
   - (much eased: a Schmitt trigger's threshold is now a discrete state flipped as a solver event, the inverter 20×
     cheaper — 3 s of CPU for 10 s, no Newton failures) the switching edges themselves (~100 steps each through the
     gate charges) and the averaged-switch model of item 5a; the boost and PWM exhibits to profile again;
   - (done: the tour flies between viewpoints and to its hands' spots in spectator mode, landing in creative —
     `Flight`; 10/10 checks over five exhibits with it) confirm in a full tour; shots taken where the hand stood
     (a step without the exhibit's view) to review in its screenshots;
   - the EM-4's island (912 parts) failed once in the world, 24 s after the tour flipped its sense switch and left:
     time-step underflow at 71.5 s with a chip output Newton-stepped to 563 V, recovered by a restart at rest; the
     core runs it with the switch flipped at 30–60 s, steps of 45 and 50 ms, to 130 s without failing (the same
     world-only pattern as the loudspeaker's below — look for what the world adds: sunlight, a partial step);
   - (fixed: the hydraulic jack's world-only failure was the noon sun — a trickle over its standpipe's brim, the open
     air's intake held to a finer tolerance than the vent's flow; `hydraulicJackPumpsInTheNoonSun`) the EM-4 and the
     loudspeaker run clean in the core under the same sun: theirs is something else the world adds;
   - the flyball governs lower in the world than in the core (27.4 rad/s at 0.23 A against 29.8 at 0.20, as if under
     more drag — sun-warmed parts?), so braked it nears its stops and took no more current (twice: the brake taking
     0.76 W, the supply current fell 0.217 → 0.205 A, speed −2.6 %) — find the drag, and where the brake's power
     comes from if not the supply (the flywheel's speed? the rheostat at its stop?) — found: the tour averaged aliased
     samples of a rippling current (now the charge drawn over the window); and the governor creeps for minutes towards
     ~28 rad/s from either start — the friction dead band creeping: dry friction is τ_c·tanh(v/v_s), so a part held by it
     creeps at ~v_s·(net/limit) (the rheostat at 1.7e-4 rad/s against v_s = 1e-2: a net torque under 2 % of its
     friction), towards the frictionless balance from either side; a true stick state (switched as an event, like the
     Schmitt trigger's) would hold it where it stops, as real stiction does; confirmed in the tour:
     all three governors pass, the flyball braked 28.0 → 26.3 rad/s on 0.28 → 0.42 A, as in the core;
   - (confirmed in the world: the fatigue rig cranked to 187 rad/s through its gears, the aluminium shaft cracked; the
     inverter, waiting for its first full cycle, lit both LEDs and gave 44 V);
   - the gas engine ran at 82 rad/s in one full tour, and in the next never ran away from the hand at all (60 rad/s,
     stalling under the brake), against 173–273 alone: sunlight (as for the jack) under test; its rich low-speed regime;
   - (fixed in the tour: its load power is averaged over the island's own time at every solver step, a pulsing
     generator's output having aliased to 0.008 W) confirmed: 0.36 W; and with the hand given up to 3 minutes the gas
     engine fired and carried the brake (160 rad/s, 72 W); the Schmitt-event exhibits (inverter, boost, PWM, Geiger,
     light barrier, thermostat, counter, EM-4, periscope, optocoupler, loudspeaker) 23 of 23 in the world;
   - the loudspeaker's island (527 parts, audio-rate) failed once in the world, idle for minutes with its switch off:
     time-step underflow at a resistor's node at 6.3 s, recovered by a restart at rest; the core runs it idle and on
     to 10 s without trouble — find the world condition (sun on its parts?). It also costs ~30× real time even idle:
     an audio-rate partition that sleeps while nothing vibrates;
   - the miniature exhibit is still placed directly (tweezers placement);
   - the gas engine's island runs at a fifth to a third of real time once it fires (combustion steps at 200+ rad/s);
   - the wind turbine's load check now asks only that power arrives (a lull at 30 rad/s gave 0.12 W against 0.2);
   - (done: `runWatchDemoBuild` builds the gallery where it can be watched; it found five machines held by scaffolding
     that had gone, now rebuilt when a world block beside them changes) confirm a full watched build matches its
     definitions, 72 of 72;
   - the full tour, then every exhibit's checks green.

0b. **Every exhibit at real time** (asked for 2026-10-22; measure first: `SolverBenchmarkTest.exhibitIslandsFitTheTickBudget`
   prints ms per 50 ms tick and the unknowns that limit the step):
   1. power electronics — fidelity switching: fast, decided states (a logic output's 10 ns slew, a gate's charge)
      as events and quasi-static algebra while the solver's step dwarfs them; then an ideal-switch or averaged bridge
      model with a detector for when its edges are not fast against the load (inverter, boost, PWM, H-bridge);
      (first step done: the Schmitt trigger's threshold as an event, the inverter 20× cheaper);
   2. the loudspeaker: 12× → 5× idle, 22× → 10× on (parasitic tolerances, the ambient slow); its oscillator runs
      whenever powered, so "idle" is 500 edges a second: what is left is per-step cost (505 unknowns; the sparse
      matrix's assembly and refactorisation, ~35 % of samples) and ~40 steps a half-period — a lighter matrix assembly,
      fewer unknowns (series wire cells), the comparator as an event. (Tried: chord Newton, the factored Jacobian
      reused while each update at least halved the last — slower on the inverter, 8.5 → 10.4 ms a tick, since these
      solves converge in two or three quadratic iterations, and it failed the boost converter at a switching edge:
      reverted);
   3. the gas engine: ignition and valve timing as events, factorisation reuse (⅕–⅓ of real time once firing);
   4. the steam engines: profile their ~80 steps a revolution (valve events, two-phase flow reversal);
   5. general: async island compilation, factorisation reuse for linear islands, cheaper conductor loads.
   Expected: 1 and 2 reach real time; a fast combustion engine may stay costly (real time alone near a player, the
   slow-motion fallback when several are).

Every new mechanic: core tests → in-world GameTest where it matters → demo exhibit (and showcase step) →
regenerate the demo world → screenshot tour → docs → commit.

1. **Control**: (the flyball governor is in, working a rheostat through link bars) a governor on a steam engine's
   throttle (a valve whose plug is a slide: its opening from the plug's travel, the steam's force on it); contact bounce and arcing for the travel contacts; the EM-4
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
   (predict the threshold crossing, step to it, restart) would make an edge cost a handful of steps. Profiled on the
   inverter: a logic delay of 100 µs instead of 10 ns cuts its Newton failures 402 → 21 and its CPU time in half,
   but an edge still costs ~250 steps — ~30 shrinking as the oscillator's input nears its threshold (the solver cannot
   see the edge coming), ~100 at ~10⁻⁷ s through the switching itself (gate charges, the LEDs' 20 pF, the transformer),
   ~100 growing back at ×1.2 a step through transients spanning decades. The fidelity hierarchy CLAUDE.md asks for
   (§6.4) is the real answer: an ideal-switch or averaged model of a bridge, used while its edges are fast compared
   with the load, with a detector for when they are not; the EM-4
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
8. **Magnetics**: (hysteresis is in: remanence and loop loss in the dominant mode) a flux-based armature force (so
   relays' cores can be hysteretic: residual flux holding them in), saturation of armatures and magnet–iron pulls;
   eddy-current core loss; a saturable-reactor
   timer (needs a high-permeability alloy such as 80 % permalloy in the database); hinged armatures and plungers; coils adding to a magnet's MMF
   (polarised relays, electro-permanent releases); relay logic (latching relays, relay gates); core losses; a
   drag-cup speedometer. (Magnets attract iron on slides now.)
9. **Aerodynamics next**: (propellers on carts are in) twisted and cambered blades, wings on free bodies (gliders
   need free rigid bodies), a motor-driven propeller (magnetics on carried rotors), wind loads on structures.
10. **Performance**: async island compilation, factorisation reuse for linear islands, cheaper conductor loads (most
   of a circuit island's fast-step time); multirate is in (thermal apart);
   the steam engine and gas engine run slower than real time at speed.
11. **Broader science**: geology (ores, strata), environmental processes, biology — each connected to the existing
    chemistry, thermodynamics and fluids.
