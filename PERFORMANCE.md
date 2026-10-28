# Performance

## Budget
One server tick advances every island by 50 ms of simulated time. **Budget: ≤ 5 ms of server time per
island per tick** (10 % of the tick). `/emergence perf` reports running averages in game;
`SolverBenchmarkTest` measures offline.

## Current measurements (dev machine, `coreTest --tests *SolverBenchmarkTest*`)
| Island | Unknowns | Steps/tick | ms/tick |
|---|---|---|---|
| Generator (human cranking, 20 rad/s) | 79 | ~24 | 1.8 |
| Gear train (cranked) | 31 | 10 | 0.55 |
| Capacitor glow / fuse (idle) | 42 | 10 | 0.6 |
| Conduction race | 28 | 10 | 0.33 |
| Flywheels | 18 | 10 | 0.24 |
| RC mesh 10×10 (50 Hz drive) | 101 | ~200 | 35 |
| RC mesh 30×30 | 901 | ~230 | 235 — **exceeds budget** |
| RC mesh 60×60 | 3601 | ~235 | 1040 — **exceeds budget** |

### Running exhibits (`coreTest --tests *RunningExhibitsBenchTest*`, switched on; 2026-10-22)
| Exhibit | Unknowns | ms/tick | × real time |
|---|---|---|---|
| Inverter | 199 | 6.0 | 0.12 (59.5 s of CPU per 10 s before the Schmitt event: ≈ 6×) |
| Boost converter | 140 | 11 | 0.22 (0.49 before the ambient was slow) |
| PWM motor | 342 | 18 | 0.36 (0.92 at the start of the day) |
| H-bridge (forward) | 243 | 1.6 | 0.03 |
| Loudspeaker, idle (its oscillator runs) | 413 | 187 | 3.75 (12.4 at the start of the day) |
| Loudspeaker, switched on | 413 | 356 | 7.1 (22.4) |
| Gas engine, running (141 rad/s) | 207 | 50 | 1.00 (80 steps a tick, 4.1 Newton iterations a step; 1.8 this morning) |
| Steam engine / condensing engine | 136 / 146 | 19 / 23 | 0.37 / 0.46 |

Idle, every other exhibit fits in 50 ms; the costliest are the flyball (41), the condensing engine (30), the boost (24)
and the steam engine (23).

Idle, 2026-10-26 (`SolverBenchmarkTest.exhibitIslandsFitTheTickBudget`, 76 exhibits): only the loudspeaker is over 50 ms
(200 ms: ~1050 steps a tick, a fifth more rejected, its 47 nF timing capacitor and Schmitt trigger setting the step).
Then the condensing engine 26, the steam engine 19, the boost 10, the governed engine 9.0, the burner 8.5, the
regulator 5.9, the PWM motor 4.7; the flyball is down to 3.5. In the game an island gets 12 ms a tick (35 while
watched): watched, every exhibit but the loudspeaker runs at real time; unwatched, the two steam engines slow to
about half.

## Compilation (`coreTest --tests *CompileTimeBenchTest*`, 2026-10-22)
A machine is compiled when it is built, changed or loaded: on the server thread, a hitch for everyone. 73 exhibits
take 14 s in all; the slowest were the optics: the colour-filter bench 6.2 s and the spectrometer 2.7 s. Light from
hot filaments is traced as ray tubes band by band (18 bands, both hemispheres: 108 traces for the filters' three
lamps). The bands are now traced side by side (each band's scene on one thread), so 1.6 s and 0.76 s. Magnet field
tables were already sampled in parallel. And compiling is off the server thread now. The world around the machine is
read first, then a worker compiles while the server waits 20 ms. An ordinary machine is still ready the same tick; a
slower one (an optical bench, a machine with magnets: 0.1–1.6 s) pauses, its state as it was, while the server and
every other machine run on. A machine changed again while compiling has its stale compile interrupted at the
compiler's next phase. In-world tests and demo generation wait for every compile, as before.
(`-Demergence.compileWaitMs=0` sends every compile off-thread: the in-world tests then fail only where they read a
machine the same tick it was built.)

## Time dilation
Each island has a wall-clock budget of 12 ms per server tick. An island whose smoothed cost exceeds it
simulates less time per tick (down to 5 %) and recovers when cheap: the physics stays exact and the
server keeps its tick rate, but that machine runs in slow motion. The steam engine (≈ 230 unknowns,
≈ 750 steps per simulated second at 650 rpm) costs ≈ 1.5× real time on the dev machine.

All islands together get 35 ms per tick (`emergence.globalBudgetMs`), shared by priority. First the machine a player
is at — each player's nearest island, or any within 6 blocks — which may also take the whole 35 ms itself rather than
the usual 12; then the islands within 48 blocks, slowed in common if they exceed what is left (down to 2 %); then the
rest, down to 1 % (nearly paused). Before, every island within 48 blocks shared one pace: in the dense gallery a costly
neighbour (the chip-level inverter) slowed the exhibit being watched to a crawl — a hand held on the press for twenty
seconds pushed it for a fraction of one. Watched islands log their pace every ten seconds in the tour
(`-Demergence.logPace=true` elsewhere). In the dense demo gallery, where everything within the
simulation distance (≈ 190 blocks) runs, the exhibit being watched keeps its pace instead of sharing it with dozens
out of sight. In-world tests and demo generation lift both budgets (they test tick counts, not throughput).

The islands step side by side, on worker threads (one fewer than the processors, at most 8; `emergence.simThreads=1`
steps them in turn on the server thread). Each step touches only its own island's compiled machine; the world is read
before (cranks, wind, daylight) and written after (light, failures, sync), on the server thread. The budgets weigh the
server's time: each island's cost counts at its share of the wall clock the steps took together. So the machines
around a player each get their own 12 ms (35 ms for the one being watched) on a core of their own, rather than a share
of one core's 35 ms.

Those controllers act on the next tick's step size, so a single step can still cost seconds: a burst of switching
edges in chip-level logic (the inverter: 1–3 s a tick), or a model failing Newton over and over. One island's step is
therefore also cut off at 60 ms of wall time (`emergence.stepWallMs`; off where the budgets are lifted): it ends on
its last accepted state, and the shortfall shows as lag, as slow motion for that machine. The server logs any rebuild,
compile or island step over 1 s ("Slow simulation: …"), naming the island's block and size.

## History
* Conductors under multirate skip the temperature derivatives the fast partition would discard (keeping only the
  Joule heat's own slope, for the coupling's stability limit): loudspeaker 15.9 → 11.2 s, governor 23.7 → 19.4 s,
  thermostat 29.6 → 20.6 s (against single rate: −46 %, −35 %, −42 %).
* Multirate (the thermal network stepped apart, its rows and columns out of the fast partition's factorisation):
  loudspeaker 20.7 → 15.9 s, governor 29.7 → 23.7 s, thermostat 35.5 → 29.6 s (alone, dev machine). The fast steps
  still load every conductor (their heat goes to thermal nodes) — the next costs to attack. The energy ledger now uses
  the quadrature of each step's method (backward Euler: the end point), which also cut single-rate energy errors
  (a filament: 5.7·10⁻⁶ → 5.4·10⁻⁹ J).
* Every island, per Newton iteration: natural convection's film-temperature air properties and Prandtl factors are
  tabulated (1 K steps) and its slope takes one extra evaluation, not four (59 % → 12 % of the load time in a
  circuit-heavy island); thermoelectric conductors reuse their Jacobian scratch arrays; the solver records the last
  unconverged unknown and formats its message only when a step fails (it built a string per unknown per iteration).
  The loudspeaker exhibit: 43 → 30 s per simulated second; it still needs ~60 000 steps a second (switching edges at
  250 Hz, each resolved through the MOSFET's picofarad capacitances) and runs ~70× slower than real time — the next
  step is multirate: 200 of its 514 unknowns are thermal and need no microsecond steps.
* Speed governor 36 000 → 1 400 steps per regulated half second: the op-amp's internal state had a 1 µV
  truncation tolerance; in a closed loop its derivative A₀v_d/τ amplifies the inputs' Newton residuals, so the
  error estimate was noise. It now has 1 mV, like capacitor voltages. The exhibit still runs about 2× slower than
  real time on the dev machine (commutation events every few milliseconds; the transistor's junction
  capacitances).
* Generator 52 → 28 ms: coil flux LTE absolute tolerance was 1e-12 Wb (signal ~0.02 Wb); now scaled to the
  signal.
* 28 → 2.8 ms: primitive open-addressing index in the sparse matrix (no boxing); realistic human cranking
  (in-solver force–velocity model) instead of a constant 2 N·m that spun the rotor to 1000+ rad/s.

* Steam engine 57 → 22 s per 15 simulated s: one-pass saturation properties with integer power chains
  and an analytic dp_s/dT (the IAPWS correlations' fractional powers were the hot spot), tSat on a light
  ln p evaluation, memoised port states (finite differences perturb p and ṁ more than T and s), cached
  Hermite-join ends, Fibonacci hashing in the sparse matrix's (row, col) table (the old hash clustered).

* EM-4 computer (4 MSI chips, ~800 wire cells): 40× slower than real time → 2.7× faster. Wire runs cut the
  network from 2639 to 722 unknowns; BDF2 for electronics islands removed trapezoidal ringing on 5 pF input
  nodes (3254 → 143 steps in the first 0.4 s); a steep Schmitt input gave the clock fast edges.

* Core test suite ~11 min → ~2.5 min: JUnit runs methods in parallel (benchmarks `@Isolated`); the servo
  fragment sweeps merged and deduplicated (85 distinct fragments), 3 steps each on a pool of its own. The
  slowest fragments cut the servo amplifier's feedback: open-loop, the comparator and MOSFET oscillate with
  µs steps (4000-step cap per tick) — physically plausible, and costly in game if a chunk boundary splits
  the servo.

* Matrix assembly replay: each element's sequence of Jacobian entries is recorded and replayed (a comparison and an
  array read per stamp instead of a hash lookup). Steam engine profile (JFR): hash lookups/inserts ~520 → ~110 of
  ~2700 samples. Its remaining cost: water/steam properties (~30 %), the thermoelectric junctions of its dissimilar-
  metal frame, LU refactorisation; ~16 700 Newton iterations per 5 simulated seconds (4.5 per step, a third of the
  steps rejected — set by a 1.85 mL valve volume; relaxing that only moved the work into more Newton iterations).
  Profile with `./gradlew coreTest --tests … -Demergence.jfr=<file>.jfr`.

* Sparse LU pivot test (all islands): the refactorisation rejected a pivot that was small against its row's largest
  entry — rows mix units, so sound pivots failed and every Newton iteration fell back to a full Markowitz
  factorisation (boxed hash maps). Now a pivot fails only if it collapses relative to its value when the order was
  chosen: the gas engine went from ~12× slower than real time to real time over its first seconds (≈ 2.5–4× at
  1100–2000 rpm, ten combustion events a second).

* Sparse LU pivot test, second half: the backstop against a pivot's own U row was still absolute (|d| > 10⁻¹⁴ of the
  row's largest entry). Threshold pivoting chooses by column, so on the burner's mixed-unit rows Markowitz picked a
  pivot ~10⁻¹⁵ of its row, the next refactorisation rejected it, and the fresh factorisation picked it again — 29 055
  full factorisations against 63 083 refactorisations over 240 s of burner, almost all while heating and lighting.
  The row share is now judged against its share when the order was chosen (a 10⁸-fold collapse fails, as for the
  pivot itself): burner exhibit test 48 → 16 s for 240 simulated seconds (≈ 3 ms a 50 ms step).

* Sunlight tracing (`DemoExhibitsTest.sunlightTracingCostPerExhibit`): 1–70 ms per exhibit, 158 ms for the solar
  furnace's 169 facets, 0.75 s for the whole gallery; repeated only when the sun moves half a degree (3 s of a
  Minecraft day) or a body carrying optics moves — never in the noon-fixed demo world. LED ray tubes at compile:
  ~20–50 k tubes (tens of ms) for an LED looking at mirrors or lenses.

* Island membership: the flood used one global reach, the largest of any block (a big gear's: 6), so exhibits
  whose wires came within 6 blocks across an aisle merged — the periscope, lens and solar furnace became one island
  costing 10 ms a step (0.13× real time). Reach is now 2 for every block, with large gears' rims and optical lines
  of sight as symmetric links: the furnace runs alone at 0.6–0.7× in the full gallery.

* Schmitt trigger as an event (its threshold a discrete state flipped where the input crosses it): the inverter
  59.5 → 3.0 s of CPU per 10 simulated s, Newton failures 26 700 → 0.
* Device capacitances as parasitics (2026-10-22): a gate's, a junction's or a pin's picofarads are integrated as before
  but their truncation error is held to 100 mV, not 1 mV. A step trace of the loudspeaker showed its 8 pF gate–drain
  capacitance limiting some forty steps per half-period, down to 50 ns, through each inductive turn-off. Loudspeaker
  322 → 214 steps per 10 ms, 12.4× → 8.6× real time; PWM 0.92× → 0.82×.
* The ambient in the slow partition: a thermal node only became slow where something stored heat, so the ambient,
  held by its fixed temperature, stayed fast, and with it every part's natural convection and radiation. They were
  loaded at every Newton iteration of every fast step, a quarter of the loudspeaker's samples (JFR). A thermal node
  written by slow candidates alone is now slow too. With a conductor's resistance cached on its (held) temperature,
  every switching exhibit is 1.5–2× faster: the loudspeaker 8.6× → 5.1×, PWM 0.82× → 0.50×.
* The reload after Newton converges (stored quantities re-evaluated at the converged point) is residuals only: it
  assembled a Jacobian nothing used. Gas passages remember their species' enthalpies and heat capacities at the last
  temperature: their finite-difference Jacobian evaluates the residual ~20 times a load, and only the temperature
  columns move the temperatures (gas engine 89 → 84 ms a tick).
* Gas passages: the species columns of their finite-difference Jacobian (16 of ~21) differenced once per step attempt,
  their slopes reused by its later Newton iterations (84 → 70 ms a tick, the iterations unchanged); molar flows
  resolved to 10⁻⁸ mol/s, not 10⁻⁹ (near zero flow the turbulent ṅ|ṅ| term makes Newton halve the error an iteration;
  6.1 → 5.7 iterations, 70 → 66 ms). `diag.newtonLaggards=true` counts which unknowns keep Newton iterating: the
  engine's exhaust valve and a vent to the air, still.
* Those two were a valve shutting on the flow the step began with: its turbulent term dominant, Newton on the pressure
  balance Δp = A·ṅ|ṅ| + B·ṅ overshoots from a small flow and halves its way down from a large one (`diag.lagTrace=<name>`
  traces one unknown's iterations: six to eighteen of them). From an iterate far off the flow Δp drives, a passage now
  writes the same equation solved for the flow (the root's stable form, scaled by its slope there), linear in ṅ — when
  its own resistance dominates how far its ends' pressures follow the flow within the step (∑RT/(Vα)); otherwise (two
  bottles joined face to face) the forms cycled. And Newton's halving of anti-parallel updates now lasts only while they
  bounce: once triggered it had halved every later update, a rate of ½ to the end. Gas engine 5.7 → 4.1 iterations a
  step, 1.33× → 1.00× real time; inverter 0.20 → 0.14×, boost 0.35 → 0.25×, PWM 0.50 → 0.41× (fewer failed solves).
  (A bounce that recurs a third time is a cycle, not an overshoot: from then on the solve is halved throughout, as
  before; a PWM comparator's op-amp at its rail needed it.)
* A contact between two conductors was two half-conductors and a node between them (each half's Joule heat into its
  own part). Nothing else meets at a contact face, so it is now one element, exactly equivalent
  (`ThermoelectricTest`: same current and temperatures to the last digit, the Jacobian against finite differences).
  That is 92 of the loudspeaker's 505 unknowns and as many loads: 5.1 → 3.75× idle, 9.3 → 7.1× on, and every circuit
  10–20 % cheaper. It first made the PWM motor 3× slower. Every circuit floated, its potential held only by 10⁻¹²
  shunts, and the fewer nodes the softer that direction. The solver now grounds one node per circuit (a datum, see
  PHYSICS_MODELS).
* The loudspeaker's remaining cost is honest work: about 50 steps per oscillator edge (≈ 10 regrowing from 10⁻⁷ s after
  the trigger's event, ≈ 20 through the gate RC's exponential at a 10⁻³ error, ≈ 15 through the MOSFET switching its
  floating drain), 500 edges a second. Each step costs ~0.18 ms: Jacobian stamping (~1500 entries a load) and the
  sparse refactorisation are each a quarter of it. The bristles of its two light slides added 20 % once on; their
  torque is now resolved to 0.1 % of its limit, not 10⁻⁹ absolute.
* Tried and dropped: chord Newton (Jacobian reuse within a step: slower on circuits that converge in two or three
  quadratic iterations, and it failed the boost converter at an edge); PI step control (Gustafsson/Söderlind gains
  0.7/0.4: halved the gas engine's rejected steps, 9 % faster, but regrew the step more slowly after every switching
  edge — the circuits 20–40 % slower); stamping exact zeros to keep the gas engine's matrix pattern fixed (no change).

## Known bottlenecks and plan
1. **Large linear networks** take ~4× more steps than the 1 ms cap requires (LTE rejections on 2000+ RC
   states) and each step refactorises. Plan: (a) per-island accuracy level / relaxed tolerances for
   game-scale circuits, (b) skip the extra residual evaluation after convergence for linear islands,
   (c) cache the factorisation when the Jacobian is unchanged (linear networks, fixed h), (d) multirate:
   thermal nodes stepped at a coarser rate.
2. **Island compilation** (incl. 256-angle magnetic tables, ~0.1–0.5 s for coil/magnet islands) runs on the
   server thread when a structure changes. Plan: asynchronous compilation with the old model continuing.
3. **Sync** sends every island every tick; plan: distance culling and delta compression.
4. Parallel stepping of independent islands.

## Large multiphysics machines at real time (2026-10-26, benchmark: the power station)
The power station is the benchmark, but nothing here is specific to it: every measure considered acts on any machine a
player builds.

**Where the time goes** (core test, wall-clock seconds per simulated second):

| Configuration | s/s | Notes |
|---|---|---|
| Whole station | 6.8 | 1141 unknowns, ~1550 accepted steps/s (+520 rejected), ~8000 Newton iterations |
| Plant alone (no generator or electronics) | 1.7 | 738 unknowns, ~450 steps/s |

An iteration costs ~0.85 ms. About 22 % of that is the LU's refactorisation, ~18 % the matrix's assembly, and ~35 %
fluid and gas elements.

What sets the step:

1. The generator. Its coil currents kink as the bridge diodes turn on and off, and its steel's magnetisation tracks
   them. That steel is a hysteresis play in its following regime, which turns no corner of its own, so these are the
   diodes' steep exponentials, and resolving them is physical.
2. The flyball weights' bristle friction, which reverses twice a revolution.
3. The engine cylinder's steam near top dead centre (1.85 cm³) and the feed pump's clearance.
4. The firebox's combustion.

Real time needs ≤ 0.7 s/s (35 ms of a watched island's 50 ms tick), or ≤ 1.0 if a large island steps on its own
thread outside the tick.

**Measured, keeping the model** (diagnostic knobs `diag.lteScale`, `diag.fluxTolScale`, `diag.frictionTolScale`,
`diag.noHyst`):

| Change | s/s | Effect on 20 s outputs |
|---|---|---|
| Flux and friction tolerances 10× looser | 5.0 | unchanged |
| Every truncation-error tolerance 10× looser, hysteresis off | 3.8–4.0 | engine, boiler, fire and charge unchanged to three digits |

**Tried, not kept:**

| Approach | Result |
|---|---|
| Fluid network stepped apart from the mechanics, as the thermal network is | Holding a flow across the boundary empties small volumes: a turbine's receiver and an engine's valve chest ran to negative temperatures, 10 core tests failed. |
| Circuits stepped apart from the plant and mechanics, the mechanics' angles carried on for the fast side | Averaging a generator's force over a synchronisation loses its damping and breaks the energy ledger, 7 exhibits failed. Needs work-conserving coupling. |
| Kinks located as events (friction reversals) | Friction-limited steps fell from ~1400 to ~500 per 10 s, but the total did not move: the generator sets it. |

**Options** (awaiting a decision; see NEXT_STEPS 0a):

- **A. Deep solver work, full fidelity:** work-conserving multirate between circuits and mechanics, and parallel
  element evaluation. Days of work; realistically 2–4×.
- **B. Small shortcuts:** looser tolerances. About 1.8×, negligible measured loss.
- **C. An averaged generator-and-rectifier fidelity level:** used when its electrical frequency is high next to the
  machine's. The station would drop to about the plant's 1.7 s/s, and to about 1.0 with B. This is a substantial
  fidelity cost: no per-pass waveform.
- **D. Off-tick stepping:** large islands step on their own thread, so 1.0× counts as real time.

**Done (2026-10-26, all generic — nothing here knows it is a power station):**

| Step | Station s/s | What it does |
|---|---|---|
| Baseline | 6.8 | one Jacobian for everything but the thermal network |
| Parallel element loading | 4.0–4.4 | a large phase's elements load on the common pool, each worker into its own residual and copy of the matrix values, summed in a fixed order (deterministic) |
| Circuits sub-cycled | ~3.0 | electrical nodes, coil currents and magnetisation step apart from the plant; the plant first, with the circuits' delivery carried on from their last two interval means; the circuits then sub-step across the interval with the plant's rows interpolated; the energy in transit is booked |
| Cheaper LU and Newton passes | 2.0 | held unknowns skip the general row pass in refactor/substitution; Newton loops visit only a phase's own unknowns; diagonal positions cached; held rows' identity stamps replayed; stored energy summed only when asked; resistivity coefficients read once |
| Independent plant parts | 1.37 | parts of the plant sharing no unknown (the firebox's gas network and the steam side meet only through the boiler's walls) step at their own pace; if an element later reaches across (a crank taken up, gears meshing), they are joined at once |
| Circuits restart alone on their own events | — | a counter's tick no longer restarts the plant |
| Circuits and plant overlapped | 1.23 | the plant takes its next step on the common pool while the circuits cross the interval before it; deterministic (the threads meet only at fixed points) |
| Thermal syncs without draining the overlap | 1.20 | the circuits' heat over the interval they lag by is in transit until the next synchronisation |
| Bristle friction resolved to 1 % of its limit (option B) | 1.11 | a governor's flyweights held against the generator's ripple set two fifths of the plant's steps; outputs unchanged to three digits |
| The plant's parts step while the plant does; convection properties memoised | 1.04 (1.0 from t = 40 s) | the gas network steps on the calling thread while it waits for the plant |
| Held unknowns pivoted in one pass in fresh factorisations | 1.0 | the Markowitz search had scanned all 1141 columns at every pivot (a fifth of the plant thread in the in-game state) |
| Adaptive accuracy (game, islands off the tick) | in-game state 1.2 → 1.05 | falling behind, an island relaxes its truncation tolerances up to ×3 before it slows; the station's outputs matched to three digits |
| Diodes limit their junction voltage (SPICE pnjlim) | in-game state 1.18 → 1.02 (0.9 with adaptive accuracy ×3) | an iterate overshooting into forward bias walked back by one n·V_T an iteration: the bridge diodes' solves took 25–30 iterations, two fifths of the circuits' |
| Warm helper threads for parallel loading (LoadTeam) | core 1.0 → 0.84; in-game state 0.9 → 0.76 (with ×3) | the common pool's wake-ups cost as much as each sub-millisecond pass |
| Off-tick budget a whole tick (50 ms) | — | the server never waits for an island off the tick; a step that overruns makes the next cover the ticks it missed |
| Off-tick stepping in the game (option D) | — | an island costing more than 8 ms a tick steps on a thread of its own across the server tick (paced against 45 ms, its wall-clock cap the ticks it covers), so ~0.9 s/s is real time; the world sees its results a tick later |

**Tried, not kept (this round):** a minimum-degree column pre-ordering (fewer fresh-factorisation costs but more
refactorisation work than Markowitz: 22.4 vs 20.8 s per 10 s); a pivot threshold of 0.01 (−2 %, not worth the
stability margin); a quadratic Newton predictor (more iterations and failures); halving Newton updates only while far
from converging, a per-unknown two-cycle detector and early abandonment of stalled solves (the engine's valve-port
failures are a port at a water surface switching between liquid and vapour outflow; none of these moved the time).

**Where it stands (21:10):** **in the game the station keeps real time** — its headless server (`runStationServer`,
no client) ran 98 s of it in 100 s, its time scale at 1.00 throughout, 36–44 ms of each 50 ms tick, accuracy relaxed
as the game's controller sees fit (≤ ×3). Core test 0.84 s/s at full accuracy. Earlier: core test ~1.0 s/s; **in the game 0.6 of real time** before the last two rows (the station's tour,
7/7 checks passing), ~1.4 s per simulated second there. Replaying the in-game state in the core (`IslandDumpReplayTest`,
`-Ddiag.dumpProfile=true`) showed most of the gap is the state, not the client: it costs 1.28 s/s in the core too, its
circuits taking ~6 Newton iterations a step against ~4 (the generator's coil currents and magnetisation lag). With the
last two rows the replay is 1.05 s/s; not yet measured in the game (the display was unavailable for the tour).

**What sets the time now** (per 10 simulated seconds, core test): the plant ~8 s on its own thread (6.4k steps, its
step set by the cylinder's 1.85 cm³ at top dead centre, the flyweights' bristles and the valve ports' flows; ~160 Newton
failures at those ports, each costing the 40-iteration cap — a fifth of the plant's iterations), the circuits ~5 s
alongside (15k steps, the generator's magnetisation following the bridge diodes) — the circuits wait ~4 s for the plant.
Outside the overlap: thermal synchronisation 0.6 s, end-of-tick drains 0.4 s. Candidates: the valve ports' Newton
failures (a port at a water surface switching between liquid and vapour outflow; a wider wetting band over the port's
opening made them worse), cheaper water/steam properties (an eighth of all solver time), the circuits' in-game Newton
count.

## The station's dashboard (2026-10-27)
Six gauges of miniature electronics (about 2700 parts) cost the station about 16 % in the core: 15.8 s of wall
time per 10 s, against 13.6 s without. Their chips at 10 kHz cost little (about 3 s of a 30 s run); the circuits'
analog nodes cost the rest. At first they cost 50 %: the fuel bottle's transducer held the firebox's gas chemistry to
the engine's steps, until passive readers no longer did (see ARCHITECTURE, "Passive readers").

## The station with seven gauges (2026-10-28, `StationTest -Ddiag.station=120 -Ddiag.profile=true`, machine idle)
Steady state, wall time per 10 simulated seconds at full accuracy:

| Build | Wall time | Unknowns | Circuits: per Newton (load, solve) |
|---|---|---|---|
| Without the dashboard | 9.1 s | 1182 | 85 µs (42, 18) |
| Seven gauges | 17.2 s | 2897 | 194 µs (57, 67) |
| Seven gauges, ribbon conductors reduced | 14.9 s | 2648 | 162 µs (52, 53) |

* The dashboard's cost is all per iteration. The step counts are the same with and without it: the circuits take
  about 14.5k steps and 65k Newton iterations per 10 s, their steps held short by the generator's magnetics.
  Without it the dashboard's circuits would step far less often.
* Ribbon conductors were a node per block per conductor: 249 along the station's trunk. They are now a node only
  where a wire joins or the ribbon branches, the blocks between one resistor (as wire runs already were).
* What is left of the dashboard's cost: about 640 electrical unknowns (chip pins on analog nets, the front ends,
  routed wiring) solved at every one of the generator's steps.
  * The next step is to step the gauges' circuits apart from the generator's. They meet it only through the
    supply and high-impedance sense lines. A multirate split of the circuits phase, coupled at those nets as the
    plant and circuits are now, would put the station near 9–10 s again.
  * Thermal nodes also grew by 849, but the thermal phase is stepped rarely (0.4 s per 10 s).

### The generator coils' flux tolerance (2026-10-28, `-Ddiag.fluxTolScale`)
The coils' flux error is held to 10⁻⁴ of the magnet flux swing they see (`MagneticSystem.setup`), ten times
tighter than the solver's general 10⁻³ relative tolerance. These coils hold the circuits' steps short at the
station (`MagneticSystem#1711` limits most of them).

| Scale | Wall time per 10 s (steady) | Battery net, DashboardTest 31 s |
|---|---|---|
| 0.3 | — | 0.065 mA·h |
| 1 (as is) | 15.4 s | 0.065 mA·h |
| 3 | 13.7 s (−11 %) | 0.067 mA·h (+0.23 mA, 0.3 % of the generator's current) |
| 10 | 12.5 s (−16 %) | 0.073 mA·h (+0.9 mA, 1.2 %) |

The tolerance is converged at 1 and stays as it is. In the game the pace controller already relaxes a slow
island's accuracy up to 3× (`relaxAccuracy`), which takes in this tolerance. A looser base would stack with
that, and the core's validation would lose 0.3–1.2 %. The whole suite passes at 10×, so nothing breaks; it is a
fidelity choice.
