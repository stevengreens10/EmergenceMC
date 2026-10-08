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
| `fluids` | Fluid property models (air so far). |
| `em` | Magnetostatics (cuboid magnets, current loops), coil geometry, `MagneticSystem` coupling. |
| `assembly` | Grid parts → physics: `Part`, `Assembly`, `AssemblyCompiler`, `CompiledAssembly`, probes, state. |
| `demo` | Platform-independent exhibit definitions shared by tests and the demo-world builder. |

### Unified multiphysics coupling

All domains share one formulation (CLAUDE.md §22): nodes carry an *across* variable (V, ω, v, T, p) and
elements exchange *through* variables (I, τ, F, Q̇, Q). Electrical, rotational, translational and hydraulic
pairs are power-conjugate (checked by `Domain.isPowerConjugate`). Cross-domain elements couple through these
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
* local-truncation-error step control per stored quantity, with signal-scaled tolerances.

### From parts to physics

`AssemblyCompiler` turns a set of grid parts into a network purely from geometry and contact:
rigid bodies (structural contact, bearing revolute joints), rotor kinematics, inertia, bearing friction,
gear meshes, electrical ports and conductors, thermal masses and links, magnetic coupling. Nothing in the
compiler recognises machines. See PHYSICS_MODELS.md §Assembly.

### Minecraft layer (`dev.emergence.mod`)

| Class | Role |
|---|---|
| `AssemblyBlock` / `AssemblyBlockEntity` | Container block for up to 16³ sub-block parts; collision shape = union of parts; persisted part list and dynamic state. |
| `PartItem` + `PartSpec` data component | One item per part kind; spec = material, size, SI parameters. Face/axis placement and shaft mounting. |
| `SimulationManager` | Per-level islands of face-adjacent assembly blocks; compiles via the core, steps 50 ms per tick, anchoring to world blocks, biome ambient temperature, failures, incandescent block light, persistence, client sync. Holds block-entity references (never queries the level from unload paths). |
| `IslandSyncPayload` / `ClientSimState` | Server-authoritative body poses (with ω for extrapolation) and part temperatures. |
| `AssemblyRenderer` | True-size part rendering, rigid-body rotation, black-body glow. |
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
