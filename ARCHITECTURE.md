# Architecture

Emergence is a NeoForge mod for **Minecraft 1.21.1** (NeoForge 21.1.219, ModDevGradle 2.0.107, Java 21).

## Platform choice

| Criterion | Choice and rationale |
|---|---|
| Version | 1.21.1 — the long-lived 1.21 line with the most mature NeoForge ecosystem and Mojang mappings at runtime (stable reflection, readable stack traces). |
| Loader | NeoForge — first-class block entities, data components, payload networking, GameTest integration, and ModDevGradle for fast iteration. |
| Toolchain | Gradle 8.14, Java 21, ModDevGradle with Parchment mappings. |

## Two layers, enforced by the build

```
core/src/main/java   source set "core"   — scientific simulation, NO Minecraft on its classpath
core/src/test/java   source set "coreTest" — JUnit validation suite (./gradlew coreTest)
src/main/java        source set "main"   — Minecraft integration (blocks, items, rendering, networking)
```

`build.gradle` gives the `core` source set an empty compile classpath, so the simulation physically cannot
import Minecraft classes, and it is tested without launching the game. Both source sets are packaged in one
mod jar.

### Core packages (`dev.emergence.core`)

| Package | Responsibility |
|---|---|
| `units` | `Dimension` (7 SI base exponents), `Quantity`, unit-expression parser, CODATA constants. |
| `data` | Dependency-free JSON reader/writer. |
| `materials` | Data-driven `MaterialDatabase` (JSON, dimension-checked), `Material` with T-dependent resistivity. |
| `math` | Dense/sparse LU solvers, `Vec3`, elliptic integrals, periodic spline/Fourier interpolants. |
| `sim` | The unified multi-domain network (`Network`, `Node`, `Element`), `LoadContext`, `TransientSolver`. |
| `sim.elements` | Physical elements (electrical, mechanical, thermal) — see PHYSICS_MODELS.md. |
| `fluids` | Working-fluid database (`fluids.json`): thermally perfect ideal gases and compressible liquids. |
| `em` | Magnetostatics (cuboid magnets, current loops), coil geometry, `MagneticSystem` coupling. |
| `optics` | Geometric optics: Snell/Fresnel/Cauchy (`Optics`), surfaces of an assembly on a cell grid per moving frame (`OpticalScene`), ray-tube light transport (`TubeTracer`). |
| `assembly` | Grid parts → physics: `Part`, `Assembly`, `AssemblyCompiler`, `CompiledAssembly`, probes, state. |
| `demo` | Platform-independent exhibit definitions shared by tests and the demo-world builder. |

### Unified multiphysics coupling

All domains share one formulation (CLAUDE.md §22): nodes carry an *across* variable (V, ω, v, T, p) and
elements exchange *through* variables (I, τ, F, Q̇, Q). Electrical, rotational, translational and hydraulic
pairs are power-conjugate (checked by `Domain.isPowerConjugate`). The FLUID domain (pressure, mass flow)
conserves mass; its energy is carried as enthalpy on paired thermal nodes. Cross-domain elements couple through these
ports with energy-consistent expressions — e.g. `MagneticSystem` derives EMF and torque from one co-energy
function; resistive elements deposit exactly their dissipated power into a thermal node. The thermal domain
uses (T, Q̇), and coupling into it is always by explicit energy flow, never by T·Q̇.

Every element reports stored energy, power exchanged with external reservoirs and untracked losses; the
solver integrates these into an **energy ledger** whose residual measures numerical energy drift.

### Solver

One implicit, adaptive DAE solver for all domains (`TransientSolver`):
* residual formulation F(x, q̇(x)) = 0 assembled by elements; time derivatives of stored quantities requested
  through `LoadContext.ddt`, so the integration rule is uniform;
* trapezoidal rule (energy-conserving for linear oscillators) with backward-Euler restarts after
  discontinuities, automatic ringing suppression, optional BDF2;
* Newton–Raphson with sparse LU (Markowitz ordering, pattern-reusing refactorisation) or dense LU (n ≤ 48);
* local-truncation-error step control per stored quantity, with signal-scaled tolerances; when repeated
  rejections drive h below 1e-4 of the attempted step the history is discarded and the step restarts
  with backward Euler (stale error history after ringing otherwise collapses the step);
* element-specific Newton limiting via `SetupContext.limitNewton` (e.g. SPICE `pnjlim` for junctions).

### From parts to physics

`AssemblyCompiler` turns a set of grid parts into a network purely from geometry and contact:
rigid bodies (structural contact, bearing revolute joints), rotor kinematics, inertia, bearing friction,
gear meshes, electrical ports and conductors, thermal masses and links, magnetic coupling. Nothing in the
compiler recognises machines. See PHYSICS_MODELS.md §Assembly.

### Minecraft layer (`dev.emergence.mod`)

| Class | Role |
|---|---|
| `AssemblyBlock` / `AssemblyBlockEntity` | Container block with a grid **resolution**: block scale (1 cell = the whole block, the default) or miniature (16³ cells). Collision shape = union of parts; persisted part list, dynamic and chemical state. |
| `PartItem` + `PartSpec` data component | One item per part kind; spec = material, size, SI parameters. Block-scale placement (one part per block, mounting on a shaft's side, long shaft stock laid block by block); miniature placement into the 1/16-block grid when precision tweezers are in the off-hand. |
| `SimulationManager` | Per-level islands of assembly blocks of one resolution (face-adjacent in miniature; at block scale every block within 2 blocks, since parts couple across air gaps; a large gear links the blocks its rim reaches, and a mirror (lens, LED, photodiode) the blocks in a 60° cone along its line of sight within 15 (8) blocks — symmetric links, so membership does not depend on flood order); compiles via the core, steps 50 ms per tick, anchoring to world blocks, biome ambient temperature, failures, incandescent block light, persistence, client sync. Holds block-entity references (never queries the level from unload paths). |
| `IslandSyncPayload` / `ClientSimState` | Server-authoritative body poses, piston states, spring end positions, part temperatures, chemically changed colours, world scale, the island's sim rate. The client draws moving things by **snapshot interpolation**: each body/piston/spring keeps its last six values stamped with the client's own arrival time and is drawn ~1.5 packet intervals in the past on a Catmull–Rom curve through them (no extrapolation: the client and server game clocks drift apart by many ticks and islands run in slow motion, which made extrapolated parts overshoot and snap back). |
| `part.PartLinks` / `part.PartShapes` | What a placed part meets across block boundaries (wires to terminals — the compiler's own `hasElectricalPort` — pipes to vessels, plates to what they are fixed to), shared by the renderer and the block shapes: hitboxes follow the drawn geometry (thin shafts, wires and pipes; plates as thick as their fill; models' bounds). |
| `AssemblyRenderer`, `GearPhasing`, `model.ObjModel`/`PartModels` | Part meshes authored in Blender (`tools/blender/build_parts.py` → `assets/emergence/models/part/*.obj`, textures in `textures/block/part/`) plus procedural gears (from tooth count and pitch radius), shafts and wires at their physical dimensions; rigid-body rotation; black-body glow; resistor colour codes; switch state; tooth phasing of meshing gears. Works at both resolutions. |
| `MultimeterItem` | Instrument reading probes from simulated state. |
| `DemoBuilder`, `AutoShowcase` | Demo-world gallery and automated visual verification. |

## Build & run tasks

| Task | Purpose |
|---|---|
| `./gradlew coreTest` | Scientific validation suite (no Minecraft). |
| `./gradlew runGameTestServer` | In-world integration tests (headless, `run-gametest/`). |
| `./gradlew generateDemoWorld` | Builds the showcase world into `run/saves/Emergence Demo`. |
| `./gradlew runDemoClient` | Client that opens the demo world directly. |
| `./gradlew runShowcaseClient` | Automated tour that saves screenshots to `run/screenshots/`. |
| `./gradlew build` | Mod jar in `build/libs/`. |

## Multirate stepping (`TransientSolver.multirate`)
Every island's thermal network — the thermal nodes of parts with a heat capacity, and the elements touching nothing
else (heat capacities, conduction, convection, radiation, fixed temperatures) — steps apart from the rest. A dry run
records the rows and history slots every element writes; elements touching only such nodes form the slow partition,
those touching nothing thermal or a mix form the fast one (a gas's or liquid's temperature, whose inertia is in its
fluid element, stays fast). The fast partition steps with temperatures held; the heat its elements put into thermal
nodes is integrated with the quadrature of each step and handed over, at synchronisation, as a constant power across
the interval, so energy crosses exactly. The thermal network then restarts (its input stepped) and integrates to the
fast partition's time under its own error control and a change bound (5 K + 1 % per step). Synchronisations come at
the end of every `advance` (a game tick) and whenever the thermal network's step or the coupling's stability ask
(interval ≤ ½·C/G, G the fast side's ∂(heat in)/∂T). Each phase factorises its own matrix, entries in held rows and
columns left out. The ledger integrates each phase's boundary power with the quadrature its steps used (the end point
after backward Euler), and counts heat in transit as stored. `-Demergence.multirate=false` steps everything together.
