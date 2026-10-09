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

## 2026-10-08 — Session 2 (continued): fluids
**Fluid networks.** New FLUID domain (pressure, mass flow) with control volumes that pair a pressure node
and a temperature node, quasi-steady branches (Darcy–Weisbach/Haaland, minor losses, gravity), choked
orifices for valves, atmosphere vents and wall convection; a fluids database (ideal gases with fitted
c_p(T), compressible liquids). Validated against Hagen–Poiseuille, Colebrook, isentropic blowdown,
choked-orifice flow and conservation of mass and energy. Found and fixed on the way: FlowSource counted
heat × temperature as power in the thermal domain.

**Rotors.** A first Euler-equation rotor model failed in the demo: in reversed flow a windmill would not
reverse, and the zero-flow pumping direction was undefined. Replaced with a blade-element model (lift and
drag on a staggered blade row) that conserves energy exactly and covers all four quadrants. Exhibit
design was then dominated by honest scale effects: a 5.6 cm rotor in 8 m/s of air makes ~1 mN·m, so the
turbine got a light disk and a bigger receiver, and the fan two 5:1 gear stages.

**Parts and world.** Pipes, pressure vessels (riveted, with a live Bourdon gauge), globe valves (stem rises
when open) and bladed rotors; shafts pass through pipes in glands; open pipe ends vent (with plumes) unless
capped by world blocks; air pressure falls with altitude.

**Heat engine milestone.** A Brayton experiment (compressor and turbine rotors on one shaft, finned heater
between) never self-sustained: a single 5.6 cm blade row reaches a pressure ratio of only ~1.01 — real
micro gas turbines need ≥ 3 — so friction and heater losses win. That is physics, not a bug, and is kept
as a documented result. Pistons came next: slider-crank kinematics with variable-volume control volumes
and an exactly power-conserving gas/shaft coupling, then CYLINDER and ECCENTRIC parts whose linkage,
rod length and timing the compiler infers from placement. An alpha Stirling engine assembled from parts
runs on a hot copper block and drives a magnet-and-coil generator. Along the way: charging it to 4 bar
made it rock instead of turn (the gas spring against an unpressurised crankcase outweighed the flywheel —
real high-pressure Stirlings pressurise the crankcase), and an open cold-cylinder head vented the charge.
In the world, two exhibits within gear reach merged into one island; plots are now 20 blocks apart and
fluid unknowns have Newton step limits.

**Electronics.** Ebers–Moll BJTs, EKV MOSFETs and wavelength-defined LEDs; transistor and LED parts. A CMOS
NAND from four MOSFETs passed its truth table first time. Bugs found by the exhibits: leaded parts conducted
through their silicon bodies (copper leads now general); a capacitor truncation-error floor below Newton's
resolution collapsed the time step on floating CMOS stack nodes (~100× fix); a floating transistor base
needed a pull-down (as in real design). Then a validated chip-level logic model (gates, Schmitt trigger, D
flip-flop) as the next fidelity level: getting a ripple counter to count exposed metastability at power-up
(fixed by latch regeneration), race-through on slow clock edges (non-overlapping master/slave windows), and
LED Newton divergence (internal junction unknown with SPICE pnjlim limiting via a new solver hook). The
4-bit counter now counts exactly with its clock.

**Closed-loop control.** NTC thermistor element and a thermostat exhibit built from a thermistor, a Schmitt
trigger chip, a power MOSFET and a heater on an aluminium plate. Getting it to run exposed (1) heater-to-plate
thermal isolation — clamped component bodies now conduct heat through their contact area — and (2) step-size
underflow after ringing from stale truncation-error history, fixed by a restart rule in the solver. A net
labelling check now catches accidental shorts between touching insulated wires in exhibit layouts.
