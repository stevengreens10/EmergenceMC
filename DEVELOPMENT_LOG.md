# Development log

## 2026-10-08 — Session 1
**Foundation.** Chose MC 1.21.1 / NeoForge 21.1.219 / ModDevGradle 2.0.107 / Java 21 (cached locally,
mature API). Built the Minecraft-free core: SI units with dimension checks, JSON loader, 37-material
database, sparse/dense LU, unified multi-domain network with adaptive trapezoidal/BE/BDF2 integration,
Newton, LTE control, energy ledger, and electrical/mechanical/thermal elements with analytical tests.

**Solver lessons.** BDF2's numerical damping (~0.15 %/cycle) would kill clocks and pendulums → trapezoidal
default with ringing suppression. Inconsistent initial conditions poisoned trapezoidal history → the first
step after a restart is discarded from history. Floating conductive islands made pivots exactly zero in
floating point → relative GMIN for electrical nodes.

**Electromagnetics.** Surface-charge cuboid magnets, elliptic-integral loops, filament coils, co-energy
`MagneticSystem`. Generator (EMF ∝ ω, Lenz braking, energy closure) and synchronous motor emerge in tests.

**Assembly layer.** Grid parts → physics compiler (bodies, bearings, gears, contacts, heat, magnetics).
Bugs found by emergence tests: shafts welded to frames they merely pass (thin shafts now touch only at end
faces), component leads conducting through their alumina bodies (leads are copper).

**Minecraft integration.** Assembly block/entity, part items with data-component specs, placement and
removal, simulation manager with islands, renderer, multimeter, sync. GameTests run headless in-world.
Fixed a server deadlock (block-entity lookups during chunk unload).

**Demo world.** Exhibits defined in the core and validated by physics tests; builder, Gradle generation
pipeline, quick-play client, and an automated spectator screenshot tour that verified every exhibit visually
(found: square gears, invisible thin wires, missing switch sync, timing of glow capture, single-cell heat
allocation → all fixed).

**Performance.** Generator island 52 → 1.8 ms/tick (signal-scaled tolerances, primitive sparse index,
in-solver human effort model). Large linear networks remain over budget (see PERFORMANCE.md).

## 2026-10-08 — Session 2
**Electrochemistry.** Butler–Volmer/Nernst electrodes on any conductor touching an electrolyte; Daniell
cell, displacement plating, corrosion and electrolysis emerge. Exhibit testing exposed a kinetics error:
the transfer coefficient was applied to all n electrons, giving hydrogen evolution a 59 mV/decade Tafel
slope and zinc a self-corrosion rate ~10⁴× too high. Now per-reaction α_c (single-electron
rate-determining step) with α_a = n − α_c.

**Block scale.** At the user's request building now defaults to one part per block (a 16× scale model,
1 block = 6.25 cm cell); the true-scale miniature grid stays available with precision tweezers in the
off-hand. Blocks carry their grid resolution; islands at block scale take in every block within
interaction reach so gears and magnets couple across air gaps (found by a new GameTest).

**Visual overhaul.** Part models built in Blender through the MCP bridge (scripted:
`tools/blender/build_parts.py`) — flange bearings with balls, bobbin-wound coils with side leads, magnets
with painted poles, knife switches, electrolytic capacitors, colour-coded resistors, glass jars of
solution — exported as OBJ and rendered by a small loader; gears are generated with real teeth, spokes and
hubs and phased to mesh; item icons rendered from the same models. Chemistry is now visible: solutions
colour by concentration, plated electrodes change colour, gas bubbles rise.

**Demo world** rebuilt at block scale (11 exhibits, three new electrochemistry plots, a miniature
generator), verified by the screenshot tour.
