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
| Inverter | 245 | 11.1 | 0.22 (59.5 s of CPU per 10 s before the Schmitt event: ≈ 6×) |
| Boost converter | 171 | 24.7 | 0.49 |
| PWM motor | 398 | 40.9 | 0.82 |
| H-bridge (forward) | 276 | 3.8 | 0.08 |
| Loudspeaker, idle (its oscillator runs) | 505 | 429 | 8.6 (12.4 before parasitic tolerances) |
| Loudspeaker, switched on | 505 | 821 | 16.4 (22.4 before) |
Idle, every other exhibit fits in 50 ms; the costliest are the flyball (41), the condensing engine (30), the boost (24)
and the steam engine (23).

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
