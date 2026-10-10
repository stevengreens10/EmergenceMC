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

## Time dilation
Each island has a wall-clock budget of 12 ms per server tick. An island whose smoothed cost exceeds it
simulates less time per tick (down to 5 %) and recovers when cheap: the physics stays exact and the
server keeps its tick rate, but that machine runs in slow motion. The steam engine (≈ 230 unknowns,
≈ 750 steps per simulated second at 650 rpm) costs ≈ 1.5× real time on the dev machine.

All islands together get 35 ms per tick (`emergence.globalBudgetMs`), shared nearest-first: islands within 48 blocks
of a player are slowed in common only if they alone exceed it; islands further from every player get what is left
(at least 10 %) and slow first, down to 1 % (nearly paused). In the dense demo gallery, where everything within the
simulation distance (≈ 190 blocks) runs, the exhibit being watched keeps its pace instead of sharing it with dozens
out of sight. In-world tests and demo generation lift both budgets (they test tick counts, not throughput).

## History
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

* Sunlight tracing (`DemoExhibitsTest.sunlightTracingCostPerExhibit`): 1–70 ms per exhibit, 158 ms for the solar
  furnace's 169 facets, 0.75 s for the whole gallery; repeated only when the sun moves half a degree (3 s of a
  Minecraft day) or a body carrying optics moves — never in the noon-fixed demo world. LED ray tubes at compile:
  ~20–50 k tubes (tens of ms) for an LED looking at mirrors or lenses.

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
