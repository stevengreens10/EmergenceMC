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
