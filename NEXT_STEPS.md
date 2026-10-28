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
   - (fixed 2026-10-25: a world's save dropped each water/steam volume's phase, so a reloaded steam plant lost its
     levels; the full tour finds it running, 110 rad/s, its condenser at 8 kPa) the condensing engine stalled in the world;
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
   - (done: the miniature generator is built with the precision tweezers, each part clicked into its cells or onto
     its shaft, and compiles as its definition);
   - the gas engine's island runs at a fifth to a third of real time once it fires (combustion steps at 200+ rad/s);
   - (fixed 2026-10-27: the tour's fault, not the engine's) the hand swung the starting handle for 3–4 minutes: the
     step's "has it fired?" condition read the crankshaft's speed off the server's thread, where the level gives no
     block entity, so it read NaN until the step's 3-minute limit. The engine fires 3.5 s into the cranking, in the
     world as in the core (replays of the world's state with relaxed accuracy and with the noon sun agree). Three
     more conditions read so (the burner's, the periscope's, the fatigue rig's); all four now read on the server.
     The engine's visit took 86 s instead of 214–247. Still open: its island runs at about a third of real time once
     it fires (combustion's fine steps at 200+ rad/s);
   - the wind turbine's load check now asks only that power arrives (a lull at 30 rad/s gave 0.12 W against 0.2);
   - (done: `runWatchDemoBuild` builds the gallery where it can be watched; it found five machines held by scaffolding
     that had gone, now rebuilt when a world block beside them changes) confirm a full watched build matches its
     definitions, 72 of 72;
   - the full tour of 2026-10-22 (75 exhibits, 146 of 155 checks): fixed since — the light barrier's beam was sampled a
     tenth of a second apart and its 20 ms cuts fell between (now read at every solver step); the spring's bounce had
     rung down before the tour came (machines near a player run at full pace now: the tour pushes it). Still open:
     - (fixed: the flyball braked sagged 15–23 % in the world against 6 % in the core — the world was right. Its
       island dumped mid-tour (`-Demergence.dumpIslands=flyball`) and replayed in the core reproduced it exactly; the
       dump matched the definition cell for cell (`IslandDumpDiffTest`), and the core build started at the world's
       speed read the same: the unloaded governor took minutes to settle, and the core test had read transients.
       Redesigned — soft springs, a steady viscous load, a 12 V bank — it now settles in a minute and holds −4 %
       braked; `SplitExhibitDiagTest` keeps the long-run probes) confirm in the world;
     - (fixed: the optocoupler's phototransistor burnt out in the noon sun before the tour came — a small receiver took
       its cell's whole face of sunlight as heat, 2.3 W; the gas engine missed its starting hand when it came mid-compile
       — a hand on a machine still compiling now waits for it);
     - (fixed: the PWM motor's hands found no spot late in the full tour — the player stood at its high viewpoint and
       the search for open sky stopped 8 blocks below the feet; a hand also waits for the client's chunks, and says
       why a plan fails) confirm in the next full tour;
     - (fixed: the governed engine 'rose 5.9–7.9 %' with its brake off — the tour compared two moments of its
       stroke-by-stroke swing, the second inside the first surge; now ten-second means of its own time, 20 s after:
       +0.4 % in the core) passed in the full tour of 2026-10-22 17:03 (150 of 155);
     - (fixed: the gas engine would not start late in a full tour — its island, dumped at the fuel and replayed,
       burnt its charges at hand speed without gaining: the head, built at 900 K, was cooling through a window
       near 650 K where the charge lights too early; built near the glow plug's standing temperature, 485 K, it
       starts in 2–3 s whenever cranked) confirmed in a tour of it;
     - one of the 17 in-world tests failed once in five demo builds (not captured);
   - the full tour, then every exhibit's checks green.

0a. **The power station** (asked for 2026-10-22: one interconnected machine, every part doing a job, built as a player
   builds, in its own world — `StationExhibit`, `./gradlew generateStationWorld`, `runStationClient`, tour
   `runStationShowcase`). In: a methane firebox (natural draft, igniter to light it) under a copper crown; boiler, safety
   valve, constant-level overflow; the governed engine; a closed water circuit (exhaust and safety valve into a finned
   condenser at ~0.45 bar, flooded suction from its well to a crankshaft feed pump, check valves into the boiler); a
   2 : 1 generator into a 5 V battery through a bridge; a charge regulator (op-amp against a diode reference, MOSFET into
   a lamp and dump load); a revolution counter (vane, IR beam, Schmitt trigger, three-stage ripple counter). Built in its world with 0
   differences. Its in-world check (`StationGameTests`, on `./gradlew generateStationWorld`) now runs it 190 s,
   the island's whole life without a failure, and checks the tour's claims headless (fire, steam, engine, vacuum,
   charge, counter, the governor under the brake). Run that long it found the boiler short of heat: a one-cell
   firebox gave its walls ~500 W of a 2 kW fire, and the boiler ran down on its crown's stored heat in three minutes
   (braked, −21 %). Now a three-cell firebox under three boiler tanks and a copper crown (~750 W: the flame stands in
   the middle cell, the inlet cell a mixing chamber), and the braked engine holds within 3 %. That needed a fix to
   the water model itself: no water moved between vessels side by side (a port did not feel the liquid over it, and
   one mid-height passage passed one phase at a time). Open:
   - it runs ~14x slower than real time in the core (2026-10-25; ~22x before the iron's magnetisation was held to its
     coils' flux tolerance), ~6 % of real time in the game: ~1400 steps a simulated second, a third more rejected by
     the error estimate (not Newton), ~5 Newton iterations each over 1143 unknowns (6 300 matrix entries, 15 000 after
     fill). The magnetic system sets most steps (the generator's magnet passes, its steel's hysteresis corners), then
     friction sticking and releasing. Time: the sparse LU 42 % (refactored each iteration), fluid and gas elements
     30 %, the solver's own work 22 %. Tried without gain: PI step control (rejections halved, more steps, 4 %); the
     assembly replay kept in step (hash lookups gone, ~3 %; kept). What is left is structural: step the fluid and gas
     network apart from the fast electromechanics (multirate), with the stiff liquid volumes at the pistons and pumps
     on the fast side and the pistons' work exchanged conservatively, as the thermal network's heat already is.
     The world adds nothing hidden: a flight recording of the headless station server with lifted budgets
     (2026-10-26, `runStationServer -Demergence.islandBudgetMs=1e9 -Demergence.globalBudgetMs=1e9
     -Demergence.jfr=<file>,duration=360s`; its one endless tick meets the 60 s watchdog after about a minute,
     enough to sample) spends its time in the solver alone: the LU's refactor 21 %, the Jacobian's assembly
     ~19 %, water/steam properties ~11 %, tanh in three smooth switches 4.5 %. Replacing tanh with the equivalent
     logistic gained nothing measurable (60 s of plant, 6 min 41 s against 6 min 39 s), so it was not kept. The
     world's slower pace is its budgets', not overhead;
   - (fixed 2026-10-26) the hot well ran dry: the exhaust chest under the engine's exhaust riser, below the well,
     gathered the water the exhaust brings out of the cylinder (~0.07 g/s; 197 g to 28 g in 14 minutes). The condenser
     now stands low, its well under the exhaust valve, the exhaust led level into its side and the feed pump hung under
     the crank: over three minutes the well stays full (197 g), only the valve bodies and the overflow riser taking
     their fill. In the core test the plant now takes ~5.5 s of wall time a simulated second (from ~14); in the world its pace is
     barely changed (~7 % of real time). The 15-minute soak (2026-10-26) still took the well from 197 g to 77 g:
     the plant's water is conserved (714.1 g in all), and what the well lost went to fill the plant's low points.
     Each level pipe and valve on the exhaust held condensate up to half its bore, because a side face between lying
     tubes drew at mid-bore only. That is fixed: every two-phase side face now passes water low and steam high.
     Still filling once and then passing water on: the engine's inlet valve (the steam carries the line's condensate
     forward, and a cylinder's side face is not split), the safety valve's discharge riser (it lifted early and its
     condensate stands on the seat, 15 g) and the overflow line. Splitting a cylinder's side faces too made the
     station's start fail (t = 0, non-finite): a Newton iterate took a steam volume to 238 K, below the triple point,
     where its wall's liquid properties gave c_p = 0 and the boiling law 0/0. Fixed (the wall takes its properties at
     no less than 273.16 K, as the ports do); a cylinder's faces could now be split, to let its water leave by its
     ports, once a cylinder's liquid is placed on its piston or head rather than at a fixed height;
   - the fire fades as the bottles empty: the needle is choked, so the methane it passes goes as the bottles' pressure
     (200 bar falling ~3 % in two minutes), and over a 14-minute soak (2026-10-26) the boiler fell from 5.1 to 3.0 bar
     with the engine still governed at 14 rad/s. A real plant puts a pressure regulator ahead of the needle. One
     built from parts now holds its outlet within 0.03 bar while a receiver falls from 20 to 10 bar (the `regulator`
     exhibit). Fitted between the station's bottles and its needle (2026-10-26: the exhibit's arrangement, the needle
     opened to 0.31 mm to pass the same 40 mg/s at 3 bar absolute), it held the needle's inlet at 3.04 bar with the fire as
     before. But it cost the station ~40 % of its speed: each gas volume on the fuel side carries every species of
     the firebox's mixture (+204 unknowns and +3000 matrix entries for six small volumes, the LU's fill nearly doubled),
     and the fire fades only over a quarter of an hour of plant time, some three hours of real time in the world. Not
     kept; fit it once gas volumes away from the reacting zone carry only the species that reach them (or the plant
     is stepped multirate). Two traps on the way: a pipe run's free end is an open vent (the bottles emptied through
     it), and one layout (an eight-cell chamber vessel) made the start non-finite at t = 0, as the split cylinder faces
     did (the steam wall below the triple point, fixed);
   - the condenser holds ~0.45 bar now that the boiler sends it more steam (checked against 80 kPa): more fin area,
     or a fan, would deepen it;
   - the generator's net charge is small (~0.03 A after the electronics' own ~35 mA, most of it the 24 mA IR beam):
     a dimmer beam with a larger photodiode load;
   - the station tour from its saved world passes (7 of 7, 2026-10-26 on the new layout: fire, steam, engine, vacuum, charge, counter,
     the governor within 3.2 % braked), judged over simulated time; it takes 8 minutes, the station running ~7 % of real
     time in the game even with the world's whole tick budget (`islandBudgetMs` 35 in its runs) — see the first item.
     A saved world restarts since the save keeps each water/steam volume's phase (`./gradlew runStationServer`
     reloads a copy headless to check);
   - an intermittent island failure in the world, ~1 run in 3 of `runStationWorld` (once at 153.6 s, 3.6 s after the
     brake went on: the hot well's flow into the feed pump's suction pipe); the core does not reproduce it. Dumps now
     also write the last good state (`island-failure-last-*.txt`): replay the next one from there.

00. **GOAL (asked 2026-10-26): monitors and custom chips — a monitor graphing data such as temperature and pressure.**
   Design (agreed with the user; decisions mine): primitive wherever the physics is the interesting part; abstract the
   inside of a part once it is mere repetition, keeping everything at its pins physical (supply current, thresholds,
   timing, loading, heat). Wireless left out for now. Pieces, in order:
   1. A chip language (text, register-transfer, Verilog-flavoured) with a parser, an evaluator and synthesis
      estimates (gates, logic depth → propagation delay and maximum clock, switching energy → power).
   2. An event-driven digital domain: chips' insides outside Newton; nets joining only chip pins taken out of the
      analog network; pins on analog nets physical (threshold crossings in, switched pull-up/down out, landing the
      circuit steps only on events an analog part can see); supply current from static and switching power.
   3. Library parts: crystal clock module, SPI ADC, 128×64 monochrome SPI display panel (SSD1306-style command set,
      page addressing, framebuffer, current by lit pixels), amplified pressure transducer on a vessel port.
   4. A shipped chart-recorder chip design (in the chip language) and a core end-to-end test: thermistor and pressure
      transducer on a heated vessel → ADC → plotter → display, the trace following them.
   5. Mod: parts and items, the framebuffer synced to clients (compressed deltas, capped rate) and drawn on the panel.
   6. Mod: a chip designer screen (text with errors and estimates, example library, writes the design onto a chip).
   7. A "Chart recorder" exhibit with tour checks; docs.
   Status 2026-10-26 23:00: 1–5 and 7 done. In the game the chart recorder's two screens draw the vessel's pressure
   and temperature climbing and levelling off; the tour's 4 checks pass (screenshot showcase-79-chartrecorder-graph).
   Next: 6, the chip designer screen. Also open: fabricating chips from materials; display sync as compressed deltas
   instead of whole frames; wire capacitance on digital nets; a thicker trace option for distant viewing.
   Status 2026-10-27 01:15: 6 done, and more, for the goal "build the counter display as a player would":
   - every chip runs by events;
   - the counter display reads its font from a 256-byte ROM chip;
   - chips are placed and removed whole, with their pins named on top;
   - the chip designer and the ROM editor are written;
   - wire runs.
   The core suite and all 20 GameTests pass. The world builds 80 exhibits by player placement with 0 problems and
   0 differences. The counter display's tour passed with both editors open (01:00).
   08:12: the chip exhibits' tour ran once the display was back. Computer, memory, geiger and counterdisplay all
   pass. The chart recorder's pressure check was fragile (fixed: gauge pressure). The full tour was stopped at
   exhibit 27 on the user's word. A miniature counter display (one block, true scale) was added and passes its tour.

0a0. **The power station runs at real time in the game** (2026-10-26 21:10): its headless server ran 98 s in 100 s,
   time scale 1.00 throughout. Still to see with a client (the station tour; the display was unavailable at 18:47,
   20:14 and 21:10): rendering shares the cores, and the tour's samplers make the server wait on each step, so expect
   its logged pace a little lower than the island's own scale.

0a1. **The power station at real time** (asked 2026-10-26; the user chose B small shortcuts + D off-tick stepping,
   then A deep solver work; not C). Done: parallel loading, circuits sub-cycled and overlapped with the plant on two
   threads, independent plant parts, cheaper LU/Newton passes, bristle tolerance 1 %, off-tick stepping in the game
   (PERFORMANCE.md, "Large multiphysics machines at real time"). Core test 6.8 → ~1.0 s/s; **in the game 0.6 of real
   time** (~1.4 s/s there); since then held-unknown pivoting and adaptive accuracy (×3 before slow motion) bring the
   in-game state, replayed in the core, from 1.28 to 1.05 s/s — **to be measured in the game** (the station tour could
   not open a window at 18:47: "Failed to find a primary monitor"). Open: the engine's valve-port Newton failures (a
   fifth of the plant's iterations), why the in-game state's circuits iterate ~6 times a step (vs ~4), water/steam
   property cost.

0a6. **Across scales.** Seen in the game on 2026-10-27 at 10:30, where the minicounter tour passes 4/4:
   - a miniature controller drives a full-size screen (display links);
   - ribbon cables join miniature and full-size circuits into one machine with two grids, each colour on its own
     conductor.
   - (done 2026-10-27, steps 1, 2, 4 and 5 of the user's list) shafts, pipes and heat cross a block face
     (`Assembly.Link`, `CrossScale`). The "Across scales" exhibit has a full-size crank turning the miniature
     generator; "Pipes and heat across scales" has a 3 bar tank filling a miniature one and a hot block warming a
     miniature cell. Both pass in core tests, a GameTest and the tour. World lookups use each cell's own scale
     (`CellMap`), and a ribbon's conductors have resistance.
   Open across scales: gears meshing across a face, and structural support (a full-size part does not hold up a
   miniature one); optical beams drawn in a joined island still use one scale.

0b2. **The station's displays and battery (2026-10-28).**
   - The screens froze after any reload, then drew garbage after the first fix. Both are fixed, and the tests now
     read the screens (see DEVELOPMENT_LOG). The station tour passes 21/21 on a reloaded world, every screen read
     right off the client before and after braking.
   - The battery gap was not world against core. Replaying the world's state in the core gives +0.24 mA over
     100 s against the world's +0.25. The core dashboard test measured its first 31 s, the battery still
     recovering from its start (+7.5 mA); steadily both are about +0.2 mA.
   - **Open:** the margin is thin. The dashboard draws about 22 mA of the generator's about 74 mA, its screens
     8.6 mA of that, leaving +0.1 to +0.25 mA for the battery in five in-world runs. One earlier run lost 6.9 mA
     on average, with no recompile during its window; unexplained. The station test now logs its regulator's
     extremes each second, so the next such run will show whether the regulator tripped. To restore a margin:
     dim the screens (contrast 0x40 to 0x20 saves about 3 mA), lighten the references, or gear the generator up.

0b1. **Scheduled tour 2026-10-28 06:23: not run.** Windows showed only its "WinDisc" placeholder (no monitor), as
   at 02:00, so no client could open. The demo world was regenerated (headless): 86 exhibits, 0 problems,
   0 differences, 24/24 in-world tests. The tour copies `run-demo/world`, so it is ready. Run
   `./gradlew runShowcaseClient` and `./gradlew runStationShowcase` when a display is back.
   *The station battery's margin:* the GameTest's net charge over 189 s fell from 0.121 mA·h (six gauges) to
   0.006 (seven). That is 2.2 mA, about what a gauge draws: the display controller's 300 µA plus 2.5 µA per lit
   pixel at quarter contrast, a reference chain, an op-amp and two chips. The generator's mean 74 mA is now nearly
   all taken by the station's loads. This is physics, not a fault. To give the battery a margin again, a player
   would lower the screens' contrast or a reference's current, or gear the generator up.

0b0. **Full tour 2026-10-28 01:15 (scheduled): 189/189 over 86 exhibits in 33 min, no hand failures.** The
   demo world was not regenerated: it was built at 00:50 from the code committed since. At 01:25 Windows lost the
   audio device ("Audio device was lost!"). Vanilla Minecraft then retries opening it every tick, which put 27 000
   stack traces in the log, but no check depends on the client's sound.

0b. **The user's seven next steps** (asked 2026-10-27: "Complete these steps").
   1. *The gas engine's slow start* (done): the tour's condition read the server off its thread (NaN). It now reads
      on the server's thread, and the engine's visit takes 86 s.
   2. *Accidental lead joins* (done): the compiler warns of a lead reached at its end and its side by different
      wires, and of components side by side. An empty-hand click on a two-terminal component shows its lead faces.
   3. *The dashboard* (done in the core): a seventh gauge, the generator's power (two ADCs, k·I·V); negative
      readings; alarm limits that invert a card; a trend interval per gauge (fuel every 30 readings).
   4. *Across scales* (done in the core): structural parts touching across a block's face are one rigid piece, and
      gears side by side across a face mesh. New exhibit "acrossscales3": a full-size 32-tooth gear drives a
      miniature 16-tooth one, 2 : 1.
   5. *Chips from materials* (done in the core): a silicon block oxidises and takes its dopant in at its simulated
      temperature, and a process sets the chips cut from it. New exhibit "waferfurnace". See PHYSICS_MODELS,
      "Chip fabrication".
   6. *Station performance* (one round done): profiled at 17.2 s per 10 s with seven gauges, against 9.1 s without
      them. Ribbon conductors reduced to a node per joined point: 14.9 s. The rest of the dashboard's cost is its
      circuits solved at every step of the generator's; the next round is a multirate split of the circuits phase
      (see PERFORMANCE, "The station with seven gauges"). The power gauge read 10–18 % under the run's mean. Found
      (2026-10-28): not a bias but sampling noise. The generator's current comes in pulses (0 to 670 mA at each
      magnet's pass), the current front ends' 0.2 s filter left a single reading anywhere from 63 to 105 mA, and the
      gauges read at different moments. The filter is now 1 s (10 µF): the current gauge holds 73–77 mA against
      73.9, and the power gauge, its constant set again from settled readings (1695), shows 0.40 W against 0.425.
      The station world rebuilt with it (01:54): 0 problems, its GameTest passing. **The station tour is still to
      run:** at 02:00 Windows had no monitor ("Failed to locate a primary monitor"; the audio device went at 01:25
      too), so no client could open. Run `./gradlew runStationWorld` (if the code changed) and
      `./gradlew runStationShowcase` when a display is back.
   7. *The EM-8 and the analyser* (done in the core): the EM-8 has 32 bytes, a stack, CALL/RET, PUSH/POP, LDN/STN
      and ADC/SBC. The analyser decodes SPI and UART and exports CSV.
   **Seen in the world (2026-10-28 00:43–01:05):**
   - Demo world: 86 exhibits, 0 problems, 0 differences, 24/24 in-world tests. The first build found the gears
     exhibit's flywheel plate not square (the tweezers lay squares); fixed.
   - Affected-exhibit tour: minicounter, em8thermostat (the decoder read CH2 0x89 off the ADC), acrossscales and
     acrossscales3 (2 : 1) all pass.
   - The wafer furnace failed first, 3/4: the tweezers build never closed the switches a miniature exhibit starts
     closed, so its heater was off. Fixed; re-toured 4/4: wafer 1456 K, oxide 4.0 nm, junctions 0.21 µm, and the
     engineer's goggles reading it live.
   - Station world: 0 problems, its GameTest passing. Station tour: 15/15, all seven screens drawn, every gauge
     agreeing, the power gauge 0.37 W. The battery's charge in the GameTest fell to 0.005 mA·h (0.121 with six
     gauges): the seventh gauge's draw nearly takes up the charging margin. Worth watching.
   **Engineer's goggles** (asked 2026-10-28, done): see DEVELOPMENT_LOG. Next for them: a machine summary (its
   power flows) when looking at a part of it, and torque on shafts.
   Since done for fabrication: a two-step process (the wafer edited between heatings, its state kept), and
   short-channel effects in the chips' model (2026-10-28 06:40). Next: wet oxidation in steam (a wafer inside a
   vessel of steam), the oxide masking the dopant (patterning), and a chip's process shown in the chip designer.

0a9. **The station's dashboard** (done 2026-10-27, asked by the user: live metrics on displays, a nice UI, miniature
   electronics wherever possible). Six screens in front of the station show fuel, boiler pressure, engine speed,
   condenser pressure, generator current and battery voltage. Each is drawn by a gauge of miniature electronics
   in the block behind it. In the core each agrees with the simulation (DashboardTest). In the station world
   (17:44) it builds with 0 problems, and the station's GameTest passes with the dashboard drawing on its battery:
   0.121 mA·h charged, the governor holding, the counter counting. The station tour of 16:29 found all six screens
   drawn and every gauge right, but that world's battery discharging; fixed since (the regulator's reference
   shorted by a tap).
   **Station tour 19:07 on the present world: 14/14**: all six screens drawn and every gauge agreeing with the
   simulation (fuel 180.8 bar against 180.76, boiler 3.70 against 3.756, engine 139 rpm against 138.6, condenser
   55.7 kPa against 55.4, generator 67 mA, battery 5.60 V against 5.620), the battery charging, the governor
   holding. The station world installed in run/saves at 19:13 (the old one kept as "Emergence Station
   (2026-10-25)"); the user opened it.
   **Full tour 20:10 (the 18:20 slot, held while the user was in the station): 182/182 over 84 exhibits, no hand
   failures.** The new exhibits, the datum and passive-reader changes and the gas-pressure sensor all hold across
   the gallery. (The gas engine's 214 s start: the tour's condition, fixed since; see 0.)
   Next for the dashboard:
   - a seventh screen for the engine's power (current × voltage: a gauge reading two ADCs);
   - alarm limits that invert a gauge's card;
   - the trend's time scale chosen per gauge.

0a7. **The EM-8 computer and the logic analyser** (done 2026-10-27). The EM-8 runs the thermostat program from its
   ROM in the world (plate 318.1–318.8 K). The tour opens the ROM's program in the program editor and clips the
   analyser onto its serial bus. Next:
   - a stack and subroutine calls, or a bigger player design: the EM-8 has 16 bytes of RAM and no calls;
   - the analyser: decode a serial bus (SPI/UART) into bytes on the screen; export a capture as CSV;
   - fabrication: chips made from materials (step 3 of the user's list, not yet asked for).

0a8. **Full tour 2026-10-27 10:40: 167/174.** Fixed: the PWM bridge's shoot-through burnout (its control circuit
   decoupled), the periscope's datum (`Network.preferDatum`), the loudspeaker's and chart recorder's checks. All
   re-toured: 19/20, then the analyser check made robust, 4/4.
   **Full tour 2026-10-27 13:00 (scheduled): 181/182 over 84 exhibits, no hand failures**, so the datum change holds
   across the gallery. The one failure: the DC motor's battery current read at one instant (12 mA against 8 A the
   tour before, the rotor at the same speed): its current pulses across the commutator's gaps. Now its peak over
   the step: 8.65 A, passing. Still open: the gas engine took 214 s again (see 0, "the hand swung the starting
   handle 3 min 12 s").

0a5. **Scheduled tours, 2026-10-27 01:11 and 06:23: not run.** The display was disconnected: Windows shows only its "WinDisc"
   placeholder, and the tour client failed with "Failed to locate a primary monitor" (also the chip tour at 00:30,
   which was left on the error dialog and stopped at 01:11). The demo world is current (built 00:28 from the
   present code: 0 problems, 0 differences, 20/20 in-world tests). When the display is back:
   1. `./gradlew runShowcaseClient -Demergence.showcaseOnly=computer,memory,geiger,chartrecorder,counterdisplay`
      (the chips are now event-driven and placed whole);
   2. the full tour.
   One launcher (`build/scratch/when-display.sh`, started 01:15) polls every minute for a display and then runs both
   (logs `build/scratch/tour-chips2.log` and `tour-full-0127.log`; reports copied beside them). At 06:23 it was still
   waiting. The 06:23 slot was folded into it rather than queuing a second client.

0a4. **Scheduled tour, 2026-10-26 20:14: not run.** The demo world regenerated (all 17 in-world tests pass), but the
   tour client could not open a window ("Failed to find a primary monitor"; also at 18:47, the station's tour). The
   display was off or locked. The next scheduled tour should cover today's solver changes (circuits overlapped
   with the plant, independent plant parts, off-tick stepping, adaptive accuracy) across every exhibit.

0a5. **Real-time findings, evening** (in-game state replayed: `IslandDumpReplayTest -Ddiag.dump=run/island-dump-1793054186346.txt
   -Ddiag.dumpProfile=true`, ~1.2 s/s): a timeline (`-Ddiag.timeline=<t>`) shows two kinds of stall. (1) The plant's
   Newton failures: ~110 per 5 s, each 40 iterations at ~220 µs (8–17 ms), at the engine's valve ports. Converged plant
   solves take 2–3 iterations, and only ~1 % take more than 12, so a lower cap for such phases or an early abandon is
   worth trying again with the timeline as the measure. (2) The circuits' switching intervals of 7–13 ms. Their 26–30-iteration solves were the
   bridge diodes walking back from forward-bias overshoot by n·V_T an iteration: fixed with SPICE junction limiting
   (in-game state replayed 1.18 → 1.02 s/s; ~0.9 with the game's adaptive accuracy). To be measured in the game. Flagged
   off: `emergence.pipeline` (free-running overlap: broke a four-bar's energy ledger and the contact governor) and
   `emergence.tightThermalS` (tightly coupled thermal nodes moved to the fast side at runtime: broke a filament's energy
   balance; it would remove the 22 ms synchronisations a small valve body under condensing steam forces).

0a2. **Tour triage, 2026-10-26** (full tour: 152 passed, 2 failed — both the PWM exhibit's hand, its checks passing):
   - the PWM hand again, 33rd of 75: the client had no block entity at the transistor's block for the whole 30 s the
     hand waits (it passes alone, 3 of 3). The hand's failure now says what the client holds there (block state,
     block entity, chunk) — read it from the next full tour. The second full tour (2026-10-26 07:19, 152 passed,
     2 failed, both again this hand) answered: the client's block there is air, with no block entity, its chunk
     loaded, while the tour's own screenshot shows the exhibit whole and its checks pass on the server. The message
     now also says what the server holds at that block and where the nearest part of the wanted kind is on the client,
     to tell a part the server re-hosted from a client that missed an update. Read it from the next full tour;
   - a burner island failure in a gas-only tour (2026-10-25 20:01, 37.3 s in, as the fuel valve opened: the valve's
     molar flow did not converge; the restart at rest failed too, then it lit). The core does not reproduce it
     (`DemoExhibitsTest.gasBurnerLitAfterStanding`, -Ddiag.burnerIdle=…, every timing tried runs on). Tour runs now
     write failure dumps by default: replay the next one.

0a3. **Tour triage, 2026-10-26 12:59** (full tour: 159 passed, 3 failed):
   - the PWM hand, 33rd of 78. The server's block at the transistor's place is air too, and the nearest
     transistor is elsewhere, so the part is gone from the world, not missing on the client. It passes alone (3 of 3).
     Parts the simulation destroys were not logged; they are now ("Part destroyed: kind #id at pos (reason; T)").
     Read the next full tour for the transistor's fate (burnt in the noon sun before the tour came?);
   - the periscope's receiver stays dark in the world, alone too (a regression since the 07:19 tour). The red LED
     gives 11.5 mW and the photodiode passes 3.34 µA, as in the core, but the Schmitt trigger's output stays high and
     the green LED off; the core exhibit lights it (6.7 mA). Its island takes in the gas engine next door by line of
     sight (251 parts); its plot moved 60 blocks east when three exhibits were added before it. The tour now waits up
     to 10 s for the LED (it was checked two seconds after compiling), reports the chain's readings and writes a dump
     on failure. Replayed, the dump keeps the receiver dark, but its photodiode gets no light at all. Next: why the
     merged island's receiver does not switch (its supply, its threshold, the engine's battery beside it?).

0b. **Every exhibit at real time** (asked for 2026-10-22; measure first: `SolverBenchmarkTest.exhibitIslandsFitTheTickBudget`
   prints ms per 50 ms tick and the unknowns that limit the step):
   1. power electronics — fidelity switching: fast, decided states (a logic output's 10 ns slew, a gate's charge)
      as events and quasi-static algebra while the solver's step dwarfs them; then an ideal-switch or averaged bridge
      model with a detector for when its edges are not fast against the load (inverter, boost, PWM, H-bridge);
      (first step done: the Schmitt trigger's threshold as an event, the inverter 20× cheaper);
   2. the loudspeaker: 12× → 3.75× idle, 22× → 7.1× on (parasitic tolerances, the ambient slow, contacts without a
      node); its oscillator runs
      whenever powered, so "idle" is 500 edges a second: what is left is per-step cost (505 unknowns; the sparse
      matrix's assembly and refactorisation, ~35 % of samples) and ~40 steps a half-period — a lighter matrix assembly,
      fewer unknowns (series wire cells), the comparator as an event. (Tried: chord Newton, the factored Jacobian
      reused while each update at least halved the last — slower on the inverter, 8.5 → 10.4 ms a tick, since these
      solves converge in two or three quadratic iterations, and it failed the boost converter at a switching edge:
      reverted; growth ×4 just after a restart while the error is tiny: no gain on the loudspeaker). Local fixes are near
      their end here (5× needs a 5× cut): its oscillator always switches 500 times a second, each edge resolved at
      device level across ~300 fast unknowns — the microphone's idle amplifier among them. Structural: solve weakly
      coupled sub-circuits apart (the acoustic path is the only link between driver and microphone), or the averaged
      switch fidelity level for the whole driver;
   3. the gas engine: 1.00× real time running (species slopes reused within a step, flows resolved to 10⁻⁸ mol/s, a
      passage's flow equation in the form Newton converges on, damping only while updates bounce); what remains: ~1100
      rejected steps per 2 s (the cylinders' gas the step limiter — combustion), ignition as an event; the steam
      engines' liquid/steam branches (`mdot[...]` laggards) may take the same two-form flow equation;
   4. (done: the steam engines run at 0.38× and 0.46× real time);
   5. general: (done: islands step side by side on worker threads; compiling is off the server thread; contacts
      have no node of their own) factorisation reuse for linear islands, series wire nodes (78 of the loudspeaker's
      413 unknowns join exactly two conductors: eliminable, with their voltages rebuilt for probes);
   6. in the game an island gets 12 ms a tick (35 ms while watched), not the bench's 50: the gas engine (50 ms) runs at
      about 70 % while watched, the loudspeaker (190–360 ms) at 10–20 %.
   Expected: 1 and 2 reach real time; a fast combustion engine may stay costly (real time alone near a player, the
   slow-motion fallback when several are).
   Status 2026-10-26 (idle bench, 76 exhibits): every exhibit but the loudspeaker (200 ms a tick) fits the 35 ms a
   watched island gets; unwatched (12 ms) the condensing and steam engines (26, 19 ms) run at about half speed.

0c. **World blocks as thermal masses** (KNOWN_LIMITATIONS: they are not): a part touching a solid block exchanges
   heat with it — a node per touched block (ρ·c_p of a mapped material — stone as granite, dirt as soil, iron blocks
   as iron… — over the block's real volume at the island's scale), its half-conduction plus a contact resistance to
   the part, and a shape-factor conduction (≈ 4·k·s for a cube at the surface) into the deep ground at ambient; its
   temperature carried across recompiles like a part's. To decide with the user first: every exhibit stands on the
   stone floor, and its fires (≈ 1100 K) would leak hundreds of watts into it — several exhibits would need insulating
   feet, and the core tests (no floor) would no longer match the world thermally unless they model it too.

Every new mechanic: core tests → in-world GameTest where it matters → demo exhibit (and showcase step) →
regenerate the demo world → screenshot tour → docs → commit.

1. **Control**: (the flyball governor is in, working a rheostat through link bars; slide-worked plug valves are in,
   `slidevalve`; and Watt's governor on the steam engine's throttle, `governed`, through bevel gears) the fluid's
   force on a plug (unbalanced valves; a spring-loaded regulator built from parts is in, `regulator`: its piston on
   the outlet side against a spring, the plug riding the piston's rod); floats (buoyancy on slides) for cistern valves; contact bounce and arcing for the travel contacts; the EM-4
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
   (polarised relays, electro-permanent releases); relay logic (done: a self-holding relay, `latch`; still: latching relays proper, relay gates); core losses; a
   drag-cup speedometer. (Magnets attract iron on slides now.)
8b. **Water** (buoyancy on slides and turning bodies is in, `floats`; a stream's current turns an undershot wheel,
   `waterwheel`; parts may stand in water, their blocks waterlogged). Still to do:
   - (done: the water wheel drives an axial-flux generator on its own axle, the hand generator's magnets and
     coils on their side: 11.6 rad/s and 0.3 W peak into 20 Ω, against 17.5 rad/s free);
   - the stream losing the momentum a paddle takes from it;
   - the added mass;
   - floats in a vessel's own liquid (a cistern's ballcock), its level falling as they displace it;
   - overshot wheels (buckets filling: water carried in a part).
9. **Aerodynamics next**: (propellers on carts are in) twisted and cambered blades, wings on free bodies (gliders
   need free rigid bodies), a motor-driven propeller (magnetics on carried rotors), wind loads on structures.
10. **Performance**: async island compilation, factorisation reuse for linear islands, cheaper conductor loads (most
   of a circuit island's fast-step time); multirate is in (thermal apart);
   the steam engine and gas engine run slower than real time at speed.
10b. **Fermentation (first biology)**: yeast turning sugar into ethanol and CO₂ in a warm vessel, the still
   downstream. The liquid model knows only volatile condensables (an Antoine curve, the vapour's enthalpy), so first
   add a solutes layer: non-volatile dissolved species (glucose, biomass) in a vessel's liquid, each with its liquid
   enthalpy of formation and partial molar volume, lowering the solvent's activity (Raoult). Then Monod growth with a
   cardinal-temperature factor and ethanol inhibition, the enthalpy of C₆H₁₂O₆ → 2 C₂H₅OH + 2 CO₂ (≈ −69 kJ/mol
   from formation enthalpies), and the CO₂ leaving through the gas space.
11. **Broader science**: geology (ores, strata), environmental processes, biology — each connected to the existing
    chemistry, thermodynamics and fluids.
